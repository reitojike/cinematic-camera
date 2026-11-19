package dev.cinematic.p0.rawcapture

import dev.cinematic.p0.BuildConfig
import java.util.concurrent.atomic.AtomicInteger

/** One advance invocation only. Serializes actual checks; never shares a failure as success.
 * Age starts BEFORE IO, and is evaluated AFTER acquiring the monitor. No timer or worker is added. */
internal class SaveAuthorityWindow(private val intervalNanos: Long, private val now: () -> Long = System::nanoTime,
    private val verify: () -> Boolean) {
    init { require(intervalNanos in setOf(0L, 50_000_000L, 100_000_000L)) }
    data class Stats(val calls: Long, val hits: Long, val durable: Long, val forced: Long,
        val maxWaitNanos: Long, val maxCheckNanos: Long, val maxReusedAgeNanos: Long)
    private var calls = 0L; private var hits = 0L; private var durable = 0L; private var forced = 0L
    private var waitMax = 0L; private var checkMax = 0L; private var ageMax = 0L
    private val pendingForces = AtomicInteger()
    private var verifiedAt: Long? = null
    private var denied = false
    private var failure: Throwable? = null
    fun check(force: Boolean, memoryValid: () -> Boolean = { true }): Boolean {
        if (intervalNanos == 0L) {
            // Debug measurement of the unmodified decision path: no latch, cache or IO serialization.
            synchronized(this) { calls++; durable++ }
            val start = now()
            try { return verify() }
            finally { synchronized(this) { checkMax = maxOf(checkMax, (now() - start).coerceAtLeast(0)) } }
        }
        val entered = now()
        if (force) pendingForces.incrementAndGet()
        try { synchronized(this) {
            val acquired = now()
            waitMax = maxOf(waitMax, (acquired - entered).coerceAtLeast(0))
            calls++
            if (failure != null) return false // Keep the first failure; owner catch must be able to settle HOLD.
            if (!memoryValid()) denied = true
            if (denied) return false
            val age = verifiedAt?.let { acquired - it }
            if (!force && pendingForces.get() == 0 && intervalNanos > 0 && age != null && age >= 0 && age < intervalNanos) {
                hits++; ageMax = maxOf(ageMax, age); return true
            }
            verifiedAt = null
            durable++; if (force) forced++
            val started = now()
            try {
                val valid = verify()
                if (valid) verifiedAt = started else denied = true
                return valid
            } catch (error: Throwable) { failure = error; throw error }
            finally { checkMax = maxOf(checkMax, (now() - started).coerceAtLeast(0)) }
        } } finally { if (force) pendingForces.decrementAndGet() }
    }
    @Synchronized fun stats() = Stats(calls, hits, durable, forced, waitMax, checkMax, ageMax)
}

/** Default is durable. Only explicit fine-grained callbacks opt into sharing.
 * The force marker is applied to ONE checkpoint, never its surrounding stage/action. */
internal object SaveAuthorityBoundary {
    private val timedDepth = ThreadLocal.withInitial { 0 }
    private val forceDepth = ThreadLocal.withInitial { 0 }
    private val verifier = ThreadLocal<(() -> Unit)?>()
    val enabled: Boolean get() = SaveAuthorityIntervalExperiment.intervalNanos() > 0
    val force: Boolean get() = (forceDepth.get() ?: 0) > 0 || (timedDepth.get() ?: 0) == 0
    fun <T> timed(action: () -> T): T {
        if (!enabled) return action()
        val old = timedDepth.get() ?: 0; timedDepth.set(old + 1)
        try { return action() } finally { timedDepth.set(old) }
    }
    fun <T> durable(action: () -> T): T {
        if (!enabled) return action()
        val old = forceDepth.get() ?: 0; forceDepth.set(old + 1)
        try { return action() } finally { forceDepth.set(old) }
    }
    fun checkpoint() { verifier.get()?.invoke() }
    fun <T> observing(check: () -> Unit, action: () -> T): T {
        check(verifier.get() == null)
        verifier.set(check)
        try { return action() } finally { verifier.remove() }
    }
}

/** Local debug comparison only. Release remains disabled; no user data or identity is retained. */
internal object SaveAuthorityIntervalExperiment {
    @Volatile private var overrideMillis: Long? = null
    @Volatile var lastStats: SaveAuthorityWindow.Stats? = null
        private set
    val measuring: Boolean get() = BuildConfig.DEBUG && overrideMillis != null
    fun intervalNanos(): Long = (if (BuildConfig.DEBUG) overrideMillis ?: BuildConfig.SAVE_AUTHORITY_INTERVAL_MS.toLong() else 0L) * 1_000_000
    fun configureForTest(millis: Long?) {
        check(BuildConfig.DEBUG); require(millis == null || millis in setOf(0L, 50L, 100L))
        overrideMillis = millis; lastStats = null
    }
    fun completed(stats: SaveAuthorityWindow.Stats) { if (BuildConfig.DEBUG) lastStats = stats }
}
