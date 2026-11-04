package dev.cinematic.p0.photoroll

import android.content.Context
import android.graphics.Bitmap
import android.os.CancellationSignal
import android.os.OperationCanceledException
import androidx.annotation.RequiresApi
import dev.cinematic.p0.jpegrender.JpegGenerationRecipe
import dev.cinematic.p0.rawcapture.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Uses the sole process queue and its retained private artifact. Never publishes a JPEG. */
@RequiresApi(29)
internal class PrivateTrialPhotoRollRenderer(context: Context, private val resources: () -> PhotoRollResources) : PhotoRollRenderer {
    private val app = context.applicationContext
    override val mode = PhotoRollPreviewMode.PRIVATE_TRIAL
    override val maxEdge = JpegGenerationRecipe.MAX_EDGE
    override val maxSourceBytes = 64L * 1024 * 1024

    override fun render(request: PhotoRollRenderRequest, cancellation: CancellationSignal): PhotoRollRenderResult {
        require(request.mode == mode && request.lookVersion == "stage-clear-v1" && request.strength == .5f && request.maxEdge == maxEdge)
        cancellation.throwIfCanceled()
        val initial = resources().developConditions(rendererAvailable = true)
        if (AutoDevelopPolicy.blockers(initial).isNotEmpty()) return unavailableConditions()
        request.onStage(PhotoRollRenderStage.TRIAL_WAIT)
        val handle = await(AndroidAutoDevelopQueue.preparePrivateTrial(app, request.original.reference), cancellation)
        require(handle.request.source == request.original.reference && handle.request.recipe == JpegGenerationRecipe.trial().snapshot)
        val issuing = Any()
        var stopped = false
        var issued = false
        fun <T> issue(operation: () -> CompletableFuture<T>): CompletableFuture<T> = synchronized(issuing) {
            cancellation.throwIfCanceled()
            check(!stopped)
            operation().also { issued = true }
        }
        cancellation.setOnCancelListener {
            synchronized(issuing) {
                if (!stopped) { stopped = true; if (issued) handle.requestStop() }
            }
        }
        try {
            var status = await(handle.status(), cancellation)
            if (status.phase == PrivateTrialPhase.RETRYABLE && request.explicitRetry)
                status = await(issue { handle.retry() }, cancellation)
            if (status.phase in setOf(PrivateTrialPhase.PREPARED, PrivateTrialPhase.RUNNING)) {
                request.onStage(PhotoRollRenderStage.TRIAL_RENDER)
                status = await(issue { handle.start(resources().developConditions(rendererAvailable = true)) }, cancellation)
            }
            while (status.phase in setOf(PrivateTrialPhase.PREPARED, PrivateTrialPhase.RUNNING)) {
                cancellation.throwIfCanceled()
                if (AutoDevelopPolicy.blockers(resources().developConditions(rendererAvailable = true)).isNotEmpty()) {
                    cancellation.cancel()
                    return unavailableConditions()
                }
                Thread.sleep(100)
                status = await(handle.status(), cancellation)
            }
            if (status.phase != PrivateTrialPhase.READY) return PhotoRollRenderResult.Unavailable(when (status.reason) {
                AutoJobReason.SOURCE_CHANGED, AutoJobReason.CONTENT_CHANGED -> "原本の内容が変わったため停止しました。Filesから選び直してください"
                AutoJobReason.SOURCE_UNAVAILABLE -> "原本を読み取れません。Filesから選び直してください"
                AutoJobReason.INTERRUPTED -> "試験現像を中断しました。原本を確認して再試行してください"
                AutoJobReason.UNSUPPORTED -> "このDNGは現在の縮小試験現像で扱えません"
                else -> "試験JPEGを確認できません。原本を確認して再試行してください"
            })
            request.onStage(PhotoRollRenderStage.TRIAL_READBACK)
            val delivery = await(issue { handle.readPreview(resources().developConditions(rendererAvailable = true)) { cancellation.isCanceled } }, cancellation)
            try {
                cancellation.throwIfCanceled()
                val preview = delivery.claim() ?: return PhotoRollRenderResult.Unavailable("試験結果が中断・変更されたため表示できません。再試行してください")
                try {
                    val observed = preview.status
                    val output = requireNotNull(observed.output)
                    require(observed.request == handle.request && observed.request.source == request.original.reference &&
                        observed.phase == PrivateTrialPhase.READY && observed.face == RenderedFaceState.NOT_RUN &&
                        observed.exposure == RenderedExposureState.DIAGNOSTIC && output.width in 1..maxEdge && output.height in 1..maxEdge &&
                        preview.bitmap.width == output.width && preview.bitmap.height == output.height && preview.bitmap.colorSpace?.isSrgb == true)
                    cancellation.throwIfCanceled()
                    // Never give a queue-owned/recycled bitmap to Compose. At most 4MiB of display pixels.
                    val bitmap = requireNotNull(preview.bitmap.copy(Bitmap.Config.ARGB_8888, false))
                    if (cancellation.isCanceled) { bitmap.recycle(); cancellation.throwIfCanceled() }
                    return PhotoRollRenderResult.Preview(bitmap, mode)
                } finally { preview.close() }
            } finally { delivery.close() }
        } catch (_: java.util.concurrent.CancellationException) {
            cancellation.throwIfCanceled()
            return PhotoRollRenderResult.Unavailable("試験処理が中断・変更されました。原本を確認して再試行してください")
        } catch (_: OutOfMemoryError) {
            cancellation.cancel()
            return PhotoRollRenderResult.Unavailable("試験表示のメモリが足りません。他のアプリを閉じて再試行してください")
        } catch (failure: Exception) {
            cancellation.cancel()
            throw failure
        } finally { cancellation.setOnCancelListener(null) }
    }

    private fun unavailableConditions() = PhotoRollRenderResult.Unavailable(
        "撮影・録画の終了、端末温度、空きメモリ・保存先の状態を確認できません。撮影を終了して再試行してください")

    private fun <T> await(future: CompletableFuture<T>, cancellation: CancellationSignal): T {
        while (true) {
            cancellation.throwIfCanceled()
            try { return future.get(250, TimeUnit.MILLISECONDS) }
            catch (_: TimeoutException) { /* Retain the queue owner until its actual completion. */ }
            catch (failure: ExecutionException) { throw (failure.cause ?: failure) }
            catch (_: InterruptedException) { Thread.currentThread().interrupt(); throw OperationCanceledException() }
        }
    }
}
