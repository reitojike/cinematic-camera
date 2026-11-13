package dev.cinematic.p0.rawcapture

import dev.cinematic.p0.gpu.preview.GpuInputAdmissionRefused
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** #350: only GPU input and an explicit foreground export may overlap. */
class GpuPreviewConcurrentAdmissionTest {
    private val safe = AutoDevelopConditions(true, false, false, false, true, true, true, true, true)
    private fun ready() = AutoDevelopCoordinator().apply { updateConditions(safe) }
    private fun gpu(c: AutoDevelopCoordinator) = requireNotNull(c.tryStartGpuDiagnosticPreviewObserved(safe).attempt)
    private fun export(c: AutoDevelopCoordinator): AutoDevelopCoordinator.Attempt {
        val owner = Any()
        val request = requireNotNull(c.reserveForegroundExport(owner, safe))
        return requireNotNull(c.tryStartForegroundExport(request, owner, safe))
    }

    @Test fun exportAndGpuInputCanStartInEitherOrderButNeitherAdmitsAnotherOwner() {
        for (gpuFirst in listOf(false, true)) {
            val c = ready()
            val first = if (gpuFirst) gpu(c) else export(c)
            val second = if (gpuFirst) export(c) else gpu(c)
            assertNotSame(first, second)
            assertFalse(c.shouldStop(first))
            assertFalse(c.shouldStop(second))
            assertNull(c.tryStartGpuDiagnosticPreviewObserved(safe).attempt)
            assertNull(c.reserveForegroundExport(Any(), safe))
            assertNull(c.tryStart())
            assertNull(c.tryStartObserved(safe))
            assertNull(c.tryStartPreviewObserved(safe))
        }
    }

    @Test fun cpuAndAutoWorkRetainTheirMutualExclusionInBothDirections() {
        val starts: List<(AutoDevelopCoordinator) -> AutoDevelopCoordinator.Attempt?> = listOf(
            { it.tryStart() }, { it.tryStartObserved(safe) }, { it.tryStartPreviewObserved(safe) })
        for (start in starts) {
            val cpuFirst = ready()
            val cpu = requireNotNull(start(cpuFirst))
            assertNull(cpuFirst.tryStartGpuDiagnosticPreviewObserved(safe).attempt)
            assertNull(cpuFirst.reserveForegroundExport(Any(), safe))
            cpuFirst.requestStop(cpu)
            assertNull(cpuFirst.tryStartGpuDiagnosticPreviewObserved(safe).attempt)
            assertTrue(cpuFirst.completeAfterResourcesClosed(cpu))
            assertNotNull(cpuFirst.tryStartGpuDiagnosticPreviewObserved(safe).attempt)

            val gpuFirst = ready()
            val preview = gpu(gpuFirst)
            assertNull(start(gpuFirst))
            gpuFirst.requestStop(preview)
            assertNull(start(gpuFirst))
            assertTrue(gpuFirst.completeAfterResourcesClosed(preview))
            assertNotNull(start(gpuFirst))
        }
    }

    @Test fun stopAndCompletionAreIdentityBoundAndCannotReleaseTheOtherOwner() {
        val c = ready()
        val save = export(c)
        val old = gpu(c)
        c.requestStop(old)
        assertTrue(c.shouldStop(old))
        assertFalse(c.shouldStop(save))
        assertTrue(c.completeAfterResourcesClosed(old))
        assertFalse(c.shouldStop(save))
        val current = gpu(c)
        c.requestStop(old)
        assertFalse(c.completeAfterResourcesClosed(old))
        assertFalse(c.shouldStop(current))
        c.requestStop(save)
        assertFalse(c.shouldStop(current))
        assertTrue(c.shouldStop(save))
        assertTrue(c.completeAfterResourcesClosed(save))
        val nextSave = export(c)
        c.requestStop(save)
        c.updateExportConditions(save, AutoDevelopConditions())
        assertFalse(c.completeAfterResourcesClosed(save))
        assertFalse(c.shouldStop(nextSave))
        assertFalse(c.shouldStop(current))
        val foreign = gpu(ready())
        c.requestStop(foreign)
        assertFalse(c.completeAfterResourcesClosed(foreign))
        assertTrue(c.shouldStop(foreign))
        assertFalse(c.shouldStop(current))
        assertFalse(c.shouldStop(nextSave))
    }

    @Test fun everyPreviewSafetyUpdateStopsGpuWithoutBorrowingServiceExportAuthority() {
        val unsafe = listOf(safe.copy(foreground = false), safe.copy(foreground = null),
            safe.copy(cameraSessionInUse = true), safe.copy(cameraSessionInUse = null),
            safe.copy(recordingOrFinalizing = true), safe.copy(rebindInProgress = true),
            safe.copy(thermalAllowed = false), safe.copy(thermalAllowed = null),
            safe.copy(storageAllowed = false), AutoDevelopConditions())
        for (observations in unsafe) {
            val c = ready()
            val save = export(c)
            val preview = gpu(c)
            c.updateConditions(observations)
            assertTrue(c.shouldStop(preview))
            assertFalse("Activity facts cannot replace the service observer", c.shouldStop(save))
            c.updateConditions(safe)
            assertTrue(c.shouldStop(preview))
            assertNull(c.tryStartGpuDiagnosticPreviewObserved(safe).attempt)
            assertTrue(c.completeAfterResourcesClosed(preview))
            assertNotNull(c.tryStartGpuDiagnosticPreviewObserved(safe).attempt)
            assertFalse(c.shouldStop(save))
        }
    }

