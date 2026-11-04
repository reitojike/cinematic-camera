package dev.cinematic.p0

import org.junit.Assert.*
import org.junit.Test

class StillResumeIntentTest {
    private val old = ManualExposure(100, 8_000_000)
    private val sent = ManualExposure(200, 15_000_000_000)
    private val latest = ManualExposure(800, 30_000_000_000)
    private val selected = ControlSnapshot(old, 2, SensorOverrides(whiteBalance = 5, focusDistance = 2f),
        AutoLocks(whiteBalance = true))

    @Test fun backgroundDuringPendingAckRetainsLatestQueuedLogicalPairNotOldAcceptedPair() {
        val intent = StillResumeIntent()
        intent.remember("wide/STILL", latest, sent, null, selected)
        val resumed = intent.peek("wide/STILL")!!.controls
        assertEquals(latest, resumed.exposure)
        assertEquals(selected.overrides, resumed.overrides)
        assertEquals(2, resumed.evIndex)
        assertTrue(resumed.locks.isEmpty)
        assertEquals(old, selected.exposure) // Accepted history is not changed by remembering intent.
    }

    @Test fun autoToFirstManualInsideCoalescingWindowRetainsUnsentPair() {
        val intent = StillResumeIntent()
        intent.remember("wide/STILL", latest, latest, null, ControlSnapshot(evIndex = 1))
        assertEquals(latest, intent.peek("wide/STILL")!!.controls.exposure)
    }

    @Test fun dispatchedLiveTargetSurvivesWhenNoQueuedEntryRemains() {
        val intent = StillResumeIntent()
        intent.remember("wide/STILL", null, sent, null, selected)
        assertEquals(sent, intent.peek("wide/STILL")!!.controls.exposure)
    }

    @Test fun latestExplicitAutoDoesNotFallBackToPreviousManualPair() {
        val intent = StillResumeIntent()
        val auto = selected.copy(exposure = null, evIndex = 3)
        intent.remember("wide/STILL", null, null, auto, selected)
        val resumed = intent.peek("wide/STILL")!!.controls
        assertNull(resumed.exposure)
        assertEquals(auto.overrides, resumed.overrides)
        assertEquals(3, resumed.evIndex)
        assertTrue(resumed.locks.isEmpty)
    }

    @Test fun failedBindOrRuntimeAttemptRetainsLatestForSameScopeRetryUntilItsAck() {
        val intent = StillResumeIntent()
        intent.remember("wide/STILL", latest, null, null, selected)
        val firstAttempt = intent.peek("wide/STILL")!!
        assertEquals(latest, firstAttempt.controls.exposure)
        // Neither bind failure nor an unacknowledged runtime request may discard this target.
        assertSame(firstAttempt, intent.peek("wide/STILL"))
        assertEquals(old, selected.exposure)
        assertTrue(intent.accepted("wide/STILL", firstAttempt))
        assertNull(intent.peek("wide/STILL"))
        assertFalse(intent.accepted("wide/STILL", firstAttempt))
    }

    @Test fun oldAcknowledgmentCannotClearNewerIntentEvenForAnIdenticalPair() {
        val intent = StillResumeIntent()
        intent.remember("wide/STILL", latest, null, null, selected)
        val oldRequest = intent.peek("wide/STILL")!!
        intent.remember("wide/STILL", latest, null, null, selected)
        val newer = intent.peek("wide/STILL")!!
        assertTrue(newer.sequence > oldRequest.sequence)
        assertFalse(intent.accepted("wide/STILL", oldRequest))
        assertSame(newer, intent.peek("wide/STILL"))
        assertFalse(intent.accepted("wide/CINE", newer))
        assertSame(newer, intent.peek("wide/STILL"))
        assertTrue(intent.accepted("wide/STILL", newer))
    }

    @Test fun explicitNewSelectionCanSupersedePendingRestoreAndMakeOldAckHarmless() {
        val intent = StillResumeIntent()
        intent.remember("wide/STILL", latest, null, null, selected)
        val failedRestore = intent.peek("wide/STILL")!!
        intent.clear()
        assertFalse(intent.accepted("wide/STILL", failedRestore))
        assertNull(intent.peek("wide/STILL"))
    }

    @Test fun cameraAndModeMismatchDiscardIntentEvenWhenReturningToOriginalScope() {
        for (other in listOf("ultrawide/STILL", "wide/CINE")) {
            val intent = StillResumeIntent()
            intent.remember("wide/STILL", latest, null, null, selected)
            assertNull(intent.peek(other))
            assertNull(intent.peek("wide/STILL"))
        }
    }

    @Test fun explicitStopOrAutoResetCanClearPendingForegroundRestore() {
        val intent = StillResumeIntent()
        intent.remember("wide/STILL", latest, null, null, selected)
        intent.clear()
        assertNull(intent.peek("wide/STILL"))
    }

    @Test fun unconfirmedAutoSurvivesFailureAndBackgroundRetryUntilOwnAck() {
        for (phase in listOf(ControlPhase.PENDING, ControlPhase.UNKNOWN)) {
            val auto = selected.copy(exposure = null, evIndex = 3)
            val request = autoResumeTarget(ControlReceipt(phase = phase, requested = auto))
            assertEquals(auto, request)
            val intent = StillResumeIntent()
            // AUTO's operation boundary has discarded the failed manual queue/live input.
            intent.remember("wide/STILL", null, null, request, selected)
            val resumed = intent.peek("wide/STILL")!!
            assertNull(resumed.controls.exposure)
            assertEquals(3, resumed.controls.evIndex)
            assertSame(resumed, intent.peek("wide/STILL"))
            assertTrue(intent.accepted("wide/STILL", resumed))
            assertNull(intent.peek("wide/STILL"))
        }
    }

    @Test fun newerManualSelectionWinsOverAnOlderUnconfirmedAuto() {
        val auto = autoResumeTarget(ControlReceipt(phase = ControlPhase.UNKNOWN,
            requested = selected.copy(exposure = null)))
        val intent = StillResumeIntent()
        intent.remember("wide/STILL", latest, sent, auto, selected)
        assertEquals(latest, intent.peek("wide/STILL")!!.controls.exposure)
        intent.remember("wide/STILL", null, sent, auto, selected)
        assertEquals(sent, intent.peek("wide/STILL")!!.controls.exposure)
    }

    @Test fun autoResumeEligibilityExcludesHistoryAndManualOrAbsentRequests() {
        val auto = selected.copy(exposure = null)
        for (phase in ControlPhase.entries.filter { it != ControlPhase.PENDING && it != ControlPhase.UNKNOWN }) {
            assertNull(autoResumeTarget(ControlReceipt(phase = phase, requested = auto)))
        }
        assertNull(autoResumeTarget(ControlReceipt(phase = ControlPhase.UNKNOWN)))
        assertNull(autoResumeTarget(ControlReceipt(phase = ControlPhase.PENDING, requested = selected)))
    }

}
