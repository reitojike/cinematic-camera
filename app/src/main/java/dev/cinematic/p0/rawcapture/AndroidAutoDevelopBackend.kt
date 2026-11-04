package dev.cinematic.p0.rawcapture

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.annotation.RequiresApi
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files

/** Owns ORIGINAL reads and distinct attempt outputs. No source/provisional/delete capability is handed to adapters. */
@RequiresApi(29)
internal class AndroidAutoDevelopBackend(
    context: Context,
    private val request: AutoDevelopRequest,
    private val catalog: AutoRendererCatalog,
) : AutoDevelopBackend {
    private val app = context.applicationContext
    private val resolver = app.contentResolver
    private val root = File(app.noBackupFilesDir, "raw-captures")
    private val generationFiles = RenderedJpegFiles(root, request.captureId, request.generation)
    private val attemptFiles = mutableMapOf<String, RenderedJpegFiles>()
    private var resources = DngIoResources()
    private val descriptors = mutableListOf<OwnedDescriptor>()
    private var publisher: AutoDevelopPublication? = null
    private var publishedAttempt: String? = null

    override val hasUnreleasedResources get() = resources.hasOpenResources || descriptors.any { !it.closed }
    override fun releaseResources() {
        var failure = resources.releaseAll()
        descriptors.asReversed().forEach { descriptor ->
            try { descriptor.close() } catch (t: Throwable) { if (failure == null) failure = t else if (failure !== t) failure!!.addSuppressed(t) }
        }
        failure?.let { throw it }
        if (hasUnreleasedResources) throw IOException("Auto-develop IO remains open")
        resources = DngIoResources()
        descriptors.clear()
    }
    private fun checkRequest(value: AutoDevelopRequest) { if (value != request) throw CaptureJournalConflict() }
    override fun supports(request: AutoDevelopRequest) = request == this.request && catalog.find(request) != null
    override fun verifyOriginal(request: AutoDevelopRequest, shouldStop: () -> Boolean) {
        checkRequest(request)
        releaseResources()
        DngContentVerifier.verify(request.source.fingerprint, { resources.input(readOriginal()) }, shouldStop)
    }
    private fun readOriginal(): InputStream = resolver.openInputStream(Uri.parse(request.source.uri)) ?: throw IOException("Shared original unavailable")

    private inner class OwnedDescriptor(private val owner: ParcelFileDescriptor) : AutoReadOnlyDescriptor {
        var closed = false
            private set
        override val fd: Int get() { if (closed) throw IOException("Original descriptor is closed"); return owner.fd }
        override fun close() { if (!closed) { owner.close(); closed = true } }
    }
    private fun files(attemptId: String): RenderedJpegFiles {
        require(attemptId.matches(Regex("[0-9a-f]{32}")))
        val captureRoot = File(root.canonicalFile, request.captureId)
        val attemptsRoot = File(captureRoot, "auto-attempts")
        listOf(captureRoot, attemptsRoot).forEach {
            if (Files.isSymbolicLink(it.toPath()) || it.canonicalFile != it) throw IOException("Auto-develop attempt directory redirected")
        }
        // Reuse fixed CREATE_NEW/writer-close ownership. One directory per attempt, never the previous failed output.
        return attemptFiles.getOrPut(attemptId) { RenderedJpegFiles(attemptsRoot, attemptId, 1) }
    }
    override fun createAttempt(request: AutoDevelopRequest, attemptId: String): AutoRenderAttempt {
        checkRequest(request)
        val adapter = catalog.find(request) ?: throw IOException("Final renderer unavailable")
        val output = files(attemptId)
        var rendering = false
        fun requireRendering() { if (!rendering) throw IOException("Renderer IO is outside its owned attempt") }
        val io = object : AutoRenderIo {
            override fun openOriginal(): InputStream { requireRendering(); return resources.input(readOriginal()) }
            override fun openOriginalDescriptor(): AutoReadOnlyDescriptor {
                requireRendering()
                val owner = resolver.openFileDescriptor(Uri.parse(request.source.uri), "r") ?: throw IOException("Original descriptor unavailable")
                return OwnedDescriptor(owner).also { descriptors += it }
            }
            override fun openOutput(): OutputStream { requireRendering(); return resources.output(output.createOutput()) }
            override fun openOutputRead(): InputStream { requireRendering(); return resources.input(output.openRead()) }
        }
        // The adapter constructor must not acquire resources: the returned owner is retained before render.
        val engine = adapter.createAttempt(request, attemptId, io)
        return object : AutoRenderAttempt {
            override fun render(shouldStop: () -> Boolean): AutoRenderReceipt {
                rendering = true
                try { return engine.render(shouldStop) }
                finally { rendering = false }
            }
            override fun requestStop() = engine.requestStop()
            override fun close() {
                engine.close() // A still-running kernel must retain its source/output handles too.
                releaseResources()
            }
        }
    }
    override fun inspect(request: AutoDevelopRequest, attemptId: String, shouldStop: () -> Boolean): JpegInspection {
        checkRequest(request)
        releaseResources()
        val output = files(attemptId)
        output.sealCompleted() // Invalid/missing output is validation failure, not a resource close failure.
        return AndroidJpegInspector().inspect({ resources.input(output.openRead()) }, shouldStop)
    }
    override fun publication(record: AutoDevelopRecord, isCurrent: () -> Boolean): AutoDevelopPublication {
        checkRequest(record.request)
        if (!supports(record.request)) throw IOException("Final renderer contract is not enrolled")
        val attemptId = requireNotNull(record.attemptId)
        publisher?.let { if (publishedAttempt != attemptId) throw CaptureJournalConflict(); return it }
        JpegOperationOwners.registry.claim(app.noBackupFilesDir.canonicalPath, request.captureId, request.generation, this)
        releaseResources()
        val directory = generationFiles.generationRoot()
        val journal = JpegPublicationJournal(request.captureId, request.generation,
            AndroidAtomicJournalStorage(requireNotNull(directory.parentFile), request.generation.toString(), CaptureJournalFile.JPEG_PUBLICATION))
        val render = record.completed()
        val fingerprint = requireNotNull(record.outputFingerprint)
        val existing = journal.read()
        if (existing == null) DngContentVerifier.verify(fingerprint, { resources.input(files(attemptId).openRead()) }, { !isCurrent() })
        else if (existing.render != render || existing.fingerprint != fingerprint) throw CaptureJournalConflict()
        val native = RecoverablePhotoPublisher(journal, MediaStoreJpegRows(app), render, setOf(request.contract),
            object : RenderedJpegInput {
                override fun openJpeg() = files(attemptId).openRead()
                override fun openOriginal(reference: SharedOriginalReference): InputStream {
                    if (reference != request.source) throw CaptureJournalConflict()
                    return readOriginal()
                }
            }, AndroidJpegInspector(), isCurrent)
        publishedAttempt = attemptId
        return RecoverableAutoPublication(native).also { publisher = it }
    }
}
