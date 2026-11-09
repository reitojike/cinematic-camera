package dev.cinematic.p0.rawcapture

import org.junit.Assert.*
import org.junit.Test

class PreviewMemoryAdmissionTest {
    private val previewReady = permittedConditions().copy(memoryBudgetConfirmed = false, previewMemoryAvailable = true)

    @Test fun previewAndSaveUseDistinctMemoryConditionsButTheSameSlot() {
        val coordinator = AutoDevelopCoordinator().apply { updateConditions(previewReady) }
        assertNull(coordinator.tryStart())
        assertNull(coordinator.tryStartObserved(previewReady))
        assertNull(coordinator.reserveForegroundExport(Any(), previewReady))
        val preview = requireNotNull(coordinator.tryStartPreviewObserved(previewReady))
        assertNull(coordinator.tryStartPreviewObserved(previewReady))
        coordinator.updateConditions(previewReady)
        assertFalse(coordinator.shouldStop(preview))
        assertTrue(coordinator.completeAfterResourcesClosed(preview))
        assertNull(coordinator.tryStart())
        assertNotNull(coordinator.tryStartPreviewObserved(previewReady))
    }

    @Test fun previewChecksBothFreshAndCurrentlyObservedMemory() {
        for (value in listOf(false, null)) {
            val blocked = previewReady.copy(previewMemoryAvailable = value)
            val coordinator = AutoDevelopCoordinator().apply { updateConditions(blocked) }
            assertNull(coordinator.tryStartPreviewObserved(previewReady))
            coordinator.updateConditions(previewReady)
            assertNull(coordinator.tryStartPreviewObserved(blocked))
        }
    }

    @Test fun pressureStopsPreviewAndRecoveryRequiresExplicitClosureAndANewAttempt() {
        for (value in listOf(false, null)) {
            val coordinator = AutoDevelopCoordinator().apply { updateConditions(previewReady) }
            val first = requireNotNull(coordinator.tryStartPreviewObserved(previewReady))
            coordinator.updateConditions(previewReady.copy(previewMemoryAvailable = value))
            assertTrue(coordinator.shouldStop(first))
            coordinator.updateConditions(previewReady)
            assertTrue(coordinator.shouldStop(first))
            assertNull(coordinator.tryStartPreviewObserved(previewReady))
            assertTrue(coordinator.completeAfterResourcesClosed(first))
            val next = requireNotNull(coordinator.tryStartPreviewObserved(previewReady))
            assertFalse(coordinator.completeAfterResourcesClosed(first))
            assertFalse(coordinator.shouldStop(next))
        }
    }

    @Test fun capturePriorityAndAllOtherBlockersStillApplyToPreview() {
        val blocked = listOf(previewReady.copy(foreground = false), previewReady.copy(cameraSessionInUse = true),
            previewReady.copy(recordingOrFinalizing = null), previewReady.copy(rebindInProgress = true),
            previewReady.copy(thermalAllowed = null), previewReady.copy(storageAllowed = false))
        for (conditions in blocked) {
            val coordinator = AutoDevelopCoordinator().apply { updateConditions(previewReady) }
            assertNull(coordinator.tryStartPreviewObserved(conditions))
            val preview = requireNotNull(coordinator.tryStartPreviewObserved(previewReady))
            coordinator.updateConditions(conditions)
            assertTrue(coordinator.shouldStop(preview))
        }
        val coordinator = AutoDevelopCoordinator().apply { updateConditions(previewReady) }
        val capture = coordinator.reserveCapture()
        assertNull(coordinator.tryStartPreviewObserved(previewReady))
        coordinator.releaseCapture(capture)
        val preview = requireNotNull(coordinator.tryStartPreviewObserved(previewReady))
        coordinator.reserveCapture()
        assertTrue(coordinator.shouldStop(preview))
        assertNull(coordinator.tryStartPreviewObserved(previewReady))
    }

    @Test fun healthyPreviewObservationCannotKeepAFullSaveRunning() {
        val fullReady = previewReady.copy(memoryBudgetConfirmed = true)
        val coordinator = AutoDevelopCoordinator().apply { updateConditions(fullReady) }
        val save = requireNotNull(coordinator.tryStartObserved(fullReady))
        coordinator.updateConditions(previewReady)
        assertTrue(coordinator.shouldStop(save))
        assertNull(coordinator.tryStartPreviewObserved(previewReady))
    }
}
