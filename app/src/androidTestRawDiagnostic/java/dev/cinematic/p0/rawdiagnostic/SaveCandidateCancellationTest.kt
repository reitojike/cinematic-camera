package dev.cinematic.p0.rawdiagnostic

import android.os.Build
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.jpegrender.original.Original24Attempt
import dev.cinematic.p0.jpegrender.original.Original24Request
import dev.cinematic.p0.raw.original.OriginalStage
import dev.cinematic.p0.raw.original.OriginalStageTrace
import dev.cinematic.p0.rawcapture.*
import dev.cinematic.p0.testing.writeSyntheticTrialDng
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Synthetic ATD only. Stage-entry cancellation is not a worker-in-flight or device timing test. */
class SaveCandidateCancellationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Before fun requireIsolatedEmulator() {
        check(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        check(context.packageName == "dev.cinematic.p0.rawdiagnostic")
        assertEquals(0, context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP)
    }

    @Test(timeout = 180000) fun mistEntryCancellationWithoutCandidatesThreeTimes() = mistEntry(1)
    @Test(timeout = 180000) fun mistEntryCancellationWithCandidatesThreeTimes() = mistEntry(2)

    private fun mistEntry(workers: Int) {
        val root = File(context.noBackupFilesDir, "save-cancel-synthetic-${UUID.randomUUID()}").also { check(it.mkdir()) }
        val original = File(root, "synthetic.dng")
        val bytes = original.outputStream().use { writeSyntheticTrialDng(it, 256, 192, 1) }
        val fingerprint = DngContentVerifier.identify(bytes, { original.inputStream() })
        val source = SharedOriginalReference("content://example.test/synthetic", fingerprint, OriginalGrant.USER_SELECTED)
        val observations = JSONArray()
        repeat(3) { index ->
            val scratch = File(root, "scratch-$index")
            val ioOwner = DiagnosticIoOwner()
            var outputOpens = 0
            val io = object : AutoRenderIo {
                override fun openOriginal() = ioOwner.input(original.inputStream())
                override fun openOriginalDescriptor(): AutoReadOnlyDescriptor = error("Snapshot required")
                override fun openOutput(): java.io.OutputStream { outputOpens++; error("Cancelled before JPEG") }
                override fun openOutputRead(): java.io.InputStream = error("Cancelled before JPEG")
            }
            var entries = 0
            var mistCompleted: Boolean? = null
            lateinit var attempt: Original24Attempt
            val trace = object : OriginalStageTrace {
                override fun started(stage: OriginalStage) {
                    if (stage == OriginalStage.MIST) { entries++; attempt.requestStop() }
                }
                override fun finished(stage: OriginalStage, elapsedNanos: Long, completed: Boolean) {
                    if (stage == OriginalStage.MIST) mistCompleted = completed
                }
            }
            attempt = Original24Attempt(context, Original24Request("synthetic_cancel", index + 1L, source),
                io, scratch, trace, parallelFoundationWorkers = 2, parallelGlowWorkers = workers,
                parallelToneWorkers = workers, parallelMistWorkers = workers, fuseR7ColorRows = false)
            try {
                assertThrows(CancellationException::class.java) { attempt.use { it.render { false } } }
            } finally { attempt.close() }
            assertEquals(1, entries)
            assertEquals(false, mistCompleted)
            assertEquals(0, outputOpens)
            assertFalse(attempt.hasUnreleasedResources)
            val cleanup = ioOwner.finish(!attempt.hasUnreleasedResources) { true }
            assertTrue(cleanup.quiescent)
            assertFalse(cleanup.closeFailed)
            assertFalse(ioOwner.hasOpenResources)
            Thread.getAllStackTraces().keys.filter { it.name == "original-r7-row" }.forEach { it.join(5000); assertFalse(it.isAlive) }
            assertFalse(scratch.exists())
            assertNoOpenScratchDescriptors(scratch)
            // Use a fresh non-cancelled verifier after render, close, and owner/worker checks.
            assertEquals(fingerprint, DngContentVerifier.verify(fingerprint, { original.inputStream() }))
            observations.put(JSONObject().put("repeat", index + 1).put("workers", workers)
                .put("cancelStage", "MIST_ENTRY").put("stopped", true).put("ownersReleased", true)
                .put("rowWorkersStopped", true).put("scratchDescriptorsClosed", true)
                .put("finalOriginalUnchanged", true).put("outputOpens", outputOpens)
                .put("retainedScratch", inventory(scratch)))
        }
        assertEquals(fingerprint, DngContentVerifier.verify(fingerprint, { original.inputStream() }))
        writeReport("mist-entry-$workers.json", JSONObject().put("width", 256).put("height", 192)
            .put("scope", "SYNTHETIC_STAGE_ENTRY_NOT_WORKER_IN_FLIGHT_NOT_DEVICE_TIMING").put("runs", observations))
    }

