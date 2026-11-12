package dev.cinematic.p0.rawcapture

import org.junit.Assert.*
import org.junit.Test

/** Test the actual process facade: native cancellation hooks cannot overwrite each other. */
class DevelopExecutionGpuStopTest {
    private val safe = AutoDevelopConditions(true, false, false, false, true, true, true, true, true)

    private fun withOwners(block: (AutoDevelopCoordinator.Attempt, AutoDevelopCoordinator.Attempt) -> Unit) {
        val owned = mutableListOf<AutoDevelopCoordinator.Attempt>()
        var request: AutoDevelopCoordinator.ForegroundExportRequest? = null
        try {
            DevelopExecution.updateConditions(safe)
            val gpu = requireNotNull(DevelopExecution.tryStartGpuDiagnosticPreviewObserved(safe).attempt).also(owned::add)
            val owner = Any()
            val reserved = requireNotNull(DevelopExecution.reserveForegroundExport(owner, safe))
            request = reserved
            val save = requireNotNull(DevelopExecution.tryStartForegroundExport(reserved, owner, safe)).also(owned::add)
            block(gpu, save)
        } finally {
            owned.forEach { DevelopExecution.completeAfterResourcesClosed(it) }
            request?.let(DevelopExecution::abandonForegroundExport)
            DevelopExecution.updateConditions(safe)
        }
    }

    @Test fun stoppingEitherOwnerInvokesOnlyItsOwnRegisteredHook() = withOwners { gpu, save ->
        var gpuStops = 0
        var saveStops = 0
        DevelopExecution.registerStop(save) { saveStops++ }
        DevelopExecution.registerGpuPreviewStop(gpu) { gpuStops++ }
        DevelopExecution.requestStop(gpu)
        assertEquals(1, gpuStops)
        assertEquals(0, saveStops)
        assertFalse(DevelopExecution.shouldStop(save))
        assertTrue(DevelopExecution.completeAfterResourcesClosed(gpu))
        DevelopExecution.requestStop(save)
        assertEquals(1, gpuStops)
        assertEquals(1, saveStops)
    }

    @Test fun captureBeforeGpuHookRegistrationStillStopsBothWithoutDroppingEitherLease() = withOwners { gpu, save ->
        var gpuStops = 0
        var saveStops = 0
        DevelopExecution.registerStop(save) { saveStops++ }
        val capture = DevelopExecution.reserveCapture()
        try {
            DevelopExecution.registerGpuPreviewStop(gpu) { gpuStops++ }
            assertEquals(1, gpuStops)
            assertEquals(1, saveStops)
            assertTrue(DevelopExecution.shouldStop(gpu))
            assertTrue(DevelopExecution.shouldStop(save))
            assertNull(DevelopExecution.tryStartGpuDiagnosticPreviewObserved(safe).attempt)
        } finally { DevelopExecution.releaseCapture(capture) }
        assertNull(DevelopExecution.tryStartPreviewObserved(safe))
    }

    @Test fun pauseStopsOnlyGpuWhileExportKeepsItsServiceAuthority() = withOwners { gpu, save ->
        var gpuStops = 0
        var saveStops = 0
        DevelopExecution.registerGpuPreviewStop(gpu) { gpuStops++ }
        DevelopExecution.registerStop(save) { saveStops++ }
        DevelopExecution.updateConditions(safe.copy(foreground = false))
        assertEquals(1, gpuStops)
        assertEquals(0, saveStops)
        assertTrue(DevelopExecution.shouldStop(gpu))
        assertFalse(DevelopExecution.shouldStop(save))
        DevelopExecution.updateExportConditions(save, safe.copy(foreground = false, thermalAllowed = false))
        assertEquals(1, saveStops)
        assertTrue(DevelopExecution.shouldStop(save))
    }

    @Test fun staleGpuCompletionCannotClearReplacementHookOrExportHook() = withOwners { gpu, save ->
        var gpuStops = 0
        var saveStops = 0
        DevelopExecution.registerGpuPreviewStop(gpu) { fail("Released input hook must not run") }
        DevelopExecution.registerStop(save) { saveStops++ }
        assertTrue(DevelopExecution.completeAfterResourcesClosed(gpu))
        val next = requireNotNull(DevelopExecution.tryStartGpuDiagnosticPreviewObserved(safe).attempt)
        try {
            DevelopExecution.registerGpuPreviewStop(next) { gpuStops++ }
            assertFalse(DevelopExecution.completeAfterResourcesClosed(gpu))
            DevelopExecution.requestStop(gpu)
            assertEquals(0, gpuStops)
            assertEquals(0, saveStops)
            DevelopExecution.requestStop(next)
            assertEquals(1, gpuStops)
            assertEquals(0, saveStops)
        } finally { DevelopExecution.completeAfterResourcesClosed(next) }
        DevelopExecution.requestStop(save)
        assertEquals(1, saveStops)
    }

    @Test fun failingFirstHookDoesNotHideSecondStopOrReplaceTheFirstFailure() = withOwners { gpu, save ->
        val first = IllegalStateException("save cancellation failed")
        val second = IllegalStateException("gpu cancellation failed")
        var gpuStops = 0
        DevelopExecution.registerStop(save) { throw first }
        DevelopExecution.registerGpuPreviewStop(gpu) { gpuStops++; throw second }
        DevelopExecution.coordinator.requestStop()
        val error = assertThrows(IllegalStateException::class.java) { DevelopExecution.updateConditions(safe) }
        assertSame(first, error)
        assertEquals(1, gpuStops)
        assertArrayEquals(arrayOf(second), error.suppressed)
        assertTrue(DevelopExecution.shouldStop(gpu))
        assertTrue(DevelopExecution.shouldStop(save))
    }
}
