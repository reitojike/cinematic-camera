package dev.cinematic.p0

import org.junit.Assert.*
import org.junit.Test

class ProbeControlsTest {
    @Test fun evUsesAdvertisedInclusiveRangeWithoutSilentClamp() {
        assertEquals(2, ProbeControls.evTarget(1, 1, -2..2, true, true))
        assertEquals(-2, ProbeControls.evTarget(-1, -1, -2..2, true, true))
        assertThrows(IllegalArgumentException::class.java) { ProbeControls.evTarget(2, 1, -2..2, true, true) }
        assertThrows(ArithmeticException::class.java) { ProbeControls.evTarget(Int.MAX_VALUE, 1, Int.MIN_VALUE..Int.MAX_VALUE, true, true) }
    }

    @Test fun manualAndUnsupportedEvRequestsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { ProbeControls.evTarget(0, 1, -2..2, true, false) }
        assertThrows(IllegalArgumentException::class.java) { ProbeControls.evTarget(0, 1, -2..2, false, true) }
    }

    @Test fun severeThermalStateStopsRecordingButUnknownDoesNotPretendTemperature() {
        assertFalse(ProbeControls.shouldStopRecording(-1))
        assertFalse(ProbeControls.shouldStopRecording(0))
        assertFalse(ProbeControls.shouldStopRecording(2))
        assertTrue(ProbeControls.shouldStopRecording(3))
        assertTrue(ProbeControls.shouldStopRecording(6))
        assertEquals("UNKNOWN(-1)", ProbeControls.thermalName(-1))
        assertEquals("SEVERE", ProbeControls.thermalName(3))
    }
}
