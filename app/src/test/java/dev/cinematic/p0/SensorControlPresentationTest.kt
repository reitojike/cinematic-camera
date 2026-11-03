package dev.cinematic.p0

import org.junit.Assert.*
import org.junit.Test

class SensorControlPresentationTest {
    private fun snapshot(wb: Int? = null, focus: Float? = null) =
        ControlSnapshot(overrides = SensorOverrides(whiteBalance = wb, focusDistance = focus))

    @Test fun focusUiIntervalsKeepExactLensUnitAnchorAndDoNotWriteRoundedDisplayBack() {
        val exact = 4.200001f
        val options = focusControlOptions(10f, exact)
        assertEquals(102, options.size)
        assertEquals(0f, options.first(), 0f)
        assertEquals(10f, options.last(), 0f)
        assertTrue(options.zipWithNext().all { (a, b) -> a < b })
        assertEquals(exact, options[focusControlIndex(options, exact)], 0f)
        assertEquals(exact, options[focusControlIndex(options, exact)].toString().toFloat(), 0f)
        assertEquals("4.2", focusDistanceText(exact))
        val next = focusControlIndex(options, exact) + 1
        assertTrue(options[next] > exact)
        assertEquals(options[next], options[focusControlIndex(options, options[next])], 0f)
        assertEquals(101, focusControlOptions(10f, 5f).size)
    }

    @Test fun focusIntervalsRespectDeviceMaximumAndMissingOrInvalidValuesStayUnknown() {
        for (maximum in listOf(null, 0f, -1f, Float.NaN, Float.POSITIVE_INFINITY))
            assertTrue(focusControlOptions(maximum, 1f).isEmpty())
        val options = focusControlOptions(0.00001f, 0.0000001f)
        assertTrue(options.all { it.isFinite() && it in 0f..0.00001f })
        assertEquals(0f, options.first(), 0f)
        assertEquals(0.00001f, options.last(), 0f)
        assertEquals(-1, focusControlIndex(options, null))
        assertEquals(-1, focusControlIndex(options, Float.NaN))
        assertEquals(-1, focusControlIndex(options, -1f))
        assertEquals(-1, focusControlIndex(options, 1f))
        assertEquals(-1, focusControlIndex(emptyList(), 0f))
        assertEquals(101, focusControlOptions(10f, Float.POSITIVE_INFINITY).size)
    }

    @Test fun evIntervalsUseOnlyReportedIndicesAndStepWithoutInventingAnUnknownCurrentIndex() {
        val options = exposureCompensationOptions(-2..1, 1.0 / 3.0)
        assertEquals(listOf(-2, -1, 0, 1), options.map { it.index })
        assertEquals(listOf("-0.7", "-0.3", "+0.0", "+0.3"), options.map { it.label })
        val unknown: Int? = null
        assertEquals(-1, options.indexOfFirst { it.index == unknown })
        assertTrue(exposureCompensationOptions(null, 1.0).isEmpty())
        assertTrue(exposureCompensationOptions(2..1, 1.0).isEmpty())
        for (step in listOf(null, 0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY))
            assertTrue(exposureCompensationOptions(-1..1, step).isEmpty())
    }

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

    @Test fun evZeroToOneAndFurtherPendingIntentsNeverWaitForOrRollBackToAnOlderAck() {
        val accepted = snapshot().copy(evIndex = 0)
        val receipt = ControlReceipt(epoch = 3, phase = ControlPhase.PENDING, accepted = accepted, requested = accepted)
        var intent = accepted.copy(evIndex = 1)
        assertEquals("+0.3", liveEvPresentation(0, 1.0 / 3.0, intent, receipt, true).value)
        intent = intent.copy(evIndex = 3)
        val olderAck = receipt.copy(phase = ControlPhase.ACCEPTED, accepted = accepted.copy(evIndex = 1))
        val shown = liveEvPresentation(1, 1.0 / 3.0, intent, olderAck, true)
        assertEquals("+1.0", shown.value); assertEquals(SensorControlTone.PENDING, shown.tone)
        assertEquals("+1.0", liveEvPresentation(3, 1.0 / 3.0, null,
            olderAck.copy(accepted = intent), false).value)
        val restoringZero = accepted
        val activeOne = receipt.copy(requested = accepted.copy(evIndex = 1))
        assertEquals(SensorControlTone.PENDING, liveEvPresentation(0, 1.0, restoringZero, activeOne, true).tone)
        assertEquals(SensorControlTone.UNKNOWN, liveEvPresentation(0, 1.0, restoringZero,
            activeOne.copy(phase = ControlPhase.UNKNOWN), false).tone)
    }

    @Test fun whiteBalancePendingSelectionsAndDefaultRemovalFollowLatestIntentNotAcceptedHistory() {
        val receipt = ControlReceipt(phase = ControlPhase.PENDING, accepted = snapshot(wb = 1), requested = snapshot(wb = 5))
        for (mode in listOf(5, 6, 7, 8, null)) {
            val latest = snapshot(wb = mode)
            val shown = liveWhiteBalancePresentation(1, latest, receipt, true)
            assertEquals(mode, shown.selectedMode); assertTrue(shown.hasSelection)
            assertEquals("AUTO", shown.acceptedLabel)
            assertEquals(SensorControlTone.PENDING, shown.tone)
            assertEquals(mode, liveWhiteBalancePresentation(5, latest,
                receipt.copy(phase = ControlPhase.ACCEPTED, accepted = snapshot(wb = 5)), true).selectedMode)
        }
    }

    @Test fun focusCanReverseWhilePendingAndDistinguishesAfInfinityUnknownAndActual() {
        val receipt = ControlReceipt(phase = ControlPhase.PENDING, accepted = snapshot(focus = 2f), requested = snapshot(focus = 3f))
        for (value in listOf(3f, 4f, 3f, 2f, 0f, null)) {
            val shown = liveFocusPresentation(2f, snapshot(focus = value), receipt, true)
            assertEquals(if (value == null) "AF" else "MF", shown.modeTag)
            assertEquals(if (value == null) "AF" else if (value == 0f) "∞" else focusDistanceText(value), shown.value)
            assertEquals(SensorControlTone.PENDING, shown.tone)
            assertEquals(2f, receipt.accepted!!.overrides.focusDistance)
        }
        val unknown = liveFocusPresentation(2f, snapshot(focus = 4f), receipt.copy(phase = ControlPhase.UNKNOWN), false)
        assertEquals("4", unknown.value); assertEquals("不明", unknown.stateTag)
        assertEquals(SensorControlTone.UNKNOWN, unknown.tone)
        val restoring = liveFocusPresentation(2f, snapshot(focus = 2f), receipt.copy(phase = ControlPhase.UNKNOWN), false)
        assertEquals("2", restoring.value); assertEquals(SensorControlTone.UNKNOWN, restoring.tone)
        assertEquals("∞", liveFocusPresentation(0f, null,
            receipt.copy(phase = ControlPhase.ACCEPTED, accepted = snapshot(focus = 0f), requested = snapshot(focus = 0f)), false).value)
    }
}
