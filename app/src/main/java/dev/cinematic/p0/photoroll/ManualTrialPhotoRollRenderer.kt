package dev.cinematic.p0.photoroll

import android.content.Context
import android.annotation.TargetApi
import android.net.Uri
import android.os.CancellationSignal
import android.os.OperationCanceledException
import dev.cinematic.p0.rawcapture.AndroidManualOriginalTrialSaves
import dev.cinematic.p0.rawcapture.AutoDevelopPolicy
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Original-capable preview and its immutable manual save generation share one queue authority. */
@TargetApi(29)
internal class ManualTrialPhotoRollRenderer(context: Context,
    private val resources: () -> PhotoRollResources) : PhotoRollRenderer {
    private val app = context.applicationContext
    override val mode = PhotoRollPreviewMode.MANUAL_TRIAL
    override val maxEdge = 1024
    override val maxSourceBytes = 64L * 1024 * 1024

    override fun discardTransientInputs() {
        AndroidManualOriginalTrialSaves.invalidatePreviews()
        AndroidManualOriginalTrialSaves.discardPreview(app)
    }

    override fun checkOriginal(repository: PhotoRollRepository, uri: Uri, fromFiles: Boolean,
        cancellation: CancellationSignal): PhotoRollOriginal =
        PhotoRollOriginalChecks.run(resources, cancellation) { readers, stop ->
            repository.check(uri, fromFiles, stop, maxSourceBytes, readers)
        }

    override fun render(request: PhotoRollRenderRequest, cancellation: CancellationSignal): PhotoRollRenderResult {
        require(request.mode == mode && request.lookVersion == "stage-clear-v1" &&
            request.strength.isFinite() && request.strength in 0.0..1.0 && request.maxEdge == maxEdge)
        cancellation.throwIfCanceled()
        if (AutoDevelopPolicy.blockers(resources().developConditions(rendererAvailable = true)).isNotEmpty())
            return PhotoRollRenderResult.Unavailable("撮影状態、空き容量、メモリまたは温度を確認し、撮影を終えてから再試行してください")
        request.onStage(PhotoRollRenderStage.TRIAL_RENDER)
        val future = AndroidManualOriginalTrialSaves.renderPreview(app, request.original.reference,
            request.strength, resources().developConditions(rendererAvailable = true), request.adjustments) {
            cancellation.isCanceled
        }
        val abandoned = AtomicBoolean(false)
        cancellation.setOnCancelListener { AndroidManualOriginalTrialSaves.invalidatePreviews() }
        future.whenComplete { delivery, _ -> if (abandoned.get() || cancellation.isCanceled) delivery?.close() }
        try {
            val delivery = awaitTrialPreview(future, cancellation)
            delivery.use {
                cancellation.throwIfCanceled()
                val handle = delivery.save
                if (!handle.matches(request.original.reference) || handle.appliedStrength != request.strength ||
                    handle.appliedAdjustments != request.adjustments ||
                    handle.geometry.outputWidth.toLong() * handle.geometry.outputHeight > 24_000_000L)
                    throw PhotoRollSourceFailure("表示と保存の原本または調整値が一致しません。再選択してください")
                val owner = ManualTrialJpegSaveOwner(handle, resources)
                val bitmap = delivery.claimBitmap()
                var before: android.graphics.Bitmap? = null
                try {
                    cancellation.throwIfCanceled()
                    before = delivery.claimComparisonBitmap()
                    if (before != null && (before === bitmap || before.width != bitmap.width || before.height != bitmap.height ||
                        before.isRecycled || before.config == android.graphics.Bitmap.Config.HARDWARE ||
                        before.colorSpace?.isSrgb != true))
                        throw PhotoRollSourceFailure("比較画像の向き・寸法を確認できません。再選択してください")
                    val width = handle.geometry.outputWidth
                    val height = handle.geometry.outputHeight
                    val scale = min(1.0, maxEdge.toDouble() / max(width, height))
                    if (bitmap.width != floor(width * scale).toInt() ||
                        bitmap.height != floor(height * scale).toInt())
                        throw PhotoRollSourceFailure("保存する原寸画像とプレビューの向き・寸法が一致しません。再選択してください")
                    return PhotoRollRenderResult.Preview(bitmap, mode, owner, comparisonBitmap = before,
                        appliedStrength = handle.appliedStrength, appliedAdjustments = handle.appliedAdjustments,
                        comparisonDescription = delivery.comparisonDescription.takeIf { before != null })
                } catch (failure: Throwable) {
                    if (!bitmap.isRecycled) bitmap.recycle()
                    if (before !== bitmap && before?.isRecycled == false) before.recycle()
                    throw failure
                }
            }
        } catch (failure: OutOfMemoryError) {
            return PhotoRollRenderResult.Unavailable("試験プレビューのメモリが不足しました。撮影を閉じて再試行してください")
        } catch (_: java.util.concurrent.CancellationException) {
            cancellation.throwIfCanceled()
            return PhotoRollRenderResult.Unavailable("試験処理を中断しました。撮影状態と原本を確認して再試行してください")
        } finally {
            abandoned.set(true)
            // A completion in the small window before abandoned was set is still reclaimed.
            try { future.getNow(null)?.close() } catch (_: Exception) { }
            cancellation.setOnCancelListener(null)
        }
    }
}

internal fun <T> awaitTrialPreview(future: java.util.concurrent.CompletableFuture<T>, cancellation: CancellationSignal): T {
    while (true) {
        cancellation.throwIfCanceled()
        try { return future.get(250, TimeUnit.MILLISECONDS) }
        catch (_: TimeoutException) { /* Keep the queue owner until it completes or cancellation stops it. */ }
        catch (failure: ExecutionException) { throw (failure.cause ?: failure) }
        catch (_: InterruptedException) { Thread.currentThread().interrupt(); throw OperationCanceledException() }
    }
}
