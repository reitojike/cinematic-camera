package dev.cinematic.p0.photoroll

import android.graphics.Bitmap
import android.net.Uri
import android.os.CancellationSignal
import dev.cinematic.p0.rawcapture.OriginalFingerprint
import dev.cinematic.p0.rawcapture.OriginalGrant
import dev.cinematic.p0.rawcapture.SharedOriginalReference

/** A checked shared original. Open only with ContentResolver's read-only stream/FD APIs. */
internal data class PhotoRollOriginal(val uri: Uri, val fingerprint: OriginalFingerprint, val grant: OriginalGrant) {
    /** Selection origin only. This never grants or persists provider permissions. */
    val reference get() = SharedOriginalReference(uri.toString(), fingerprint, grant)
}

internal data class PhotoRollRenderRequest(
    val original: PhotoRollOriginal,
    val lookVersion: String = "stage-clear-v1",
    val strength: Double = 0.5,
    val maxEdge: Int = 1024,
    val mode: PhotoRollPreviewMode = PhotoRollPreviewMode.STAGE_CLEAR,
    val explicitRetry: Boolean = false,
    val onStage: (PhotoRollRenderStage) -> Unit = {},
)

internal sealed interface PhotoRollRenderResult {
    /** Display preview only, not a full-resolution JPEG or publication receipt. */
    data class Preview(
        val bitmap: Bitmap,
        val mode: PhotoRollPreviewMode,
        /** Optional retained completed-JPEG owner; trial renderers must leave this null. */
        val jpegSave: PhotoRollJpegSave? = null,
        /** Independent display-owned pixels. Same source, orientation and dimensions as [bitmap]. */
        val comparisonBitmap: Bitmap? = null,
        /** Frozen strength of a checked private artifact, never the UI's current draft. */
        val trialStrength: Double? = null,
    ) : PhotoRollRenderResult {
        val comparisonLabel get() = comparisonBitmap?.let {
            "RAWの基準表示（撮影時WB・暫定色、露出補正・ルックなし）"
        }
    }
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
    fun checkOriginal(repository: PhotoRollRepository, uri: Uri, fromFiles: Boolean, cancellation: CancellationSignal) =
        repository.check(uri, fromFiles, { cancellation.isCanceled }, maxSourceBytes)
    fun render(request: PhotoRollRenderRequest, cancellation: CancellationSignal): PhotoRollRenderResult
}

internal object UnconnectedPhotoRollRenderer : PhotoRollRenderer {
    override fun render(request: PhotoRollRenderRequest, cancellation: CancellationSignal) =
        PhotoRollRenderResult.Unavailable("DNG現像処理は接続準備中です。原本の確認まで利用できます")
}
