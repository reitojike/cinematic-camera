package dev.cinematic.p0.rawcapture

import org.junit.Assert.*
import org.junit.Test

class MemoryContinuationPolicyTest {
    @Test fun memoryBlocksNewWorkButDoesNotRevokeAnAdmittedAttempt() {
        val low = permittedConditions().copy(memoryBudgetConfirmed = false, previewMemoryAvailable = false)
        assertEquals(setOf(AutoDevelopBlocker.MEMORY), AutoDevelopPolicy.blockers(low))
        assertTrue(AutoDevelopPolicy.continuationBlockers(low).isEmpty())
        assertTrue(AutoDevelopPolicy.previewContinuationBlockers(low).isEmpty())
        val coordinator = AutoDevelopCoordinator()
        val good = permittedConditions().copy(previewMemoryAvailable = true)
        coordinator.updateConditions(good)
        val admitted = requireNotNull(coordinator.tryStartObserved(good))
        coordinator.updateConditions(low)
        assertFalse(coordinator.shouldStop(admitted))
        coordinator.updateConditions(low.copy(thermalAllowed = false))
        assertTrue(coordinator.shouldStop(admitted))
    }
    @Test fun gpuAndForegroundExportKeepTheirOwnSlotsAcrossPressure() {
        for (memory in listOf(false, null)) {
            val good = permittedConditions().copy(previewMemoryAvailable = true)
            val coordinator = AutoDevelopCoordinator().apply { updateConditions(good) }
            val owner = Any()
            val request = requireNotNull(coordinator.reserveForegroundExport(owner, good))
            val export = requireNotNull(coordinator.tryStartForegroundExport(request, owner, good))
            val gpu = requireNotNull(coordinator.tryStartGpuDiagnosticPreviewObserved(good).attempt)
            val pressure = good.copy(memoryBudgetConfirmed = memory, previewMemoryAvailable = memory)
            coordinator.updateExportConditions(export, pressure)
            coordinator.updateConditions(pressure)
            assertFalse(coordinator.shouldStop(export))
            assertFalse(coordinator.shouldStop(gpu))
            assertNull(coordinator.tryStartGpuDiagnosticPreviewObserved(good).attempt)
            coordinator.reserveCapture()
            assertTrue(coordinator.shouldStop(export))
            assertTrue(coordinator.shouldStop(gpu))
        }
    }
    @Test fun unknownAndUnsafeNonMemoryReasonsRemainStoppingConditions() {
        assertEquals(AutoDevelopBlocker.entries.toSet() - AutoDevelopBlocker.MEMORY,
            AutoDevelopPolicy.continuationBlockers(AutoDevelopConditions()))
        val pressure = permittedConditions().copy(memoryBudgetConfirmed = false)
        assertEquals(setOf(AutoDevelopBlocker.CAMERA_SESSION),
            AutoDevelopPolicy.continuationBlockers(pressure.copy(cameraSessionInUse = true)))
        assertEquals(setOf(AutoDevelopBlocker.FOREGROUND),
            AutoDevelopPolicy.continuationBlockers(pressure.copy(foreground = false)))
    }
}
