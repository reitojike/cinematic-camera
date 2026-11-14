package dev.cinematic.p0.gpu.export

import dev.cinematic.p0.metrics.SaveTiming
import dev.cinematic.p0.metrics.SaveTimingOutcome
import dev.cinematic.p0.metrics.SaveTimingStage
import dev.cinematic.p0.metrics.timingBestEffort

/** GL-thread memory only. Drained on queue IO after the existing Future has completed. */
internal class GpuExportTimings {
    private val spans = mutableMapOf<SaveTimingStage, Pair<Long, Boolean>>()
    private var collectionFailed = false
    fun finished(name: String, nanos: Long, completed: Boolean) { try {
        val stage = SaveTimingStage.valueOf(name)
        if (stage in stages) spans[stage] = nanos to completed
    } catch (_: Throwable) { collectionFailed = true } }
    fun skipped(name: String) = finished(name, -1, true)
    fun drain(timing: SaveTiming?, completed: Boolean) = timingBestEffort {
        for (stage in stages) {
            val span = spans[stage]
            timing?.event(stage, when {
                span != null && span.first == -1L -> SaveTimingOutcome.SKIPPED
                span != null -> if (span.second) SaveTimingOutcome.COMPLETED else SaveTimingOutcome.INCOMPLETE
                collectionFailed -> SaveTimingOutcome.UNAVAILABLE
                completed -> SaveTimingOutcome.UNAVAILABLE
                else -> SaveTimingOutcome.NOT_REACHED
            }, span?.first?.takeIf { it >= 0 })
        }
    }
    companion object {
        private val stages = listOf(SaveTimingStage.B1, SaveTimingStage.B1_QUANTILES, SaveTimingStage.B2,
            SaveTimingStage.B3, SaveTimingStage.B4, SaveTimingStage.B5, SaveTimingStage.FINAL)
    }
}
