package dev.cinematic.p0.gpu.export

import dev.cinematic.p0.metrics.SaveTiming
import dev.cinematic.p0.metrics.SaveTimingKind
import dev.cinematic.p0.metrics.SaveTimingOutcome
import dev.cinematic.p0.metrics.SaveTimingRecord
import dev.cinematic.p0.metrics.SaveTimingStage
import org.junit.Assert.*
import org.junit.Test

class GpuExportWorkTimingsTest {
    @Test fun accumulatesCallsAndSeparatesParallelReaderFromCaller() {
        var now = 0L
        val caller = GpuExportWorkTimings { now }
        val reader = requireNotNull(caller.fork())
        reader.measure(GpuExportDetail.READER_TOTAL) {
            now += 5
            reader.measure(GpuExportDetail.INPUT_CALLBACK) {
                now += 3
                reader.measure(GpuExportDetail.INPUT_FREE_WAIT) { now += 11 }
                now += 4
            }
            now += 7
        }
        caller.measure(GpuExportDetail.UPLOAD) { now += 2 }
        caller.measure(GpuExportDetail.UPLOAD) { now += 3 }
        caller.mergeInput(reader)
        assertEquals(12L, caller.sample(GpuExportDetail.INPUT_READ_CONVERT)?.nanos)
        assertEquals(7L, caller.sample(GpuExportDetail.INPUT_PACK)?.nanos)
        assertEquals(5L, caller.sample(GpuExportDetail.UPLOAD)?.nanos)
        assertNull(caller.sample(GpuExportDetail.FENCE_WAIT))
    }

    @Test fun brokenClockCannotChangeSuccessOrPrimaryFailure() {
        val timing = GpuExportWorkTimings { throw IllegalStateException("clock") }
        assertEquals(17, timing.measure(GpuExportDetail.UPLOAD) { 17 })
        val failure = IllegalArgumentException("producer")
        assertSame(failure, assertThrows(IllegalArgumentException::class.java) {
            timing.measure(GpuExportDetail.UPLOAD) { throw failure }
        })
        assertNull(timing.sample(GpuExportDetail.UPLOAD)?.nanos)
        assertFalse(requireNotNull(timing.sample(GpuExportDetail.UPLOAD)).completed)
    }

    @Test fun readerFailureKeepsPartialStatusAndNeverFabricatesUnreachedPacking() {
        var now = 0L
        val timing = GpuExportWorkTimings { now }
        val reader = requireNotNull(timing.fork())
        assertThrows(IllegalStateException::class.java) {
            reader.measure(GpuExportDetail.READER_TOTAL) { now = 10; error("read") }
        }
        timing.mergeInput(reader)
        assertEquals(10L, timing.sample(GpuExportDetail.INPUT_READ_CONVERT)?.nanos)
        assertFalse(requireNotNull(timing.sample(GpuExportDetail.INPUT_READ_CONVERT)).completed)
        assertNull(timing.sample(GpuExportDetail.INPUT_PACK))
    }

    @Test fun backwardClockAndOverflowAreUnavailableNotZero() {
        val ticks = ArrayDeque(listOf(10L, 5L, 0L, Long.MAX_VALUE, 0L, 1L))
        val timing = GpuExportWorkTimings { ticks.removeFirst() }
        timing.measure(GpuExportDetail.UPLOAD) { }
        timing.measure(GpuExportDetail.FENCE_WAIT) { }
        timing.measure(GpuExportDetail.FENCE_WAIT) { }
        assertNull(timing.sample(GpuExportDetail.UPLOAD)?.nanos)
        assertNull(timing.sample(GpuExportDetail.FENCE_WAIT)?.nanos)
    }

    @Test fun detailDrainDistinguishesSkippedMissingIncompleteAndClockFailure() {
        val records = mutableListOf<SaveTimingRecord>()
        val save = SaveTiming.start(22, SaveTimingKind.SAVE, records::addAll)
        val gpu = GpuExportTimings { throw IllegalStateException("clock") }
        requireNotNull(gpu.detail("B1")).measure(GpuExportDetail.UPLOAD) { }
        gpu.finished("B1", 20, true)
        gpu.skipped("B2")
        requireNotNull(gpu.detail("B4")).measure(GpuExportDetail.READBACK) { }
        gpu.finished("B4", 10, true)
        requireNotNull(gpu.detail("FINAL")).measure(GpuExportDetail.READBACK) { }
        gpu.finished("FINAL", 15, false)
        gpu.drain(save, false)
        save?.finish(SaveTimingOutcome.CANCELLED)
        fun record(stage: SaveTimingStage) = records.single { it.stage == stage }
        assertEquals(SaveTimingOutcome.UNAVAILABLE, record(SaveTimingStage.B1_UPLOAD).outcome)
        assertNull(record(SaveTimingStage.B1_UPLOAD).nanos)
        assertEquals(SaveTimingOutcome.UNAVAILABLE, record(SaveTimingStage.B1_INPUT_WAIT).outcome)
        assertEquals(SaveTimingOutcome.SKIPPED, record(SaveTimingStage.B2_UPLOAD).outcome)
        assertEquals(SaveTimingOutcome.NOT_REACHED, record(SaveTimingStage.B3_UPLOAD).outcome)
        assertEquals(SaveTimingOutcome.INCOMPLETE, record(SaveTimingStage.FINAL_READBACK).outcome)
        assertEquals(SaveTimingOutcome.NOT_REACHED, record(SaveTimingStage.FINAL_ROW_CONSUME).outcome)
        assertEquals(SaveTimingOutcome.UNAVAILABLE, record(SaveTimingStage.B4_READBACK).outcome)
        assertEquals(SaveTimingOutcome.NOT_REACHED, record(SaveTimingStage.B1_QUANTILES_READBACK).outcome)
        assertFalse(records.any { it.stage.name.contains("READER_TOTAL") })
    }
}
