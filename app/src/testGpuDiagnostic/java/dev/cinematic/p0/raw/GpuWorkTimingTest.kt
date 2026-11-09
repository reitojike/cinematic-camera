package dev.cinematic.p0.raw

import org.junit.Assert.*
import org.junit.Test

class GpuWorkTimingTest {
    @Test fun exclusiveIntervalsAndRemainderReconcile() {
        var now = 0L
        val timing = GpuWorkTiming { now }
        timing.count(GpuWorkTiming.Count.CALLS)
        val result = timing.allocate(64) { now += 5; "output" }
        timing.measure(GpuWorkTiming.Phase.CPU_PACK) { now += 7 }
        now += 3
        val snapshot = timing.snapshot(now)
        assertEquals("output", result)
        assertEquals(5L, snapshot.intervals[GpuWorkTiming.Phase.CPU_ALLOCATE.ordinal])
        assertEquals(7L, snapshot.intervals[GpuWorkTiming.Phase.CPU_PACK.ordinal])
        assertEquals(3L, snapshot.otherNs)
        assertEquals(1L, snapshot.counts[GpuWorkTiming.Count.CPU_ALLOCATIONS.ordinal])
        assertEquals(64L, snapshot.counts[GpuWorkTiming.Count.CPU_BYTES.ordinal])
        assertTrue(snapshot.fields().contains("timingMode=split-allocation-v1"))
        assertEquals(snapshot.totalNs, snapshot.intervals.sum() + snapshot.otherNs)
    }
    @Test fun exceptionsCloseIntervalsWithoutMaskingFailure() {
        var now = 0L
        val timing = GpuWorkTiming { now }
        val expected = IllegalStateException("synthetic failure")
        try {
            timing.measure(GpuWorkTiming.Phase.UPLOAD) { now += 9; throw expected }
            fail("Expected failure")
        } catch (failure: IllegalStateException) { assertSame(expected, failure) }
        timing.measure(GpuWorkTiming.Phase.RELEASE) { now += 2 }
        assertEquals(11L, timing.snapshot(now).totalNs)
    }
    @Test fun nestedIntervalsAndNegativeRemaindersAreRejected() {
        val timing = GpuWorkTiming { 0 }
        timing.begin(GpuWorkTiming.Phase.CPU_PACK)
        try { timing.begin(GpuWorkTiming.Phase.UPLOAD); fail("Overlapping timers") }
        catch (_: IllegalStateException) { }
        timing.end(GpuWorkTiming.Phase.CPU_PACK, 0)
        val intervals = List(GpuWorkTiming.Phase.entries.size) { if (it == 0) 2L else 0L }
        try { GpuWorkTiming.Snapshot(1, intervals, List(GpuWorkTiming.Count.entries.size) { 0L }); fail("Negative remainder") }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun aggregatePreservesCountsAndWholeWallTime() {
        var now = 0L
        val timing = GpuWorkTiming { now }
        timing.count(GpuWorkTiming.Count.CALLS)
        timing.count(GpuWorkTiming.Count.PASSES, 13)
        timing.measure(GpuWorkTiming.Phase.READBACK) { now += 10 }
        val single = timing.snapshot(12)
        val two = single + single
        assertEquals(24L, two.totalNs)
        assertEquals(4L, two.otherNs)
        assertEquals(2L, two.counts[GpuWorkTiming.Count.CALLS.ordinal])
        assertEquals(26L, two.counts[GpuWorkTiming.Count.PASSES.ordinal])
    }
    @Test fun disabledTimingDoesNotChangeResultOrException() {
        val timing: GpuWorkTiming? = null
        assertEquals(42, timing.allocate(4) { 42 })
        try { timing.measure(GpuWorkTiming.Phase.DRAW) { error("unchanged") }; fail("Expected failure") }
        catch (failure: IllegalStateException) { assertEquals("unchanged", failure.message) }
    }
}
