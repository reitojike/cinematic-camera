package dev.cinematic.p0.rawcapture

import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption

internal enum class CaptureJournalFile(val fileName: String) {
    RECEIPT("receipt.bin"), DNG_PUBLICATION("dng-publication.bin"), JPEG_PUBLICATION("jpeg-publication.bin"),
}

/** Pure path validation; root must be a trusted app-private directory supplied by the caller. */
internal object CaptureJournalPaths {
    fun receiptFile(root: File, captureId: String): File = journalFile(root, captureId, CaptureJournalFile.RECEIPT)

    fun journalFile(root: File, captureId: String, kind: CaptureJournalFile): File {
        requireCaptureId(captureId)
        val directory = File(root.canonicalFile, captureId)
        if (Files.isSymbolicLink(directory.toPath()) || directory.canonicalFile != directory) {
            throw IOException("Capture directory is redirected")
        }
        val file = File(directory, kind.fileName)
        listOf(file, File(file.path + ".new"), File(file.path + ".bak")).forEach {
            if (Files.isSymbolicLink(it.toPath()) || it.canonicalFile != it) {
                throw IOException("Capture journal path is redirected")
            }
        }
        return file
    }
}

/** One journal/writer per capture and file kind. AtomicFile does not provide inter-writer locking. */
internal class AndroidAtomicJournalStorage(
    private val root: File,
    private val captureId: String,
    private val kind: CaptureJournalFile = CaptureJournalFile.RECEIPT,
) : AtomicJournalStorage {
    private val file = CaptureJournalPaths.journalFile(root, captureId, kind)
    private val atomic = AtomicFile(file)

    override fun read(maxBytes: Int): ByteArray? {
        require(maxBytes in 1..CaptureReceiptCodec.MAX_BYTES)
        checkPath()
        try {
            return atomic.openRead().use { input ->
                val bytes = ByteArrayOutputStream()
                val chunk = ByteArray(512)
                while (true) {
                    val count = input.read(chunk)
                    if (count < 0) break
                    if (bytes.size() + count > maxBytes) throw IOException("Capture journal too large")
                    bytes.write(chunk, 0, count)
                }
                bytes.toByteArray()
            }
        } catch (e: FileNotFoundException) {
            // Absence, permission errors and unfinished sidecars are deliberately not conflated.
            val absent = listOf(file, File(file.path + ".new"), File(file.path + ".bak")).all {
                Files.notExists(it.toPath(), LinkOption.NOFOLLOW_LINKS)
            }
            if (absent) return null
            throw e
        }
    }

    override fun replace(contents: ByteArray) {
        require(contents.size in 1..CaptureReceiptCodec.MAX_BYTES)
        checkPath()
        val parent = requireNotNull(file.parentFile)
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Cannot create capture journal directory")
        checkPath()
        val stream = atomic.startWrite()
        var finishing = false
        try {
            stream.write(contents)
            finishing = true
            atomic.finishWrite(stream)
        } catch (failure: Exception) {
            try {
                if (!finishing) atomic.failWrite(stream) else stream.close()
            } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
            throw failure // After finish was invoked the commit may already have happened.
        }
        // CaptureJournal performs bounded readback; finishWrite returning alone is not acceptance.
    }

    private fun checkPath() {
        if (CaptureJournalPaths.journalFile(root, captureId, kind) != file) throw IOException("Capture journal root changed")
    }
}
