package dev.cinematic.p0.rawcapture

import org.junit.Assert.*
import org.junit.Test

class AutoDevelopStopOwnershipTest {
    @Test fun aDelayedStopForTheOldOwnerCannotCancelTheNextJob() {
        val coordinator = AutoDevelopCoordinator()
        coordinator.updateConditions(AutoDevelopConditions(true, false, false, false, true, true, true, true))
        val old = coordinator.tryStart()!!
        coordinator.completeAfterResourcesClosed(old)
        val current = coordinator.tryStart()!!
        coordinator.requestStop(old)
        assertFalse(coordinator.shouldStop(current))
        coordinator.requestStop(current)
        assertTrue(coordinator.shouldStop(current))
        coordinator.completeAfterResourcesClosed(current)
    }
}
