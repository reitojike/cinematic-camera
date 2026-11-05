package dev.cinematic.p0.photoroll

import android.content.Context
import android.graphics.Bitmap
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.util.Log
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
    private val reuseLock = Any()
    private var reuseSession: PrivateTrialReuseSession? = null
    override fun discardTransientInputs() = synchronized(reuseLock) {
        reuseSession?.close()
        reuseSession = null
    }
    private fun currentReuseSession() = synchronized(reuseLock) {
        reuseSession?.takeUnless { it.closed }
            ?: PrivateTrialReuseRegistry.openSession().also { reuseSession = it }
    }
    override val mode = PhotoRollPreviewMode.PRIVATE_TRIAL
    override val maxEdge = JpegGenerationRecipe.MAX_EDGE
    override val maxSourceBytes = 64L * 1024 * 1024
    override fun checkOriginal(repository: PhotoRollRepository, uri: android.net.Uri, fromFiles: Boolean, cancellation: CancellationSignal) =
        PhotoRollOriginalChecks.run(resources, cancellation) { readers, stop ->
            repository.check(uri, fromFiles, stop, maxSourceBytes, readers)
        }

    override fun render(request: PhotoRollRenderRequest, cancellation: CancellationSignal): PhotoRollRenderResult {
        require(request.mode == mode && request.lookVersion == "stage-clear-v1" && request.strength.isFinite() &&
            request.strength in 0.0..1.0 && request.maxEdge == maxEdge)
        val recipe = JpegGenerationRecipe.trial(request.strength)
        cancellation.throwIfCanceled()
        val initial = resources().developConditions(rendererAvailable = true)
        if (AutoDevelopPolicy.blockers(initial).isNotEmpty()) {
            discardTransientInputs()
            return unavailableConditions()
        }
        val session = currentReuseSession()
        request.onStage(PhotoRollRenderStage.TRIAL_WAIT)
        val handle = await(AndroidAutoDevelopQueue.preparePrivateTrial(app, request.original.reference, recipe,
            reuseSession = session) { cancellation.isCanceled }, cancellation)
        require(handle.request.source == request.original.reference && handle.request.recipe == recipe.snapshot)
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
        var stage = "status"
        try {
            var status = await(handle.status(), cancellation)
            if (status.phase == PrivateTrialPhase.RETRYABLE && request.explicitRetry) {
                stage = "retry"
                status = await(issue { handle.retry() }, cancellation)
            }
            if (status.phase in setOf(PrivateTrialPhase.PREPARED, PrivateTrialPhase.RUNNING)) {
                request.onStage(PhotoRollRenderStage.TRIAL_RENDER)
                stage = "start"
                status = await(issue { handle.start(resources().developConditions(rendererAvailable = true)) }, cancellation)
            }
            while (status.phase in setOf(PrivateTrialPhase.PREPARED, PrivateTrialPhase.RUNNING)) {
                stage = "poll"
                cancellation.throwIfCanceled()
                if (AutoDevelopPolicy.blockers(resources().developConditions(rendererAvailable = true)).isNotEmpty()) {
                    discardTransientInputs()
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
            stage = "readback"
            val delivery = await(issue { handle.readPreview(resources().developConditions(rendererAvailable = true)) { cancellation.isCanceled } }, cancellation)
            try {
                cancellation.throwIfCanceled()
                stage = "claim"
                val preview = delivery.claim() ?: return PhotoRollRenderResult.Unavailable("試験結果が中断・変更されたため表示できません。再試行してください")
                try {
                    val observed = preview.status
                    val output = requireNotNull(observed.output)
                    require(observed.request == handle.request && observed.request.source == request.original.reference &&
                        observed.phase == PrivateTrialPhase.READY && observed.face == RenderedFaceState.NOT_RUN &&
                        observed.exposure == RenderedExposureState.DIAGNOSTIC && output.width in 1..maxEdge && output.height in 1..maxEdge &&
                        preview.bitmap.width == output.width && preview.bitmap.height == output.height && preview.bitmap.colorSpace?.isSrgb == true)
                    val copied = copyPrivateTrialDisplay(preview.bitmap, preview.comparisonBitmap, cancellation)
                    return PhotoRollRenderResult.Preview(copied.after, mode, comparisonBitmap = copied.comparison,
                        trialStrength = recipe.strength)
                } finally { preview.close() }
            } finally { delivery.close() }
        } catch (failure: java.util.concurrent.CancellationException) {
            cancellation.throwIfCanceled()
            val kind = when (failure.message) {
                "Manual invocation cancelled" -> "STALE_INTENT"
                "Private preview not current" -> "STALE_DELIVERY"
                "Private trial validation cancelled" -> "READBACK_STOP"
                else -> "OTHER"
            }
            // Bounded reason codes only: never log a source URI, filename, DNG content or throwable.
            Log.w("CinematicRaw", "PRIVATE_TRIAL_INTERRUPTED stage=$stage kind=$kind")
            return PhotoRollRenderResult.Unavailable("試験処理が中断されました。状態を確認して再試行してください")
        } catch (_: OutOfMemoryError) {
            discardTransientInputs()
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

internal data class PrivateTrialDisplayCopy(val after: Bitmap, val comparison: Bitmap?)

/** No queue-owned image reaches Compose. Optional comparison-copy failure preserves the checked After. */
internal fun copyPrivateTrialDisplay(after: Bitmap, before: Bitmap?, cancellation: CancellationSignal,
    copyComparison: (Bitmap) -> Bitmap? = { it.copy(Bitmap.Config.ARGB_8888, false) }): PrivateTrialDisplayCopy {
    cancellation.throwIfCanceled()
    val bitmap = requireNotNull(after.copy(Bitmap.Config.ARGB_8888, false))
    var comparison: Bitmap? = null
    try {
        cancellation.throwIfCanceled()
        if (before != null && !before.isRecycled && before.config != Bitmap.Config.HARDWARE && before.colorSpace?.isSrgb == true &&
            before.width == after.width && before.height == after.height) {
            comparison = try { copyComparison(before) }
            catch (failure: OperationCanceledException) { throw failure }
            catch (failure: java.util.concurrent.CancellationException) { throw failure }
            catch (_: OutOfMemoryError) { null }
            catch (_: RuntimeException) { null }
        }
        cancellation.throwIfCanceled()
        return PrivateTrialDisplayCopy(bitmap, comparison)
    } catch (failure: Throwable) {
        bitmap.recycle(); comparison?.recycle()
        throw failure
    }
}
