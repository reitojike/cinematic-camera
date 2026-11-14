package dev.cinematic.p0.photoroll

import android.content.ContentResolver
import android.graphics.Bitmap
import android.os.CancellationSignal
import dev.cinematic.p0.raw.DngPreviewDecoder
import dev.cinematic.p0.raw.DngPreviewResult

/** Display-only diagnostic route. No detector, completed Pixel profile or JPEG publication. */
internal class StageClearTrialRenderer(
    private val resolver: ContentResolver,
    private val resources: () -> PhotoRollResources,
) : PhotoRollRenderer {
    override val mode = PhotoRollPreviewMode.STAGE_CLEAR_TRIAL
    override val maxSourceBytes = 64L * 1024 * 1024
    override val maxEdge = StageClearTrialPipeline.MAX_EDGE

    override fun render(request: PhotoRollRenderRequest, cancellation: CancellationSignal): PhotoRollRenderResult {
        if (request.mode != mode || request.lookVersion != StageClearTrialPipeline.LOOK_VERSION ||
            request.strength != StageClearTrialPipeline.STRENGTH || request.maxEdge !in 1..maxEdge) {
            return PhotoRollRenderResult.Unavailable("この試験表示はStage Clear v1・強度0.5・長辺512以下に限ります")
        }
        cancellation.throwIfCanceled()
        resources().blockedReason()?.let { return PhotoRollRenderResult.Unavailable(it) }
        return try {
            request.onStage(PhotoRollRenderStage.DECODE)
            cancellation.throwIfCanceled()
            when (val decoded = DngPreviewDecoder.decode(resolver, request.original.uri, cancellation, request.maxEdge)) {
                is DngPreviewResult.Failed -> PhotoRollRenderResult.Unavailable(dngPreviewFailureMessage(decoded.reason))
                is DngPreviewResult.Ready -> {
                    val image = StageClearTrialPipeline.render(decoded.preliminaryLinearSrgb, { cancellation.isCanceled }, request.onStage)
                    cancellation.throwIfCanceled()
                    request.onStage(PhotoRollRenderStage.DISPLAY)
                    val pixels = TrialDisplayPixels.encode(image, request.maxEdge) { cancellation.isCanceled }
                    cancellation.throwIfCanceled()
                    val bitmap = Bitmap.createBitmap(pixels, image.width, image.height, Bitmap.Config.ARGB_8888)
                    if (cancellation.isCanceled) { bitmap.recycle(); cancellation.throwIfCanceled() }
                    PhotoRollRenderResult.Preview(bitmap, mode)
                }
            }
        } catch (_: OutOfMemoryError) {
            PhotoRollRenderResult.Unavailable("試験表示のメモリが不足しました。他のアプリを閉じて再試行してください")
        }
    }
}
