package dev.cinematic.p0.photoroll

import dev.cinematic.p0.BuildConfig
import java.util.concurrent.atomic.AtomicReference
import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import dev.cinematic.p0.gpu.preview.GpuInputAdmissionRefused
import dev.cinematic.p0.gpu.preview.GpuInputRequestDeadline
import dev.cinematic.p0.jpegrender.original.Original24Preflight
import dev.cinematic.p0.jpegrender.original.Original24PreflightGeometry
import dev.cinematic.p0.jpegrender.original.Original24PreviewSession
import dev.cinematic.p0.jpegrender.original.Original24Resources
import dev.cinematic.p0.raw.LinearSrgbImage
import dev.cinematic.p0.rawcapture.*
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

/** GPU-only input IO. Never owns save generations, journals, publication or the export token.
 * One worker and one retained owner bound original check/preflight/decode and failed cleanup.
 * The existing CPU preview and serial save queue are deliberately not used for input work. */
internal object GpuPhotoRollInputs {
    private val io = Executors.newSingleThreadExecutor { Thread(it, "gpu-preview-input").apply { priority = Thread.MIN_PRIORITY } }
    internal interface DiagnosticObserver {
        /** Called with a real private snapshot/FD retained, before native preflight. */
        fun preflightOpened() {}
        /** Called only after the exact input token and every IO/native owner is closed. */
        fun released() {}
    }
    private val observer = AtomicReference<DiagnosticObserver?>()
    internal fun observeForDiagnostics(value: DiagnosticObserver): AutoCloseable {
        check(BuildConfig.BUILD_TYPE == "gpuDiagnostic")
        check(observer.compareAndSet(null, value))
        return AutoCloseable { check(observer.compareAndSet(value, null)) }
    }
    private var retained: Owner? = null // Dedicated IO only, including failed-close ownership.
    internal data class Input(val original: PhotoRollOriginal, val image: LinearSrgbImage,
        val geometry: Original24PreflightGeometry)

    fun open(context: Context, resources: () -> PhotoRollResources, repository: PhotoRollRepository,
        uri: Uri, fromFiles: Boolean, maxSourceBytes: Long, cancellation: CancellationSignal,
        deadline: GpuInputRequestDeadline, admitted: (Throwable?) -> Unit,
        stale: () -> Boolean): CompletableFuture<Input> {
        val app = context.applicationContext
        // Capture the initial restore future, not a barrier enqueued after an active export.
        val startup = AndroidAutoDevelopQueue.startupCompletion(app)
        return CompletableFuture<Input>().also { result -> io.execute {
            var announced = false
            try {
                awaitTrialPreview(startup, cancellation, awaitCancelledOwner = true)
                retained?.close()
                retained = null // close throws until every resource and the exact token is released.
                cancellation.throwIfCanceled()
                if (stale()) throw DngVerificationStopped()
                val observations = resources().developConditions(rendererAvailable = true)
                lateinit var decision: AutoDevelopCoordinator.GpuDiagnosticPreviewAdmission
                val token = deadline.tryAdmit {
                    DevelopExecution.tryStartGpuDiagnosticPreviewObserved(observations).also { decision = it }.attempt
                } ?: throw GpuInputAdmissionRefused(decision.snapshot)
                val owner = Owner(app, token, cancellation, stale).also { retained = it }
                var failure: Throwable? = null
                try {
                    DevelopExecution.registerGpuPreviewStop(token, owner::stop)
                    cancellation.setOnCancelListener(owner::stop)
                    announced = true
                    admitted(null)
                    val original = repository.check(uri, fromFiles, owner::stopped, maxSourceBytes, owner.readers)
                    val geometry = owner.inspect(original.reference)
                    val image = owner.load(original, geometry) { readers, stop ->
                        repository.verifyPreview(original, stop, readers)
                    }
                    repository.verifyPreview(original, owner::stopped, readers = owner.readers)
                    if (owner.stopped()) throw DngVerificationStopped()
                    // Complete the future only after cleanup, never hand out input from a held owner.
                    owner.close()
                    retained = null
                    result.complete(Input(original, image, geometry))
                } catch (error: Throwable) { failure = error; throw error }
                finally {
                    cancellation.setOnCancelListener(null)
                    try { owner.close(); retained = null }
                    catch (closing: Throwable) {
                        if (failure == null) throw closing
                        if (failure !== closing) failure.addSuppressed(closing)
                    }
                }
            } catch (failure: Throwable) {
                if (!announced) try { admitted(failure) } catch (reporting: Throwable) {
                    if (reporting !== failure) failure.addSuppressed(reporting)
                }
                result.completeExceptionally(failure)
            }
        } }
    }