    @Test(timeout = 180000) fun boundaryCancellationRemovesPrivateScratchAcrossSessions() {
        val uri = Uri.parse("content://dev.cinematic.p0.rawdiagnostic.test.synthetic/fixture.dng")
        context.contentResolver.call(uri, "prepareSynthetic", null, android.os.Bundle().apply {
            putByteArray("bytes", dev.cinematic.p0.testing.syntheticTrialDng(64, 48))
        })
        val observations = JSONArray()
        val retainedRoots = mutableListOf<File>()

        repeat(3) { index ->
            val existing = context.noBackupFilesDir.listFiles().orEmpty().map { it.name }.toSet()
            val runner = RawDiagnosticRunner(context)
            val report = JSONObject(runner.runCancellation(uri, DiagnosticComparison.SAVE_SAFETY) {})
            assertEquals("SUCCESS", report.getString("status"))
            assertTrue(report.getBoolean("selectedOriginalUnchanged"))
            assertTrue(report.getBoolean("resourcesQuiescent"))
            assertTrue(report.getBoolean("privateInputRemoved"))
            assertTrue(runner.resourcesQuiescent)
            val root = context.noBackupFilesDir.listFiles().orEmpty().single {
                it.name.startsWith("raw-diagnostic-") && it.name !in existing
            }
            retainedRoots += root
            assertFalse(File(root, "input.dng").exists())
            val attempts = JSONArray()
            repeat(2) { attempt ->
                val item = report.getJSONArray("runs").getJSONObject(attempt)
                assertFalse(item.getBoolean("privateFailedScratchRetained"))
                val scratch = File(root, "attempt-$attempt/scratch")
                assertFalse(scratch.exists())
                assertNoOpenScratchDescriptors(scratch)
                assertFalse(File(root, "attempt-$attempt/result.jpg").exists())
                attempts.put(JSONObject().put("boundary", item.getString("cancelBoundary"))
                    .put("retainedScratch", inventory(scratch)))
            }
            val total = retainedRoots.sumOf { root -> root.walkTopDown().filter { it.isFile && it.name != "report.json" }.sumOf { it.length() } }
            assertEquals("No photograph copies accumulate across sessions", 0L, total)
            observations.put(JSONObject().put("session", index + 1).put("attempts", attempts)
                .put("retainedDataBytesAcrossSessions", total))
        }
        writeReport("boundary-scratch-retention.json", JSONObject().put("width", 64).put("height", 48)
            .put("sessions", observations).put("accumulatesAcrossSessions", false))
    }

