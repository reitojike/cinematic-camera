package dev.cinematic.p0.rawcapture

import android.graphics.Bitmap
import java.io.Closeable

internal enum class PrivateTrialPhase { PREPARED, RUNNING, RETRYABLE, READY, HOLD }
internal data class PrivateTrialRequest(val id: String, val generation: Long, val source: SharedOriginalReference,
    val recipe: RenderRecipeSnapshot, val quality: AutoRenderQuality = AutoRenderQuality.PRIVATE_TRIAL_1024)
internal data class PrivateTrialStatus(val request: PrivateTrialRequest, val phase: PrivateTrialPhase,
    val output: JpegOutputDescription?, val fingerprint: OriginalFingerprint?, val reason: AutoJobReason) {
    val face get() = RenderedFaceState.NOT_RUN
    val exposure get() = RenderedExposureState.DIAGNOSTIC
    companion object {
        fun from(record: AutoDevelopRecord): PrivateTrialStatus {
            require(record.request.boundedTrial)
            val phase = when (record.phase) {
                AutoJobPhase.QUEUED -> PrivateTrialPhase.PREPARED
                AutoJobPhase.RENDERING -> PrivateTrialPhase.RUNNING
                AutoJobPhase.RETRYABLE -> PrivateTrialPhase.RETRYABLE
                AutoJobPhase.RENDERED -> PrivateTrialPhase.READY
                AutoJobPhase.HELD -> PrivateTrialPhase.HOLD
                else -> throw CaptureJournalConflict()
            }
            val r = record.request
            return PrivateTrialStatus(PrivateTrialRequest(r.captureId, r.generation, r.source, r.recipe), phase,
                record.observed?.output, record.outputFingerprint, record.reason)
        }
    }
}

/** Display result only. UI closes after removing it from display; no encoded bytes/file/public URI. */
internal class PrivateTrialPreview(val bitmap: Bitmap, val status: PrivateTrialStatus,
    /** Same oriented, bounded RAW decode before diagnostic exposure/R7/look. No encoded source preview. */
    val comparisonBitmap: Bitmap? = null) : Closeable {
    @Synchronized override fun close() {
        if (!bitmap.isRecycled) bitmap.recycle()
        comparisonBitmap?.let { if (!it.isRecycled) it.recycle() }
    }
}

/** Deliberately has no completed result, publish, shared URI, source-write or file-copy API. */
internal class PrivateTrialHandle internal constructor(private val queue: AutoDevelopQueue,
    private val definition: AutoDevelopRequest, private val control: ManualDevelopControl) {
    val request = PrivateTrialRequest(definition.captureId, definition.generation, definition.source, definition.recipe)
    fun start(observations: AutoDevelopConditions) =
        queue.startTrial(definition, observations, control.begin()).thenApply(PrivateTrialStatus::from)
    fun status() = queue.trialStatus(definition).thenApply(PrivateTrialStatus::from)
    fun requestStop() { try { control.stop() } finally { queue.stopManual(definition) } }
    fun retry() = queue.retryTrial(definition, control.begin()).thenApply(PrivateTrialStatus::from)
    fun readPreview(observations: AutoDevelopConditions, shouldStop: () -> Boolean) =
        queue.readTrial(definition, observations, control.begin(), shouldStop)
}
