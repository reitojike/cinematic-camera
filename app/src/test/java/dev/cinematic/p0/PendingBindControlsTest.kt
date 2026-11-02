package dev.cinematic.p0

import org.junit.Assert.*
import org.junit.Test

class PendingBindControlsTest {
    private val controls = ControlSnapshot(overrides = SensorOverrides(focusDistance = 2f), locks = AutoLocks(whiteBalance = true))
    @Test fun restoreBelongsToOneAttemptAndFailureCannotCarryItToAnotherCameraOrMode() {
        val pending = PendingBindControls()
        pending.offer("wide/STILL", 8, controls)
        assertEquals(controls, pending.take("wide/STILL", 8)) // This attempt can fail after consumption.
        assertNull(pending.take("ultrawide/STILL", 9))
        assertNull(pending.take("wide/CINE", 10))
        assertNull(pending.take("wide/STILL", 11))
    }
    @Test fun mismatchedAttemptDiscardsSnapshotEvenWhenReturningToOldKey() {
        val pending = PendingBindControls()
        pending.offer("wide/STILL", 8, controls)
        assertNull(pending.take("wide/CINE", 8))
        assertNull(pending.take("wide/STILL", 8))
        pending.offer("wide/STILL", 8, controls)
        assertNull(pending.take("wide/STILL", 9))
        assertNull(pending.take("wide/STILL", 8))
    }
    @Test fun explicitStopClearsTransientLocksRatherThanRestoringThemOnRestart() {
        val pending = PendingBindControls()
        pending.offer("wide/STILL", 8, controls)
        pending.clear()
        assertNull(pending.take("wide/STILL", 8))
    }
}
