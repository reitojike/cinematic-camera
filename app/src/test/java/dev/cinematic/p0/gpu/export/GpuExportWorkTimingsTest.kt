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
            now += 3
            reader.measure(GpuExportDetail.INPUT_FREE_WAIT) { now += 11 }
            now += 4
            now += 7
        }
        caller.measure(GpuExportDetail.UPLOAD) { now += 2 }
        caller.measure(GpuExportDetail.UPLOAD) { now += 3 }
        caller.mergeInput(reader)
        assertEquals(19L, caller.sample(GpuExportDetail.INPUT_READ_CONVERT)?.nanos)
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

    @Test fun readerFailureKeepsPartialStatusWithNoBufferWait() {
        var now = 0L
        val timing = GpuExportWorkTimings { now }
        val reader = requireNotNull(timing.fork())
        assertThrows(IllegalStateException::class.java) {
            reader.measure(GpuExportDetail.READER_TOTAL) { now = 10; error("read") }
        }
        timing.mergeInput(reader)
        assertEquals(10L, timing.sample(GpuExportDetail.INPUT_READ_CONVERT)?.nanos)
        assertFalse(requireNotNull(timing.sample(GpuExportDetail.INPUT_READ_CONVERT)).completed)
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

    @Test fun clockFailureIsLocalToItsIntervalAndNestedReadsCannotRepairIt() {
        for (failedRead in listOf(0, 3)) {
            var calls = 0
            val timing = GpuExportWorkTimings {
                val call = calls++
                if (call == failedRead) error("clock")
                call.toLong()
            }
            timing.measure(GpuExportDetail.READER_TOTAL) {
                timing.measure(GpuExportDetail.INPUT_FREE_WAIT) { }
            }
            timing.measure(GpuExportDetail.UPLOAD) { }
            assertNull(timing.sample(GpuExportDetail.READER_TOTAL)?.nanos)
            assertEquals(1L, timing.sample(GpuExportDetail.INPUT_FREE_WAIT)?.nanos)
            assertEquals(1L, timing.sample(GpuExportDetail.UPLOAD)?.nanos)
            assertTrue(requireNotNull(timing.sample(GpuExportDetail.READER_TOTAL)).completed)
        }
        var call = 0
        val nestedFailure = GpuExportWorkTimings {
            val tick = call++
            if (tick == 2) error("nested clock")
            tick.toLong()
        }
        nestedFailure.measure(GpuExportDetail.READER_TOTAL) {
            nestedFailure.measure(GpuExportDetail.INPUT_FREE_WAIT) { }
        }
        assertEquals(3L, nestedFailure.sample(GpuExportDetail.READER_TOTAL)?.nanos)
        assertNull(nestedFailure.sample(GpuExportDetail.INPUT_FREE_WAIT)?.nanos)
    }

    @Test fun primitiveClockRetainsSignedOriginsAndRejectsElapsedOverflow() {
        val ticks = ArrayDeque(listOf(-5L, -2L, Long.MIN_VALUE, Long.MAX_VALUE))
        val timing = GpuExportWorkTimings { ticks.removeFirst() }
        assertEquals(42, gpuWork(timing, GpuExportDetail.UPLOAD) { 42 })
        timing.measure(GpuExportDetail.FENCE_WAIT) { }
        assertEquals(3L, timing.sample(GpuExportDetail.UPLOAD)?.nanos)
        assertNull(timing.sample(GpuExportDetail.FENCE_WAIT)?.nanos)
    }

    @Test fun defaultPrimitiveClockAndForkRecordAvailableIntervals() {
        val timing = GpuExportWorkTimings()
        val reader = requireNotNull(timing.fork())
        timing.measure(GpuExportDetail.UPLOAD) { }
        reader.measure(GpuExportDetail.READER_TOTAL) { }
        assertTrue(requireNotNull(timing.sample(GpuExportDetail.UPLOAD)?.nanos) >= 0)
        assertTrue(requireNotNull(reader.sample(GpuExportDetail.READER_TOTAL)?.nanos) >= 0)
    }
    @Test fun mergedReaderDistinguishesMissingWaitFromFailedClockAndNotReached() {
        val absent = GpuExportWorkTimings { 0L }
        absent.mergeInput(requireNotNull(absent.fork()))
        assertNull(absent.sample(GpuExportDetail.INPUT_READ_CONVERT))
        val missing = GpuExportWorkTimings { 0L }
        missing.mergeInput(null)
        assertNotNull(missing.sample(GpuExportDetail.INPUT_READ_CONVERT))
        assertNull(missing.sample(GpuExportDetail.INPUT_READ_CONVERT)?.nanos)
        var call = 0
        val reader = GpuExportWorkTimings {
            val tick = call++
            if (tick == 1) error("buffer wait clock")
            tick.toLong()
        }
        reader.measure(GpuExportDetail.READER_TOTAL) {
            reader.measure(GpuExportDetail.INPUT_FREE_WAIT) { }
        }
        val caller = GpuExportWorkTimings()
        caller.mergeInput(reader)
        assertNull(caller.sample(GpuExportDetail.INPUT_READ_CONVERT)?.nanos)
        assertTrue(requireNotNull(caller.sample(GpuExportDetail.INPUT_READ_CONVERT)).completed)
    }

    @Test fun rowProcessMeasuresWholeBandAndKeepsPrimaryFailure() {
        var now = 0L
        var calls = 0
        val timing = GpuExportWorkTimings { calls++; now }
        timing.measure(GpuExportDetail.ROW_PROCESS) {
            repeat(1200) { now += 2; now += 3 }
        }
        assertEquals(2, calls)
        assertEquals(6000L, timing.sample(GpuExportDetail.ROW_PROCESS)?.nanos)
        val failure = IllegalStateException("consumer")
        assertSame(failure, assertThrows(IllegalStateException::class.java) {
            timing.measure(GpuExportDetail.ROW_PROCESS) { now += 1; throw failure }
        })
        assertFalse(requireNotNull(timing.sample(GpuExportDetail.ROW_PROCESS)).completed)
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
        assertEquals(SaveTimingOutcome.NOT_REACHED, record(SaveTimingStage.FINAL_ROW_PROCESS).outcome)
        assertEquals(SaveTimingOutcome.UNAVAILABLE, record(SaveTimingStage.B4_READBACK).outcome)
        assertEquals(SaveTimingOutcome.NOT_REACHED, record(SaveTimingStage.B1_QUANTILES_READBACK).outcome)
        assertFalse(records.any { it.stage.name.contains("READER_TOTAL") })
    }
}
