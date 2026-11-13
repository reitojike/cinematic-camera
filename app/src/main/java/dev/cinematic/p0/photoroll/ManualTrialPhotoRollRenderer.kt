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
import java.util.concurrent.atomic.AtomicLong
import dev.cinematic.p0.rawcapture.DngIoResources
import dev.cinematic.p0.rawcapture.DngVerificationStopped
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Original-capable preview and its immutable manual save generation share one queue authority. */
@TargetApi(29)
internal class ManualTrialPhotoRollRenderer(context: Context,
    private val resources: () -> PhotoRollResources,
    private val previewCache: PhotoRollPreviewCache? = null,
    val developEngine: dev.cinematic.p0.gpu.DevelopEngine = dev.cinematic.p0.gpu.DevelopEngineSetting(context).current(),
) : PhotoRollRenderer {
    private val app = context.applicationContext
    override val mode = PhotoRollPreviewMode.MANUAL_TRIAL
    override val maxEdge = 1024
    override val maxSourceBytes = 64L * 1024 * 1024

    private val originalEpoch = AtomicLong(0)
    private data class CheckedOriginal(val original: PhotoRollOriginal, val fromFiles: Boolean, val epoch: Long,
        val repository: PhotoRollRepository)
    @Volatile private var checkedOriginal: CheckedOriginal? = null

    override fun discardTransientInputs() {
        originalEpoch.incrementAndGet()
        checkedOriginal = null
        if (developEngine == dev.cinematic.p0.gpu.DevelopEngine.CPU) {
            AndroidManualOriginalTrialSaves.invalidatePreviews()
            AndroidManualOriginalTrialSaves.discardPreview(app)
        }
    }

    override fun checkOriginal(repository: PhotoRollRepository, uri: Uri, fromFiles: Boolean,
        cancellation: CancellationSignal): PhotoRollOriginal =
        PhotoRollOriginalChecks.run(resources, cancellation, preview = true) { readers, stop ->
            val epoch = originalEpoch.get()
            val prior = checkedOriginal?.takeIf { it.epoch == epoch && it.original.uri == uri && it.fromFiles == fromFiles }
            val original = if (prior != null) {
                repository.verifyPreview(prior.original, stop, readers = readers)
                prior.original
            } else repository.check(uri, fromFiles, stop, maxSourceBytes, readers)
            if (stop() || epoch != originalEpoch.get()) throw DngVerificationStopped()
            checkedOriginal = CheckedOriginal(original, fromFiles, epoch, repository)
            original
        }

    private fun checked(original: PhotoRollOriginal): CheckedOriginal = checkedOriginal?.takeIf {
        it.epoch == originalEpoch.get() && it.original.reference == original.reference
    } ?: throw PhotoRollSourceFailure("原本の確認が失効しました。写真を開き直してください")

    private fun previewCheck(original: PhotoRollOriginal): (DngIoResources, () -> Boolean) -> Unit {
        val checked = checked(original)
        return { io, stop ->
            checked.repository.verifyPreview(checked.original, { stop() || checked.epoch != originalEpoch.get() }, io)
        }
    }

    override fun verifyPreviewOriginal(repository: PhotoRollRepository, original: PhotoRollOriginal,
        cancellation: CancellationSignal, shouldStop: () -> Boolean) {
        val checked = checked(original)
        PhotoRollOriginalChecks.run(resources, cancellation, preview = true) { readers, stop ->
            repository.verifyPreview(checked.original,
                { shouldStop() || stop() || checked.epoch != originalEpoch.get() }, readers = readers)
        }
    }

    internal data class GpuInput(val original: PhotoRollOriginal,
        val input: dev.cinematic.p0.raw.LinearSrgbImage,
        val geometry: dev.cinematic.p0.jpegrender.original.Original24PreflightGeometry,
        val values: dev.cinematic.p0.photoedit.PhotoEditValues,
        val retainedSave: PhotoRollJpegSaveOwner?)

    /** Called once per open/retry, on IO. The frame loop never reopens the original. */
    fun openGpu(repository: PhotoRollRepository, uri: Uri, fromFiles: Boolean,
        cancellation: CancellationSignal, deadline: dev.cinematic.p0.gpu.preview.GpuInputRequestDeadline,
        admitted: (Throwable?) -> Unit,
        values: (PhotoRollOriginal) -> dev.cinematic.p0.photoedit.PhotoEditValues): GpuInput {
        check(developEngine == dev.cinematic.p0.gpu.DevelopEngine.GPU)
        val epoch = originalEpoch.get()
        val loaded = awaitTrialPreview(GpuPhotoRollInputs.open(app, resources, repository, uri, fromFiles,
            maxSourceBytes, cancellation, deadline, admitted) { epoch != originalEpoch.get() },
            cancellation, awaitCancelledOwner = true)
        cancellation.throwIfCanceled()
        if (epoch != originalEpoch.get()) throw DngVerificationStopped()
        checkedOriginal = CheckedOriginal(loaded.original, fromFiles, epoch, repository)
        return GpuInput(loaded.original, loaded.image, loaded.geometry, values(loaded.original),
            AndroidManualOriginalTrialSaves.knownPrepared(loaded.original.reference)?.let { ManualTrialJpegSaveOwner(it, resources) })
    }

    /** Metadata may wait behind another photo's export. Never put this on the input/display path. */
    fun restoreGpuSave(loaded: GpuInput, cancellation: CancellationSignal): PhotoRollJpegSaveOwner? {
        val epoch = originalEpoch.get()
        val restored = awaitTrialPreview(AndroidManualOriginalTrialSaves.restorePreparedGpu(app,
            loaded.original.reference, loaded.geometry) { cancellation.isCanceled || epoch != originalEpoch.get() }, cancellation)
        cancellation.throwIfCanceled()
        return restored?.let { ManualTrialJpegSaveOwner(it, resources) }
    }

    /** URI is used only to pause this exact live export, never to authorize new source content. */
    internal data class ActiveGpuSave(val source: dev.cinematic.p0.rawcapture.SharedOriginalReference,
        val save: PhotoRollJpegSaveOwner, val values: dev.cinematic.p0.photoedit.PhotoEditValues)
    fun activeGpuSave(uri: String): ActiveGpuSave? {
        val handle = ForegroundExports.gate.active()?.value?.identity as?
            AndroidManualOriginalTrialSaves.ManualOriginalTrialHandle ?: return null
        return handle.takeIf { it.request.source.uri == uri }?.let {
            ActiveGpuSave(it.request.source, ManualTrialJpegSaveOwner(it, resources),
                dev.cinematic.p0.photoedit.PhotoEditValues(it.appliedStrength, it.appliedAdjustments.warmth, it.appliedAdjustments.exposureEv))
        }
    }

    /** A committed recipe gets its own existing save generation, never a GPU-frame-side write. */
    fun prepareGpuSave(loaded: GpuInput, values: dev.cinematic.p0.photoedit.PhotoEditValues,
        cancellation: CancellationSignal): PhotoRollJpegSaveOwner {
        check(developEngine == dev.cinematic.p0.gpu.DevelopEngine.GPU)
        val original = loaded.original
        checked(original)
        val handle = awaitTrialPreview(AndroidManualOriginalTrialSaves.prepareGpu(app, original.reference, loaded.geometry,
            values.strength, resources().developConditions(true),
            dev.cinematic.p0.photoedit.PhotoAdjustmentPolicy.adjustments(values)) { cancellation.isCanceled },
            cancellation, awaitCancelledOwner = true)
        cancellation.throwIfCanceled()
        require(handle.matches(original.reference) && handle.geometry == loaded.geometry &&
            handle.appliedStrength == values.strength &&
            handle.appliedAdjustments == dev.cinematic.p0.photoedit.PhotoAdjustmentPolicy.adjustments(values))
        return ManualTrialJpegSaveOwner(handle, resources)
    }

    override fun renderDraft(request: PhotoRollRenderRequest, cancellation: CancellationSignal): PhotoRollRenderResult.Preview? {
        check(developEngine == dev.cinematic.p0.gpu.DevelopEngine.CPU) { "GPU preview must not enter the CPU draft path" }
        require(request.mode == mode && request.lookVersion == "stage-clear-v1" && request.maxEdge == 256)
        cancellation.throwIfCanceled()
        if (AutoDevelopPolicy.previewBlockers(resources().developConditions(rendererAvailable = true)).isNotEmpty()) return null
        val future = AndroidManualOriginalTrialSaves.renderDraftPreview(app, request.original.reference,
            request.strength, resources().developConditions(rendererAvailable = true), request.adjustments, request.diagnosticWorkers) { cancellation.isCanceled }
        val abandoned = AtomicBoolean(false)
        cancellation.setOnCancelListener { AndroidManualOriginalTrialSaves.invalidatePreviews() }
        future.whenComplete { delivery, _ -> if (abandoned.get() || cancellation.isCanceled) delivery?.close() }
        try {
            val delivery = awaitTrialPreview(future, cancellation, awaitCancelledOwner = true) ?: return null
            delivery.use {
                cancellation.throwIfCanceled()
                val bitmap = it.claimBitmap()
                var before: android.graphics.Bitmap? = null
                try {
                    before = it.claimComparisonBitmap()
                    cancellation.throwIfCanceled()
                    return PhotoRollRenderResult.Preview(bitmap, mode, comparisonBitmap = before,
                        comparisonDescription = before?.let { "撮影時WBのRAW基準表示（R7・ルック・手動調整なし、暫定色）" })
                } catch (error: Throwable) { bitmap.recycle(); before?.recycle(); throw error }
            }
        } finally {
            abandoned.set(true)
            try { future.getNow(null)?.close() } catch (_: Exception) { }
            cancellation.setOnCancelListener(null)
        }
    }

    override fun render(request: PhotoRollRenderRequest, cancellation: CancellationSignal): PhotoRollRenderResult {
        check(developEngine == dev.cinematic.p0.gpu.DevelopEngine.CPU) { "GPU preview must not enter the CPU render path" }
        require(request.mode == mode && request.lookVersion == "stage-clear-v1" &&
            request.strength.isFinite() && request.strength in 0.0..1.0 && request.maxEdge == maxEdge)
        cancellation.throwIfCanceled()
        photoRollAdmissionFailure(resources().developConditions(rendererAvailable = true), preview = true)?.let {
            return PhotoRollRenderResult.Unavailable(it)
        }
        val key = PhotoRollPreviewKey(request.original.reference, request.strength, request.adjustments,
            request.lookVersion, request.maxEdge, request.mode)
        // Diagnostic comparisons must execute, never return a retained display-only bitmap.
        val cacheTicket = if (request.diagnosticWorkers != null) null else previewCache?.begin(key, request.explicitRetry)
        val retained = if (request.refreshDecisions) null else cacheTicket?.let { previewCache?.copy(it) }
        if (retained != null) {
            var adopted = false
            try {
                // This reopens generation authority, not a previous UI save receipt. The screen has
                // checked the original before render(), and verifies it again before displaying.
                val handle = awaitTrialPreview(AndroidManualOriginalTrialSaves.prepare(app, request.original.reference,
                    request.strength, resources().developConditions(rendererAvailable = true), request.adjustments) {
                    cancellation.isCanceled
                }, cancellation, request.awaitCancelledOwner)
                cancellation.throwIfCanceled()
                require(handle.matches(request.original.reference) && handle.appliedStrength == request.strength &&
                    handle.appliedAdjustments == request.adjustments)
                val scale = min(1.0, maxEdge.toDouble() / max(handle.geometry.outputWidth, handle.geometry.outputHeight))
                require(retained.after.width == floor(handle.geometry.outputWidth * scale).toInt() &&
                    retained.after.height == floor(handle.geometry.outputHeight * scale).toInt())
                val result = PhotoRollRenderResult.Preview(retained.after, mode, ManualTrialJpegSaveOwner(handle, resources),
                    comparisonBitmap = retained.before, appliedStrength = request.strength,
                    appliedAdjustments = request.adjustments, comparisonDescription = retained.label)
                adopted = true
                return result
            } finally { if (!adopted) retained.close() }
        }
        request.onStage(PhotoRollRenderStage.TRIAL_RENDER)
        val future = AndroidManualOriginalTrialSaves.renderPreview(app, request.original.reference,
            request.strength, resources().developConditions(rendererAvailable = true), request.adjustments, request.diagnosticWorkers, previewCheck(request.original)) {
            cancellation.isCanceled
        }
        val abandoned = AtomicBoolean(false)
        cancellation.setOnCancelListener { AndroidManualOriginalTrialSaves.invalidatePreviews() }
        future.whenComplete { delivery, _ -> if (abandoned.get() || cancellation.isCanceled) delivery?.close() }
        try {
            val delivery = awaitTrialPreview(future, cancellation, request.awaitCancelledOwner)
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
                    cancellation.throwIfCanceled()
                    cacheTicket?.let { previewCache?.remember(it, bitmap, before, delivery.comparisonDescription) }
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
            discardTransientInputs()
            return PhotoRollRenderResult.Unavailable("表示用のメモリが不足しました。原本は変更していません。状態を確認して再試行してください")
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

internal fun <T> awaitTrialPreview(
    future: java.util.concurrent.CompletableFuture<T>,
    cancellation: CancellationSignal,
    awaitCancelledOwner: Boolean = false,
): T {
    try {
        while (true) {
            cancellation.throwIfCanceled()
            try { return future.get(250, TimeUnit.MILLISECONDS) }
            catch (_: TimeoutException) { /* Keep checking cancellation while the queue owns its work. */ }
            catch (failure: ExecutionException) { throw (failure.cause ?: failure) }
            catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw OperationCanceledException()
            }
        }
    } finally {
        // A cancelled await is not proof that the queue producer released its resources.
        // Only the existing IO caller waits; normal mode keeps its old behavior.
        if (awaitCancelledOwner) awaitPreviewOwnerCompletion(future)
    }
}
