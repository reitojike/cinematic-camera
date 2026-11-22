package dev.cinematic.p0.metrics

import dev.cinematic.p0.gpu.export.GpuExportTimings
import dev.cinematic.p0.jpegrender.original.recordDecodeTimings
import dev.cinematic.p0.raw.original.OriginalStage
import dev.cinematic.p0.raw.original.originalStage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SaveTimingTest {
    @get:Rule val temp = TemporaryFolder()
    private fun record(index: Int = 0) = SaveTimingRecord(index.toString(16).padStart(32, '0'), 10,
        SaveTimingKind.SAVE, SaveTimingStage.NATIVE_DECODE, SaveTimingOutcome.COMPLETED, 12_000_000, 123)

    @Test fun reportingFailureNeverChangesActionOrPrimaryFailure() {
        val timing = SaveTiming.start(10, SaveTimingKind.SAVE, { throw IOException("private URI must not escape") })!!
        var calls = 0
        assertEquals(7, originalStage(timing, OriginalStage.NATIVE_DECODE) { calls++; 7 })
        assertEquals(1, calls)
        val failure = CancellationException("private capture name")
        try { measured(timing, SaveTimingStage.PUBLICATION) { throw failure }; fail() }
        catch (actual: CancellationException) { assertSame(failure, actual); assertEquals(0, actual.suppressed.size) }
        timing.finish(SaveTimingOutcome.CANCELLED)
    }

    @Test fun missingClockIsNotZeroAndFailedSpansAreIncomplete() {
        val records = mutableListOf<SaveTimingRecord>()
        val timing = SaveTiming.start(10, SaveTimingKind.SAVE, records::addAll) { throw IOException() }!!
        try { timing.measure(SaveTimingStage.PUBLICATION) { throw IOException() }; fail() } catch (_: IOException) { }
        assertTrue(records.isEmpty())
        timing.finish(SaveTimingOutcome.FAILED)
        val publication = records.single { it.stage == SaveTimingStage.PUBLICATION }
        assertEquals(SaveTimingOutcome.INCOMPLETE, publication.outcome)
        assertNull(publication.nanos)
        assertNull(records.last().nanos)
    }

    @Test fun repeatedCleanupAndPixelsRemainSeparateEvents() {
        val records = mutableListOf<SaveTimingRecord>()
        var now = 0L
        val timing = SaveTiming.start(10, SaveTimingKind.SAVE, records::addAll) { now }!!
        timing.started(OriginalStage.NATIVE_DECODE); now += 100
        timing.finished(OriginalStage.NATIVE_DECODE, 100, true)
        assertTrue(records.isEmpty())
        timing.pixels(4000, 3000)
        timing.started(OriginalStage.RESOURCE_CLOSE); now += 12
        timing.finished(OriginalStage.RESOURCE_CLOSE, 12, true)
        timing.started(OriginalStage.RESOURCE_CLOSE); now += 34
        timing.finished(OriginalStage.RESOURCE_CLOSE, 34, false)
        timing.finish(SaveTimingOutcome.HOLD)
        timing.finish(SaveTimingOutcome.PUBLISHED)
        val cleanup = records.filter { it.stage == SaveTimingStage.RESOURCE_CLOSE && it.outcome != SaveTimingOutcome.STARTED }
        assertEquals(listOf(12L, 34L), cleanup.map { it.nanos })
        assertEquals(listOf(SaveTimingOutcome.COMPLETED, SaveTimingOutcome.INCOMPLETE), cleanup.map { it.outcome })
        assertNull(records.single { it.stage == SaveTimingStage.NATIVE_DECODE }.pixels)
        assertEquals(12_000_000L, records.last().pixels)
        assertEquals(SaveTimingOutcome.HOLD, records.last().outcome)
    }

    @Test fun recheckNeverFabricatesGenerationTimes() {
        val records = mutableListOf<SaveTimingRecord>()
        val timing = SaveTiming.start(10, SaveTimingKind.RECHECK, records::addAll)!!
        timing.measure(SaveTimingStage.RECONCILIATION) { 1 }
        timing.finish(SaveTimingOutcome.UNCONFIRMED)
        for (stage in listOf(SaveTimingStage.NATIVE_DECODE, SaveTimingStage.JPEG_ENCODE, SaveTimingStage.GPU_RENDER)) {
            val missing = records.single { it.stage == stage }
            assertEquals(SaveTimingOutcome.NOT_REACHED, missing.outcome)
            assertNull(missing.nanos)
        }
    }

    @Test fun gpuSpansDistinguishSuccessSkippedIncompleteAndUnavailable() {
        val records = mutableListOf<SaveTimingRecord>()
        val timing = SaveTiming.start(10, SaveTimingKind.SAVE, records::addAll)!!
        val gpu = GpuExportTimings()
        gpu.finished("B1", 123, true)
        gpu.finished("B1_QUANTILES", 42, false)
        gpu.drain(timing, false)
        timing.finish(SaveTimingOutcome.FAILED)
        assertEquals(SaveTimingOutcome.INCOMPLETE, records.single { it.stage == SaveTimingStage.B1_QUANTILES }.outcome)
        assertEquals(SaveTimingOutcome.NOT_REACHED, records.single { it.stage == SaveTimingStage.FINAL }.outcome)
        assertEquals(SaveTimingOutcome.NOT_REACHED, records.single { it.stage == SaveTimingStage.B2 }.outcome)
        records.clear()
        gpu.skipped("B3")
        val second = SaveTiming.start(10, SaveTimingKind.SAVE, records::addAll)!!
        gpu.drain(second, true)
        second.finish(SaveTimingOutcome.PUBLISHED)
        assertEquals(SaveTimingOutcome.SKIPPED, records.single { it.stage == SaveTimingStage.B3 }.outcome)
        assertEquals(SaveTimingOutcome.UNAVAILABLE, records.single { it.stage == SaveTimingStage.B2 }.outcome)
    }

    @Test fun slowReportingIsOutsideActionIntervals() {
        var now = 0L
        val records = mutableListOf<SaveTimingRecord>()
        val timing = SaveTiming.start(10, SaveTimingKind.SAVE, { records += it; now += 1_000_000 }) { now }!!
        timing.started(OriginalStage.NATIVE_DECODE)
        now += 123
        timing.finished(OriginalStage.NATIVE_DECODE, 1_000_123, true)
        timing.measure(SaveTimingStage.PUBLICATION) { now += 456 }
        assertTrue(records.isEmpty())
        timing.finish(SaveTimingOutcome.PUBLISHED)
        assertEquals(123L, records.single { it.stage == SaveTimingStage.NATIVE_DECODE }.nanos)
        assertEquals(456L, records.single { it.stage == SaveTimingStage.PUBLICATION }.nanos)
        assertEquals(579L, records.last().nanos)
        assertEquals(1_000_579L, now)
    }

    @Test fun restartAndVersionUpdateKeepValidRecords() {
        val directory = temp.newFolder()
        SaveTimingStore(directory).append(record())
        SaveTimingStore(directory).append(record(1).copy(version = 11))
        val restored = SaveTimingStore(directory).snapshot().toString(Charsets.US_ASCII)
        assertEquals(record().line() + record(1).copy(version = 11).line(), restored)
    }

    @Test fun recordAndByteBoundsRotateOldestWithoutPartialLines() {
        val directory = temp.newFolder()
        val store = SaveTimingStore(directory)
        repeat(SaveTimingStore.MAX_RECORDS + 7) { store.append(record(it)) }
        val bytes = store.snapshot()
        assertTrue(bytes.size <= SaveTimingStore.MAX_BYTES)
        val lines = bytes.toString(Charsets.US_ASCII).trimEnd().lines()
        assertEquals(SaveTimingStore.MAX_RECORDS, lines.size)
        assertEquals(record(7).line().trimEnd(), lines.first())
        assertTrue(lines.all(SaveTimingRecord::valid))
        File(directory, "records.tsv").writeBytes(ByteArray(SaveTimingStore.MAX_BYTES.toInt() + 1))
        assertEquals(0, store.snapshot().size)
        store.append(record(99))
        assertEquals(record(99).line(), store.snapshot().toString(Charsets.US_ASCII))
    }

    @Test fun partialCorruptTailAndForeignTextAreNeverExported() {
        val directory = temp.newFolder()
        File(directory, "records.tsv").writeText(record().line() + "content://private/photo\n" + record(1).line().dropLast(1))
        val store = SaveTimingStore(directory)
        assertEquals(record().line(), store.snapshot().toString(Charsets.US_ASCII))
        store.append(record(2))
        assertEquals(record().line() + record(2).line(), store.snapshot().toString(Charsets.US_ASCII))
    }

    @Test fun concurrentStoresDoNotLoseCommittedEvents() {
        val directory = temp.newFolder()
        val pool = Executors.newFixedThreadPool(4)
        try {
            val jobs = (0 until 80).map { index -> pool.submit { SaveTimingStore(directory).append(record(index)) } }
            jobs.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally { pool.shutdownNow() }
        val lines = SaveTimingStore(directory).snapshot().toString(Charsets.US_ASCII).trimEnd().lines()
        assertEquals(80, lines.size)
        assertEquals(80, lines.toSet().size)
    }

    @Test fun unwritableLocationDoesNotThrowOrOverwriteForeignFile() {
        val directory = temp.newFile()
        directory.writeText("existing")
        SaveTimingStore(directory).append(record())
        assertEquals("existing", directory.readText())
        assertFalse(SaveTimingRecord.valid(record().line().trimEnd().replace("COMPLETED", "secret-photo-name")))
    }
    @Test fun oneBatchAfterTerminalOutcomeAndNoStartedEvents() {
        var calls = 0
        val batches = mutableListOf<List<SaveTimingRecord>>()
        val timing = SaveTiming.start(20, SaveTimingKind.SAVE, { calls++; batches += it })!!
        timing.measure(SaveTimingStage.GPU_RENDER) {
            timing.started(OriginalStage.RESOURCE_CLOSE)
            timing.finished(OriginalStage.RESOURCE_CLOSE, 1, true)
            assertEquals(0, calls)
        }
        timing.finish(SaveTimingOutcome.CANCELLED)
        timing.event(SaveTimingStage.PUBLICATION, SaveTimingOutcome.COMPLETED, 99)
        timing.finish(SaveTimingOutcome.PUBLISHED)
        assertEquals(1, calls)
        assertEquals(SaveTimingOutcome.CANCELLED, batches.single().last().outcome)
        assertTrue(batches.single().none { it.outcome == SaveTimingOutcome.STARTED })
    }

    @Test fun unendedAttemptIsNotPersistedAndFailedSinkIsNotRetried() {
        var calls = 0
        val timing = SaveTiming.start(20, SaveTimingKind.SAVE, { calls++; throw IOException() })!!
        timing.measure(SaveTimingStage.PUBLICATION) { 1 }
        assertEquals(0, calls) // Includes process termination before finish: no durable partial attempt.
        timing.finish(SaveTimingOutcome.FAILED)
        timing.finish(SaveTimingOutcome.FAILED)
        assertEquals(1, calls)
    }

    @Test fun batchPreservesLegacyStartedAndVersionRows() {
        val directory = temp.newFolder()
        val legacy = record().copy(version = 19, outcome = SaveTimingOutcome.STARTED, nanos = null)
        val store = SaveTimingStore(directory)
        store.append(legacy)
        val timing = SaveTiming.start(20, SaveTimingKind.SAVE, store::appendAll)!!
        timing.measure(SaveTimingStage.PUBLICATION) { assertEquals(legacy.line(), store.snapshot().toString(Charsets.US_ASCII)) }
        timing.finish(SaveTimingOutcome.PUBLISHED)
        val lines = store.snapshot().toString(Charsets.US_ASCII).trimEnd().lines()
        assertEquals(legacy.line().trimEnd(), lines.first())
        assertTrue(lines.drop(1).none { it.contains("STARTED") })
    }

    @Test fun concurrentBatchesStayContiguousAndSnapshotsNeverSeePartialBatches() {
        val directory = temp.newFolder()
        val pool = Executors.newFixedThreadPool(4)
        try {
            (0 until 40).map { index -> pool.submit {
                SaveTimingStore(directory).appendAll(List(15) { record(index) })
                val lines = SaveTimingStore(directory).snapshot().toString(Charsets.US_ASCII).trimEnd().lines()
                assertEquals(0, lines.size % 15)
                assertTrue(lines.chunked(15).all { it.toSet().size == 1 })
            } }.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally { pool.shutdownNow() }
        assertEquals(600, SaveTimingStore(directory).snapshot().toString(Charsets.US_ASCII).trimEnd().lines().size)
    }

    @Test fun normalGpuSaveHasBoundedEndRecordsAndLegacyRowsRotateByActualCount() {
        val records = mutableListOf<SaveTimingRecord>()
        val timing = SaveTiming.start(20, SaveTimingKind.SAVE, records::addAll)!!
        timing.measure(SaveTimingStage.NATIVE_DECODE) { 1 }
        timing.pixels(6000, 4000)
        timing.measure(SaveTimingStage.GPU_RENDER) { 1 }
        val gpu = GpuExportTimings()
        for (stage in listOf("B1", "B1_QUANTILES", "B2", "B3", "B4", "B5", "FINAL")) gpu.finished(stage, 1, true)
        gpu.drain(timing, true)
        timing.measure(SaveTimingStage.JPEG_ENCODE) { 1 }
        repeat(2) { timing.started(OriginalStage.RESOURCE_CLOSE); timing.finished(OriginalStage.RESOURCE_CLOSE, 1, true) }
        timing.measure(SaveTimingStage.PUBLICATION) { 1 }
        timing.finish(SaveTimingOutcome.PUBLISHED)
        assertTrue(records.size >= 24)
        assertTrue(records.size < SaveTimingStore.MAX_RECORDS)
        val store = SaveTimingStore(temp.newFolder())
        store.append(record().copy(version = 19, outcome = SaveTimingOutcome.STARTED))
        repeat((SaveTimingStore.MAX_RECORDS - 1) / records.size) { store.appendAll(records) }
        assertTrue(store.snapshot().toString(Charsets.US_ASCII).contains("\t19\t"))
        store.appendAll(records)
        assertFalse(store.snapshot().toString(Charsets.US_ASCII).contains("\t19\t"))
        // Fixed-width schema upper bound, even with maximum numeric fields.
        val longest = SaveTimingRecord("f".repeat(32), Int.MAX_VALUE, SaveTimingKind.RECHECK,
            SaveTimingStage.RECONCILIATION, SaveTimingOutcome.NOT_REACHED, 24_000_000, Long.MAX_VALUE).line()
        assertTrue(longest.length * SaveTimingStore.MAX_RECORDS < SaveTimingStore.MAX_BYTES)
    }

    @Test fun environmentSnapshotsAreFixedSchemaAndPreserveLegacyRows() {
        val records = mutableListOf<SaveTimingRecord>()
        var sample = SaveTimingEnvironment(SaveVisibility.FOREGROUND, 0)
        var now = 10L
        val timing = SaveTiming.startObserved(22, SaveTimingKind.SAVE, records::addAll, { sample }, { now })!!
        sample = SaveTimingEnvironment(SaveVisibility.BACKGROUND, 3)
        now = 30L
        timing.finish(SaveTimingOutcome.CANCELLED)
        val env = records.filter { it.environment != null }
        assertEquals(listOf(SaveTimingStage.ENVIRONMENT_START, SaveTimingStage.ENVIRONMENT_END), env.map { it.stage })
        assertEquals(listOf(SaveVisibility.FOREGROUND, SaveVisibility.BACKGROUND), env.map { it.environment!!.visibility })
        assertEquals(listOf(0, 3), env.map { it.environment!!.thermal })
        assertEquals(20L, records.last().nanos)
        assertTrue(env.all { it.line().startsWith("2\t") && SaveTimingRecord.valid(it.line().trimEnd()) })
        val store = SaveTimingStore(temp.newFolder())
        store.append(record())
        store.appendAll(records)
        val lines = store.snapshot().toString(Charsets.US_ASCII).trimEnd().lines()
        assertTrue(lines.first().startsWith("1\t"))
        assertTrue(lines.all(SaveTimingRecord::valid))
        assertFalse(SaveTimingRecord.valid(env.first().line().trimEnd().replace("FOREGROUND", "photo-name")))
        assertFalse(SaveTimingRecord.valid(env.first().line().trimEnd().replace("\t0", "\t7")))
    }

    @Test fun failingEnvironmentIsUnknownAndEndSampleIsOutsideTotalDuration() {
        val records = mutableListOf<SaveTimingRecord>()
        var now = 0L
        val timing = SaveTiming.startObserved(22, SaveTimingKind.SAVE, records::addAll,
            { now += 5; throw IOException("must not escape") }, { now })!!
        now += 20
        timing.finish(SaveTimingOutcome.FAILED)
        assertEquals(25L, records.last().nanos)
        assertEquals(30L, now)
        assertTrue(records.filter { it.environment != null }.all { it.environment == SaveTimingEnvironment() })
        assertEquals(2, records.count { it.environment != null })
        timing.finish(SaveTimingOutcome.FAILED)
        assertEquals(2, records.count { it.environment != null })
    }

    @Test fun visibilityTracksMultipleActivitiesAndUnknownIsNotBackground() {
        val counter = SaveVisibilityCounter()
        assertEquals(SaveVisibility.UNKNOWN, counter.snapshot())
        counter.initialize()
        assertEquals(SaveVisibility.BACKGROUND, counter.snapshot())
        counter.started(); counter.started(); counter.stopped()
        assertEquals(SaveVisibility.FOREGROUND, counter.snapshot())
        counter.stopped()
        assertEquals(SaveVisibility.BACKGROUND, counter.snapshot())
        counter.stopped()
        assertEquals(SaveVisibility.UNKNOWN, counter.snapshot())
    }

    @Test fun existingUnclassifiedSpansAreRecordedWithoutChangingAction() {
        val records = mutableListOf<SaveTimingRecord>()
        var now = 0L
        val timing = SaveTiming.start(22, SaveTimingKind.SAVE, records::addAll) { now }!!
        val stages = listOf(OriginalStage.SOURCE_VERIFY_BEFORE, OriginalStage.SOURCE_SNAPSHOT,
            OriginalStage.DECODE_CLOSE, OriginalStage.ANALYSIS_REDUCE, OriginalStage.CONTROL_PREPARE,
            OriginalStage.BITMAP_ALLOC, OriginalStage.JPEG_INSPECT, OriginalStage.SOURCE_VERIFY_AFTER, OriginalStage.SCRATCH_CLEANUP)
        stages.forEach { originalStage(timing, it) { now += 123 } }
        timing.finish(SaveTimingOutcome.PUBLISHED)
        stages.forEach { stage ->
            val row = records.single { it.stage.name == stage.name }
            assertEquals(123L, row.nanos)
            assertEquals(SaveTimingOutcome.COMPLETED, row.outcome)
        }
    }

    @Test fun repeatedCallbacksAreBoundedAndKeepTerminalOutcome() {
        var batch = emptyList<SaveTimingRecord>()
        val timing = SaveTiming.start(20, SaveTimingKind.SAVE, { batch = it })!!
        repeat(SaveTimingStore.MAX_RECORDS + 100) {
            timing.event(SaveTimingStage.RESOURCE_CLOSE, SaveTimingOutcome.COMPLETED, 1)
        }
        timing.finish(SaveTimingOutcome.HOLD)
        assertEquals(SaveTimingStore.MAX_RECORDS, batch.size)
        assertEquals(SaveTimingOutcome.HOLD, batch.last().outcome)
    }

    @Test fun decodeTimingAndParityStagesStaySchema1WithinByteBoundAndAreOmittedByDefault() {
        val diagStages = listOf(
            SaveTimingStage.DECODE_PREAD,
            SaveTimingStage.DECODE_OPEN_PARSE,
            SaveTimingStage.DECODE_UNPACK,
            SaveTimingStage.DECODE_RAW2IMAGE,
            SaveTimingStage.DECODE_SCALE_COLORS,
            SaveTimingStage.DECODE_PRE_INTERP,
            SaveTimingStage.DECODE_AHD,
            SaveTimingStage.DECODE_CONVERT_RGB,
            SaveTimingStage.DECODE_MAKE_IMAGE,
            SaveTimingStage.DECODE_WRITE,
            SaveTimingStage.ANALYSIS_REDUCE_PARITY,
        )
        // Default finish() (SAVE and RECHECK) never auto-emits any of the 11 diagnostic stages.
        for (kind in listOf(SaveTimingKind.SAVE, SaveTimingKind.RECHECK)) {
            val defaultRecords = mutableListOf<SaveTimingRecord>()
            val timing = SaveTiming.start(26, kind, defaultRecords::addAll)!!
            timing.finish(SaveTimingOutcome.PUBLISHED)
            assertTrue(defaultRecords.none { it.stage in diagStages })
        }
        // Every diagnostic stage serializes as valid schema 1 within the fixed-width byte ceiling.
        for (stage in diagStages) {
            val row = SaveTimingRecord(
                "f".repeat(32), Int.MAX_VALUE, SaveTimingKind.SAVE,
                stage, SaveTimingOutcome.COMPLETED, 24_000_000L, Long.MAX_VALUE,
            )
            val line = row.line()
            assertTrue(line.startsWith("1\t"))
            assertTrue(SaveTimingRecord.valid(line.trimEnd()))
            assertTrue(line.length * SaveTimingStore.MAX_RECORDS < SaveTimingStore.MAX_BYTES)
        }
        // recordDecodeTimings maps all 10 keys, preserving verified pixels and marking missing/negative as UNAVAILABLE.
        val recorded = mutableListOf<SaveTimingRecord>()
        val timing = SaveTiming.start(26, SaveTimingKind.SAVE, recorded::addAll)!!
        timing.pixels(6000, 4000)
        val samples = mapOf<String, Long?>(
            "pread" to 0L,
            "openParse" to 12_345L,
            "unpack" to 23_456L,
            "raw2image" to -1L,
            "scaleColors" to 34_567L,
            "preInterp" to null,
            "ahd" to 45_678L,
            "convertRgb" to 56_789L,
            "makeImage" to 67_890L,
            // "write" omitted -> UNAVAILABLE
        )
        recordDecodeTimings(timing) { key -> samples[key] }
        timing.finish(SaveTimingOutcome.PUBLISHED)
        val byStage = recorded.associateBy { it.stage }
        assertEquals(SaveTimingOutcome.COMPLETED, byStage[SaveTimingStage.DECODE_PREAD]?.outcome)
        assertEquals(0L, byStage[SaveTimingStage.DECODE_PREAD]?.nanos)
        assertEquals(24_000_000L, byStage[SaveTimingStage.DECODE_PREAD]?.pixels)
        assertEquals(SaveTimingOutcome.COMPLETED, byStage[SaveTimingStage.DECODE_OPEN_PARSE]?.outcome)
        assertEquals(12_345L, byStage[SaveTimingStage.DECODE_OPEN_PARSE]?.nanos)
        assertEquals(SaveTimingOutcome.UNAVAILABLE, byStage[SaveTimingStage.DECODE_RAW2IMAGE]?.outcome)
        assertNull(byStage[SaveTimingStage.DECODE_RAW2IMAGE]?.nanos)
        assertEquals(SaveTimingOutcome.UNAVAILABLE, byStage[SaveTimingStage.DECODE_PRE_INTERP]?.outcome)
        assertNull(byStage[SaveTimingStage.DECODE_PRE_INTERP]?.nanos)
        assertEquals(SaveTimingOutcome.UNAVAILABLE, byStage[SaveTimingStage.DECODE_WRITE]?.outcome)
        assertNull(byStage[SaveTimingStage.DECODE_WRITE]?.nanos)
        assertEquals(SaveTimingOutcome.COMPLETED, byStage[SaveTimingStage.DECODE_AHD]?.outcome)
        assertEquals(45_678L, byStage[SaveTimingStage.DECODE_AHD]?.nanos)
    }

}
