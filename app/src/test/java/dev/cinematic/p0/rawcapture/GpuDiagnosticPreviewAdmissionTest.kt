package dev.cinematic.p0.rawcapture

import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class GpuDiagnosticPreviewAdmissionTest {
    private val previewReady = permittedConditions().copy(memoryBudgetConfirmed = false, previewMemoryAvailable = true)

    @Test fun cleanAdmissionCapturesPreDecisionStateAndOwnsTheOneGpuSlot() {
        val coordinator = ready()
        val result = coordinator.tryStartGpuDiagnosticPreviewObserved(previewReady)
        val attempt = requireNotNull(result.attempt)
        assertEquals(emptySet<AutoDevelopBlocker>(), result.snapshot.freshPreviewBlockers)
        assertEquals(emptySet<AutoDevelopBlocker>(), result.snapshot.storedGlobalPreviewBlockers)
        assertTrue(result.snapshot.conditionsObserved)
        assertEquals(true, result.snapshot.freshForeground)
        assertEquals(true, result.snapshot.storedForeground)
        assertEquals(true, result.snapshot.freshRendererAvailable)
        assertFalse(result.snapshot.hasActiveSlot)
        assertNull(result.snapshot.activeStopRequested)
        assertEquals(0, result.snapshot.captureCount)
        assertFalse(result.snapshot.hasPendingExport)
        assertFalse(coordinator.shouldStop(attempt))
        assertNull(coordinator.tryStartPreviewObserved(previewReady))
        assertTrue(coordinator.completeAfterResourcesClosed(attempt))
        assertNotNull(coordinator.tryStartPreviewObserved(previewReady))
    }

    @Test fun freshUnsafeOrUnconfirmedConditionsReportTheExactPreviewBlockers() {
        for (observations in blockedConditions()) {
            val result = assertSameDecision(observations)
            assertNull(result.attempt)
            assertEquals(AutoDevelopPolicy.previewBlockers(observations), result.snapshot.freshPreviewBlockers)
            assertEquals(emptySet<AutoDevelopBlocker>(), result.snapshot.storedGlobalPreviewBlockers)
            assertEquals(observations.foreground, result.snapshot.freshForeground)
            assertEquals(observations.rendererAvailable, result.snapshot.freshRendererAvailable)
        }
    }

    @Test fun storedForegroundCanBlockAFreshForegroundObservation() {
        for (foreground in listOf(false, null)) {
            val result = assertSameDecision(stored = previewReady.copy(foreground = foreground))
            assertNull(result.attempt)
            assertTrue(result.snapshot.conditionsObserved)
            assertEquals(true, result.snapshot.freshForeground)
            assertEquals(foreground, result.snapshot.storedForeground)
            assertEquals(emptySet<AutoDevelopBlocker>(), result.snapshot.freshPreviewBlockers)
            assertEquals(setOf(AutoDevelopBlocker.FOREGROUND), result.snapshot.storedGlobalPreviewBlockers)
        }
    }

    @Test fun storedBlockersUseTheExistingRendererNormalizationAndPreviewMemoryGate() {
        for (stored in blockedConditions()) {
            val result = assertSameDecision(stored = stored)
            val expected = AutoDevelopPolicy.previewBlockers(stored.copy(rendererAvailable = true))
            assertEquals(expected, result.snapshot.storedGlobalPreviewBlockers)
            assertEquals(expected.isEmpty(), result.attempt != null)
            assertFalse(result.snapshot.storedGlobalPreviewBlockers.contains(AutoDevelopBlocker.RENDERER))
        }
        assertNotNull(assertSameDecision(stored = previewReady.copy(rendererAvailable = false)).attempt)
    }

    @Test fun unobservedDefaultGlobalStateIsRecordedButDoesNotBecomeANewGate() {
        val result = assertSameDecision(stored = null)
        assertNotNull(result.attempt)
        assertFalse(result.snapshot.conditionsObserved)
        assertNull(result.snapshot.storedForeground)
        assertEquals(AutoDevelopBlocker.entries.toSet() - AutoDevelopBlocker.RENDERER,
            result.snapshot.storedGlobalPreviewBlockers)
    }

    @Test fun activeAndStoppedButUnreleasedSlotsRemainDistinctAndBlocked() {
        for (stopped in listOf(false, true)) {
            val result = assertSameDecision(setup = { coordinator ->
                val old = requireNotNull(coordinator.tryStartPreviewObserved(previewReady))
                if (stopped) coordinator.requestStop(old)
            })
            assertNull(result.attempt)
            assertTrue(result.snapshot.hasActiveSlot)
            assertEquals(stopped, result.snapshot.activeStopRequested)
            assertTrue(result.snapshot.freshPreviewBlockers.isEmpty())
            assertTrue(result.snapshot.storedGlobalPreviewBlockers.isEmpty())
        }
        val coordinator = ready()
        val old = requireNotNull(coordinator.tryStartPreviewObserved(previewReady))
        coordinator.requestStop(old)
        assertNull(coordinator.tryStartGpuDiagnosticPreviewObserved(previewReady).attempt)
        assertTrue(coordinator.shouldStop(old))
        assertTrue(coordinator.completeAfterResourcesClosed(old))
        val next = requireNotNull(coordinator.tryStartGpuDiagnosticPreviewObserved(previewReady).attempt)
        assertFalse(coordinator.completeAfterResourcesClosed(old))
        assertFalse(coordinator.shouldStop(next))
    }

    @Test fun captureReservationsRemainCountedWithoutBeingReleased() {
        val result = assertSameDecision(setup = { it.reserveCapture(); it.reserveCapture() })
        assertNull(result.attempt)
        assertEquals(2, result.snapshot.captureCount)
        assertFalse(result.snapshot.hasActiveSlot)
        assertNull(result.snapshot.activeStopRequested)
        assertFalse(result.snapshot.hasPendingExport)
    }

    @Test fun snapshotReportsAllBlockersEvenWhenAnExistingSlotAlreadyRejectsAdmission() {
        val result = assertSameDecision(observations = previewReady.copy(rendererAvailable = false), setup = {
            assertNotNull(it.tryStartPreviewObserved(previewReady))
            it.reserveCapture()
            it.updateConditions(previewReady.copy(foreground = false))
        })
        assertNull(result.attempt)
        assertTrue(result.snapshot.hasActiveSlot)
        assertEquals(true, result.snapshot.activeStopRequested)
        assertEquals(1, result.snapshot.captureCount)
        assertEquals(setOf(AutoDevelopBlocker.RENDERER), result.snapshot.freshPreviewBlockers)
        assertEquals(setOf(AutoDevelopBlocker.FOREGROUND), result.snapshot.storedGlobalPreviewBlockers)
    }

    @Test fun pendingExportAllowsGpuPreviewButStillBlocksCpuPreview() {
        val saveReady = previewReady.copy(memoryBudgetConfirmed = true)
        val coordinator = ready(saveReady)
        val owner = Any()
        val request = requireNotNull(coordinator.reserveForegroundExport(owner, saveReady))
        assertNull(coordinator.tryStartPreviewObserved(previewReady))
        val result = coordinator.tryStartGpuDiagnosticPreviewObserved(previewReady)
        val preview = requireNotNull(result.attempt)
        assertTrue(result.snapshot.hasPendingExport)
        assertFalse(result.snapshot.hasActiveSlot)
        assertFalse(result.snapshot.hasConflictingWork)
        assertEquals(0, result.snapshot.captureCount)
        val export = requireNotNull(coordinator.tryStartForegroundExport(request, owner, saveReady))
        assertFalse(coordinator.shouldStop(preview))
        assertFalse(coordinator.shouldStop(export))
        assertTrue(coordinator.completeAfterResourcesClosed(preview))
        assertFalse(coordinator.shouldStop(export))
    }
    @Test fun snapshotIsDetachedAndBlockerSetsCannotBeMutated() {
        val coordinator = ready(previewReady.copy(foreground = false))
        val snapshot = coordinator.tryStartGpuDiagnosticPreviewObserved(
            previewReady.copy(rendererAvailable = false)).snapshot
        coordinator.updateConditions(previewReady)
        coordinator.reserveCapture()
        assertEquals(false, snapshot.storedForeground)
        assertEquals(0, snapshot.captureCount)
        assertEquals(setOf(AutoDevelopBlocker.RENDERER), snapshot.freshPreviewBlockers)
        assertEquals(setOf(AutoDevelopBlocker.FOREGROUND), snapshot.storedGlobalPreviewBlockers)
        for (blockers in listOf(snapshot.freshPreviewBlockers, snapshot.storedGlobalPreviewBlockers)) {
            assertThrows(UnsupportedOperationException::class.java) {
                (blockers as MutableSet<AutoDevelopBlocker>).clear()
            }
        }
    }

    @Test fun concurrentDiagnosticAdmissionsReturnSnapshotsFromTheirOwnDecisionLock() {
        val coordinator = ready()
        val executor = Executors.newFixedThreadPool(4)
        val go = CountDownLatch(1)
        try {
            val futures = List(32) { executor.submit(Callable {
                go.await()
                coordinator.tryStartGpuDiagnosticPreviewObserved(previewReady)
            }) }
            go.countDown()
            val results = futures.map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it.attempt != null })
            results.forEach { assertEquals(it.attempt == null, it.snapshot.hasActiveSlot) }
        } finally { executor.shutdownNow() }
    }

    private fun ready(conditions: AutoDevelopConditions = previewReady) =
        AutoDevelopCoordinator().apply { updateConditions(conditions) }

    private fun assertSameDecision(
        observations: AutoDevelopConditions = previewReady,
        stored: AutoDevelopConditions? = previewReady,
        setup: (AutoDevelopCoordinator) -> Unit = {},
    ): AutoDevelopCoordinator.GpuDiagnosticPreviewAdmission {
        fun coordinator() = AutoDevelopCoordinator().also { value ->
            stored?.let(value::updateConditions)
            setup(value)
        }
        val legacy = coordinator()
        val diagnostic = coordinator()
        val expected = legacy.tryStartPreviewObserved(observations)
        val result = diagnostic.tryStartGpuDiagnosticPreviewObserved(observations)
        assertEquals(expected != null, result.attempt != null)
        return result
    }

    private fun blockedConditions() = listOf(
        previewReady.copy(foreground = false), previewReady.copy(foreground = null),
        previewReady.copy(cameraSessionInUse = true), previewReady.copy(cameraSessionInUse = null),
        previewReady.copy(recordingOrFinalizing = true), previewReady.copy(recordingOrFinalizing = null),
        previewReady.copy(rebindInProgress = true), previewReady.copy(rebindInProgress = null),
        previewReady.copy(thermalAllowed = false), previewReady.copy(thermalAllowed = null),
        previewReady.copy(storageAllowed = false), previewReady.copy(storageAllowed = null),
        previewReady.copy(rendererAvailable = false), previewReady.copy(rendererAvailable = null),
        previewReady.copy(previewMemoryAvailable = false), previewReady.copy(previewMemoryAvailable = null),
    )
}
