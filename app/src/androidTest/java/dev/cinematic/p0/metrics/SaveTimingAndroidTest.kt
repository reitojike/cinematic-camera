package dev.cinematic.p0.metrics

import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
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
        for (stage in listOf("NATIVE_DECODE", "B1", "B1_QUANTILES", "B2", "B3", "B4", "B5", "FINAL", "JPEG_ENCODE", "PUBLICATION")) {
            assertTrue("Missing completed $stage", records.any { it[4] == stage && it[5] == "COMPLETED" && it[7] != "NA" })
        }
        assertTrue(records.any { it[4] == "ATTEMPT" && it[5] == "PUBLISHED" && it[6] == (width.toLong() * height).toString() })
        for (privateText in listOf(name, original.toString(), save.request.captureId, source.fingerprint.toString()))
            assertFalse(snapshot.contains(privateText))
        assertEquals(fingerprint, DngContentVerifier.verify(fingerprint, { requireNotNull(resolver.openInputStream(original)) }))
        // Delete only exact rows created by this successful synthetic case; keep failure evidence.
        resolver.delete(Uri.parse(requireNotNull(result.uri)), null, null)
        resolver.delete(original, null, null)
        return records.joinToString("\n", postfix = "\n") { it.joinToString("\t") }
    }
}
