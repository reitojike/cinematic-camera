package dev.cinematic.p0.rawcapture

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assume.assumeFalse
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AutoDevelopInventoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun request(id: String = "capture", generation: Long = 1) = AutoDevelopRequest(id, generation,
        SharedOriginalReference("content://test/original/1", OriginalFingerprint(3,
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"), OriginalGrant.APP_OWNED),
        RenderRecipeSnapshot("test-v1", "fixed"), "test-final-v1", 16, 12, AutoRenderQuality.FINAL_ORIGINAL,
        RenderedFaceState.VERIFIED_NONE, RenderedExposureState.SUPPORTED, AutoInputProfile.SUPPORTED)
    private fun write(root: File, record: AutoDevelopRecord, suffix: String = ""): File =
        File(root, "${record.request.captureId}/renders/${record.request.generation}/auto-develop.bin$suffix").also {
            check(it.parentFile!!.mkdirs() || it.parentFile!!.isDirectory)
            it.writeBytes(AutoDevelopCodec.encode(record))
        }
    private fun inventory(root: File, read: (String, Long) -> AutoDevelopRecord? = { id, generation ->
        AutoDevelopCodec.decode(File(root, "$id/renders/$generation/auto-develop.bin").readBytes())
    }) = AutoDevelopInventory(root, read)

    @Test fun absentRootAndCapturesWithoutRequestsDoNotInventJobsOrTouchOriginals() {
        val root = File(temporary.root, "absent")
        assertTrue(inventory(root).load().isEmpty())
        val original = File(root, "capture/original.dng").also { it.parentFile!!.mkdirs(); it.writeBytes(byteArrayOf(1, 2, 3)) }
        val provisional = File(original.parentFile, "provisional.jpg").also { it.writeBytes(byteArrayOf(4, 5)) }
        File(original.parentFile, "renders/1").mkdirs() // Publication-only output without an auto journal.
        assertTrue(inventory(root).load().isEmpty())
        assertArrayEquals(byteArrayOf(1, 2, 3), original.readBytes())
        assertArrayEquals(byteArrayOf(4, 5), provisional.readBytes())
    }

    @Test fun discoversAllGenerationsAndCapturesBeforeReturning() {
        val root = temporary.newFolder()
        val requests = listOf(request(generation = 2), request("other"), request())
        requests.forEach { write(root, AutoDevelopRecord(it)) }
        assertEquals(requests.toSet(), inventory(root).load().toSet())
    }

    @Test fun normalizesTheTrustedAppRootBeforeValidatingItsChildren() {
        val root = temporary.newFolder()
        val record = AutoDevelopRecord(request())
        write(root, record)
        val aliasedRoot = File(root, ".")
        assertEquals(listOf(record.request), inventory(aliasedRoot).load())
    }

    @Test fun backupAndUnresolvedNewSidecarsCannotBeSkippedAsAbsent() {
        val root = temporary.newFolder()
        val record = AutoDevelopRecord(request())
        val backup = write(root, record, ".bak")
        var reads = 0
        assertEquals(listOf(record.request), inventory(root) { _, _ ->
            reads++; AutoDevelopCodec.decode(backup.readBytes())
        }.load())
        assertEquals(1, reads)
        backup.renameTo(File(backup.path.removeSuffix(".bak") + ".new"))
        assertThrows(IOException::class.java) { inventory(root) { _, _ -> null }.load() }
    }

    @Test fun corruptNewerGenerationAndUnknownCodecFailTheWholeInventory() {
        val root = temporary.newFolder()
        write(root, AutoDevelopRecord(request()))
        val newer = write(root, AutoDevelopRecord(request(generation = 2)))
        newer.writeBytes(byteArrayOf(1, 2, 3))
        assertThrows(IOException::class.java) { inventory(root).load() }
        // Future schema/corruption is held rather than reset or silently discarded.
        val bytes = AutoDevelopCodec.encode(AutoDevelopRecord(request(generation = 2)))
        bytes[4] = 99
        newer.writeBytes(bytes)
        assertThrows(IOException::class.java) { inventory(root).load() }
    }

    @Test fun outerIdAndGenerationMustMatchTheDecodedRequest() {
        val root = temporary.newFolder()
        write(root, AutoDevelopRecord(request()))
        assertThrows(IOException::class.java) { inventory(root) { _, _ -> AutoDevelopRecord(request("foreign")) }.load() }
        assertThrows(IOException::class.java) { inventory(root) { _, _ -> AutoDevelopRecord(request(generation = 2)) }.load() }
    }

    @Test fun malformedGenerationAndIncompleteEnumerationAreHeld() {
        val root = temporary.newFolder()
        val file = write(root, AutoDevelopRecord(request()))
        check(file.parentFile!!.renameTo(File(file.parentFile!!.parentFile, "01")))
        assertThrows(IOException::class.java) { inventory(root).load() }
        val noList = AutoDevelopInventory(root, { _, _ -> error("Do not read partial inventory") }, { null })
        assertThrows(IOException::class.java) { noList.load() }
    }

    @Test fun directoriesCannotMasqueradeAsJournals() {
        val root = temporary.newFolder()
        File(root, "capture/renders/1/auto-develop.bin.bak").mkdirs()
        assertThrows(IOException::class.java) { inventory(root).load() }
    }

    @Test fun trustedRootAliasIsAllowedButChildRedirectsAreRejected() {
        assumeFalse(System.getProperty("os.name").startsWith("Windows")) // Windows symlink creation needs host privilege.
        val root = temporary.newFolder()
        val record = AutoDevelopRecord(request())
        val file = write(root, record)
        val alias = File(temporary.root, "trusted-alias")
        Files.createSymbolicLink(alias.toPath(), root.toPath())
        assertEquals(listOf(record.request), inventory(alias).load())
        val redirected = File(temporary.root, "redirected-capture")
        check(file.parentFile!!.parentFile!!.parentFile!!.renameTo(redirected))
        Files.createSymbolicLink(File(root, "capture").toPath(), redirected.toPath())
        assertThrows(IOException::class.java) { inventory(root).load() }
    }
}
