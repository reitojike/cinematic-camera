package dev.cinematic.p0.photoroll

import android.graphics.Bitmap
import android.net.Uri
import android.os.CancellationSignal
import dev.cinematic.p0.rawcapture.OriginalFingerprint

/** A checked shared original. Open only with ContentResolver's read-only stream/FD APIs. */
internal data class PhotoRollOriginal(val uri: Uri, val fingerprint: OriginalFingerprint)

internal data class PhotoRollRenderRequest(
    val original: PhotoRollOriginal,
    val lookVersion: String = "stage-clear-v1",
    val strength: Float = 0.5f,
    val maxEdge: Int = 1024,
    val mode: PhotoRollPreviewMode = PhotoRollPreviewMode.STAGE_CLEAR,
    val onStage: (PhotoRollRenderStage) -> Unit = {},
)

internal sealed interface PhotoRollRenderResult {
    /** Display preview only, not a full-resolution JPEG or publication receipt. */
    data class Preview(val bitmap: Bitmap, val mode: PhotoRollPreviewMode) : PhotoRollRenderResult
    data class Unavailable(val reason: String) : PhotoRollRenderResult
}

/**
 * Called serially off the UI thread, only while this screen is foreground.
 * Validate actual DNG structure/camera/metadata and honor cancellation between stages.
 * STAGE_CLEAR must reject unknown face/exposure/input support, never invent defaults.
 * Diagnostic trial modes must explicitly label unresolved inputs and disabled face protection.
 * Do not write, copy, delete or publish an original or a JPEG here.
 * Preview must be software sRGB, correctly oriented and bounded by request.maxEdge.
 * Cancellation is a request; the serial permit remains held until render returns.
 */
internal interface PhotoRollRenderer {
    val mode: PhotoRollPreviewMode get() = PhotoRollPreviewMode.STAGE_CLEAR
    val maxSourceBytes: Long get() = PhotoRollRepository.MAX_SOURCE_BYTES
    val maxEdge: Int get() = 1024
    fun render(request: PhotoRollRenderRequest, cancellation: CancellationSignal): PhotoRollRenderResult
}

internal object UnconnectedPhotoRollRenderer : PhotoRollRenderer {
    override fun render(request: PhotoRollRenderRequest, cancellation: CancellationSignal) =
        PhotoRollRenderResult.Unavailable("DNG現像処理は接続準備中です。原本の確認まで利用できます")
}
