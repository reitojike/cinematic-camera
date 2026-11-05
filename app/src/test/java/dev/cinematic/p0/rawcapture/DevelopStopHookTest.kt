package dev.cinematic.p0.rawcapture

import org.junit.Assert.*
import org.junit.Test

class DevelopStopHookTest {
    private val allowed = AutoDevelopConditions(true, false, false, false, true, true, true, true)

    @Test fun captureBeforeBindingStillCancelsNativeTargetAndKeepsAdmissionHeld() {
        val coordinator = AutoDevelopCoordinator()
        val slot = requireNotNull(coordinator.tryStartObserved(allowed))
        val capture = coordinator.reserveCapture()
        var cancelled = 0
        val hooks = DevelopStopHook()
        hooks.bind(slot) { cancelled++ }
        hooks.stopIfRequested(coordinator)
        assertEquals(1, cancelled)
        coordinator.releaseCapture(capture)
        assertNull(coordinator.tryStartObserved(allowed)) // Cancellation is never close acknowledgement.
        hooks.clear(slot)
        coordinator.completeAfterResourcesClosed(slot)
        assertNotNull(coordinator.tryStartObserved(allowed))
    }

    @Test fun delayedOldCompletionAndStopCannotEraseANewOwner() {
        val coordinator = AutoDevelopCoordinator()
        val old = requireNotNull(coordinator.tryStartObserved(allowed))
        val hooks = DevelopStopHook()
        hooks.bind(old) { fail("Released target must not run") }
        hooks.clear(old)
        coordinator.completeAfterResourcesClosed(old)
        val current = requireNotNull(coordinator.tryStartObserved(allowed))
        var cancelled = false
        hooks.bind(current) { cancelled = true }
        hooks.clear(old)
        coordinator.requestStop(old)
        hooks.stopIfRequested(coordinator)
        assertFalse(cancelled)
        coordinator.requestStop(current)
        hooks.stopIfRequested(coordinator)
        assertTrue(cancelled)
        assertNull(coordinator.tryStartObserved(allowed))
    }

    @Test fun callbackRunsOutsideHookLock() {
        val coordinator = AutoDevelopCoordinator()
        val slot = requireNotNull(coordinator.tryStartObserved(allowed))
        val hooks = DevelopStopHook()
        hooks.bind(slot) {
            val thread = Thread { hooks.clear(slot) }.apply { start() }
            thread.join(1000)
            assertFalse("Native cancellation must not hold the hook lock", thread.isAlive)
        }
        coordinator.requestStop(slot)
        hooks.stopIfRequested(coordinator)
    }
}
