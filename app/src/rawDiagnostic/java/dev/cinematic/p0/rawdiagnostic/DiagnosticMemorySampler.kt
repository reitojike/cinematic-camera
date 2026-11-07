package dev.cinematic.p0.rawdiagnostic

import java.util.concurrent.atomic.AtomicBoolean

/** Memory-only session. Constant-space observations, not an allocation profiler or true peak. */
internal class DiagnosticMemorySampler(
    private val readMemory: () -> Reading,
    private val nanoTime: () -> Long = System::nanoTime,
    private val sleepNanos: (Long) -> Unit = { Thread.sleep(it / 1_000_000, (it % 1_000_000).toInt()) },
    private val threadFactory: (Runnable) -> Thread = { Thread(it, "raw-memory-sampler") },
    private val intervalNanos: Long = 100_000_000L,
) {
    data class Reading(val managedBytes: Long, val nativeBytes: Long)
    data class Summary(
        val quiescent: Boolean,
        val failed: Boolean,
        val sampleCount: Long,
        val observedMaxManagedBytes: Long?,
        val observedMaxNativeBytes: Long?,
        val requestedIntervalNanos: Long,
        val meanIntervalNanos: Long?,
        val maxGapNanos: Long,
        val elapsedNanos: Long,
        val collectionTotalNanos: Long,
        val collectionMaxNanos: Long,
        val skippedIntervals: Long,
        val failedSamples: Long,
    )

    init { require(intervalNanos > 0) }
    private val stopped = AtomicBoolean()
    @Volatile private var thread: Thread? = null
    private var started = false
    private var failed = false
    private var startNanos = 0L
    private var endNanos: Long? = null
    private var count = 0L
    private var maxManaged: Long? = null
    private var maxNative: Long? = null
    private var firstSample: Long? = null
    private var lastSample: Long? = null
    private var maxGap = 0L
    private var totalCost = 0L
    private var maxCost = 0L
    private var skipped = 0L
    private var readFailures = 0L

    /** One-shot; the owner must call stopAndJoin in finally even when start returns false. */
    @Synchronized fun start(): Boolean {
        check(!started)
        started = true
        return try {
            startNanos = nanoTime()
            thread = threadFactory(Runnable { collect() })
            thread!!.start()
            true
        } catch (_: Throwable) {
            failed = true
            false
        }
    }

    fun requestStop() {
        stopped.set(true)
        thread?.interrupt()
    }

    /** A timed-out or interrupted join never proves quiescence. The owner must HOLD resources. */
    fun stopAndJoin(timeoutMillis: Long = 1_000): Summary {
        require(timeoutMillis > 0)
        requestStop()
        val worker = thread
        var interrupted = false
        try {
            if (worker != null && worker !== Thread.currentThread()) worker.join(timeoutMillis)
        } catch (_: InterruptedException) {
            interrupted = true
            Thread.currentThread().interrupt()
        }
        val quiescent = !interrupted && (worker == null || !worker.isAlive)
        return synchronized(this) {
            val end = endNanos ?: nanoTime()
            val elapsed = if (started) (end - startNanos).coerceAtLeast(0) else 0
            // Include startup and trailing blind windows, not only gaps between successful reads.
            val tail = if (started) (end - (lastSample ?: startNanos)).coerceAtLeast(0) else 0
            Summary(quiescent, failed, count, maxManaged, maxNative, intervalNanos,
                if (count > 1) (lastSample!! - firstSample!!) / (count - 1) else null,
                maxOf(maxGap, tail), elapsed, totalCost, maxCost, skipped, readFailures)
        }
    }

    private fun collect() {
        try {
            var next = startNanos
            while (!stopped.get()) {
                val wait = next - nanoTime()
                if (wait > 0) sleepNanos(wait)
                if (stopped.get()) break
                val begin = nanoTime()
                val reading = try {
                    readMemory().also { require(it.managedBytes >= 0 && it.nativeBytes >= 0) }
                } catch (error: Throwable) {
                    synchronized(this) { readFailures++ }
                    throw error
                } finally {
                    val cost = (nanoTime() - begin).coerceAtLeast(0)
                    synchronized(this) { totalCost += cost; maxCost = maxOf(maxCost, cost) }
                }
                synchronized(this) {
                    maxGap = maxOf(maxGap, (begin - (lastSample ?: startNanos)).coerceAtLeast(0))
                    if (firstSample == null) firstSample = begin
                    lastSample = begin
                    count++
                    maxManaged = maxOf(maxManaged ?: 0, reading.managedBytes)
                    maxNative = maxOf(maxNative ?: 0, reading.nativeBytes)
                }
                next += intervalNanos
                val now = nanoTime()
                if (now > next) {
                    // Skip overdue slots rather than burst-sampling to catch up.
                    val missed = (now - next - 1) / intervalNanos + 1
                    synchronized(this) { skipped += missed }
                    next += missed * intervalNanos
                }
            }
        } catch (_: InterruptedException) {
            if (!stopped.get()) synchronized(this) { failed = true }
        } catch (_: Throwable) {
            synchronized(this) { failed = true }
        } finally {
            synchronized(this) { endNanos = nanoTime() }
        }
    }
}
