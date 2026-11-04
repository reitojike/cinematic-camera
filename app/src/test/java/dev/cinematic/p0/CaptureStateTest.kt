package dev.cinematic.p0

import org.junit.Assert.*
import org.junit.Test

class CaptureStateTest {
    @Test fun exposureAutoKeepsIndependentSettingsAndResetClearsThem() {
        val extras = SensorOverrides(1, 0.5f, 3)
        val previous = ControlSnapshot(ManualExposure(800, 10_000_000), 1, extras, AutoLocks(false, true))
        val auto = autoControlSnapshot(previous, 2, false)
        assertNull(auto.exposure); assertEquals(extras, auto.overrides)
        assertEquals(2, auto.evIndex); assertEquals(previous.locks, auto.locks)
        assertEquals(ControlSnapshot(evIndex = 0), autoControlSnapshot(previous, 2, true))
    }
    @Test fun oldSessionOrOutOfOrderResultCannotReplaceCurrentSample() {
        val sample = CaptureSample(3, 40, 400, SensorValues(), SensorValues())
        assertEquals(sample, newerSample(sample, sample.copy(epoch = 2, frame = 900), 3))
        assertEquals(sample, newerSample(sample, sample.copy(frame = 39), 3))
        assertEquals(41L, newerSample(sample, sample.copy(frame = 41), 3)?.frame)
    }
    @Test fun acceptanceAndMeasuredEqualityAreIndependent() {
        val snapshot = ControlSnapshot(ManualExposure(800, 10_000_000))
        val receipt = ControlReceipt(2, 1, ControlPhase.ACCEPTED, snapshot, snapshot, 100)
        val requested = SensorValues(800, 10_000_000, 0, afMode = 1, aeLock = false, wbLock = false)
        val result = CaptureSample(2, 1, 101, requested, SensorValues(400, 9_000_000))
        assertTrue(result.corresponds(receipt))
        assertEquals(400, result.actual.iso) // Never overwrite measured values with accepted ones.
        assertFalse(result.copy(epoch = 1).corresponds(receipt))
        assertFalse(result.copy(receivedAtMs = 100).corresponds(receipt))
        assertFalse(result.copy(request = requested.copy(iso = 400)).corresponds(receipt))
        assertFalse(result.corresponds(receipt.copy(phase = ControlPhase.UNKNOWN)))
        assertTrue(result.corresponds(receipt.copy(phase = ControlPhase.REJECTED)))
        assertTrue(result.copy(request = requested.copy(afMode = 0)).corresponds(receipt))
    }
    @Test fun inputRejectionNeverMakesUnknownHardwareStateTrusted() {
        val prior = ControlReceipt(2, 1, ControlPhase.UNKNOWN, accepted = ControlSnapshot(), acceptedAtMs = 10)
        val rejected = prior.failure("ISO outside range", validation = true)
        assertEquals(ControlPhase.UNKNOWN, rejected.phase)
        assertNull(rejected.acceptedAtMs)
        assertFalse(CaptureSample(2, 10, 20, SensorValues(aeMode = 1), SensorValues()).corresponds(rejected))
    }
    @Test fun exposureKeysShareDraftButClosingAndOtherGroupsDiscardIt() {
        val editor = CaptureEditor(epoch = 3, selected = CaptureKey.ISO, exposure = ExposureDraft("800", "5"))
        assertEquals(editor.exposure, editor.select(CaptureKey.SHUTTER).exposure)
        assertNull(editor.select(CaptureKey.WB).exposure)
        assertNull(editor.close().selected)
        assertEquals(CaptureKey.ISO, editor.cancel().selected)
        assertFalse(editor.cancel().dirty)
        assertEquals(editor, editor.session(3))
        assertFalse(editor.session(4).dirty)
    }
    @Test fun stillAndPermissionDenialKeepDraftButAcceptedRecordingDiscards() {
        val editor = CaptureEditor(selected = CaptureKey.ISO, exposure = ExposureDraft("800", "5"))
        assertTrue(editor.recording(RecordingStage.IDLE).dirty)
        assertFalse(editor.recording(RecordingStage.STARTING).dirty)
    }
    @Test fun failedRecorderPreparationNeverSignalsAcceptedStart() {
        val gate = RecordingGate()
        gate.begin("failed")
        assertEquals(0L, gate.startSequence)
        gate.finalize("failed"); gate.complete("failed")
        assertEquals(0L, gate.startSequence)
        gate.started("failed")
        assertEquals(0L, gate.startSequence)
        gate.begin("next"); gate.started("next"); gate.started("next")
        assertEquals(1L, gate.startSequence)
    }
    @Test fun startNotificationAfterStopStillReportsOnceWithoutUndoingStop() {
        val gate = RecordingGate()
        gate.begin("t"); gate.stop(); gate.started("t")
        assertEquals(RecordingStage.STOPPING, gate.stage)
        assertEquals(1L, gate.startSequence)
    }
    @Test fun lateCompletionAndRepeatedInputCannotAlterPendingEditor() {
        val editor = CaptureEditor(epoch = 2, selected = CaptureKey.FOCUS, focus = "1").sent(5)
        assertEquals(editor, editor.cancel()); assertEquals(editor, editor.close())
        assertEquals(editor, editor.select(CaptureKey.WB))
        assertEquals(editor, editor.complete(ControlReceipt(epoch = 1, sequence = 5, phase = ControlPhase.ACCEPTED)))
        assertEquals(editor, editor.complete(ControlReceipt(epoch = 2, sequence = 4, phase = ControlPhase.ACCEPTED)))
        assertTrue(editor.complete(ControlReceipt(epoch = 2, sequence = 5, phase = ControlPhase.UNKNOWN, detail = "unknown")).dirty)
        assertFalse(editor.complete(ControlReceipt(epoch = 2, sequence = 5, phase = ControlPhase.ACCEPTED)).dirty)
    }
    @Test fun previewTapRoutesRulerDismissalWithoutMetering() {
        for (key in listOf(CaptureKey.ISO, CaptureKey.SHUTTER, CaptureKey.EV, CaptureKey.FOCUS, CaptureKey.WB)) {
            assertEquals(PreviewTapAction.DISMISS_RULER, previewTapAction(CapturePanel.NONE, key, true))
            assertEquals(PreviewTapAction.DISMISS_RULER, previewTapAction(CapturePanel.NONE, key, false))
            val editor = CaptureEditor(epoch = 7, selected = key)
            assertNull(editor.close().selected)
            assertEquals(7, editor.close().epoch)
        }
        assertEquals(PreviewTapAction.FOCUS, previewTapAction(CapturePanel.NONE, null, true))
        assertEquals(PreviewTapAction.IGNORE, previewTapAction(CapturePanel.NONE, null, false))
        for (panel in listOf(CapturePanel.SETTINGS, CapturePanel.DIAGNOSTICS)) {
            assertEquals(PreviewTapAction.IGNORE, previewTapAction(panel, CaptureKey.ISO, true))
            assertEquals(PreviewTapAction.IGNORE, previewTapAction(panel, null, true))
        }
        val sending = CaptureEditor(epoch = 7, selected = CaptureKey.ISO, submitting = 12)
        assertEquals(sending, sending.close()) // Existing legacy submission guard is retained.
    }

    @Test fun nativePreviewAccessibilityReturnsWhenRulerCloses() {
        assertTrue(previewAccessibilityVisible(CapturePanel.NONE, null))
        for (key in listOf(CaptureKey.ISO, CaptureKey.SHUTTER, CaptureKey.EV, CaptureKey.FOCUS, CaptureKey.WB)) {
            assertFalse(previewAccessibilityVisible(CapturePanel.NONE, key))
            assertTrue(previewAccessibilityVisible(CapturePanel.NONE, CaptureEditor(selected = key).close().selected))
        }
        for (panel in listOf(CapturePanel.SETTINGS, CapturePanel.DIAGNOSTICS)) {
            assertFalse(previewAccessibilityVisible(panel, null))
            assertFalse(previewAccessibilityVisible(panel, CaptureKey.ISO))
        }
    }

}
