package dev.cinematic.p0.rawcapture

import dev.cinematic.p0.jpegrender.original.Original24Request
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Original24ScratchRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val uuid = "01234567-89ab-cdef-0123-456789abcdef"
    private val source = SharedOriginalReference("content://example.test/source", OriginalFingerprint(4, "0".repeat(64)), OriginalGrant.USER_SELECTED)
    private val id get() = ManualOriginalTrialRequests.identity(source)
    private fun file(root: File, path: String, data: String = "copy"): File = File(root, path).also {
        check(requireNotNull(it.parentFile).let { parent -> parent.isDirectory || parent.mkdirs() }); it.writeText(data)
    }
    private fun scratch(root: File, path: String) {
        file(root, "$path/source-snapshot.dng")
        file(root, "$path/source-linear.rgb16")
        file(root, "$path/raster-0.f32")
    }

    @Test fun oldProductCopiesAreRemovedButOriginalAndPublishedOutputRemain() {
        val root = temporary.newFolder()
        val generation = "raw-captures/$id/renders/1"
        scratch(root, "$generation/original-work")
        val original = file(root, "raw-captures/$id/original.dng", "original")
        val output = file(root, "$generation/output.jpg", "published")
        val journal = file(root, "$generation/jpeg-publication.bin.bak", "journal")
        val preview = "manual-original24-previews/$uuid"
        file(root, "$preview/source-snapshot.dng")
        file(root, "$preview/source-linear.rgb16")
        file(root, "manual-original24-preflight/$uuid.dng")
        Original24ScratchRecovery.product(root)
        Original24ScratchRecovery.product(root)
        assertFalse(File(root, "$generation/original-work").exists())
        assertFalse(File(root, preview).exists())
        assertTrue(File(root, "manual-original24-preflight").listFiles()!!.isEmpty())
        assertEquals("original", original.readText())
        assertEquals("published", output.readText())
        assertEquals("journal", journal.readText())
    }

    @Test fun abandonedPrePublicationJpegNeedsMatchingDurableIntent() {
        val root = temporary.newFolder()
        val generation = "raw-captures/$id/renders/1"
        val output = file(root, "$generation/output.jpg")
        val intent = file(root, "$generation/manual-request.bin")
        intent.writeBytes(ManualOriginalTrialRequestCodec.encode(Original24Request(id, 1, source)))
        Original24ScratchRecovery.product(root)
        assertFalse(output.exists())
        assertTrue(intent.exists())
        assertEquals(1, ManualOriginalTrialRequestCodec.decode(intent.readBytes()).generation)
    }

    @Test fun uncertainIntentDoesNotAuthorizeOutputDeletion() {
        val root = temporary.newFolder()
        val generation = "raw-captures/$id/renders/1"
        val output = file(root, "$generation/output.jpg")
        file(root, "$generation/manual-request.bin.new")
        assertThrows(IOException::class.java) { Original24ScratchRecovery.product(root) }
        assertTrue(output.exists())
    }

    @Test fun diagnosticCopiesAreRemovedWhileNumericReportSurvives() {
        val root = temporary.newFolder()
        val session = "raw-diagnostic-$uuid"
        val report = file(root, "$session/report.json", "{}")
        file(root, "$session/input.dng")
        file(root, "$session/attempt-0/result.jpg")
        scratch(root, "$session/attempt-0/scratch")
        Original24ScratchRecovery.diagnostics(root)
        Original24ScratchRecovery.diagnostics(root)
        assertEquals(listOf("report.json"), requireNotNull(report.parentFile).listFiles()!!.map { it.name })
    }

    @Test fun unknownScratchFileHoldsWithoutDeletingKnownFilesAndCanRetry() {
        val root = temporary.newFolder()
        val path = "raw-captures/$id/renders/1/original-work"
        scratch(root, path)
        val unknown = file(root, "$path/unknown.bin", "keep")
        assertThrows(IOException::class.java) { Original24ScratchRecovery.product(root) }
        assertTrue(File(root, "$path/source-snapshot.dng").exists())
        assertEquals("keep", unknown.readText())
        // The fixture owner resolves its unknown file, then the same cleanup can be retried.
        assertTrue(unknown.delete())
        Original24ScratchRecovery.product(root)
        assertFalse(File(root, path).exists())
    }

    @Test fun uncertainStatNeverMeansAbsent() {
        val file = temporary.newFile()
        assertTrue(Original24ScratchRecovery.exists(file))
        assertFalse(Original24ScratchRecovery.exists(file) { throw java.nio.file.NoSuchFileException("synthetic") })
        assertThrows(java.nio.file.AccessDeniedException::class.java) {
            Original24ScratchRecovery.exists(file) { throw java.nio.file.AccessDeniedException("synthetic") }
        }
        assertThrows(IOException::class.java) {
            Original24ScratchRecovery.exists(file) { throw IOException("Synthetic stat failure") }
        }
    }

    @Test fun redirectedScratchNeverDeletesTheTarget() {
        val root = temporary.newFolder()
        val foreign = temporary.newFolder()
        file(foreign, "source-snapshot.dng", "keep")
        val work = File(root, "raw-captures/$id/renders/1/original-work")
        check(requireNotNull(work.parentFile).mkdirs())
        try { Files.createSymbolicLink(work.toPath(), foreign.toPath()) } catch (e: Exception) { assumeNoException(e) }
        assertThrows(IOException::class.java) { Original24ScratchRecovery.product(root) }
        assertEquals("keep", File(foreign, "source-snapshot.dng").readText())
    }
}
