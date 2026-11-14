package dev.cinematic.p0

import org.junit.Assert.*
import org.junit.Test

class CaptureFeedbackTest {
    private val ready = CaptureAccess(true, false, false, false, true, false, false, evSupported = true, focusSupported = true)

    @Test fun backendGuardDoesNotAdoptThePreviousAcceptanceOrDiscardTheDraft() {
        val editor = CaptureEditor(epoch = 2).select(CaptureKey.FOCUS).copy(focus = "1.5")
        val history = ControlReceipt(epoch = 2, sequence = 7, phase = ControlPhase.ACCEPTED)
        val unchanged = beginEditorReceipt(editor, history, history)
        assertEquals(editor, unchanged.editor)
        assertNull(unchanged.editor.submitting)
        assertFalse(unchanged.notice!!.text.contains("受付完了"))
        assertEquals(editor, beginEditorReceipt(editor, history, history.copy(epoch = 3, sequence = 8)).editor)
    }

    @Test fun onlyANewSameSessionReceiptCanStartAndImmediatelyCompleteSubmission() {
        val editor = CaptureEditor(epoch = 2).select(CaptureKey.FOCUS).copy(focus = "1.5")
        val history = ControlReceipt(epoch = 2, sequence = 7, phase = ControlPhase.ACCEPTED)
        val result = beginEditorReceipt(editor, history, history.copy(sequence = 8))
        assertNull(result.editor.focus)
        assertNull(result.editor.submitting)
        assertNotNull(result.notice)
        assertEquals("設定を受け付けました。実測値は診断で確認できます。", result.notice!!.text)
        assertEquals(CaptureChromeTone.ACCEPTED, result.notice.tone)
    }

    @Test fun mfAcceptanceAnnouncesCompletionOnceWithoutClaimingMeasurement() {
        val editor = CaptureEditor(epoch = 2).select(CaptureKey.FOCUS).copy(focus = "1").sent(7)
        val receipt = ControlReceipt(epoch = 2, sequence = 7, phase = ControlPhase.ACCEPTED)
        val completed = completeEditorReceipt(editor, receipt)
        assertNotNull(completed.notice)
        assertEquals("設定を受け付けました。実測値は診断で確認できます。", completed.notice!!.text)
        assertEquals(CaptureChromeTone.ACCEPTED, completed.notice.tone)
        assertNull(completed.editor.submitting)
        assertNull(completed.editor.note)
        assertNull(completeEditorReceipt(completed.editor, receipt).notice)
    }

    @Test fun pendingAndStaleReceiptsCannotAnnounceMfCompletion() {
        val editor = CaptureEditor(epoch = 2).select(CaptureKey.FOCUS).sent(7)
        for (receipt in listOf(ControlReceipt(epoch = 2, sequence = 7, phase = ControlPhase.PENDING),
            ControlReceipt(epoch = 1, sequence = 7, phase = ControlPhase.ACCEPTED),
            ControlReceipt(epoch = 2, sequence = 6, phase = ControlPhase.ACCEPTED))) {
            assertNull(completeEditorReceipt(editor, receipt).notice)
            assertNotNull(completeEditorReceipt(editor, receipt).editor.submitting)
        }
    }

    @Test fun recordingFailureHasFailureToneWithoutAnotherProbeFailure() {
        assertEquals(CaptureChromeTone.FAILURE, captureMessages(recordingFailed = true).banner!!.tone)
    }

    @Test fun failureNowPrecedesWarningPerMessageContract() {
        val result = captureMessages(thermalWarning = true, captureFailed = true)
        assertEquals("capture.failure", result.banner!!.fact)
        assertEquals(CaptureChromeTone.FAILURE, result.banner.tone)
        assertEquals(ScreenMessages(), captureMessages())
    }

    @Test fun pendingLiveInputsRemainAvailableButCaptureRecordingAndUnknownDoNot() {
        val pending = ready.copy(busy = true, ready = false, exposurePending = true)
        assertTrue(controlAvailability(CaptureKey.SHUTTER, pending).enabled)
        assertFalse(controlAvailability(CaptureKey.WB, pending).enabled)
        val live = pending.copy(liveSelectable = true)
        for (key in listOf(CaptureKey.EV, CaptureKey.WB, CaptureKey.FOCUS)) {
            assertTrue(controlAvailability(key, live).enabled)
            assertFalse(controlAvailability(key, live.copy(ownsCapture = true)).enabled)
            assertFalse(controlAvailability(key, live.copy(recording = true)).enabled)
            assertFalse(controlAvailability(key, live.copy(bound = false)).enabled)
            assertFalse(controlAvailability(key, live.copy(busy = false, liveSelectable = false)).enabled)
        }
        assertFalse(controlAvailability(CaptureKey.ISO, live.copy(exposureSelectable = false)).enabled)
        assertFalse(controlAvailability(CaptureKey.ISO, pending.copy(ownsCapture = true)).enabled)
        assertFalse(controlAvailability(CaptureKey.ISO, pending.copy(recording = true)).enabled)
    }

    @Test fun unsupportedAndUnavailableControlsCarryAnExplicitReason() {
        for ((key, state) in listOf(CaptureKey.EV to ready.copy(manualExposure = true),
            CaptureKey.FOCUS to ready.copy(focusSupported = false), CaptureKey.ISO to ready.copy(bound = false),
            CaptureKey.LOOK to ready, CaptureKey.ISO to ready.copy(manualBlockReason = "MANUAL_SENSORなし"))) {
            val value = controlAvailability(key, state)
            assertFalse(value.enabled)
            assertFalse(value.reason.isNullOrBlank())
        }
    }

    @Test fun diagnosticHistoryAndUnknownMeasurementNeverBecomeTheNewRequest() {
        val old = ControlSnapshot(ManualExposure(100, 8_000_000))
        val requested = ControlSnapshot(ManualExposure(800, 1_000_000_000))
        val receipt = ControlReceipt(epoch = 2, phase = ControlPhase.PENDING, requested = requested, accepted = old)
        val table = controlDiagnosticTable(receipt, null, 2)
        val iso = table.rows.first { it.label == "ISO" }
        assertEquals("800", iso.requested)
        assertEquals("100", iso.acceptedHistory)
        assertEquals("—", iso.observed)
        assertFalse(table.corresponds)
    }

    @Test fun diagnosticRowsExcludeOldEpochReceiptsAndFrames() {
        val receipt = ControlReceipt(epoch = 1, requested = ControlSnapshot(ManualExposure(100, 8_000_000)))
        val sample = CaptureSample(1, 5, 10, SensorValues(iso = 100), SensorValues(iso = 90))
        val table = controlDiagnosticTable(receipt, sample, 2)
        assertFalse(table.sampleCurrent)
        assertFalse(table.receiptCurrent)
        assertTrue(table.rows.all { it.requested == "—" && it.acceptedHistory == "—" && it.observed == "—" })
    }

    @Test fun stillCaptureMetadataCannotMasqueradeAsTheCurrentPreviewMeasurement() {
        val sample = CaptureSample(2, 6, 11, SensorValues(iso = 200), SensorValues(iso = 190),
            intent = CaptureSampleIntent.STILL_CAPTURE)
        val receipt = ControlReceipt(epoch = 2)
        assertFalse(controlDiagnosticTable(receipt, sample, 2).sampleCurrent)
        assertTrue(controlDiagnosticTable(receipt, sample.copy(intent = CaptureSampleIntent.VIDEO_RECORD), 2).sampleCurrent)
    }
}
