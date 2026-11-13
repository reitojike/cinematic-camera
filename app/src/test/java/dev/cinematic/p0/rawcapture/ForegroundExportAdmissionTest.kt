package dev.cinematic.p0.rawcapture

import org.junit.Assert.*
import org.junit.Test

class ForegroundExportAdmissionTest {
    private fun safe() = AutoDevelopConditions(true, false, false, false, true, true, true, true)
    private fun ready() = AutoDevelopCoordinator().apply { updateConditions(safe()) }
    private val owner = Any()
    private fun start(c: AutoDevelopCoordinator): AutoDevelopCoordinator.Attempt {
        val request = requireNotNull(c.reserveForegroundExport(owner, safe()))
        return requireNotNull(c.tryStartForegroundExport(request, owner, safe()))
    }
    @Test fun onlyForegroundCanReserveButAcceptedSaveSurvivesImmediatePause() {
        val c = AutoDevelopCoordinator()
        assertNull(c.reserveForegroundExport(owner, safe()))
        c.updateConditions(safe().copy(foreground = false))
        assertNull(c.reserveForegroundExport(owner, safe()))
        c.updateConditions(safe())
        val request = requireNotNull(c.reserveForegroundExport(owner, safe()))
        c.updateConditions(AutoDevelopConditions(foreground = false))
        assertNull(c.tryStartObserved(safe()))
        val attempt = requireNotNull(c.tryStartForegroundExport(request, owner, safe().copy(foreground = false)))
        c.updateExportConditions(attempt, safe().copy(foreground = false))
        assertFalse(c.shouldStop(attempt))
        c.completeAfterResourcesClosed(attempt)
        assertNull(c.reserveForegroundExport(owner, safe()))
    }
    @Test fun normalWorkStillStopsOnPauseAndCannotBorrowExportUpdates() {
        val c = ready()
        val normal = requireNotNull(c.tryStartObserved(safe()))
        c.updateConditions(safe().copy(foreground = false))
        c.updateExportConditions(normal, safe())
        assertTrue(c.shouldStop(normal))
    }
    @Test fun captureBeforeAdmissionInvalidatesRequestAndDoesNotAutorestartIt() {
        val c = ready()
        val request = requireNotNull(c.reserveForegroundExport(owner, safe()))
        val capture = c.reserveCapture()
        c.releaseCapture(capture)
        assertNull(c.tryStartForegroundExport(request, owner, safe()))
        assertNull(c.reserveForegroundExport(owner, safe()))
        c.abandonForegroundExport(request)
        assertNotNull(c.reserveForegroundExport(owner, safe()))
    }
    @Test fun captureAfterAdmissionLatchesStopUntilActualClosure() {
        val c = ready()
        val first = start(c)
        val capture = c.reserveCapture()
        assertTrue(c.shouldStop(first))
        c.releaseCapture(capture)
        c.updateExportConditions(first, safe())
        assertTrue(c.shouldStop(first))
        assertNull(c.reserveForegroundExport(owner, safe()))
        c.completeAfterResourcesClosed(first)
        assertNotNull(c.reserveForegroundExport(owner, safe()))
    }
    @Test fun everyNonForegroundSafetyGateStillBlocksAndLatchesStop() {
        val unsafe = listOf(safe().copy(cameraSessionInUse = true), safe().copy(recordingOrFinalizing = true),
            safe().copy(rebindInProgress = true), safe().copy(thermalAllowed = null),
            safe().copy(storageAllowed = false), safe().copy(rendererAvailable = false),
            AutoDevelopConditions())
        unsafe.forEach { conditions ->
            val c = ready()
            val request = requireNotNull(c.reserveForegroundExport(owner, safe()))
            assertNull(c.tryStartForegroundExport(request, owner, conditions))
            val attempt = requireNotNull(c.tryStartForegroundExport(request, owner, safe()))
            c.updateExportConditions(attempt, conditions)
            c.updateExportConditions(attempt, safe())
            assertTrue(c.shouldStop(attempt))
        }
    }
    @Test fun requestIsOwnerBoundSingleUseAndStaleObserverCannotStopNextAttempt() {
        val c = ready()
        val request = requireNotNull(c.reserveForegroundExport(owner, safe()))
        assertNull(c.tryStartForegroundExport(request, Any(), safe()))
        val old = requireNotNull(c.tryStartForegroundExport(request, owner, safe()))
        c.completeAfterResourcesClosed(old)
        assertNull(c.tryStartForegroundExport(request, owner, safe()))
        val next = start(c)
        c.updateExportConditions(old, AutoDevelopConditions())
        assertFalse(c.completeAfterResourcesClosed(old))
        assertFalse(c.shouldStop(next))
    }
    @Test fun abandonedAndCancelledRequestsNeverAcquireSlot() {
        val c = ready()
        val request = requireNotNull(c.reserveForegroundExport(owner, safe()))
        c.stopForegroundExport(request)
        assertNull(c.tryStartForegroundExport(request, owner, safe()))
        c.abandonForegroundExport(request)
        val next = requireNotNull(c.reserveForegroundExport(owner, safe()))
        c.abandonForegroundExport(request)
        assertNull(c.tryStart())
        assertNotNull(c.tryStartForegroundExport(next, owner, safe()))
    }
}
