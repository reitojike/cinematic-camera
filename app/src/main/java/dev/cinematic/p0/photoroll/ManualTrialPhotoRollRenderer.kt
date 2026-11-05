package dev.cinematic.p0.photoroll

import android.net.Uri
import android.os.CancellationSignal
import dev.cinematic.p0.raw.PhotoAdjustments

/** Prepares a durable, generation-bound save action without starting full-size rendering. */
internal fun interface ManualTrialSavePreparation {
    fun prepare(original: PhotoRollOriginal, strength: Double, adjustments: PhotoAdjustments): PhotoRollJpegSaveOwner
}

/** Joins the existing bounded preview to a distinct manual original-size trial save action. */
internal class ManualTrialPhotoRollRenderer(
    private val previewRenderer: PhotoRollRenderer,
    private val savePreparation: ManualTrialSavePreparation,
) : PhotoRollRenderer {
    init { require(previewRenderer.mode == PhotoRollPreviewMode.PRIVATE_TRIAL) }
    override val mode = PhotoRollPreviewMode.MANUAL_TRIAL
    override val maxEdge get() = previewRenderer.maxEdge
    override val maxSourceBytes get() = previewRenderer.maxSourceBytes
    override fun discardTransientInputs() = previewRenderer.discardTransientInputs()
    override fun checkOriginal(repository: PhotoRollRepository, uri: Uri, fromFiles: Boolean,
        cancellation: CancellationSignal): PhotoRollOriginal =
        previewRenderer.checkOriginal(repository, uri, fromFiles, cancellation)

    override fun render(request: PhotoRollRenderRequest, cancellation: CancellationSignal): PhotoRollRenderResult {
        require(request.mode == mode && request.strength.isFinite() && request.strength in 0.0..1.0)
        cancellation.throwIfCanceled()
        val result = previewRenderer.render(request.copy(mode = PhotoRollPreviewMode.PRIVATE_TRIAL), cancellation)
        if (result !is PhotoRollRenderResult.Preview) return result
        try {
            if (result.mode != PhotoRollPreviewMode.PRIVATE_TRIAL || result.trialStrength != request.strength ||
                result.trialAdjustments != request.adjustments ||
                result.jpegSave != null) throw PhotoRollSourceFailure("試験プレビューの強度または保存状態が一致しません")
            cancellation.throwIfCanceled()
            val owner = savePreparation.prepare(request.original, request.strength, request.adjustments)
            if (!owner.matches(request.original.reference, mode) || owner.appliedStrength != request.strength ||
                owner.appliedAdjustments != request.adjustments)
                throw PhotoRollSourceFailure("原寸JPEGの保存対象が表示強度と一致しません")
            cancellation.throwIfCanceled()
            return PhotoRollRenderResult.Preview(result.bitmap, mode, owner, result.comparisonBitmap,
                appliedStrength = request.strength, appliedAdjustments = request.adjustments,
                comparisonDescription = result.comparisonLabel)
        } catch (_: OutOfMemoryError) {
            result.recycleDisplayPixels()
            return PhotoRollRenderResult.Unavailable("試験現像用のメモリが不足しました。アプリを閉じて再試行してください")
        } catch (failure: Throwable) {
            result.recycleDisplayPixels()
            throw failure
        }
    }
}
