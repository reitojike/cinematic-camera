package dev.cinematic.p0

import org.junit.Assert.*
import org.junit.Test

class ControlOperationTest {
    @Test fun deadlineEndsOwnershipAndRejectsLateAckOrOldNextChain() {
        val gate = ControlOperation()
        val token = gate.begin(2, 100, 3000)
        assertTrue(gate.current(token))
        assertFalse(gate.expire(token, 3099))
        assertTrue(gate.expire(token, 3100))
        assertFalse(gate.current(token))
        assertFalse(gate.expire(token, 4000))
        val next = gate.begin(2, 4000, 3000)
        gate.finish(token) // A stale completion must not finish the new command.
        assertTrue(gate.current(next))
        assertFalse(gate.current(token))
    }
    @Test fun successfulFinishAndLifecycleInvalidationMakeScheduledTimeoutHarmless() {
        val gate = ControlOperation()
        val successful = gate.begin(1, 0, 100)
        gate.finish(successful)
        assertFalse(gate.expire(successful, 1000))
        val background = gate.begin(1, 1000, 100)
        gate.invalidate()
        assertFalse(gate.current(background))
        val rebound = gate.begin(2, 1000, 100)
        assertFalse(gate.current(background)); assertTrue(gate.current(rebound))
    }
    @Test fun hundredChangesKeepOneActiveAndOnlyTheLatestPair() {
        val queue = LatestExposureQueue()
        queue.offer(1, ManualExposure(100, 8_000_000))
        val active = queue.take()!!
        for (iso in 101..200) queue.offer(1, ManualExposure(iso, 6_250_000))
        assertEquals(active, queue.active)
        assertEquals(ManualExposure(200, 6_250_000), queue.latest?.pair)
        assertNull(queue.take())
        val latest = queue.complete(active)!!
        assertEquals(ManualExposure(200, 6_250_000), latest.pair)
        assertNull(queue.queued)
        assertNull(queue.complete(active))
        assertEquals(latest, queue.active)
        queue.complete(latest)
        assertFalse(queue.hasWork)
    }
    @Test fun failureOrBackgroundDiscardsQueueAndLateCompletionCannotRestartIt() {
        val queue = LatestExposureQueue()
        queue.offer(1, ManualExposure(100, 8_000_000)); val old = queue.take()!!
        queue.offer(1, ManualExposure(200, 8_000_000))
        queue.cancel()
        assertNull(queue.complete(old)); assertFalse(queue.hasWork)
        queue.offer(2, ManualExposure(400, 6_250_000)); val current = queue.take()!!
        assertNull(queue.complete(old)); assertEquals(current, queue.active)
    }
    @Test fun longToShortBudgetAccountsForPreviousExposureAndObservedFramePeriod() {
        assertTrue(controlBudgetMs(8_000_000, 30_000_000_000, 33_333_333) >= 92_000)
        assertTrue(controlBudgetMs(8_000_000, 8_000_000, 2_000_000_000) >= 8_000)
        assertTrue(controlBudgetMs(30_000_000_000, 8_000_000, 33_333_333) >= 92_000)
        assertTrue(controlBudgetMs(null, null, null) >= 3000)
    }
}
