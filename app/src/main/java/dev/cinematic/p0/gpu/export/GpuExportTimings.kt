package dev.cinematic.p0.gpu.export

import dev.cinematic.p0.metrics.SaveTiming
import dev.cinematic.p0.metrics.SaveTimingOutcome
import dev.cinematic.p0.metrics.SaveTimingStage
import dev.cinematic.p0.metrics.timingBestEffort

/** GL-thread memory only. Drained on queue IO after the existing Future has completed. */
internal class GpuExportTimings(private val clock: (() -> Long)? = null) {
    private val spans = mutableMapOf<SaveTimingStage, Pair<Long, Boolean>>()
    private val details = mutableMapOf<SaveTimingStage, GpuExportWorkTimings>()
    private var collectionFailed = false
    fun detail(name: String): GpuExportWorkTimings? = try {
        val stage = SaveTimingStage.valueOf(name)
        if (stage !in stages) null else details.getOrPut(stage) { GpuExportWorkTimings(clock) }
    } catch (_: Throwable) { collectionFailed = true; null }
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
            for (part in detailParts(stage)) {
                val sample = details[stage]?.sample(part)
                val outcome = when {
                    span?.first == -1L -> SaveTimingOutcome.SKIPPED
                    sample != null && (span?.second == false || !sample.completed) -> SaveTimingOutcome.INCOMPLETE
                    sample != null && sample.nanos != null -> SaveTimingOutcome.COMPLETED
                    sample != null || collectionFailed || span?.second == true -> SaveTimingOutcome.UNAVAILABLE
                    else -> SaveTimingOutcome.NOT_REACHED
                }
                timing?.event(SaveTimingStage.valueOf("${stage.name}_${part.name}"), outcome, sample?.nanos)
            }
        }
    }
    private fun detailParts(stage: SaveTimingStage): List<GpuExportDetail> = when (stage) {
        SaveTimingStage.B1_QUANTILES -> listOf(GpuExportDetail.FENCE_WAIT, GpuExportDetail.READBACK)
        SaveTimingStage.B4 -> scanParts + GpuExportDetail.READBACK
        SaveTimingStage.FINAL -> scanParts + listOf(GpuExportDetail.READBACK,
            GpuExportDetail.ROW_CONVERT, GpuExportDetail.ROW_CONSUME)
        else -> scanParts
    }
    companion object {
        private val scanParts = listOf(GpuExportDetail.INPUT_WAIT, GpuExportDetail.INPUT_READ_CONVERT,
            GpuExportDetail.INPUT_PACK, GpuExportDetail.UPLOAD, GpuExportDetail.FENCE_WAIT)
        private val stages = listOf(SaveTimingStage.B1, SaveTimingStage.B1_QUANTILES, SaveTimingStage.B2,
            SaveTimingStage.B3, SaveTimingStage.B4, SaveTimingStage.B5, SaveTimingStage.FINAL)
    }
}
