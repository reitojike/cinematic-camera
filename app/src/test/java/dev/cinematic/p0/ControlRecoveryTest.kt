package dev.cinematic.p0

import org.junit.Assert.*
import org.junit.Test

class ControlRecoveryTest {
    private val failed = ControlRecoveryState(
        epoch = 4, bound = true, foreground = true, closed = false, busy = false,
        recordingBusy = false, captureActive = false, ownsCapture = false,
        controlPhase = ControlPhase.UNKNOWN, stillPhase = null,
    )

    @Test fun apiTimeoutAndPreviewObservationTimeoutOfferExplicitReconnection() {
        assertTrue(failed.canReconnect(4))
        assertTrue(failed.copy(controlPhase = ControlPhase.ACCEPTED,
            stillPhase = StillExposurePhase.UNKNOWN).canReconnect(4))
        assertFalse(failed.copy(controlPhase = ControlPhase.ACCEPTED,
            stillPhase = StillExposurePhase.READY).canReconnect(4))
        assertFalse(failed.copy(controlPhase = ControlPhase.PENDING,
            stillPhase = StillExposurePhase.PREVIEW_APPLYING).canReconnect(4))
        assertFalse(failed.copy(controlPhase = ControlPhase.UNBOUND).canReconnect(4))
    }

    @Test fun actionRenderedBeforeLifecycleOrSessionChangeCannotReconnect() {
        assertFalse(failed.copy(epoch = 5).canReconnect(4))
        assertFalse(failed.copy(foreground = false).canReconnect(4))
        assertFalse(failed.copy(closed = true).canReconnect(4))
        assertFalse(failed.copy(bound = false).canReconnect(4))
    }

    @Test fun pendingOperationsAndCaptureOwnershipBlockReconnectionIndependently() {
        assertFalse(failed.copy(busy = true).canReconnect(4))
        assertFalse(failed.copy(recordingBusy = true).canReconnect(4))
        assertFalse(failed.copy(captureActive = true).canReconnect(4))
        // Even after files finish, capture ownership lasts until preview restoration is confirmed.
        assertFalse(failed.copy(ownsCapture = true,
            stillPhase = StillExposurePhase.UNKNOWN).canReconnect(4))
    }

    @Test fun reconnectCannotReviveTimedOutOperationOrAcceptItsLateCompletion() {
        val operation = ControlOperation()
        val expired = operation.begin(4, 0, 3000)
        assertTrue(operation.expire(expired, 3000))
        assertTrue(failed.canReconnect(4))
        operation.invalidate()
        val reconnecting = failed.copy(epoch = 5, controlPhase = ControlPhase.PENDING, busy = true)
        val replacement = operation.begin(5, 3001, 3000)
        operation.finish(expired)
        assertFalse(operation.current(expired))
        assertTrue(operation.current(replacement))
        assertFalse(reconnecting.canReconnect(4))
        assertFalse(reconnecting.canReconnect(5))
    }
}
