package dev.cinematic.p0.rawcapture

import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

/**
 * Unconnected capture-local storage. One live store/writer per capture, on an IO executor.
 * root is trusted app-private storage; journalStorage must address this capture under that root.
 * The injected opener must create exclusively without following links (production default below).
 * Only a bound handoff reservation may release this capture's fixed temporary DNG.
 * No shared image, provisional JPEG or other capture is moved/deleted.
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
    private val deleteOriginalFile: (File) -> Boolean = { Files.deleteIfExists(it.toPath()) },
) {
    private val directory = requireNotNull(CaptureJournalPaths.receiptFile(root, captureId).parentFile)
    private val journal = CaptureJournal(captureId, journalStorage)
    private val handles = mutableMapOf<RawOutput, RawCaptureOutput>()
    private var createdHere = false
    private var handoffJournal: DngPublicationJournal? = null
    private var originalReaders = 0
    private var releaseOwner: Any? = null

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
        val handoff = handoffRecord(receipt)
        val localOptional = handoff?.phase in setOf(DngPublicationPhase.SHARED_READY, DngPublicationPhase.LOCAL_RELEASE_PENDING, DngPublicationPhase.SHARED_ONLY)
        receipt.receivedBytes.forEach { (format, expectedBytes) ->
            if (format == RawOutput.DNG && localOptional && !originalExists()) return@forEach
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

    /** Establish/recheck local byte identity before handoff; heavy reads never hold the store lock. */
    fun verifyOriginal(expectedRevision: Long, shouldStop: () -> Boolean = { false }): CaptureReceipt {
        val receipt = synchronized(this) {
            val current = current(expectedRevision)
            if (RawOutput.DNG !in current.receivedBytes) throw IOException("DNG has not been received")
            current
        }
        val openReadOnly = { openOriginalReader() }
        val fingerprint = receipt.originalFingerprint?.let {
            DngContentVerifier.verify(it, openReadOnly, shouldStop)
        } ?: DngContentVerifier.identify(receipt.receivedBytes.getValue(RawOutput.DNG), openReadOnly, shouldStop)
        if (shouldStop()) throw DngVerificationStopped() // Caller code runs outside the update lock.
        return synchronized(this) {
            current(expectedRevision) // Rechecks fixed paths, sizes and revision after the read/close.
            journal.recordOriginalFingerprint(expectedRevision, fingerprint)
        }
    }

    /** Only in a new process after previous writers are gone; no file scanning or repair. */
    @Synchronized
    fun recoverAfterProcessDeath(): CaptureReceipt? {
        if (handles.isNotEmpty()) throw IOException("Cannot recover a live capture writer")
        readReceipt() // Stop on missing/changed recorded files before mutating the journal.
        return journal.recoverAfterProcessDeath()
    }

    /** Bind once before exposing this process-scoped store. Persistent release phases gate new readers. */
    @Synchronized
    fun handoffSource(publication: DngPublicationJournal): DngHandoffSource {
        require(handoffJournal == null || handoffJournal === publication) { "Different live handoff journal" }
        handoffJournal = publication
        return object : DngHandoffSource {
            override fun receipt(): CaptureReceipt = synchronized(this@RawCaptureStore) { handoffReceipt() }
            override fun openOriginal(): InputStream = openOriginalReader()
            override fun reserveRelease(record: DngPublicationRecord): DngOriginalRelease = reserveOriginalRelease(record)
        }
    }

    private fun handoffRecord(receipt: CaptureReceipt): DngPublicationRecord? = handoffJournal?.read()?.also {
        if (it.target.captureId != captureId || it.fingerprint != receipt.originalFingerprint) throw IOException("Handoff source mismatch")
    }

    /** Receipt metadata and auxiliary JPEG only; local DNG absence is possible after release/death. */
    private fun handoffReceipt(): CaptureReceipt {
        checkedDirectory()
        val receipt = journal.read() ?: throw IOException("Capture receipt unavailable")
        handoffRecord(receipt)
        receipt.receivedBytes[RawOutput.JPEG]?.let { if (closedFileSize(RawOutput.JPEG) != it) throw IOException("Provisional JPEG changed") }
        return receipt
    }

    @Synchronized
    private fun openOriginalReader(owner: Any? = null): InputStream {
        if (releaseOwner != null && releaseOwner !== owner) throw IOException("Original release is reserved")
        val receipt = handoffReceipt()
        val phase = handoffRecord(receipt)?.phase
        if (owner == null && phase in setOf(DngPublicationPhase.LOCAL_RELEASE_PENDING, DngPublicationPhase.SHARED_ONLY)) {
            throw IOException("Original has moved to shared storage")
        }
        if (owner != null && releaseOwner !== owner) throw IOException("Foreign original release reservation")
        if (receipt.receivedBytes[RawOutput.DNG] != closedFileSize(RawOutput.DNG)) throw IOException("DNG receipt mismatch")
        val stream = Files.newInputStream(File(checkedDirectory(), RawOutput.DNG.fileName).toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
        originalReaders++
        return object : FilterInputStream(stream) {
            private var released = false
            @Synchronized override fun close() {
                if (released) return
                super.close()
                released = true
                synchronized(this@RawCaptureStore) { originalReaders-- }
            }
        }
    }

    @Synchronized
    private fun reserveOriginalRelease(record: DngPublicationRecord): DngOriginalRelease {
        val receipt = handoffReceipt()
        require(record.target.captureId == captureId && record.fingerprint == receipt.originalFingerprint) { "Foreign original release" }
        if (handoffJournal?.read() != record || record.phase !in setOf(DngPublicationPhase.SHARED_READY, DngPublicationPhase.LOCAL_RELEASE_PENDING)) {
            throw CaptureJournalConflict()
        }
        if (releaseOwner != null || originalReaders != 0 || handles[RawOutput.DNG]?.isReleased() == false) throw IOException("Original references remain active")
        val token = Any()
        releaseOwner = token
        return object : DngOriginalRelease {
            private fun checkOwner() { if (releaseOwner !== token) throw IOException("Original release reservation expired") }
            override fun exists(): Boolean = synchronized(this@RawCaptureStore) { checkOwner(); originalExists() }
            override fun openOriginal(): InputStream = openOriginalReader(token)
            override fun deleteOriginal(record: DngPublicationRecord) = synchronized(this@RawCaptureStore) {
                require(record.target.captureId == captureId && record.fingerprint == receipt.originalFingerprint) { "Foreign original release" }
                checkOwner()
                if (record.phase != DngPublicationPhase.LOCAL_RELEASE_PENDING || handoffJournal?.read() != record) throw CaptureJournalConflict()
                if (originalReaders != 0 || handles[RawOutput.DNG]?.isReleased() == false) throw IOException("Original references remain active")
                if (originalExists()) {
                    if (closedFileSize(RawOutput.DNG) != record.fingerprint.bytes) throw DngContentChanged()
                    deleteOriginalFile(File(checkedDirectory(), RawOutput.DNG.fileName))
                }
                if (!Files.notExists(File(checkedDirectory(), RawOutput.DNG.fileName).toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    throw IOException("Temporary original still exists")
                }
            }
            override fun close() = synchronized(this@RawCaptureStore) { if (releaseOwner === token) releaseOwner = null }
        }
    }

    private fun originalExists(): Boolean {
        val file = File(checkedDirectory(), RawOutput.DNG.fileName)
        if (Files.notExists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return false
        if (!Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).isRegularFile) {
            throw IOException("Original is not a regular file")
        }
        return true
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

    @Synchronized internal fun isReleased(): Boolean = closed

    private inline fun writing(action: () -> Unit) {
        if (closed || failed) throw IOException("Capture output is closed or failed")
        try { action() }
        catch (failure: Throwable) { failed = true; throw failure }
    }
}
