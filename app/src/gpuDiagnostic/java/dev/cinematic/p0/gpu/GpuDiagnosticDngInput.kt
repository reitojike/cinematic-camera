package dev.cinematic.p0.gpu

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import dev.cinematic.p0.jpegrender.original.Original24Preflight
import dev.cinematic.p0.jpegrender.original.Original24PreviewSession
import dev.cinematic.p0.jpegrender.original.Original24Resources
import dev.cinematic.p0.photoroll.PhotoRollReadResources
import dev.cinematic.p0.photoroll.PhotoRollRepository
import dev.cinematic.p0.photoroll.PhotoRollResources
import dev.cinematic.p0.raw.LinearSrgbImage
import dev.cinematic.p0.rawcapture.AndroidAutoDevelopQueue
import dev.cinematic.p0.rawcapture.AutoDevelopCoordinator
import dev.cinematic.p0.rawcapture.DevelopExecution
import dev.cinematic.p0.rawcapture.DngContentVerifier
import dev.cinematic.p0.rawcapture.DngIoResources
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.util.UUID

/** Diagnostic-only, one-shot input. Same serial IO owner and process admission as CPU previews.
 * Never creates a save request, Bitmap, CPU look, persistent grant or a new decoding path.
 * A failed close keeps the exact process-retained owner and admission until an explicit retry.
 */
internal object GpuDiagnosticDngInput {
    private var pending: Owner? = null // Access only from manualOriginalIo, including cleanup.

    fun load(context: Context, uri: Uri, resources: () -> PhotoRollResources, signal: CancellationSignal) =
        AndroidAutoDevelopQueue.get(context.applicationContext).manualOriginalIo {
            releasePending()
            signal.throwIfCanceled()
            val slot = DevelopExecution.tryStartPreviewObserved(resources().developConditions(true))
                ?: throw IOException("Diagnostic input admission unavailable")
            val owner = Owner(context.applicationContext, slot, signal).also { pending = it }
            DevelopExecution.registerStop(slot, signal::cancel)
            var failure: Throwable? = null
            try { owner.load(uri) }
            catch (error: Throwable) { failure = error; throw error }
            finally {
                try { releasePending() } catch (cleanup: Throwable) {
                    if (failure == null) throw cleanup
                    if (failure !== cleanup) failure.addSuppressed(cleanup)
                }
            }
        }

    fun release(context: Context) = AndroidAutoDevelopQueue.get(context.applicationContext).manualOriginalIo {
        releasePending()
    }

    private fun releasePending() {
        val owner = pending ?: return
        owner.close()
        DevelopExecution.completeAfterResourcesClosed(owner.slot)
        pending = null
    }

    private class Owner(val app: Context, val slot: AutoDevelopCoordinator.Attempt,
        val signal: CancellationSignal) {
        private val readers = PhotoRollReadResources()
        private val io = DngIoResources()
        private var descriptor: ParcelFileDescriptor? = null
        private var snapshot: File? = null
        private var session: Original24PreviewSession? = null
        private val stop = { signal.isCanceled || DevelopExecution.shouldStop(slot) }

        fun load(uri: Uri): LinearSrgbImage {
            val repository = PhotoRollRepository(app.contentResolver, app.packageName)
            val original = repository.check(uri, fromFiles = true, shouldStop = stop,
                maxBytes = 64L * 1024 * 1024, readers = readers)
            // Reuse the fixed paths covered by Original24ScratchRecovery.product at queue startup.
            val parent = directory("manual-original24-preflight")
            if (parent.usableSpace < 2L * 1024 * 1024 * 1024) throw IOException("Diagnostic workspace budget unavailable")
            fun openOriginal() = app.contentResolver.openInputStream(uri)
                ?: throw IOException("Diagnostic original unavailable")
            val file = File(parent, UUID.randomUUID().toString() + ".dng")
            DngContentVerifier.copy(original.fingerprint, { io.input(openOriginal()) }, {
                io.output(Files.newOutputStream(file.toPath(), StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).also { snapshot = file })
            }, stop)
            checkpoint()
            val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).also { descriptor = it }
            val geometry = Original24Preflight.inspect(fd.fd, signal)
            checkpoint()
            DngContentVerifier.verify(original.fingerprint, { io.input(openOriginal()) }, stop)
            releasePreflight()
            val inputSession = Original24PreviewSession(original.reference,
                File(directory("manual-original24-previews"), UUID.randomUUID().toString()),
                { stage -> checkpoint(); Original24Resources.requireAvailable(app, stage) }, geometry,
                openOriginal = ::openOriginal).also { session = it }
            // CancellationSignal also interrupts an in-flight native decode, not only its checkpoints.
            signal.setOnCancelListener(inputSession::requestStop)
            val input = inputSession.loadGpuAsShotInput(stop)
            repository.verifyPreview(original, stop, readers = readers)
            checkpoint()
            return input
        }

        private fun directory(name: String): File {
            val directory = File(app.noBackupFilesDir.canonicalFile, name)
            if (Files.isSymbolicLink(directory.toPath()) || directory.canonicalFile != directory ||
                (!directory.isDirectory && !directory.mkdir())) throw IOException("Diagnostic workspace unavailable")
            return directory
        }

        private fun checkpoint() {
            signal.throwIfCanceled()
            if (DevelopExecution.shouldStop(slot)) throw IOException("Diagnostic input stopped")
        }

        private fun releasePreflight() {
            io.releaseAll()?.let { throw it }
            descriptor?.close(); descriptor = null
            snapshot?.let { Files.deleteIfExists(it.toPath()); snapshot = null }
        }

        fun close() {
            // Null ownership only after positive close. An exception leaves pending/slot retained.
            session?.close(); session = null
            signal.setOnCancelListener(null)
            releasePreflight()
            if (!readers.closeRetained()) throw IOException("Diagnostic readers remain held")
        }
    }
}
