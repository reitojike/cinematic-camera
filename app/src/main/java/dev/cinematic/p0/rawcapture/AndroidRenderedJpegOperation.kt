package dev.cinematic.p0.rawcapture

import android.content.Context
import android.net.Uri
import androidx.annotation.RequiresApi
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption

/** Fixed private output paths. Never accepts a source DNG/preview filename or an arbitrary output path. */
internal class RenderedJpegFiles(private val root: File, private val captureId: String, private val generation: Long) {
    private var writerOpen = false
    private var writerFailed = false
    private var sealed = false
    init { requireCaptureId(captureId); require(generation > 0) }
    fun generationRoot(): File {
        val canonicalRoot = root.canonicalFile
        val capture = File(canonicalRoot, captureId)
        val renders = File(capture, "renders")
        val directory = File(renders, generation.toString())
        listOf(capture, renders, directory).forEach {
            if (Files.isSymbolicLink(it.toPath()) || it.canonicalFile != it) throw IOException("Rendered output directory is redirected")
        }
        return directory
    }
    @Synchronized fun createOutput(): OutputStream {
        if (writerOpen || sealed || writerFailed) throw IOException("Rendered output is already owned")
        val directory = generationRoot()
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create rendered output directory")
        val file = checkedOutput()
        val stream = Files.newOutputStream(file.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
        writerOpen = true
        return object : FilterOutputStream(stream) {
            private var closed = false
            private fun <T> writing(action: () -> T): T {
                synchronized(this@RenderedJpegFiles) {
                    if (closed) throw IOException("Rendered writer is closed")
                    try { return action() } catch (t: Throwable) { writerFailed = true; throw t }
                }
            }
            override fun write(value: Int) = writing { out.write(value) }
            override fun write(bytes: ByteArray, offset: Int, length: Int) = writing { out.write(bytes, offset, length) }
            override fun flush() = writing { out.flush() }
            override fun close() {
                synchronized(this@RenderedJpegFiles) {
                    if (closed) return
                    try { out.close() } catch (t: Throwable) { writerFailed = true; throw t }
                    closed = true
                    writerOpen = false
                }
            }
        }
    }
    /** Failed producer output only. Never remove a sealed publication/recovery input. */
    @Synchronized fun discardFailedOutput() {
        if (writerOpen || sealed) throw IOException("Rendered output cleanup is not authorized")
        Files.deleteIfExists(checkedOutput().toPath())
    }
    @Synchronized fun sealCompleted() {
        if (writerOpen || writerFailed) throw IOException("Rendered writer has not successfully completed")
        val file = checkedOutput()
        if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) throw IOException("Completed JPEG unavailable")
        sealed = true
    }
    @Synchronized fun openRead(): InputStream {
        if (writerOpen || writerFailed) throw IOException("Rendered writer is not released")
        val file = checkedOutput()
        if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) throw IOException("Completed JPEG unavailable")
        return Files.newInputStream(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
    }
    private fun checkedOutput(): File = File(generationRoot(), "output.jpg").also {
        if (Files.isSymbolicLink(it.toPath()) || it.canonicalFile != it) throw IOException("Rendered output is redirected")
    }
}

/**
 * Unconnected factory. Integration retains one instance per operation and closes the producer before publish.
 * No contracts are enabled implicitly; the renderer owner must explicitly enroll its validated adapter.
 */
@RequiresApi(29)
internal class AndroidRenderedJpegOperation private constructor(context: Context, private val captureId: String, private val generation: Long) {
    private val app = context.applicationContext
    private val files = RenderedJpegFiles(File(app.noBackupFilesDir, "raw-captures"), captureId, generation)
    private var publisherOwner: RecoverablePhotoPublisher? = null
    fun createOutput(): OutputStream = files.createOutput()
    fun openOutputRead(): InputStream = files.openRead()
    fun discardFailedOutput() = files.discardFailedOutput()

    companion object {
        // Do not evict a live operation: failed closes and AtomicFile ownership survive every retry.
        private val operations = mutableMapOf<Triple<String, String, Long>, AndroidRenderedJpegOperation>()
        @Synchronized fun get(context: Context, captureId: String, generation: Long): AndroidRenderedJpegOperation {
            requireCaptureId(captureId)
            require(generation > 0)
            val app = context.applicationContext
            val key = Triple(app.noBackupFilesDir.canonicalPath, captureId, generation)
            return operations.getOrPut(key) { AndroidRenderedJpegOperation(app, captureId, generation) }
        }
    }

    @Synchronized fun publisher(render: JpegPublicationRender, supportedContracts: Set<RendererPublicationContract>, isCurrent: () -> Boolean): RecoverablePhotoPublisher {
        if (publisherOwner != null) throw IOException("Retain the existing JPEG publisher for retries")
        require(render.request.captureId == captureId && render.request.generation == generation)
        render.requirePublishable(supportedContracts)
        files.sealCompleted()
        JpegOperationOwners.registry.claim(app.noBackupFilesDir.canonicalPath, captureId, generation, this)
        val directory = files.generationRoot()
        val root = requireNotNull(directory.parentFile)
        val journal = JpegPublicationJournal(captureId, generation,
            AndroidAtomicJournalStorage(root, generation.toString(), CaptureJournalFile.JPEG_PUBLICATION))
        return RecoverablePhotoPublisher(journal, MediaStoreJpegRows(app), render, supportedContracts.toSet(),
            object : RenderedJpegInput {
                override fun openJpeg() = files.openRead()
                override fun openOriginal(reference: SharedOriginalReference): InputStream =
                    app.contentResolver.openInputStream(Uri.parse(reference.uri)) ?: throw IOException("Shared original unavailable")
            }, AndroidJpegInspector(), isCurrent).also { publisherOwner = it }
    }
}
