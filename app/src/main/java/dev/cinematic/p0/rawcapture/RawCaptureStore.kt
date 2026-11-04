package dev.cinematic.p0.rawcapture

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

/**
 * Unconnected capture-local storage. One live store/writer per capture, on an IO executor.
 * root is trusted app-private storage; journalStorage must address this capture under that root.
 * The injected opener must create exclusively without following links (production default below).
 * This class never deletes, moves, publishes, hashes or decodes media.
 */
internal class RawCaptureStore(
    private val root: File,
    private val captureId: String,
    journalStorage: AtomicJournalStorage,
    private val openNewOutput: (File) -> OutputStream = { file ->
        Files.newOutputStream(
            file.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS,
        )
    },
) {
    private val directory = requireNotNull(CaptureJournalPaths.receiptFile(root, captureId).parentFile)
    private val journal = CaptureJournal(captureId, journalStorage)
    private val handles = mutableMapOf<RawOutput, RawCaptureOutput>()
    private var createdHere = false

    /** A failed creation also reserves the directory; never reuse even an empty orphan. */
    @Synchronized
    fun create(expectedFormats: Set<RawOutput>): CaptureReceipt {
        require(RawOutput.DNG in expectedFormats) { "RAW capture requires DNG" }
        checkedDirectory()
        if (journal.read() != null) throw CaptureJournalConflict()
        Files.createDirectories(requireNotNull(directory.parentFile).toPath())
        checkedDirectory()
        Files.createDirectory(directory.toPath())
        return journal.create(expectedFormats).also { createdHere = true }
    }

    /** Existing receipt sizes are checked, but same-size changes/DNG validity are not inferred. */
    @Synchronized
    fun readReceipt(): CaptureReceipt? {
        checkedDirectory()
        val receipt = journal.read()
        if (receipt == null) {
            if (Files.notExists(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) return null
            throw IOException("Capture directory has no confirmed receipt")
        }
        if (!Files.readAttributes(directory.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).isDirectory) {
            throw IOException("Capture directory is unavailable")
        }
        receipt.receivedBytes.forEach { (format, expectedBytes) ->
            if (closedFileSize(format) != expectedBytes) throw IOException("Recorded capture file size changed")
        }
        return receipt
    }

    @Synchronized
    fun openOutput(expectedRevision: Long, format: RawOutput): RawCaptureOutput {
        if (!createdHere) throw IOException("Only a newly created capture can open writers")
        val receipt = current(expectedRevision)
        require(format in receipt.expectedFormats) { "Unexpected capture format" }
        if (receipt.phase != CaptureReceiptPhase.CAPTURING || format in receipt.receivedBytes || format in handles) {
            throw IOException("Capture output is not available for a new writer")
        }
        val file = File(checkedDirectory(), format.fileName)
        return RawCaptureOutput(format, openNewOutput(file)).also { handles[format] = it }
    }

    /** Call only after the format's success callback; successful close alone is not a callback. */
    @Synchronized
    fun received(expectedRevision: Long, handle: RawCaptureOutput): CaptureReceipt {
        require(handles[handle.format] === handle) { "Foreign capture output" }
        current(expectedRevision)
        if (!handle.closedSuccessfully()) throw IOException("Capture output did not close successfully")
        return journal.received(expectedRevision, handle.format, closedFileSize(handle.format))
    }

    /** Request-level interruption does not close a stream or release another writer. */
    @Synchronized
    fun interrupt(expectedRevision: Long): CaptureReceipt {
        current(expectedRevision)
        return journal.interrupt(expectedRevision)
    }

    /** Only in a new process after previous writers are gone; no file scanning or repair. */
    @Synchronized
    fun recoverAfterProcessDeath(): CaptureReceipt? {
        if (handles.isNotEmpty()) throw IOException("Cannot recover a live capture writer")
        readReceipt() // Stop on missing/changed recorded files before mutating the journal.
        return journal.recoverAfterProcessDeath()
    }

    private fun current(expectedRevision: Long): CaptureReceipt {
        val receipt = readReceipt() ?: throw CaptureJournalConflict()
        if (receipt.revision != expectedRevision) throw CaptureJournalConflict()
        return receipt
    }

    private fun closedFileSize(format: RawOutput): Long {
        val file = File(checkedDirectory(), format.fileName)
        val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (!attributes.isRegularFile || attributes.size() <= 0) throw IOException("Capture file is not a nonempty regular file")
        return attributes.size()
    }

    private fun checkedDirectory(): File {
        if (CaptureJournalPaths.receiptFile(root, captureId).parentFile != directory) {
            throw IOException("Capture storage root changed")
        }
        RawOutput.entries.forEach { format ->
            val file = File(directory, format.fileName)
            if (Files.isSymbolicLink(file.toPath()) || file.canonicalFile != file) {
                throw IOException("Capture output path is redirected")
            }
        }
        return directory
    }
}

/** A stream failure latches permanently; failed close may retry release, never receipt success. */
internal class RawCaptureOutput internal constructor(val format: RawOutput, private val stream: OutputStream) : OutputStream() {
    private var closed = false
    private var failed = false

    @Synchronized
    override fun write(value: Int) = writing { stream.write(value) }

    @Synchronized
    override fun write(bytes: ByteArray, offset: Int, length: Int) = writing { stream.write(bytes, offset, length) }

    @Synchronized
    override fun flush() = writing { stream.flush() }

    @Synchronized
    override fun close() {
        if (closed) return
        try { stream.close(); closed = true }
        catch (failure: Throwable) { failed = true; throw failure }
    }

    @Synchronized
    internal fun closedSuccessfully(): Boolean = closed && !failed

    private inline fun writing(action: () -> Unit) {
        if (closed || failed) throw IOException("Capture output is closed or failed")
        try { action() }
        catch (failure: Throwable) { failed = true; throw failure }
    }
}