    private class Owner(private val app: Context, private val token: AutoDevelopCoordinator.Attempt,
        private val cancellation: CancellationSignal, private val stale: () -> Boolean) {
        val readers = PhotoRollReadResources()
        private val preflightReaders = DngIoResources()
        private val signal = CancellationSignal()
        @Volatile private var session: Original24PreviewSession? = null
        private var descriptor: ParcelFileDescriptor? = null
        private var snapshot: File? = null
        private var released = false
        private val diagnostic = if (BuildConfig.BUILD_TYPE == "gpuDiagnostic") observer.get() else null
        fun stop() { signal.cancel(); session?.requestStop() }
        fun stopped() = cancellation.isCanceled || signal.isCanceled || stale() || DevelopExecution.shouldStop(token)
        private fun original(source: SharedOriginalReference) = app.contentResolver.openInputStream(Uri.parse(source.uri))
            ?: throw IOException("Shared original unavailable")

        fun inspect(source: SharedOriginalReference): Original24PreflightGeometry {
            if (stopped()) throw DngVerificationStopped()
            val directory = checkedDirectory("manual-original24-preflight")
            val file = File(directory, UUID.randomUUID().toString() + ".dng")
            DngContentVerifier.copy(source.fingerprint, { preflightReaders.input(original(source)) }, {
                preflightReaders.output(Files.newOutputStream(file.toPath(), StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).also { snapshot = file })
            }, ::stopped)
            if (stopped()) throw DngVerificationStopped()
            descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            diagnostic?.preflightOpened()
            if (stopped()) throw DngVerificationStopped()
            val geometry = Original24Preflight.inspect(checkNotNull(descriptor).fd, signal)
            if (stopped()) throw DngVerificationStopped()
            DngContentVerifier.verify(source.fingerprint, { preflightReaders.input(original(source)) }, ::stopped)
            closePreflight()
            return geometry
        }

        fun load(original: PhotoRollOriginal, geometry: Original24PreflightGeometry,
            checkSource: (DngIoResources, () -> Boolean) -> Unit): LinearSrgbImage {
            if (stopped()) throw DngVerificationStopped()
            val source = original.reference
            val parent = checkedDirectory("manual-original24-previews")
            val current = Original24PreviewSession(source, File(parent, UUID.randomUUID().toString()),
                { stage -> Original24Resources.requireAvailable(app, stage) }, geometry,
                checkSource) { this.original(source) }.also { session = it }
            if (stopped()) current.requestStop()
            return current.loadGpuAsShotInput(::stopped)
        }

        private fun checkedDirectory(name: String): File {
            val directory = File(app.noBackupFilesDir.canonicalFile, name)
            if (Files.isSymbolicLink(directory.toPath()) || directory.canonicalFile != directory ||
                (!directory.isDirectory && !directory.mkdirs())) throw IOException("GPU input workspace unavailable")
            return directory
        }

        private fun closePreflight() {
            var failure = preflightReaders.releaseAll()
            try { descriptor?.close(); descriptor = null }
            catch (closing: Throwable) { if (failure == null) failure = closing else failure.addSuppressed(closing) }
            if (!preflightReaders.hasOpenResources && descriptor == null) {
                try { snapshot?.let { Files.deleteIfExists(it.toPath()); snapshot = null } }
                catch (closing: Throwable) { if (failure == null) failure = closing else failure.addSuppressed(closing) }
            }
            failure?.let { throw it }
        }

        fun close() {
            if (released) return
            var failure: Throwable? = null
            fun attempt(block: () -> Unit) { try { block() } catch (closing: Throwable) {
                val prior = failure
                if (prior == null) failure = closing else if (prior !== closing) prior.addSuppressed(closing)
            } }
            attempt { session?.let { it.close(); check(!it.hasUnreleasedResources); session = null } }
            attempt { closePreflight() }
            attempt { if (!readers.closeRetained()) throw IOException("GPU original readers remain held") }
            failure?.let { throw it }
            check(session == null && descriptor == null && snapshot == null && !preflightReaders.hasOpenResources)
            check(DevelopExecution.completeAfterResourcesClosed(token))
            released = true
            diagnostic?.released()
        }
    }
}