    @Test fun exportResourceStopDoesNotCancelGpuAndCannotApplyToAGpuToken() {
        val c = ready()
        val preview = gpu(c)
        val save = export(c)
        c.updateExportConditions(preview, AutoDevelopConditions())
        assertFalse(c.shouldStop(preview))
        assertFalse(c.shouldStop(save))
        c.updateExportConditions(save, safe.copy(thermalAllowed = false))
        assertTrue(c.shouldStop(save))
        assertFalse(c.shouldStop(preview))
        // Full-export memory and job-local renderer state do not replace preview memory/capability.
        c.updateConditions(safe.copy(memoryBudgetConfirmed = false, rendererAvailable = false))
        assertFalse(c.shouldStop(preview))
    }

    @Test fun captureStopsBothAndNeitherCanResumeBeforeItsOwnPositiveClosure() {
        val c = ready()
        val preview = gpu(c)
        val save = export(c)
        val capture = c.reserveCapture()
        assertTrue(c.shouldStop(preview))
        assertTrue(c.shouldStop(save))
        assertTrue(c.releaseCapture(capture))
        c.updateConditions(safe)
        c.updateExportConditions(save, safe)
        assertTrue(c.shouldStop(preview))
        assertTrue(c.shouldStop(save))
        assertNull(c.tryStartGpuDiagnosticPreviewObserved(safe).attempt)
        assertNull(c.reserveForegroundExport(Any(), safe))
        assertTrue(c.completeAfterResourcesClosed(preview))
        assertFalse(c.completeAfterResourcesClosed(preview))
        assertTrue(c.shouldStop(save))
        assertNull(c.reserveForegroundExport(Any(), safe))
        assertTrue(c.completeAfterResourcesClosed(save))
        assertNotNull(c.tryStartPreviewObserved(safe))
    }

    @Test fun globalStopAlsoLatchesGpuAndPendingExportWithoutReleasingEither() {
        val c = ready()
        val preview = gpu(c)
        val owner = Any()
        val request = requireNotNull(c.reserveForegroundExport(owner, safe))
        c.requestStop()
        c.updateConditions(safe)
        assertTrue(c.shouldStop(preview))
        assertNull(c.tryStartForegroundExport(request, owner, safe))
        assertNull(c.tryStartGpuDiagnosticPreviewObserved(safe).attempt)
        assertTrue(c.completeAfterResourcesClosed(preview))
        assertNull(c.tryStartPreviewObserved(safe))
        c.abandonForegroundExport(request)
        assertNotNull(c.tryStartPreviewObserved(safe))
    }

    @Test fun cameraRetryClassificationIgnoresNonConflictingExportButNotAnotherPreview() {
        for (pending in listOf(false, true)) {
            val c = ready()
            if (pending) requireNotNull(c.reserveForegroundExport(Any(), safe)) else export(c)
            val refused = c.tryStartGpuDiagnosticPreviewObserved(safe.copy(cameraSessionInUse = true))
            assertNull(refused.attempt)
            assertFalse(refused.snapshot.hasConflictingWork)
            assertTrue(GpuInputAdmissionRefused(refused.snapshot).canWaitForCamera)
            gpu(c)
            val conflict = c.tryStartGpuDiagnosticPreviewObserved(safe.copy(cameraSessionInUse = true))
            assertTrue(conflict.snapshot.hasConflictingWork)
            assertFalse(GpuInputAdmissionRefused(conflict.snapshot).canWaitForCamera)
        }
    }

    @Test fun captureRaceEitherRefusesGpuOrLatchesItsStopAlongsideExport() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(50) {
                val c = ready()
                val save = export(c)
                val go = CountDownLatch(1)
                val start = executor.submit(Callable { go.await(); c.tryStartGpuDiagnosticPreviewObserved(safe).attempt })
                val reserve = executor.submit(Callable { go.await(); c.reserveCapture() })
                go.countDown()
                val preview = start.get(5, TimeUnit.SECONDS)
                val capture = reserve.get(5, TimeUnit.SECONDS)
                assertTrue(c.shouldStop(save))
                assertNull(c.tryStartGpuDiagnosticPreviewObserved(safe).attempt)
                if (preview != null) {
                    assertTrue(c.shouldStop(preview))
                    assertTrue(c.completeAfterResourcesClosed(preview))
                }
                assertTrue(c.completeAfterResourcesClosed(save))
                assertNull(c.tryStartGpuDiagnosticPreviewObserved(safe).attempt)
                assertTrue(c.releaseCapture(capture))
                assertNotNull(c.tryStartGpuDiagnosticPreviewObserved(safe).attempt)
            }
        } finally { executor.shutdownNow() }
    }
    @Test fun concurrentGpuRequestsDuringExportAdmitExactlyOneInputOwner() {
        val c = ready()
        val save = export(c)
        val executor = Executors.newFixedThreadPool(4)
        val go = CountDownLatch(1)
        try {
            val futures = List(32) { executor.submit(Callable {
                go.await(); c.tryStartGpuDiagnosticPreviewObserved(safe)
            }) }
            go.countDown()
            val results = futures.map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it.attempt != null })
            results.forEach { assertEquals(it.attempt == null, it.snapshot.hasConflictingWork) }
            assertFalse(c.shouldStop(save))
            assertFalse(c.shouldStop(requireNotNull(results.single { it.attempt != null }.attempt)))
        } finally { executor.shutdownNow() }
    }
}
