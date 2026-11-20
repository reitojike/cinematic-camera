package dev.cinematic.p0.rawcapture

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
    private val verifier = ThreadLocal<() -> Unit>()

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

    fun checkpoint() { verifier.get()?.invoke() }

    fun <T> observing(check: () -> Unit, action: () -> T): T {
        check(verifier.get() == null)
        verifier.set(check)
        try { return action() } finally { verifier.remove() }
    }
}
