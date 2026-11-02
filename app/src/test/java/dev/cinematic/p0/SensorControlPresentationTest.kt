package dev.cinematic.p0

import org.junit.Assert.*
import org.junit.Test

class SensorControlPresentationTest {
    private fun snapshot(wb: Int? = null, focus: Float? = null) =
        ControlSnapshot(overrides = SensorOverrides(whiteBalance = wb, focusDistance = focus))

    @Test fun focusFormattingIsFiniteAndNeverTurnsPositiveLensUnitsIntoInfinity() {
        assertEquals("4.2", focusDistanceText(4.200001f))
        assertEquals("0", focusDistanceText(-0f))
        assertEquals("0.00001", focusDistanceText(0.00001f))
        for (value in listOf(null, Float.NaN, Float.POSITIVE_INFINITY, -1f)) assertNull(focusDistanceText(value))
    }

    @Test fun focusKeepsValueUnitsAndModeSeparateAndDoesNotApplyDraft() {
        val receipt = ControlReceipt(phase = ControlPhase.ACCEPTED, accepted = snapshot(focus = 2f))
        val draft = focusPresentation(2f, "4.2000", receipt)
        assertEquals("4.2", draft.value); assertEquals("LU", draft.unit); assertEquals("MF", draft.modeTag)
        assertEquals("編集中", draft.stateTag)
        assertEquals(2f, receipt.accepted!!.overrides.focusDistance)
        assertEquals("AF", focusPresentation(null, null, receipt).modeTag)
        assertEquals(SensorControlTone.FAILED, focusPresentation(2f, "NaN", receipt).tone)
    }

    @Test fun pendingAutofocusRequestDoesNotRemainDisplayedAsAcceptedManualFocus() {
        val pending = ControlReceipt(phase = ControlPhase.PENDING, requested = snapshot(), accepted = snapshot(focus = 2f))
        val display = focusPresentation(2f, null, pending)
        assertEquals("AF", display.value); assertEquals("", display.unit)
        assertEquals("送信中", display.stateTag)
        assertEquals("—", focusPresentation(2f, null, pending.copy(phase = ControlPhase.UNKNOWN)).value)
    }

    @Test fun whiteBalanceOptionsExcludeOffAndUnreportedPresetsWithoutInventingKelvin() {
        val options = whiteBalanceOptions(setOf(0, 1, 5, 99))
        assertEquals(listOf(null, 1, 5), options.map { it.mode })
        assertEquals("CameraX標準", options.first().fullLabel)
        assertEquals(listOf<Int?>(null), whiteBalanceOptions(emptySet()).map { it.mode })
    }

    @Test fun whiteBalanceSelectionRequestAndAcceptanceHistoryRemainDistinctDuringSendAndFailure() {
        val receipt = ControlReceipt(phase = ControlPhase.PENDING, requested = snapshot(wb = 5), accepted = snapshot(wb = 1))
        val sending = whiteBalancePresentation(1, receipt)
        assertEquals(5, sending.selectedMode)
        assertEquals("DAYLT", sending.requestedLabel); assertEquals("AUTO", sending.acceptedLabel)
        assertEquals("送信中", sending.stateTag)
        val unknown = whiteBalancePresentation(1, receipt.copy(phase = ControlPhase.UNKNOWN))
        assertEquals("不明", unknown.stateTag); assertEquals("AUTO", unknown.acceptedLabel)
        val rejected = whiteBalancePresentation(1, receipt.copy(phase = ControlPhase.REJECTED))
        assertEquals(1, rejected.selectedMode); assertEquals("入力拒否", rejected.stateTag)
        assertEquals("AUTO", rejected.acceptedLabel)
    }

    @Test fun unboundOrMissingAcceptanceDoesNotInventAnAcceptedDefault() {
        val unbound = whiteBalancePresentation(null, ControlReceipt())
        assertFalse(unbound.hasSelection); assertEquals("—", unbound.acceptedLabel)
        assertEquals("未確認", unbound.stateTag)
        val incomplete = whiteBalancePresentation(null, ControlReceipt(phase = ControlPhase.ACCEPTED))
        assertEquals("未確認", incomplete.stateTag); assertEquals("—", incomplete.acceptedLabel)
    }

    @Test fun unrelatedExposureChangesDoNotMarkWhiteBalanceOrFocusPendingOrUnknown() {
        val before = snapshot(wb = 5, focus = 2f).copy(exposure = ManualExposure(100, 8_000_000))
        val after = before.copy(exposure = ManualExposure(3200, 468_750_000))
        for (phase in listOf(ControlPhase.PENDING, ControlPhase.UNKNOWN, ControlPhase.REJECTED)) {
            val receipt = ControlReceipt(phase = phase, requested = after, accepted = before)
            val wb = whiteBalancePresentation(5, receipt)
            assertFalse(wb.affected); assertEquals("履歴", wb.stateTag)
            assertEquals(SensorControlTone.MUTED, wb.tone)
            val focus = focusPresentation(2f, null, receipt)
            assertFalse(focus.affected); assertEquals("2", focus.value)
            assertEquals("履歴", focus.stateTag); assertEquals(SensorControlTone.MUTED, focus.tone)
            assertEquals("編集中", focusPresentation(2f, "3", receipt).stateTag)
        }
    }

    @Test fun unrelatedWhiteBalanceRequestDoesNotConsumeOrSendManualFocusDraft() {
        val receipt = ControlReceipt(phase = ControlPhase.PENDING,
            requested = snapshot(wb = 5, focus = 2f), accepted = snapshot(wb = 1, focus = 2f))
        assertTrue(whiteBalancePresentation(1, receipt).affected)
        val draft = focusPresentation(2f, "4.2", receipt)
        assertEquals("編集中", draft.stateTag); assertFalse(draft.affected)
        assertEquals(SensorControlTone.EDITING, draft.tone)
        val noHistory = receipt.copy(accepted = null)
        assertEquals("未確認", whiteBalancePresentation(1, noHistory).stateTag)
        assertEquals("—", focusPresentation(null, null, noHistory).value)
        val accepted = receipt.copy(phase = ControlPhase.ACCEPTED, accepted = receipt.requested)
        assertEquals("履歴", whiteBalancePresentation(5, accepted).stateTag)
    }
}
