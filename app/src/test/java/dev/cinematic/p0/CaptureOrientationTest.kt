package dev.cinematic.p0

import org.junit.Assert.*
import org.junit.Test

class CaptureOrientationTest {
    @Test fun sensor90ClockwiseTopEdgeRightUiMinus90() {
        val value = CaptureOrientation().observe(90)
        assertEquals(-90f, value.uiAngle, 0f); assertEquals(3, value.surfaceRotation)
    }
    @Test fun sensor270CounterClockwiseTopEdgeLeftUiPlus90() {
        val value = CaptureOrientation().observe(270)
        assertEquals(90f, value.uiAngle, 0f); assertEquals(1, value.surfaceRotation)
    }
    @Test fun hysteresisRejectsBoundaryJitterAndUnknown() {
        val initial = CaptureOrientation()
        assertEquals(initial, initial.observe(-1))
        assertEquals(initial, initial.observe(57))
        val right = initial.observe(58)
        assertEquals(90, right.sensorQuarter)
        assertEquals(right, right.observe(34))
        assertEquals(0, right.observe(32).sensorQuarter)
        assertEquals(initial, initial.observe(360))
    }
    @Test fun animationAlwaysTakesShortestPathAcrossWrap() {
        var value = CaptureOrientation()
        for (degrees in listOf(270, 180, 90, 0, 90, 180, 270, 0)) {
            val next = value.observe(degrees)
            assertTrue(kotlin.math.abs(next.uiAngle - value.uiAngle) <= 180)
            value = next
        }
        assertEquals(0, value.surfaceRotation)
    }
    @Test fun outputFreezesThroughPhotoAndWholeRecordingGateThenUsesLatest() {
        val starting = OutputOrientation().observe(1)
        val frozen = starting.copy(applied = starting.target(false, false)).observe(3)
        assertEquals(1, frozen.applied)
        assertNull(frozen.target(true, false))
        for (stage in listOf(RecordingStage.STARTING, RecordingStage.RECORDING, RecordingStage.STOPPING, RecordingStage.FINALIZING)) {
            assertNull(frozen.target(false, stage != RecordingStage.IDLE))
        }
        assertEquals(3, frozen.target(false, false))
        assertEquals(3, frozen.copy(applied = frozen.target(false, false)).applied)
    }
}
