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
    @Test fun autoRotateOffKeepsPortraitUiForEveryPhysicalQuarter() {
        var physical = CaptureOrientation()
        var ui = CaptureOrientation()
        for (degrees in listOf(90, 180, 270, 0, -1)) {
            physical = physical.observe(degrees)
            ui = physical.forUi(autoRotateEnabled = false, previousUi = ui)
            assertEquals(0, ui.sensorQuarter)
            assertEquals(0f, ui.uiAngle, 0f)
            assertFalse(ui.horizontal)
            if (degrees >= 0) assertEquals(degrees, physical.sensorQuarter)
        }
    }
    @Test fun autoRotateOnSupportsBothLandscapesAndUpsideDown() {
        var physical = CaptureOrientation()
        var ui = CaptureOrientation()
        for (degrees in listOf(90, 180, 270, 0)) {
            physical = physical.observe(degrees)
            ui = physical.forUi(autoRotateEnabled = true, previousUi = ui)
            assertEquals(degrees, ui.sensorQuarter)
            assertEquals(physical.surfaceRotation, ui.surfaceRotation)
            assertEquals(degrees == 90 || degrees == 270, ui.horizontal)
        }
    }
    @Test fun togglingSettingReprojectsLatestPhysicalDirectionWithoutNewSensorEvent() {
        var physical = CaptureOrientation().observe(90)
        var ui = physical.forUi(autoRotateEnabled = true, previousUi = CaptureOrientation())
        assertEquals(-90f, ui.uiAngle, 0f)
        ui = physical.forUi(autoRotateEnabled = false, previousUi = ui)
        assertEquals(0, ui.sensorQuarter)
        physical = physical.observe(270)
        ui = physical.forUi(autoRotateEnabled = false, previousUi = ui)
        assertEquals(0, ui.sensorQuarter)
        ui = physical.forUi(autoRotateEnabled = true, previousUi = ui)
        assertEquals(270, ui.sensorQuarter)
        assertEquals(90f, ui.uiAngle, 0f)
        assertEquals(ui, physical.forUi(autoRotateEnabled = true, previousUi = ui))
    }
    @Test fun togglingAfterMultipleTurnsStillUsesShortestUiAnimation() {
        var physical = CaptureOrientation()
        var ui = CaptureOrientation()
        repeat(5) {
            for (degrees in listOf(90, 180, 270, 0)) {
                physical = physical.observe(degrees)
                val enabled = physical.forUi(autoRotateEnabled = true, previousUi = ui)
                assertTrue(kotlin.math.abs(enabled.uiAngle - ui.uiAngle) <= 180f)
                val disabled = physical.forUi(autoRotateEnabled = false, previousUi = enabled)
                assertEquals(0, disabled.sensorQuarter)
                assertTrue(kotlin.math.abs(disabled.uiAngle - enabled.uiAngle) <= 180f)
                ui = disabled
            }
        }
        assertTrue(kotlin.math.abs(physical.uiAngle) > 360f)
    }
    @Test fun uiSettingTogglesDoNotChooseOrThawRecordingOutputOrientation() {
        var physical = CaptureOrientation().observe(90)
        var ui = physical.forUi(autoRotateEnabled = false, previousUi = CaptureOrientation())
        var output = OutputOrientation().observe(physical.surfaceRotation)
        output = output.copy(applied = output.target(photoActive = false, recordingActive = false))
        assertEquals(3, output.applied)
        assertEquals(0, ui.sensorQuarter)
        val stages = listOf(RecordingStage.STARTING, RecordingStage.RECORDING, RecordingStage.STOPPING, RecordingStage.FINALIZING)
        for ((index, stage) in stages.withIndex()) {
            physical = physical.observe(if (index % 2 == 0) 180 else 270)
            ui = physical.forUi(autoRotateEnabled = index % 2 == 0, previousUi = ui)
            output = output.observe(physical.surfaceRotation)
            assertNull(output.target(photoActive = false, recordingActive = stage != RecordingStage.IDLE))
            assertEquals(3, output.applied)
        }
        assertEquals(1, output.target(photoActive = false, recordingActive = false))
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
