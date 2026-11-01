package dev.cinematic.p0

import org.junit.Assert.*
import org.junit.Test

class ProbeControlsTest {
    @Test fun previewCoordinatesRejectOutsideNonFiniteAndUnreadyLayout() {
        assertEquals(0f to 1f, ProbeControls.normalizedPreviewPoint(0f, 100f, 200, 100))
        assertEquals(1f to 0f, ProbeControls.normalizedPreviewPoint(200f, 0f, 200, 100))
        assertEquals(.5f to .5f, ProbeControls.normalizedPreviewPoint(100f, 50f, 200, 100))
        listOf(-.01f to 50f, 201f to 50f, 100f to -1f, 100f to 101f,
            Float.NaN to 50f, 100f to Float.POSITIVE_INFINITY).forEach { (x, y) ->
            assertThrows(IllegalArgumentException::class.java) { ProbeControls.normalizedPreviewPoint(x, y, 200, 100) }
        }
        assertThrows(IllegalArgumentException::class.java) { ProbeControls.normalizedPreviewPoint(0f, 0f, 0, 100) }
        assertThrows(IllegalArgumentException::class.java) { ProbeControls.normalizedPreviewPoint(0f, 0f, 200, -1) }
    }

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
