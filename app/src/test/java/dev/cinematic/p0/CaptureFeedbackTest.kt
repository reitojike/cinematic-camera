package dev.cinematic.p0

import org.junit.Assert.*
import org.junit.Test

class CaptureFeedbackTest {
    private val ready = CaptureAccess(true, false, false, false, true, false, false, evSupported = true, focusSupported = true)

    @Test fun recordingFailureHasFailureToneWithoutAnotherProbeFailure() {
        assertEquals(FeedbackTone.FAILURE, captureMessage(true, null, null, null, "録音・保存に失敗", null, null)!!.tone)
    }

    @Test fun warningPriorityDoesNotInheritAnUnrelatedFailureColor() {
        val message = captureMessage(true, "熱制限", null, "古い失敗", null, null, null)!!
        assertEquals("熱制限", message.text)
        assertEquals(FeedbackTone.WARNING, message.tone)
        assertNull(captureMessage(true, null, null, null, null, null, null))
    }

    @Test fun exposureReplacementRemainsAvailableButOtherMutationsWait() {
        val pending = ready.copy(busy = true, ready = false, exposurePending = true)
        assertTrue(controlAvailability(CaptureKey.SHUTTER, pending).enabled)
        assertFalse(controlAvailability(CaptureKey.WB, pending).enabled)
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
