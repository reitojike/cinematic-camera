package dev.cinematic.p0.rawcapture

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * One admitted advance only. Serializes durable checks across the owner and export reader.
 * A success ages from the start of its I/O, and the age is checked after acquiring the monitor.
 */
internal class SaveAuthorityWindow(
    private val now: () -> Long = System::nanoTime,
    private val verify: () -> Boolean,
) {
    private val pendingForces = AtomicInteger()
    private var verifiedAt: Long? = null
    private var denied = false

    fun check(force: Boolean, memoryValid: () -> Boolean = { true }): Boolean {
        if (force) pendingForces.incrementAndGet()
        try {
            synchronized(this) {
                if (!denied && !memoryValid()) denied = true
                if (denied) return false
                val age = verifiedAt?.let { now() - it }
                if (!force && pendingForces.get() == 0 && age != null && age >= 0 && age < REUSE_NANOS)
                    return true

                verifiedAt = null
                val started = now()
                try {
                    val valid = verify()
                    if (valid) verifiedAt = started else denied = true
                    return valid
                } catch (failure: Throwable) {
                    // Report the first failure; later checks must let the owner settle HOLD.
                    denied = true
                    throw failure
                }
            }
        } finally {
            if (force) pendingForces.decrementAndGet()
        }
    }

    private companion object {
        const val REUSE_NANOS = 50_000_000L
    }
}

/** Only fine-grained callbacks opt into sharing; a forced checkpoint overrides nested timed callbacks. */
internal object SaveAuthorityBoundary {
    private val timedDepth = ThreadLocal<Int>()
    private val forceDepth = ThreadLocal<Int>()
    private val verifier = ThreadLocal<() -> Boolean>()

    // The actual durable observer belongs to the serial IO owner, never the GPU reader.
    val isObserving: Boolean get() = verifier.get() != null
    val force: Boolean get() = (forceDepth.get() ?: 0) > 0 || (timedDepth.get() ?: 0) == 0

    fun <T> timed(action: () -> T): T = scoped(timedDepth, action)
    fun <T> durable(action: () -> T): T = scoped(forceDepth, action)
    private fun <T> scoped(depth: ThreadLocal<Int>, action: () -> T): T {
        val old = depth.get()
        depth.set((old ?: 0) + 1)
        try {
            return action()
        } finally {
            if (old == null) depth.remove() else depth.set(old)
        }
    }

    fun checkpoint() {
        val check = verifier.get() ?: return
        val current = try { check() } catch (failure: IOException) {
            // This observer reads authority; it is not the journal's durable-proof comparison.
            throw IOException("Save authority could not be observed", failure)
        }
        if (!current) throw IOException("Save authority no longer current")
    }

    fun <T> observing(check: () -> Boolean, action: () -> T): T {
        check(verifier.get() == null)
        verifier.set(check)
        try { return action() } finally { verifier.remove() }
    }
}

// Only the manual-save intent, attempt and JPEG journals opt in. Their revision/record
// comparisons still throw their own CaptureJournalConflict, unchanged.
internal fun AtomicJournalStorage.checkingSaveAuthority(): AtomicJournalStorage =
    object : AtomicJournalStorage by this {
        override fun replace(contents: ByteArray) {
            SaveAuthorityBoundary.checkpoint()
            this@checkingSaveAuthority.replace(contents)
        }
    }
