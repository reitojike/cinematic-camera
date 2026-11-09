package dev.cinematic.p0.photoroll

import android.graphics.Bitmap
import android.net.Uri
import android.os.CancellationSignal
import dev.cinematic.p0.rawcapture.OriginalFingerprint
import dev.cinematic.p0.rawcapture.OriginalGrant
import dev.cinematic.p0.rawcapture.SharedOriginalReference
import dev.cinematic.p0.raw.PhotoAdjustments

/** A checked shared original. Open only with ContentResolver's read-only stream/FD APIs. */
internal data class PhotoRollOriginal(val uri: Uri, val fingerprint: OriginalFingerprint, val grant: OriginalGrant,
    /** In-memory preview hint only; deliberately excluded from reference and durable identity. */
    val previewObservation: PhotoRollSourceObservation? = null) {
    /** Selection origin only. This never grants or persists provider permissions. */
    val reference get() = SharedOriginalReference(uri.toString(), fingerprint, grant)
}

internal data class PhotoRollRenderRequest(
    val original: PhotoRollOriginal,
    val lookVersion: String = "stage-clear-v1",
    val strength: Double = 0.5,
    val adjustments: PhotoAdjustments = PhotoAdjustments(),
    val maxEdge: Int = 1024,
    val mode: PhotoRollPreviewMode = PhotoRollPreviewMode.STAGE_CLEAR,
    /** Null outside the integrated diagnostic; 1 is normal serial, 2 is candidate pixel workers. */
    val diagnosticWorkers: Int? = null,
    val explicitRetry: Boolean = false,
    /** An edit release must refresh exact decisions even if display pixels were retained. */
    val refreshDecisions: Boolean = false,
    /** Diagnostic serial admission keeps the permit until an abandoned queue future finishes. */
    val awaitCancelledOwner: Boolean = false,
    val onStage: (PhotoRollRenderStage) -> Unit = {},
)

internal sealed interface PhotoRollRenderResult {
    /** Display preview only, not a full-resolution JPEG or publication receipt.
     * Every result transfers fresh UI-owned Bitmap copies; no alias or reuse across render calls. */
    data class Preview(
        val bitmap: Bitmap,
        val mode: PhotoRollPreviewMode,
        /** Optional retained owner. Manual-trial publication uses a distinct explicit contract. */
        val jpegSave: PhotoRollJpegSaveOwner? = null,
        /** Independent display-owned pixels. Same source, orientation and dimensions as [bitmap]. */
        val comparisonBitmap: Bitmap? = null,
        /** Frozen strength of a checked private artifact, never the UI's current draft. */
        val trialStrength: Double? = null,
        val trialAdjustments: PhotoAdjustments? = null,
        /** Frozen strength of this saveable render, checked before editing or Save. */
        val appliedStrength: Double? = null,
        val appliedAdjustments: PhotoAdjustments? = null,
        /** What the same-size Before pixels represent; required for completed-render comparison. */
        val comparisonDescription: String? = null,
    ) : PhotoRollRenderResult {
        val comparisonLabel get() = comparisonBitmap?.let {
            comparisonDescription ?: if (mode == PhotoRollPreviewMode.PRIVATE_TRIAL)
                "RAWの基準表示（撮影時WB・暫定色、露出補正・ルックなし）" else null
        }
    }
    data class Unavailable(val reason: String) : PhotoRollRenderResult
}

/** Rejected or superseded display copies never remain owned by a cancelled screen request. */
internal fun PhotoRollRenderResult.Preview.recycleDisplayPixels() {
    if (!bitmap.isRecycled) bitmap.recycle()
    comparisonBitmap?.let { before ->
        if (before !== bitmap && !before.isRecycled) before.recycle()
    }
}

/**
 * Called serially off the UI thread, only while this screen is foreground.
 * Validate actual DNG structure/camera/metadata and honor cancellation between stages.
 * STAGE_CLEAR must reject unknown face/exposure/input support, never invent defaults.
 * MANUAL_TRIAL is a separate, explicitly labeled manual-publication contract; never use
 * the completed-quality publisher for a diagnostic render.
 * Diagnostic trial modes must explicitly label unresolved inputs and disabled face protection.
 * Do not write, copy, delete or publish an original or a JPEG here.
 * Preview must be software sRGB, correctly oriented and bounded by request.maxEdge.
 * Cancellation is a request; the serial permit remains held until render returns.
 */
internal interface PhotoRollRenderer {
    /** Discard only optional in-memory input reuse. Never release an in-flight native/IO owner here. */
    fun discardTransientInputs() {}
    /** Optional display-only approximation. Null means keep the previous image, not re-meter at 256px. */
    fun renderDraft(request: PhotoRollRenderRequest, cancellation: CancellationSignal): PhotoRollRenderResult.Preview? = null
    val mode: PhotoRollPreviewMode get() = PhotoRollPreviewMode.STAGE_CLEAR
    val maxSourceBytes: Long get() = PhotoRollRepository.MAX_SOURCE_BYTES
    val maxEdge: Int get() = 1024
    fun checkOriginal(repository: PhotoRollRepository, uri: Uri, fromFiles: Boolean, cancellation: CancellationSignal) =
        repository.check(uri, fromFiles, { cancellation.isCanceled }, maxSourceBytes)
    fun verifyPreviewOriginal(repository: PhotoRollRepository, original: PhotoRollOriginal,
        cancellation: CancellationSignal, shouldStop: () -> Boolean) =
        repository.verify(original, shouldStop)
    fun render(request: PhotoRollRenderRequest, cancellation: CancellationSignal): PhotoRollRenderResult
}

internal object UnconnectedPhotoRollRenderer : PhotoRollRenderer {
    override fun render(request: PhotoRollRenderRequest, cancellation: CancellationSignal) =
        PhotoRollRenderResult.Unavailable("DNG現像処理は接続準備中です。原本の確認まで利用できます")
}
