package dev.cinematic.p0.photoroll

import dev.cinematic.p0.rawcapture.AutoDevelopBlocker
import dev.cinematic.p0.rawcapture.AutoDevelopPolicy
import org.junit.Assert.*
import org.junit.Test

class PhotoRollDevelopConditionsTest {
    private val idle = PhotoRollResources(true, 0, false, 1024L * 1024 * 1024,
        PhotoRollCameraSnapshot(false, false, false, false), true, true)

    @Test fun observedIdleCanStartOnlyWithThisJobsRenderer() {
        assertTrue(AutoDevelopPolicy.blockers(idle.developConditions(true)).isEmpty())
        assertEquals(setOf(AutoDevelopBlocker.RENDERER), AutoDevelopPolicy.blockers(idle.developConditions(false)))
    }

    @Test fun ownerFreeEditorStillNeedsPositiveHardwareAvailability() {
        val restored = idle.copy(camera = PhotoRollCameraOwners().snapshot())
        assertTrue(AutoDevelopPolicy.blockers(restored.developConditions(true)).isEmpty())
        for (hardware in listOf(false, null)) {
            assertEquals(setOf(AutoDevelopBlocker.CAMERA_SESSION),
                AutoDevelopPolicy.blockers(restored.copy(camerasIdle = hardware).developConditions(true)))
        }
    }

    @Test fun hardwareIdleDoesNotEraseFinalizeOrPendingCameraAcquisition() {
        assertTrue(AutoDevelopBlocker.RECORDING_OR_FINALIZING in AutoDevelopPolicy.blockers(
            idle.copy(camera = idle.camera.copy(recordingOrFinalizing = true)).developConditions(true)))
        assertTrue(AutoDevelopBlocker.CAMERA_SESSION in AutoDevelopPolicy.blockers(
            idle.copy(camera = idle.camera.copy(cameraMayStart = true)).developConditions(true)))
        assertTrue(AutoDevelopBlocker.CAMERA_SESSION in AutoDevelopPolicy.blockers(
            idle.copy(camera = idle.camera.copy(captureInProgress = true)).developConditions(true)))
    }

    @Test fun unknownProcessObservationOrBackgroundOrStorageDoesNotStart() {
        val missing = idle.copy(camera = PhotoRollCameraSnapshot()).developConditions(true)
        assertNull(missing.cameraSessionInUse)
        assertNull(missing.recordingOrFinalizing)
        assertNull(missing.rebindInProgress)
        assertFalse(AutoDevelopPolicy.blockers(missing).isEmpty())
        assertEquals(setOf(AutoDevelopBlocker.FOREGROUND), AutoDevelopPolicy.blockers(idle.copy(foreground = false).developConditions(true)))
        assertEquals(setOf(AutoDevelopBlocker.STORAGE), AutoDevelopPolicy.blockers(idle.copy(privateStorageAllowed = null).developConditions(true)))
    }

    @Test fun invalidThermalAndUnknownOrLowMemoryStayBlocked() {
        assertNull(idle.copy(thermalStatus = -1).developConditions(true).thermalAllowed)
        assertEquals(false, idle.copy(thermalStatus = 3).developConditions(true).thermalAllowed)
        assertNull(idle.copy(lowMemory = null).developConditions(true).memoryBudgetConfirmed)
        assertEquals(false, idle.copy(lowMemory = true).developConditions(true).memoryBudgetConfirmed)
        assertEquals(true, idle.copy(availableBytes = 128L * 1024 * 1024).developConditions(true).memoryBudgetConfirmed)
    }
}
