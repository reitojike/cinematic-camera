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
    @Test fun longToShortBudgetAccountsForPreviousExposureAndObservedFramePeriod() {
        assertTrue(controlBudgetMs(8_000_000, 30_000_000_000, 33_333_333) >= 92_000)
        assertTrue(controlBudgetMs(8_000_000, 8_000_000, 2_000_000_000) >= 8_000)
        assertTrue(controlBudgetMs(30_000_000_000, 8_000_000, 33_333_333) >= 92_000)
        assertTrue(controlBudgetMs(null, null, null) >= 3000)
    }
    @Test fun sameCameraRebindRetainsLongDeadlineUntilClearIsAccepted() {
        val history = ExposureDurations()
        history.submitted("wide", 15_000_000_000)
        // No deletion on stop/rebind: the provider may reuse the same CameraControl.
        assertEquals(47_000L, controlBudgetMs(null, history.previous("wide"), null))
        assertNull(history.previous("ultrawide"))
        history.submitted("wide", 8_000_000) // Short submission alone does not prove old long options are gone.
        assertEquals(15_000_000_000, history.previous("wide"))
        history.accepted("wide", null)
        assertNull(history.previous("wide"))
        assertEquals(3_002L, controlBudgetMs(null, history.previous("wide"), null))
        history.submitted("wide", 15_000_000_000)
        history.accepted("wide", 8_000_000)
        assertEquals(8_000_000L, history.previous("wide"))
    }
}