    @Test(timeout = 60000) fun failedCleanupRetainsOwnerAndRetriesWithoutDeletingUnknownFile() {
        val root = File(context.noBackupFilesDir, "save-cleanup-synthetic-${UUID.randomUUID()}").also { check(it.mkdir()) }
        val original = File(root, "synthetic.dng")
        val bytes = original.outputStream().use { writeSyntheticTrialDng(it, 64, 48, 1) }
        val fingerprint = DngContentVerifier.identify(bytes, { original.inputStream() })
        val source = SharedOriginalReference("content://example.test/cleanup", fingerprint, OriginalGrant.USER_SELECTED)
        val scratch = File(root, "scratch")
        val blocker = File(scratch, "owned-by-test.tmp")
        lateinit var attempt: Original24Attempt
        val trace = object : OriginalStageTrace {
            override fun started(stage: OriginalStage) {
                if (stage == OriginalStage.MIST) { blocker.writeText("keep"); attempt.requestStop() }
            }
            override fun finished(stage: OriginalStage, elapsedNanos: Long, completed: Boolean) = Unit
        }
        val io = object : AutoRenderIo {
            override fun openOriginal() = original.inputStream()
            override fun openOriginalDescriptor(): AutoReadOnlyDescriptor = error("Snapshot required")
            override fun openOutput(): java.io.OutputStream = error("No output expected")
            override fun openOutputRead(): java.io.InputStream = error("No output expected")
        }
        attempt = Original24Attempt(context, Original24Request("synthetic_cleanup", 1, source), io, scratch, trace)
        assertThrows(CancellationException::class.java) { attempt.render { false } }
        assertTrue(attempt.hasUnreleasedResources)
        assertEquals("keep", blocker.readText())
        assertThrows(IllegalStateException::class.java) { attempt.close() }
        assertTrue(attempt.hasUnreleasedResources)
        assertTrue(blocker.delete()) // Only this test owns the injected unknown file.
        attempt.close()
        assertFalse(attempt.hasUnreleasedResources)
        assertFalse(scratch.exists())
        assertEquals(fingerprint, DngContentVerifier.verify(fingerprint, { original.inputStream() }))
    }
    @Test(timeout = 120000) fun productBackendRemovesCopiesAndPartialJpegOnCancelOrFailure() {
        val uri = Uri.parse("content://dev.cinematic.p0.rawdiagnostic.test.synthetic/fixture.dng")
        val bytes = dev.cinematic.p0.testing.syntheticTrialDng(64, 48)
        context.contentResolver.call(uri, "prepareSynthetic", null, android.os.Bundle().apply { putByteArray("bytes", bytes) })
        val fingerprint = DngContentVerifier.identify(bytes.size.toLong(), { requireNotNull(context.contentResolver.openInputStream(uri)) })
        val source = SharedOriginalReference(uri.toString(), fingerprint, OriginalGrant.USER_SELECTED)
        val id = ManualOriginalTrialRequests.identity(source)
        val geometry = dev.cinematic.p0.jpegrender.original.Original24PreflightGeometry(64, 48, 64, 48, 1, 64, 48, 0, 0)
        for (afterOutput in listOf(false, true)) for (failure in listOf(false, true)) {
            val request = Original24Request(id, System.nanoTime(), source)
            val directory = RenderedJpegFiles(File(context.noBackupFilesDir, "raw-captures"), id, request.generation).generationRoot()
            val scratch = File(directory, "original-work")
            val output = File(directory, "output.jpg")
            val backend = AndroidManualOriginalTrialSaves.Backend(context, request, geometry, { error("No publication expected") }, { true })
            var reached = false
            assertThrows(Exception::class.java) {
                backend.render {
                    val trigger = if (afterOutput) output.length() > 0 else File(scratch, "source-snapshot.dng").exists()
                    if (trigger) {
                        reached = true
                        if (failure) throw java.io.IOException("Synthetic producer failure")
                    }
                    trigger
                }
            }
            backend.releaseResources()
            assertTrue("Cancellation/failure must reach the intended owned file", reached)
            assertFalse(backend.hasUnreleasedResources)
            assertFalse(scratch.exists())
            assertFalse(output.exists())
            assertEquals(fingerprint, DngContentVerifier.verify(fingerprint, { requireNotNull(context.contentResolver.openInputStream(uri)) }))
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
    private fun assertNoOpenScratchDescriptors(scratch: File) {
        val prefix = scratch.canonicalPath + "/"
        val open = File("/proc/self/fd").listFiles().orEmpty().mapNotNull {
            runCatching { Files.readSymbolicLink(it.toPath()).toString() }.getOrNull()
        }
        assertTrue("No descriptor still references this synthetic attempt", open.none { it.startsWith(prefix) })
    }

    private fun inventory(scratch: File): JSONObject {
        val files = scratch.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }
        return JSONObject().put("bytes", files.sumOf { it.length() }).put("files", JSONArray().apply {
            files.forEach { put(JSONObject().put("kind", it.name).put("bytes", it.length())) }
        })
    }

    private fun writeReport(name: String, report: JSONObject) {
        val configured = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        val root = if (configured != null) File(configured) else
            File(requireNotNull(context.externalMediaDirs.firstOrNull()), "additional_test_output")
        val reports = File(root, "save-candidate-synthetic").also { check(it.isDirectory || it.mkdirs()) }
        File(reports, name).writeText(report.toString(2))
    }
}
