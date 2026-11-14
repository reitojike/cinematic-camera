package dev.cinematic.p0.photoroll

import android.content.Context
import android.annotation.TargetApi
import android.net.Uri
import android.os.CancellationSignal
import android.os.OperationCanceledException
import dev.cinematic.p0.rawcapture.AndroidManualOriginalTrialSaves
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import dev.cinematic.p0.rawcapture.DngVerificationStopped

/** Original-capable preview and its immutable manual save generation share one queue authority. */
@TargetApi(29)
internal class ManualTrialPhotoRollRenderer(context: Context,
    private val resources: () -> PhotoRollResources,
    private val previewCache: PhotoRollPreviewCache? = null,
    val developEngine: dev.cinematic.p0.gpu.DevelopEngine = dev.cinematic.p0.gpu.DevelopEngine.GPU,
) : PhotoRollRenderer {
    init { require(developEngine == dev.cinematic.p0.gpu.DevelopEngine.GPU) { "CPU preview has been retired" } }
    private val app = context.applicationContext
    override val mode = PhotoRollPreviewMode.MANUAL_TRIAL
    override val maxEdge = 1024
    override val maxSourceBytes = 64L * 1024 * 1024

    private val originalEpoch = AtomicLong(0)
    private data class CheckedOriginal(val original: PhotoRollOriginal, val fromFiles: Boolean, val epoch: Long,
        val repository: PhotoRollRepository)
    @Volatile private var checkedOriginal: CheckedOriginal? = null

    /** Explicit retry only. Cached pixels are independent copies, never displayed/save-owned data. */
    fun releaseUnusedMemoryForRetry() {
        previewCache?.clear()
        discardTransientInputs()
    }

    override fun discardTransientInputs() {
        originalEpoch.incrementAndGet()
        checkedOriginal = null

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

    // These bitmap CPU entry points already rejected GPU calls before retirement.
    // Actual GPU rendering stays in openGpu -> GpuPhotoRollSession/renderer.
    override fun renderDraft(request: PhotoRollRenderRequest, cancellation: CancellationSignal): PhotoRollRenderResult.Preview? =
        error("GPU preview must not enter the retired CPU draft path")

    override fun render(request: PhotoRollRenderRequest, cancellation: CancellationSignal): PhotoRollRenderResult =
        error("GPU preview must not enter the retired CPU render path")

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
