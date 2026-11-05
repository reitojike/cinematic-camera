package dev.cinematic.p0.photoroll

import dev.cinematic.p0.rawcapture.CompletedRender
import dev.cinematic.p0.rawcapture.JpegPublicationPhase
import dev.cinematic.p0.rawcapture.JpegPublicationRecord
import dev.cinematic.p0.rawcapture.RendererPublicationContract
import dev.cinematic.p0.rawcapture.SharedOriginalReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow

internal enum class PhotoRollSavePhase { READY, SAVING, PUBLISHED, UNCONFIRMED, HOLD }
internal data class PhotoRollSaveSnapshot(val phase: PhotoRollSavePhase, val uri: String? = null)
internal fun interface PhotoRollJpegPublisher {
    fun advance(shouldStop: () -> Boolean): JpegPublicationRecord
}

/** UI-only boundary. Trial publication must have its own explicit manual contract and durable owner.
 * A manual-trial implementation must bind appliedStrength to its immutable private JPEG recipe,
 * retain one operation for retries, serialize advance, recheck the original, and hold UNKNOWN.
 */
internal interface PhotoRollJpegSaveOwner {
    val snapshots: StateFlow<PhotoRollSaveSnapshot>
    val snapshot: PhotoRollSaveSnapshot
    val width: Int
    val height: Int
    val appliedStrength: Double? get() = null
    fun matches(source: SharedOriginalReference, mode: PhotoRollPreviewMode): Boolean
    fun advance(shouldStop: () -> Boolean): PhotoRollSaveSnapshot
}

/** Retained by the completed renderer per durable operation, never recreated by a UI retry. */
internal class PhotoRollJpegSave private constructor(
    private val render: CompletedRender,
    private val publisher: PhotoRollJpegPublisher,
    private val isCurrent: () -> Boolean,
) : PhotoRollJpegSaveOwner {
    private val running = AtomicBoolean(false)
    private val state = MutableStateFlow(PhotoRollSaveSnapshot(PhotoRollSavePhase.READY))
    override val snapshots = state.asStateFlow()
    override val snapshot get() = state.value
    override val width get() = render.request.output.width
    override val height get() = render.request.output.height

    override fun matches(source: SharedOriginalReference, mode: PhotoRollPreviewMode) =
        mode == PhotoRollPreviewMode.STAGE_CLEAR && render.request.source == source

    /** Also used immediately before opening a published result; reuses and rechecks the same publisher. */
    override fun advance(shouldStop: () -> Boolean): PhotoRollSaveSnapshot {
        // A contender has not rechecked anything. Never return another call's published receipt.
        if (!running.compareAndSet(false, true)) return PhotoRollSaveSnapshot(PhotoRollSavePhase.SAVING)
        try {
            if (snapshot.phase == PhotoRollSavePhase.HOLD) return snapshot
            state.value = PhotoRollSaveSnapshot(PhotoRollSavePhase.SAVING)
            val stopped = { shouldStop() || !isCurrent() }
            if (stopped()) return unconfirmed()
            val record = publisher.advance(stopped)
            state.value = when {
                stopped() -> PhotoRollSaveSnapshot(PhotoRollSavePhase.UNCONFIRMED)
                record.render != render || record.target.captureId != render.request.captureId ||
                    record.target.generation != render.request.generation -> PhotoRollSaveSnapshot(PhotoRollSavePhase.HOLD)
                record.phase == JpegPublicationPhase.PUBLISHED && record.uri != null ->
                    PhotoRollSaveSnapshot(PhotoRollSavePhase.PUBLISHED, record.uri)
                record.phase == JpegPublicationPhase.UNKNOWN -> PhotoRollSaveSnapshot(PhotoRollSavePhase.HOLD)
                else -> PhotoRollSaveSnapshot(PhotoRollSavePhase.UNCONFIRMED)
            }
            return snapshot
        } catch (_: Exception) { return unconfirmed() }
        catch (_: OutOfMemoryError) { return unconfirmed() }
        finally { running.set(false) }
    }

    private fun unconfirmed() = PhotoRollSaveSnapshot(PhotoRollSavePhase.UNCONFIRMED).also { state.value = it }

    companion object {
        /** Validation precedes factory invocation: trial/unsupported inputs cannot allocate a public operation. */
        fun prepare(
            source: SharedOriginalReference,
            mode: PhotoRollPreviewMode,
            render: CompletedRender,
            supportedContracts: Set<RendererPublicationContract>,
            isCurrent: () -> Boolean,
            createPublisher: () -> PhotoRollJpegPublisher,
        ): PhotoRollJpegSave {
            require(mode == PhotoRollPreviewMode.STAGE_CLEAR) { "Trial previews cannot be saved as completed JPEG" }
            require(render.request.source == source) {
                "Completed JPEG must refer to the selected checked original"
            }
            render.requirePublishable(supportedContracts)
            return PhotoRollJpegSave(render, createPublisher(), isCurrent)
        }
    }
}

internal fun unavailableJpegSaveMessage(mode: PhotoRollPreviewMode): String = when (mode) {
    PhotoRollPreviewMode.MANUAL_TRIAL -> "試験品質JPEGの手動保存は準備中です。原本は変更しません"
    PhotoRollPreviewMode.PRIVATE_TRIAL -> "縮小試験JPEGはアプリ内表示のみです。共有写真への保存は無効です"
    PhotoRollPreviewMode.STAGE_CLEAR_TRIAL -> "長辺512以下の試験画像は、完成JPEGとして保存できません"
    PhotoRollPreviewMode.DNG_TRIAL -> "この画像はルック未適用の試験表示です。完成JPEGとしては保存できません"
    PhotoRollPreviewMode.STAGE_CLEAR -> "完成JPEGの生成・保存経路はまだ接続されていません"
}
