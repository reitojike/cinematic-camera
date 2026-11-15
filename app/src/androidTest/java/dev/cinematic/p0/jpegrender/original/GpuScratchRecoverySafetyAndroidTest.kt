package dev.cinematic.p0.jpegrender.original

import dev.cinematic.p0.rawcapture.*
import android.content.ContentValues
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import dev.cinematic.p0.testing.writeSyntheticTrialDng
import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

/** Android filesystem guarantees, restricted to a fresh synthetic state namespace. */
class GpuScratchRecoverySafetyAndroidTest {
    private val context get() = isolatedGpuSaveSafetyApp()
    @Test fun realGpuBackendRemovesPartialJpegAfterCancellationAndFailure() = withOriginal { source ->
        for (afterOutput in listOf(false, true)) for (failure in listOf(false, true)) {
            cancelledBackend(source, afterOutput, failure)
        }
    }

    @Test fun repeatedGpuCancellationLeavesNoScratchDescriptorsOrPhotoCopies() = withOriginal { source ->
        repeat(3) {
            for (afterOutput in listOf(false, true)) cancelledBackend(source, afterOutput, false)
            val captures = File(context.noBackupFilesDir, "raw-captures")
            val retained = captures.walkTopDown().filter { it.isFile }.toList()
            assertTrue("No photograph or partial JPEG accumulates across cancelled sessions: ${retained.map { it.name }}",
                retained.isEmpty())
        }
    }

    private fun cancelledBackend(source: SharedOriginalReference, afterOutput: Boolean, fail: Boolean) {
        val app = context
        val id = ManualOriginalTrialRequests.identity(source)
        val request = Original24Request(id, System.nanoTime(), source)
        val geometry = Original24PreflightGeometry(64, 48, 64, 48, 1, 64, 48, 0, 0)
        val directory = RenderedJpegFiles(File(app.noBackupFilesDir, "raw-captures"), id, request.generation).generationRoot()
        val scratch = File(directory, "original-work")
        val output = File(directory, "output.jpg")
        val journal = JpegPublicationJournal(request.captureId, request.generation,
            AndroidAtomicJournalStorage(requireNotNull(directory.parentFile), request.generation.toString(),
                CaptureJournalFile.JPEG_PUBLICATION))
        val backend = AndroidManualOriginalTrialSaves.Backend(app, request, geometry,
            { journal }, { true })
        var reached = false
        var primary: Throwable? = null
        try {
            assertThrows(Exception::class.java) {
                backend.render {
                    val trigger = if (afterOutput) output.length() > 0 else File(scratch, "source-snapshot.dng").exists()
                    if (trigger) {
                        reached = true
                        if (fail) throw java.io.IOException("Synthetic producer failure after owned boundary")
                    }
                    trigger
                }
            }
            backend.releaseResources()
            assertTrue("Must reach the exact requested private-file boundary", reached)
            assertFalse(backend.hasUnreleasedResources)
            assertFalse(scratch.exists())
            assertFalse(output.exists())
            assertNull("No publication journal may be written by render-only failure/cancellation", journal.read())
            val prefix = directory.canonicalPath + "/"
            val descriptors = File("/proc/self/fd").listFiles() ?: error("Cannot enumerate descriptors")
            val open = descriptors.mapNotNull { runCatching { Files.readSymbolicLink(it.toPath()).toString() }.getOrNull() }
            assertTrue("No descriptor may retain the cancelled generation", open.none { it.startsWith(prefix) })
            assertEquals(source.fingerprint, DngContentVerifier.verify(source.fingerprint,
                { requireNotNull(app.contentResolver.openInputStream(Uri.parse(source.uri))) }))
        } catch (error: Throwable) { primary = error; throw error }
        finally { preserveSafetyCleanup(primary, { backend.releaseResources() }) }
    }

    private fun withOriginal(body: (SharedOriginalReference) -> Unit) {
        val app = context
        val resolver = app.contentResolver
        val name = "synthetic_retired_safety_${UUID.randomUUID()}.dng"
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/x-adobe-dng")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Cinematic Camera Synthetic Tests/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }))
        var success = false
        try {
            val bytes = requireNotNull(resolver.openOutputStream(uri, "w")).use { writeSyntheticTrialDng(it, 64, 48, 1) }
            check(resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1)
            val fingerprint = DngContentVerifier.identify(bytes, { requireNotNull(resolver.openInputStream(uri)) })
            body(SharedOriginalReference(uri.toString(), fingerprint, OriginalGrant.APP_OWNED))
            assertEquals(fingerprint, DngContentVerifier.verify(fingerprint, { requireNotNull(resolver.openInputStream(uri)) }))
            success = true
        } finally {
            // Preserve evidence on failure; never delete any other row or installed app data.
            if (success) {
                resolver.query(uri, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.OWNER_PACKAGE_NAME), null, null, null)!!.use { cursor ->
                    check(cursor.moveToFirst() && cursor.count == 1)
                    check(cursor.getLong(0) == ContentUris.parseId(uri) && cursor.getString(1) == name)
                    check(cursor.getString(2) == app.packageName)
                }
                check(resolver.delete(uri, null, null) == 1)
            }
        }
    }

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
        var primary: Throwable? = null
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
        } catch (error: Throwable) { primary = error; throw error }
        finally {
            preserveSafetyCleanup(primary,
                { if (blocker.exists()) { assertEquals("keep", blocker.readText()); check(blocker.delete()) } },
                { release.invoke(authority) })
        }
    }
}
