package dev.cinematic.p0.photoroll

import dev.cinematic.p0.rawcapture.AutoDevelopConditions
import dev.cinematic.p0.rawcapture.AutoDevelopCoordinator
import org.junit.Assert.*
import org.junit.Test

class PhotoRollCameraPriorityTest {
    private val allowed = AutoDevelopConditions(true, false, false, false, true, true, true, true)
    private fun priority(coordinator: AutoDevelopCoordinator) = PhotoRollCameraPriority(
        coordinator::reserveCapture, { coordinator.releaseCapture(it) }, { coordinator.updateConditions(AutoDevelopConditions()) })

    @Test fun cameraResumeStopsWorkerButDoesNotReleaseItsResources() {
        val gate = AutoDevelopCoordinator()
        gate.updateConditions(allowed)
        val worker = requireNotNull(gate.tryStartObserved(allowed))
        val camera = priority(gate)
        camera.starting(); camera.starting()
        assertTrue(gate.shouldStop(worker))
        camera.background()
        assertNull(gate.tryStartObserved(allowed))
        gate.completeAfterResourcesClosed(worker)
        assertNull(gate.tryStartObserved(allowed)) // The old allowed snapshot was invalidated.
        gate.updateConditions(allowed)
        assertNotNull(gate.tryStartObserved(allowed))
    }

    @Test fun lateOldActivityCannotReleaseNewCameraReservation() {
        val gate = AutoDevelopCoordinator()
        val old = priority(gate)
        val newer = priority(gate)
        old.starting(); newer.starting()
        old.background(); old.background()
        gate.updateConditions(allowed)
        assertNull(gate.tryStartObserved(allowed))
        newer.background()
        assertNull(gate.tryStartObserved(allowed))
        gate.updateConditions(allowed)
        assertNotNull(gate.tryStartObserved(allowed))
    }
}
