package dev.cinematic.p0.metrics

import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.BuildConfig
import dev.cinematic.p0.rawcapture.*
import dev.cinematic.p0.testing.writeSyntheticTrialDng
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Explicit synthetic emulator run only. Also run extraction against the non-debuggable release. */
class SaveTimingAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext
    @Before fun gate() {
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        check(InstrumentationRegistry.getArguments().getString("isolated") == "true")
    }
    private fun extract(): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand("content read --uri content://${app.packageName}.save-timing/records")
    ).bufferedReader().use { it.readText() }

    @Test fun shellExtractsFixedSnapshotButAppUidCannotReadOrWrite() {
        val record = SaveTimingRecord(UUID.randomUUID().toString().replace("-", ""), 10, SaveTimingKind.RECHECK,
            SaveTimingStage.RECONCILIATION, SaveTimingOutcome.COMPLETED, 3072, 123)
        timingStore(app).append(record)
        assertTrue(extract().contains(record.line()))
        val uri = Uri.parse("content://${app.packageName}.save-timing/records")
        try { app.contentResolver.openInputStream(uri)?.close(); fail("App UID must not extract") }
        catch (_: SecurityException) { }
        try { app.contentResolver.openOutputStream(uri)?.close(); fail("Never writable") }
        catch (_: SecurityException) { }
        val unknown = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "content read --uri content://${app.packageName}.save-timing/other"
        )).bufferedReader().use { it.readText() }
        assertFalse(unknown.contains(record.attempt))
    }

    @Test fun ordinaryGpuSaveRecordsDecodeGpuEncodeAndPublicationWithoutPhotoIdentifiers() {
        runSyntheticSave(64, 48)
    }

    /** Absolute diagnostic cost only; not a save benchmark or a Pixel performance claim. */
    @Test fun captureEnvironmentAndBoundedBatchCost() {
        check(InstrumentationRegistry.getArguments().getString("timingCost") == "true")
        val directory = java.io.File(app.cacheDir, "synthetic-timing-cost-${UUID.randomUUID()}")
        check(directory.mkdir())
        val store = SaveTimingStore(directory)
        val snapshots = mutableListOf<Long>()
        val batches = mutableListOf<Long>()
        try {
            repeat(24) { iteration ->
                val sampleStart = System.nanoTime()
                val environment = AndroidSaveTimingEnvironment.snapshot(app)
                val sampleNanos = System.nanoTime() - sampleStart
                assertTrue(environment.thermal == null || environment.thermal in 0..6)
                val batchStart = System.nanoTime()
                val timing = SaveTiming.startObserved(22, SaveTimingKind.SAVE, store::appendAll,
                    { AndroidSaveTimingEnvironment.snapshot(app) })!!
                timing.pixels(1600, 1200)
                val gpu = dev.cinematic.p0.gpu.export.GpuExportTimings()
                listOf("B1", "B1_QUANTILES", "B2", "B3", "B4", "B5", "FINAL").forEach {
                    gpu.finished(it, 1, true)
                }
                gpu.drain(timing, true)
                timing.finish(SaveTimingOutcome.COMPLETED)
                val batchNanos = System.nanoTime() - batchStart
                if (iteration >= 4) { snapshots += sampleNanos; batches += batchNanos }
            }
            val stored = store.snapshot()
            assertTrue(stored.size <= SaveTimingStore.MAX_BYTES)
            assertEquals(SaveTimingStore.MAX_RECORDS, stored.toString(Charsets.US_ASCII).trimEnd().lines().size)
            android.util.Log.i("SaveTimingCost", "snapshot_samples_ns=${snapshots.joinToString(",")}")
            android.util.Log.i("SaveTimingCost", "environment_and_batch_samples_ns=${batches.joinToString(",")}")
        } finally {
            // Only this test-created private directory, never user media or production timing rows.
            directory.deleteRecursively()
        }
    }

    /** Explicit opt-in; one warm-up and three measured saves, with identical synthetic input. */
    @Test fun captureSaveStageBenchmark() {
        check(InstrumentationRegistry.getArguments().getString("saveBenchmark") == "true")
        val label = InstrumentationRegistry.getArguments().getString("saveBenchmarkRun")!!
        require(label.matches(Regex("[a-z0-9-]{1,40}")))
        val directory = java.io.File(app.getExternalFilesDir(null), "save-benchmark/$label")
        check(!directory.exists() && directory.mkdirs())
        repeat(4) { iteration ->
            val records = runSyntheticSave(1600, 1200)
            java.io.File(directory, "save-$iteration.tsv").writeText(records, Charsets.US_ASCII)
        }
    }

    private fun runSyntheticSave(width: Int, height: Int): String {
        check(app.packageName == "dev.cinematic.p0")
        val resolver = app.contentResolver
        val name = "synthetic_timing_${UUID.randomUUID()}.dng"
        val original = requireNotNull(resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/x-adobe-dng")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Cinematic Camera Synthetic Tests/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }))
        val bytes = requireNotNull(resolver.openOutputStream(original, "w")).use { writeSyntheticTrialDng(it, width, height, 1) }
        check(resolver.update(original, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1)
        val fingerprint = DngContentVerifier.identify(bytes, { requireNotNull(resolver.openInputStream(original)) })
        val source = SharedOriginalReference(original.toString(), fingerprint, OriginalGrant.APP_OWNED)
        val allowed = AutoDevelopConditions(true, false, false, false, true, true, true, true, previewMemoryAvailable = true)
        DevelopExecution.updateConditions(allowed)
        val before = extract().lines().filter { SaveTimingRecord.valid(it) }.map { it.split('\t')[1] }.toSet()
        val save = AndroidManualOriginalTrialSaves.prepare(app, source, .45, allowed).get(60, TimeUnit.SECONDS)
        val result = save.advance(allowed) { false }.get(120, TimeUnit.SECONDS)
        assertEquals(ManualOriginalTrialSavePhase.PUBLISHED, result.phase)
        assertFalse(save.hasUnreleasedResources)
        val snapshot = extract()
        val records = snapshot.lines().filter { SaveTimingRecord.valid(it) }
            .map { it.split('\t') }.filter { it[1] !in before }
        assertEquals(1, records.map { it[1] }.toSet().size)
        assertTrue(records.size >= 26)
        assertTrue(records.size <= SaveTimingStore.MAX_RECORDS)
        for (stage in listOf("ENVIRONMENT_START", "ENVIRONMENT_END")) {
            val row = records.single { it[4] == stage }
            assertEquals("2", row[0]); assertEquals(10, row.size)
            assertTrue(row[8] in setOf("FOREGROUND", "BACKGROUND", "UNKNOWN"))
            assertTrue(row[9] == "NA" || row[9].toInt() in 0..6)
        }
        assertTrue(records.none { it[5] == "STARTED" })
        assertTrue(records.single { it[4] == "ATTEMPT" }[7] != "NA")
        for (stage in listOf("NATIVE_DECODE", "B1", "B1_QUANTILES", "B2", "B3", "B4", "B5", "FINAL", "JPEG_ENCODE", "PUBLICATION", "SOURCE_VERIFY_BEFORE", "SOURCE_SNAPSHOT", "DECODE_CLOSE",
            "ANALYSIS_REDUCE", "CONTROL_PREPARE", "BITMAP_ALLOC", "JPEG_INSPECT", "SOURCE_VERIFY_AFTER", "SCRATCH_CLEANUP")) {
            assertTrue("Missing completed $stage", records.any { it[4] == stage && it[5] == "COMPLETED" && it[7] != "NA" })
        }
        assertTrue(records.any { it[4] == "ATTEMPT" && it[5] == "PUBLISHED" && it[6] == (width.toLong() * height).toString() })
        val publicationDetails = records.filter { it[4].startsWith("PUB_") }
        if (BuildConfig.PUBLICATION_DIAGNOSTIC) {
            assertEquals(PublicationInterval.entries.map { it.stage.name }, publicationDetails.map { it[4] })
            assertTrue(publicationDetails.all { it.size == 8 && it[5] == "COMPLETED" && it[7] != "NA" })
            val publication = records.single { it[4] == "PUBLICATION" }
            assertTrue(records.indexOf(publication) < records.indexOf(publicationDetails.first()))
            assertTrue(publicationDetails.sumOf { it[7].toLong() } <= publication[7].toLong())
        } else {
            assertTrue(publicationDetails.isEmpty())
        }
        for (privateText in listOf(name, original.toString(), save.request.captureId, source.fingerprint.toString()))
            assertFalse(snapshot.contains(privateText))
        assertEquals(fingerprint, DngContentVerifier.verify(fingerprint, { requireNotNull(resolver.openInputStream(original)) }))
        // Delete only exact rows created by this successful synthetic case; keep failure evidence.
        resolver.delete(Uri.parse(requireNotNull(result.uri)), null, null)
        resolver.delete(original, null, null)
        return records.joinToString("\n", postfix = "\n") { it.joinToString("\t") }
    }
}
