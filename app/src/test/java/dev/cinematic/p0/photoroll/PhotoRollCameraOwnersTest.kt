package dev.cinematic.p0.photoroll

import org.junit.Assert.*
import org.junit.Test

class PhotoRollCameraOwnersTest {
    private val idle = PhotoRollCameraSnapshot(false, false, false, false)

    @Test fun noCameraOwnerIsUnknown() { assertEquals(PhotoRollCameraSnapshot(), PhotoRollCameraOwners().snapshot()) }

    @Test fun observationReadsCurrentStateRatherThanLaunchState() {
        val owners = PhotoRollCameraOwners()
        var value = idle
        owners.register { value }
        assertEquals(idle, owners.snapshot())
        value = idle.copy(recordingOrFinalizing = true)
        assertEquals(true, owners.snapshot().recordingOrFinalizing)
    }

    @Test fun newActivityCannotEraseOldFinalize() {
        val owners = PhotoRollCameraOwners()
        var old = idle.copy(recordingOrFinalizing = true)
        val oldOwner = owners.register { old }
        owners.register { idle }
        assertEquals(true, owners.snapshot().recordingOrFinalizing)
        old = idle
        oldOwner.close()
        assertEquals(idle, owners.snapshot())
    }

    @Test fun failedCloseRetainsUnknownWithoutOldActivityReader() {
        val owners = PhotoRollCameraOwners()
        var calls = 0
        val old = owners.register { calls++; idle }
        old.holdUnknown()
        owners.register { idle }
        assertEquals(PhotoRollCameraSnapshot(), owners.snapshot())
        assertEquals(0, calls)
        old.close()
        assertEquals(idle, owners.snapshot())
    }

    @Test fun knownBlockerWinsOverUnknownAndDuplicateOldCloseCannotRemoveNewOwner() {
        val owners = PhotoRollCameraOwners()
        val old = owners.register { PhotoRollCameraSnapshot() }
        owners.register { idle.copy(cameraMayStart = true) }
        assertEquals(true, owners.snapshot().cameraMayStart)
        old.close(); old.close(); old.holdUnknown()
        assertEquals(true, owners.snapshot().cameraMayStart)
        assertEquals(false, owners.snapshot().rebindInProgress)
    }

    @Test fun failedReaderDoesNotMeanIdle() {
        val owners = PhotoRollCameraOwners()
        owners.register { throw IllegalStateException() }
        owners.register { idle }
        assertEquals(PhotoRollCameraSnapshot(), owners.snapshot())
    }

    @Test fun interruptedCaptureOutlivesItsUiOwnerUntilCallbacksAndWritersReallyFinish() {
        val owners = PhotoRollCameraOwners()
        val old = owners.register { idle }
        var callbacksFinished = false
        var writersClosed = false
        owners.holdCaptureUntil { callbacksFinished && writersClosed }
        old.close() // UI cancellation/recreation, not completion.
        owners.register { idle }
        assertEquals(true, owners.snapshot().captureInProgress)
        callbacksFinished = true
        assertEquals(true, owners.snapshot().captureInProgress)
        writersClosed = true
        assertEquals(idle, owners.snapshot())
        assertEquals(idle, owners.snapshot())
    }
}
