package dev.cinematic.p0.rawcapture

import dev.cinematic.p0.metrics.SaveTiming
import dev.cinematic.p0.metrics.SaveTimingOutcome
import dev.cinematic.p0.metrics.SaveTimingStage
import dev.cinematic.p0.metrics.measured
import dev.cinematic.p0.metrics.timingBestEffort

internal enum class PublicationInterval(val stage: SaveTimingStage) {
    ORIGINAL(SaveTimingStage.PUB_VERIFY_ORIGINAL),
    PRIVATE_JPEG(SaveTimingStage.PUB_PRIVATE_JPEG_CHECK),
    COPY(SaveTimingStage.PUB_COPY),
    ROW_PENDING(SaveTimingStage.PUB_VERIFY_ROW_PENDING),
    ROW_PUBLIC(SaveTimingStage.PUB_VERIFY_ROW_PUBLIC),
    JOURNAL(SaveTimingStage.PUB_JOURNAL),
    MEDIASTORE(SaveTimingStage.PUB_MEDIASTORE),
    AUTHORITY(SaveTimingStage.PUB_AUTHORITY_CHECK),
}

private const val MISSING = -1L

/** One collector per synchronous advance. A negative total poisons the entire key. */
internal class PublicationTiming(private val clock: () -> Long = System::nanoTime) {
    private val totals = LongArray(PublicationInterval.entries.size)
    private val entered = BooleanArray(totals.size)
    private val failed = BooleanArray(totals.size)
    private var current: PublicationInterval? = null
    private var children = 0L
    private var unavailable = false

    private fun add(left: Long, right: Long): Long =
        if (left < 0 || right < 0 || Long.MAX_VALUE - left < right) MISSING else left + right

    inline fun <T> measure(interval: PublicationInterval, subtractFromParent: Boolean, action: () -> T): T {
        val parent = current
        val previousChildren = children
        current = interval
        children = 0
        var start = 0L
        var clockAvailable = false
        timingBestEffort {
            start = clock()
            clockAvailable = true
        }
        var completed = false
        try {
            val result = action()
            completed = true
            return result
        } finally {
            var elapsed = MISSING
            timingBestEffort {
                val end = clock()
                if (clockAvailable) elapsed = end - start
            }
            val exclusive = if (children < 0 || elapsed < children) MISSING else elapsed - children
            val index = interval.ordinal
            entered[index] = true
            if (!completed) failed[index] = true
            totals[index] = add(totals[index], exclusive)
            current = parent
            children = if (subtractFromParent && when (interval) {
                    PublicationInterval.MEDIASTORE -> parent == PublicationInterval.COPY ||
                        parent == PublicationInterval.ROW_PENDING || parent == PublicationInterval.ROW_PUBLIC
                    PublicationInterval.AUTHORITY -> parent == PublicationInterval.JOURNAL
                    else -> false
                }) add(previousChildren, elapsed) else previousChildren
        }
    }

    fun <T> scoped(action: () -> T): T {
        var previous: PublicationTiming? = null
        var installed = false
        timingBestEffort {
            previous = active.get()
            active.set(this)
            installed = true
        }
        if (!installed) unavailable = true
        try {
            return action()
        } finally {
            if (installed) timingBestEffort {
                if (previous == null) active.remove() else active.set(previous)
            }
        }
    }

    fun emit(timing: SaveTiming, published: Boolean) {
        for (interval in PublicationInterval.entries) {
            val index = interval.ordinal
            val nanos = totals[index].takeIf { !unavailable && entered[index] && it >= 0 }
            val outcome = when {
                failed[index] -> SaveTimingOutcome.INCOMPLETE
                unavailable -> SaveTimingOutcome.UNAVAILABLE
                !entered[index] -> if (published) SaveTimingOutcome.SKIPPED else SaveTimingOutcome.NOT_REACHED
                nanos == null -> SaveTimingOutcome.UNAVAILABLE
                else -> SaveTimingOutcome.COMPLETED
            }
            timing.event(interval.stage, outcome, nanos)
        }
    }

    companion object {
        private val active = ThreadLocal<PublicationTiming>()

        inline fun <T> measure(interval: PublicationInterval, subtractFromParent: Boolean = false, action: () -> T): T {
            val collector = try { active.get() } catch (_: Throwable) { null }
            return if (collector == null) action() else collector.measure(interval, subtractFromParent, action)
        }
    }
}

internal fun measurePublication(timing: SaveTiming?, enabled: Boolean,
    create: () -> PublicationTiming = { PublicationTiming() },
    advance: (PublicationTiming?) -> JpegPublicationRecord): JpegPublicationRecord {
    if (!enabled || timing == null) return measured(timing, SaveTimingStage.PUBLICATION) { advance(null) }

    var collector: PublicationTiming? = null
    timingBestEffort { collector = create() }
    var published = false
    try {
        val result = measured(timing, SaveTimingStage.PUBLICATION) { advance(collector) }
        published = result.phase == JpegPublicationPhase.PUBLISHED
        return result
    } finally {
        timingBestEffort {
            val collected = collector
            if (collected != null) collected.emit(timing, published)
            else for (interval in PublicationInterval.entries) {
                timing.event(interval.stage, SaveTimingOutcome.UNAVAILABLE)
            }
        }
    }
}
