package dev.cinematic.p0.jpegrender.original

import dev.cinematic.p0.rawcapture.*
import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

/** Android filesystem guarantees, restricted to a fresh synthetic state namespace. */
class GpuScratchRecoverySafetyAndroidTest {
    private val context get() = isolatedGpuSaveSafetyApp()
    @Test fun coldRecoveryRejectsSymlinksOnAndroid() {
        val root = File(context.noBackupFilesDir, "save-symlink-synthetic-${UUID.randomUUID()}").also { check(it.mkdir()) }
        val target = File(root, "foreign").also { check(it.mkdir()) }
        val original = File(target, "source-snapshot.dng").also { it.writeText("keep") }
        val work = File(root, "raw-captures/TRIAL24_${"a".repeat(56)}/renders/1/original-work")
        check(requireNotNull(work.parentFile).mkdirs())
        Files.createSymbolicLink(work.toPath(), target.toPath())
        assertThrows(java.io.IOException::class.java) { Original24ScratchRecovery.product(root) }
        assertEquals("keep", original.readText())
        Files.delete(work.toPath())
        val files = RenderedJpegFiles(root, "synthetic", 1)
        check(files.generationRoot().mkdirs())
        val output = File(files.generationRoot(), "output.jpg")
        Files.createSymbolicLink(output.toPath(), original.toPath())
        assertThrows(java.io.IOException::class.java) { files.discardFailedOutput() }
        assertEquals("keep", original.readText())
        Files.delete(output.toPath())
    }

    @Test fun preflightFailedDeletionRetainsSnapshotAndSlotForRetry() {
        val root = File(context.noBackupFilesDir, "save-preflight-synthetic-${UUID.randomUUID()}").also { check(it.mkdir()) }
        // A nonempty directory injects delete failure without changing filesystem permissions.
        val snapshot = File(root, "snapshot.dng").also { check(it.mkdir()) }
        val blocker = File(snapshot, "owned-by-test").also { it.writeText("keep") }
        val source = SharedOriginalReference("content://example.test/preflight", OriginalFingerprint(1, "0".repeat(64)), OriginalGrant.USER_SELECTED)
        val type = Class.forName("dev.cinematic.p0.rawcapture.AndroidManualOriginalTrialSaves\$Authority")
        val authority = type.getDeclaredConstructor(android.content.Context::class.java, SharedOriginalReference::class.java)
            .apply { isAccessible = true }.newInstance(context, source)
        val snapshotField = type.getDeclaredField("preflightSnapshot").apply { isAccessible = true }
        val slotField = type.getDeclaredField("preflightSlot").apply { isAccessible = true }
        val release = type.getDeclaredMethod("releasePreflight").apply { isAccessible = true }
        val conditions = AutoDevelopConditions(true, false, false, false, true, true, true, true, true)
        val slot = requireNotNull(DevelopExecution.tryStartPreviewObserved(conditions))
        snapshotField.set(authority, snapshot); slotField.set(authority, slot)
        try {
            val error = assertThrows(java.lang.reflect.InvocationTargetException::class.java) { release.invoke(authority) }
            assertTrue(error.cause is java.nio.file.DirectoryNotEmptyException)
            assertSame(snapshot, snapshotField.get(authority)); assertSame(slot, slotField.get(authority))
            assertNull(DevelopExecution.tryStartPreviewObserved(conditions))
            assertEquals("keep", blocker.readText())
            assertTrue(blocker.delete())
            release.invoke(authority)
            assertFalse(snapshot.exists())
            assertNull(snapshotField.get(authority)); assertNull(slotField.get(authority))
            val next = requireNotNull(DevelopExecution.tryStartPreviewObserved(conditions))
            assertTrue(DevelopExecution.completeAfterResourcesClosed(next))
        } finally {
            if (blocker.exists()) check(blocker.delete())
            release.invoke(authority)
        }
    }
}
