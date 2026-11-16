package dev.cinematic.p0.metrics

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GpuPreviewTimingTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun record(index: Int = 0) = GpuPreviewTimingRecord("%032x".format(index), 22,
        GpuPreviewTimingStage.NATIVE_DECODE, GpuPreviewTimingOutcome.COMPLETED, 3072, 123)

    @Test fun measuresActionOnlyAndWritesOnceAfterPresentation() {
        var now = 0L
        val batches = mutableListOf<List<GpuPreviewTimingRecord>>()
        val timing = requireNotNull(GpuPreviewTiming.start(22, { batches += it }, { now }))
        timing.pixels(64, 48)
        now = 10
        assertEquals(7, timing.measure(GpuPreviewTimingStage.NATIVE_DECODE) { now = 110; 7 })
        timing.measure(GpuPreviewTimingStage.BOX_REDUCE) { now = 130 }
        assertTrue(batches.isEmpty())
        timing.firstFrameStarted(); now = 230; timing.presented()
        timing.presented(); timing.finish(GpuPreviewTimingOutcome.CANCELLED)
        assertEquals(1, batches.size)
        val rows = batches.single().associateBy { it.stage }
        assertEquals(100L, rows.getValue(GpuPreviewTimingStage.NATIVE_DECODE).nanos)
        assertEquals(20L, rows.getValue(GpuPreviewTimingStage.BOX_REDUCE).nanos)
        assertEquals(100L, rows.getValue(GpuPreviewTimingStage.GPU_FIRST_FRAME).nanos)
        assertEquals(230L, rows.getValue(GpuPreviewTimingStage.OPEN).nanos)
        assertEquals(GpuPreviewTimingOutcome.PRESENTED, rows.getValue(GpuPreviewTimingStage.OPEN).outcome)
        assertEquals(GpuPreviewTimingOutcome.NOT_REACHED, rows.getValue(GpuPreviewTimingStage.GUIDE).outcome)
    }

    @Test fun cancellingAnActiveStageIsIncompleteNeverNotReachedOrSuccess() {
        var rows = emptyList<GpuPreviewTimingRecord>()
        val timing = requireNotNull(GpuPreviewTiming.start(22, { rows = it }, { 0 }))
        timing.measure(GpuPreviewTimingStage.NATIVE_DECODE) { timing.finish(GpuPreviewTimingOutcome.CANCELLED) }
        timing.firstFrameStarted(); timing.presented()
        assertEquals(GpuPreviewTimingOutcome.INCOMPLETE, rows.single { it.stage == GpuPreviewTimingStage.NATIVE_DECODE }.outcome)
        assertEquals(GpuPreviewTimingOutcome.NOT_REACHED, rows.single { it.stage == GpuPreviewTimingStage.GPU_FIRST_FRAME }.outcome)
        assertEquals(GpuPreviewTimingOutcome.CANCELLED, rows.single { it.stage == GpuPreviewTimingStage.OPEN }.outcome)
    }

    @Test fun observerFailuresPreserveActionResultsAndOriginalExceptions() {
        val sinkFails = requireNotNull(GpuPreviewTiming.start(22, { throw OutOfMemoryError() }))
        assertEquals(42, sinkFails.measure(GpuPreviewTimingStage.SNAPSHOT) { 42 })
        sinkFails.finish(GpuPreviewTimingOutcome.FAILED)
        val error = IllegalStateException("test action")
        val observed = requireNotNull(GpuPreviewTiming.start(22, {}))
        assertSame(error, assertThrows(IllegalStateException::class.java) {
            observed.measure(GpuPreviewTimingStage.NATIVE_DECODE) { throw error }
        })
        assertNull(GpuPreviewTiming.start(22, {}, { throw OutOfMemoryError() }))
    }

    @Test fun clockFailureDoesNotChangeReachedStagesOrTerminalOutcome() {
        var reads = 0
        var rows = emptyList<GpuPreviewTimingRecord>()
        val timing = requireNotNull(GpuPreviewTiming.start(22, { rows = it }, {
            if (reads++ == 0) 0 else throw IllegalStateException("clock")
        }))
        assertEquals(42, timing.measure(GpuPreviewTimingStage.NATIVE_DECODE) { 42 })
        timing.firstFrameStarted(); timing.presented()
        assertEquals(GpuPreviewTimingOutcome.PRESENTED, rows.single { it.stage == GpuPreviewTimingStage.OPEN }.outcome)
        assertEquals(GpuPreviewTimingOutcome.COMPLETED, rows.single { it.stage == GpuPreviewTimingStage.NATIVE_DECODE }.outcome)
        assertEquals(GpuPreviewTimingOutcome.COMPLETED, rows.single { it.stage == GpuPreviewTimingStage.GPU_FIRST_FRAME }.outcome)
        assertTrue(rows.all { it.nanos == null })
    }

    @Test fun failureCleanupDoesNotReclassifyReadinessTimeoutOrMemoryRefusalAsCancellation() {
        for (failure in listOf(
            dev.cinematic.p0.gpu.preview.GpuInputReadinessTimeout(dev.cinematic.p0.gpu.preview.GpuInputWaitReason.OTHER_PREPARATION),
            dev.cinematic.p0.jpegrender.original.Original24MemoryUnavailable(),
            java.util.concurrent.CancellationException()
        )) {
            var rows = emptyList<GpuPreviewTimingRecord>()
            val timing = requireNotNull(GpuPreviewTiming.start(22, { rows = it }))
            // Mirrors the readiness catch: cancel the worker for cleanup, then rethrow original failure.
            val cleanupCancelled = java.util.concurrent.atomic.AtomicBoolean()
            try { try { throw failure } catch (cause: Exception) { cleanupCancelled.set(true); throw cause } }
            catch (cause: Exception) { timing.failed(cause) }
            assertTrue(cleanupCancelled.get())
            assertEquals(if (failure is java.util.concurrent.CancellationException) GpuPreviewTimingOutcome.CANCELLED
                else GpuPreviewTimingOutcome.FAILED, rows.single { it.stage == GpuPreviewTimingStage.OPEN }.outcome)
        }
    }

    @Test fun incompleteFrameAndMissingStagesRemainExplicit() {
        var rows = emptyList<GpuPreviewTimingRecord>()
        val timing = requireNotNull(GpuPreviewTiming.start(22, { rows = it }))
        timing.firstFrameStarted(); timing.finish(GpuPreviewTimingOutcome.FAILED)
        assertEquals(GpuPreviewTimingStage.entries.size, rows.size)
        assertEquals(GpuPreviewTimingOutcome.INCOMPLETE, rows.single { it.stage == GpuPreviewTimingStage.GPU_FIRST_FRAME }.outcome)
        assertTrue(rows.all { GpuPreviewTimingRecord.valid(it.line().trimEnd()) })
    }

    @Test fun storeBoundsAndCorruptLinesDoNotLeakForeignText() {
        val directory = temporary.newFolder()
        val store = GpuPreviewTimingStore(directory)
        store.appendAll((0 until GpuPreviewTimingStore.MAX_RECORDS + 5).map(::record))
        var rows = store.snapshot().toString(Charsets.US_ASCII).trimEnd().lines()
        assertEquals(GpuPreviewTimingStore.MAX_RECORDS, rows.size)
        assertEquals(record(5).line().trimEnd(), rows.first())
        File(directory, "records.tsv").writeText(record().line() + "private free text\n" + record(1).line().dropLast(1))
        assertEquals(record().line(), store.snapshot().toString(Charsets.US_ASCII))
        File(directory, "records.tsv").writeBytes(ByteArray(GpuPreviewTimingStore.MAX_BYTES.toInt() + 1))
        assertTrue(store.snapshot().isEmpty())
        store.append(record(9))
        assertEquals(record(9).line(), store.snapshot().toString(Charsets.US_ASCII))
    }

    @Test fun nonDirectoryCannotBeOverwrittenByDiagnostics() {
        val target = temporary.newFile().apply { writeText("keep") }
        GpuPreviewTimingStore(target).append(record())
        assertEquals("keep", target.readText())
    }

    @Test fun schemaRejectsOtherKindsInvalidRangesAndArbitraryStrings() {
        assertTrue(GpuPreviewTimingRecord.valid(record().line().trimEnd()))
        for (invalid in listOf(record().line().replace("GPU_OPEN", "SAVE"),
            record().line().replace("COMPLETED", "private"), record().line().replace("3072", "24000001"),
            record().line().replace("123", "-1"))) assertFalse(GpuPreviewTimingRecord.valid(invalid.trimEnd()))
    }
}
