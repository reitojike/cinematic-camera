package dev.cinematic.p0.photoroll

import android.content.ContentResolver
import android.graphics.Bitmap
import android.os.CancellationSignal
import dev.cinematic.p0.raw.DngPreviewDecoder
import dev.cinematic.p0.raw.DngPreviewFailure
import dev.cinematic.p0.raw.DngPreviewResult

internal class DngTrialRenderer(
    private val resolver: ContentResolver,
    private val resources: () -> PhotoRollResources,
) : PhotoRollRenderer {
    override val mode = PhotoRollPreviewMode.DNG_TRIAL
    override val maxSourceBytes = 64L * 1024 * 1024

    override fun render(request: PhotoRollRenderRequest, cancellation: CancellationSignal): PhotoRollRenderResult {
        if (request.mode != mode) return PhotoRollRenderResult.Unavailable("この表示はDNGの試験用です。Stage Clear現像はまだ利用できません")
        cancellation.throwIfCanceled()
        resources().blockedReason()?.let { return PhotoRollRenderResult.Unavailable(it) }
        return try { when (val decoded = DngPreviewDecoder.decode(resolver, request.original.uri, cancellation, request.maxEdge)) {
            is DngPreviewResult.Failed -> PhotoRollRenderResult.Unavailable(failureMessage(decoded.reason))
            is DngPreviewResult.Ready -> {
                val image = decoded.preliminaryLinearSrgb
                val pixels = TrialDisplayPixels.encode(image, request.maxEdge) { cancellation.isCanceled }
                cancellation.throwIfCanceled()
                val bitmap = Bitmap.createBitmap(pixels, image.width, image.height, Bitmap.Config.ARGB_8888)
                if (cancellation.isCanceled) { bitmap.recycle(); cancellation.throwIfCanceled() }
                PhotoRollRenderResult.Preview(bitmap, mode)
            }
        } } catch (_: OutOfMemoryError) { PhotoRollRenderResult.Unavailable(failureMessage(DngPreviewFailure.MEMORY)) }
    }

    private fun failureMessage(reason: DngPreviewFailure) = when (reason) {
        DngPreviewFailure.BUSY -> "別のDNGを処理しています。処理が終了してから再試行してください"
        DngPreviewFailure.CANCELLED -> "DNGの試験表示を中断しました"
        DngPreviewFailure.SOURCE -> "原本を読み取れません。Filesから選び直してください"
        DngPreviewFailure.LIMIT -> "この試験表示は64 MiB以下・16MP以下のDNGに限られます"
        DngPreviewFailure.UNSUPPORTED -> "このDNGの形式や撮影情報にはまだ対応していません"
        DngPreviewFailure.MEMORY -> "DNGの処理メモリが不足しました。他のアプリを閉じて再試行してください"
        DngPreviewFailure.UNAVAILABLE -> "DNGデコード機能を利用できません"
    }
}
