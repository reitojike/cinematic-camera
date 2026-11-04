package dev.cinematic.p0.photoroll

import dev.cinematic.p0.rawcapture.CompletedRender
import dev.cinematic.p0.rawcapture.JpegPublicationPhase
import dev.cinematic.p0.rawcapture.JpegPublicationRecord
import dev.cinematic.p0.rawcapture.OriginalFingerprint
import dev.cinematic.p0.rawcapture.RendererPublicationContract
import java.util.concurrent.atomic.AtomicBoolean

internal enum class PhotoRollSavePhase { READY, SAVING, PUBLISHED, UNCONFIRMED, HOLD }
internal data class PhotoRollSaveSnapshot(val phase: PhotoRollSavePhase, val uri: String? = null)
internal fun interface PhotoRollJpegPublisher {
    fun advance(shouldStop: () -> Boolean): JpegPublicationRecord
}

/** Retained by the completed renderer per durable operation, never recreated by a UI retry. */
internal class PhotoRollJpegSave private constructor(
    private val render: CompletedRender,
    private val publisher: PhotoRollJpegPublisher,
    private val isCurrent: () -> Boolean,
) {
    private val running = AtomicBoolean(false)
    @Volatile private var current = PhotoRollSaveSnapshot(PhotoRollSavePhase.READY)
    val snapshot get() = current
    val width get() = render.request.output.width
    val height get() = render.request.output.height

    fun matches(uri: String, fingerprint: OriginalFingerprint, mode: PhotoRollPreviewMode) =
        mode == PhotoRollPreviewMode.STAGE_CLEAR && render.request.source.uri == uri &&
            render.request.source.fingerprint == fingerprint

    /** Also used immediately before opening a published result; reuses and rechecks the same publisher. */
    fun advance(shouldStop: () -> Boolean): PhotoRollSaveSnapshot {
        if (!running.compareAndSet(false, true)) return current
        try {
            if (current.phase == PhotoRollSavePhase.HOLD) return current
            current = PhotoRollSaveSnapshot(PhotoRollSavePhase.SAVING)
            val stopped = { shouldStop() || !isCurrent() }
            if (stopped()) return unconfirmed()
            val record = publisher.advance(stopped)
            current = when {
                stopped() -> PhotoRollSaveSnapshot(PhotoRollSavePhase.UNCONFIRMED)
                record.render != render || record.target.captureId != render.request.captureId ||
                    record.target.generation != render.request.generation -> PhotoRollSaveSnapshot(PhotoRollSavePhase.HOLD)
                record.phase == JpegPublicationPhase.PUBLISHED && record.uri != null ->
                    PhotoRollSaveSnapshot(PhotoRollSavePhase.PUBLISHED, record.uri)
                record.phase == JpegPublicationPhase.UNKNOWN -> PhotoRollSaveSnapshot(PhotoRollSavePhase.HOLD)
                else -> PhotoRollSaveSnapshot(PhotoRollSavePhase.UNCONFIRMED)
            }
            return current
        } catch (_: Exception) { return unconfirmed() }
        catch (_: OutOfMemoryError) { return unconfirmed() }
        finally { running.set(false) }
    }

    private fun unconfirmed() = PhotoRollSaveSnapshot(PhotoRollSavePhase.UNCONFIRMED).also { current = it }

    companion object {
        /** Validation precedes factory invocation: trial/unsupported inputs cannot allocate a public operation. */
        fun prepare(
            originalUri: String,
            fingerprint: OriginalFingerprint,
            mode: PhotoRollPreviewMode,
            render: CompletedRender,
            supportedContracts: Set<RendererPublicationContract>,
            isCurrent: () -> Boolean,
            createPublisher: () -> PhotoRollJpegPublisher,
        ): PhotoRollJpegSave {
            require(mode == PhotoRollPreviewMode.STAGE_CLEAR) { "Trial previews cannot be saved as completed JPEG" }
            require(render.request.source.uri == originalUri && render.request.source.fingerprint == fingerprint) {
                "Completed JPEG must refer to the selected checked original"
            }
            render.requirePublishable(supportedContracts)
            return PhotoRollJpegSave(render, createPublisher(), isCurrent)
        }
    }
}

internal fun unavailableJpegSaveMessage(mode: PhotoRollPreviewMode): String = when (mode) {
    PhotoRollPreviewMode.STAGE_CLEAR_TRIAL -> "長辺512以下の試験画像は、完成JPEGとして保存できません"
    PhotoRollPreviewMode.DNG_TRIAL -> "この画像はルック未適用の試験表示です。完成JPEGとしては保存できません"
    PhotoRollPreviewMode.STAGE_CLEAR -> "完成JPEGの生成・保存経路はまだ接続されていません"
}
