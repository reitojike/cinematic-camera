package dev.cinematic.p0.saveprogress

import org.junit.Assert.*
import org.junit.Test

class SaveProgressTest {
    private var now = 0L
    private val store = SaveProgressStore { now }
    @Test fun barIsMonotonicAndAtMostTenHzIncludingFirstPhaseFrame() {
        val sink = store.begin()
        sink.phase(SaveProgressPhase.DEVELOPING)
        sink.developed(.1f)
        assertEquals(0f, store.state.value.developedFraction)
        now = 99_999_999L; sink.developed(.3f)
        assertEquals(0f, store.state.value.developedFraction)
        now++; sink.developed(.2f)
        assertEquals(.3f, store.state.value.developedFraction)
        now += 99_999_999L; sink.developed(.5f)
        assertEquals(.3f, store.state.value.developedFraction)
        now++; sink.developed(.4f)
        assertEquals(.5f, store.state.value.developedFraction)
    }
    @Test fun shortPhasesBypassBarThrottleAndLateBandsNeverRestoreDevelopment() {
        val sink = store.begin()
        sink.phase(SaveProgressPhase.DEVELOPING)
        now = 1L; sink.developed(.8f)
        sink.phase(SaveProgressPhase.ENCODING)
        assertEquals(SaveProgress(SaveProgressPhase.ENCODING), store.state.value)
        now = 2L; sink.phase(SaveProgressPhase.SAVING)
        assertEquals(SaveProgress(SaveProgressPhase.SAVING), store.state.value)
        now += SaveProgressStore.BAR_INTERVAL_NANOS
        sink.developed(1f); sink.phase(SaveProgressPhase.DEVELOPING)
        assertEquals(SaveProgress(SaveProgressPhase.SAVING), store.state.value)
    }
    @Test fun finishImmediatelyDiscardsPendingBarsAndRejectsOldRequestAfterRetry() {
        val old = store.begin(); old.phase(SaveProgressPhase.DEVELOPING)
        old.developed(.9f); store.finish()
        assertFalse(store.state.value.running)
        assertNull(store.state.value.developedFraction)
        now += SaveProgressStore.BAR_INTERVAL_NANOS; old.developed(1f)
        assertFalse(store.state.value.running)
        val next = store.begin()
        old.phase(SaveProgressPhase.SAVING); old.developed(1f)
        assertEquals(SaveProgress(), store.state.value)
        next.phase(SaveProgressPhase.DEVELOPING)
        now += SaveProgressStore.BAR_INTERVAL_NANOS; next.developed(.2f)
        assertEquals(.2f, store.state.value.developedFraction)
    }
    @Test fun finalBandCannotClaimPublishedAndInvalidTelemetryIsIgnored() {
        val sink = store.begin(); sink.phase(SaveProgressPhase.DEVELOPING)
        now += SaveProgressStore.BAR_INTERVAL_NANOS; sink.developed(Float.NaN)
        sink.developed(Float.POSITIVE_INFINITY); sink.developed(-1f)
        assertEquals(0f, store.state.value.developedFraction)
        sink.developed(1f)
        assertTrue(requireNotNull(store.state.value.developedFraction) < 1f)
        store.finish()
        assertNull(store.state.value.developedFraction)
    }
    @Test fun brokenObservationCannotThrowIntoSave() {
        bestEffort { throw IllegalStateException("synthetic observer failure") }
    }
}
