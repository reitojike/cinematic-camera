package dev.cinematic.p0

import org.junit.Assert.*
import org.junit.Test

class FocusObservationTest {
    private fun frame(number: Long = 1, time: Long = 100, focus: Float? = 2f, mode: Int? = 4,
        epoch: Int = 3, intent: CaptureSampleIntent = CaptureSampleIntent.PREVIEW) =
        CaptureSample(epoch, number, time, SensorValues(), SensorValues(focus = focus, afMode = mode), intent = intent)
    private fun ready(at: Long = 50) = AutofocusObservations(epoch = 3)
        .controlsAccepted(3, at, null, null, true)

    @Test fun onlyCurrentPreviewAndVideoAutofocusObservationsAreFresh() {
        for (mode in 1..4) for (intent in listOf(CaptureSampleIntent.PREVIEW, CaptureSampleIntent.VIDEO_RECORD)) {
            val sample = frame(mode = mode, intent = intent)
            assertEquals(sample, freshAutofocusSample(sample, 3, 2100, 10f))
        }
        val sample = frame()
        assertNull(freshAutofocusSample(sample, 3, 2101, 10f))
        assertNull(freshAutofocusSample(sample, 3, 99, 10f))
        assertNull(freshAutofocusSample(sample, 4, 100, 10f))
        for (intent in listOf(CaptureSampleIntent.STILL_CAPTURE, CaptureSampleIntent.UNKNOWN))
            assertNull(freshAutofocusSample(frame(intent = intent), 3, 100, 10f))
        for (mode in listOf(null, 0, 5, 6, -1))
            assertNull(freshAutofocusSample(frame(mode = mode), 3, 100, 10f))
    }

    @Test fun lensUnitsRequireFiniteNonnegativeValueAndKnownRange() {
        for (value in listOf(null, -1f, Float.NaN, Float.POSITIVE_INFINITY, 10.1f))
            assertNull(freshAutofocusSample(frame(focus = value), 3, 100, 10f))
        for (maximum in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY))
            assertNull(freshAutofocusSample(frame(), 3, 100, maximum))
        assertNotNull(freshAutofocusSample(frame(focus = 0f), 3, 100, 10f))
        assertNotNull(freshAutofocusSample(frame(focus = 10f), 3, 100, 10f))
        assertNotNull(freshAutofocusSample(frame(), 3, 100, null))
        assertNull(freshAutofocusSample(frame(time = -1), 3, 100, null))
    }

    @Test fun afMfAfRequiresFrameStrictlyAfterNewAcceptance() {
        var state = ready().observe(frame(), 3, 100, 10f)
        assertNotNull(state.sample)
        state = state.suspend()
        assertNull(state.sample)
        state = state.observe(frame(2, 110), 3, 110, 10f)
        assertNull(state.sample)
        state = state.controlsAccepted(3, 120, 3f, null, true)
        assertFalse(state.accepting)
        state = state.controlsAccepted(3, 130, null, null, true)
        for (time in listOf(100L, 129L, 130L)) {
            state = state.observe(frame(3, time), 3, 140, 10f)
            assertNull(state.sample)
        }
        state = state.observe(frame(4, 131), 3, 140, 10f)
        assertEquals(131L, state.sample!!.receivedAtMs)
    }

    @Test fun olderAfAckCannotReopenWhileLatestIntentIsManual() {
        val queue = LatestLiveControlQueue()
        queue.offer(3, ControlSnapshot())
        val first = queue.take()!!
        queue.offer(3, ControlSnapshot(overrides = SensorOverrides(focusDistance = 4f)))
        val state = ready().suspend().controlsAccepted(3, 100,
            first.controls.overrides.focusDistance, queue.intent!!.overrides.focusDistance, true)
        assertFalse(state.accepting)
        assertNull(state.observe(frame(2, 101), 3, 101, 10f).sample)
    }

    @Test fun missingInvalidOrUnchangedAfAckCannotRefreshOldTimestamp() {
        var state = ready().observe(frame(), 3, 100, 10f)
        for ((index, value) in listOf(null, Float.NaN, -1f, 11f).withIndex())
            state = state.observe(frame(index + 2L, 1000 + index.toLong(), focus = value), 3, 1500, 10f)
        state = state.controlsAccepted(3, 2000, null, null, true)
        assertEquals(100L, state.sample!!.receivedAtMs)
        assertNotNull(freshAutofocusSample(state.sample, 3, 2100, 10f))
        assertNull(freshAutofocusSample(state.sample, 3, 2101, 10f))
    }

    @Test fun nonAfModeClearsObservationAndOldEpochOrFrameCannotReplaceIt() {
        var state = ready().observe(frame(), 3, 100, 10f)
        assertEquals(state, state.observe(frame(2, 101, epoch = 2), 3, 101, 10f))
        assertEquals(state, state.observe(frame(1, 101), 3, 101, 10f))
        assertEquals(state, state.controlsAccepted(2, 102, 4f, 4f, false))
        state = state.observe(frame(2, 110, mode = 0), 3, 110, 10f)
        assertNull(state.sample)
        state = state.observe(frame(3, 111), 3, 111, 10f)
        assertNotNull(state.sample)
        assertNull(state.observe(frame(4, 112, mode = 5), 3, 112, 10f).sample)
        assertNull(AutofocusObservations(epoch = 4).observe(frame(5, 120), 4, 120, 10f).sample)
    }

    @Test fun unsupportedErrorAndBackgroundSuspensionCannotReopenWithoutAcceptance() {
        val active = ready().observe(frame(), 3, 100, 10f)
        for (state in listOf(active.suspend(), active.controlsAccepted(3, 110, null, null, false))) {
            assertNull(state.sample)
            assertNull(state.observe(frame(2, 120), 3, 120, 10f).sample)
        }
    }

    @Test fun mailboxBoundsBurstAndPreservesValidTimestampThroughMissingFrames() {
        val mailbox = AutofocusObservationMailbox(10f)
        assertTrue(mailbox.offer(frame()))
        for (number in 2L..1000L) assertFalse(mailbox.offer(frame(number, number + 100)))
        assertFalse(mailbox.offer(frame(1001, 1200, focus = null)))
        assertFalse(mailbox.offer(frame(1002, 1300, focus = 11f)))
        val sample = mailbox.drain()!!
        assertEquals(1000L, sample.frame)
        assertEquals(1100L, sample.receivedAtMs)
        assertNull(mailbox.drain())
        assertFalse(mailbox.offer(frame(1000, 1400)))
        assertTrue(mailbox.offer(frame(1003, 1500)))
        assertFalse(mailbox.offer(frame(1004, 1600, mode = 0)))
        assertEquals(0, mailbox.drain()!!.actual.afMode)
    }

    @Test fun mailboxExcludesCaptureAndOldSessions() {
        val mailbox = AutofocusObservationMailbox(10f)
        assertFalse(mailbox.offer(frame(intent = CaptureSampleIntent.STILL_CAPTURE)))
        assertFalse(mailbox.offer(frame(time = -1)))
        assertTrue(mailbox.offer(frame()))
        assertFalse(mailbox.offer(frame(2, 110, epoch = 2)))
        assertFalse(mailbox.offer(frame(1, 120, epoch = 4, intent = CaptureSampleIntent.VIDEO_RECORD)))
        assertEquals(4, mailbox.drain()!!.epoch)
    }
}
