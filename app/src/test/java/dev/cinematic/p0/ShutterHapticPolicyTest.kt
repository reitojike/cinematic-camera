package dev.cinematic.p0

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShutterHapticPolicyTest {
    private fun still(stage: PhotoStage) = ShutterPressState(CaptureMode.STILL, stage, RecordingStage.IDLE)
    private fun cine(stage: RecordingStage) = ShutterPressState(CaptureMode.CINE, PhotoStage.IDLE, stage)

    @Test fun stillOnlyAcknowledgesANewCaptureRequest() {
        listOf(PhotoStage.IDLE, PhotoStage.SAVED, PhotoStage.FAILED).forEach {
            assertTrue(shutterPressAccepted(still(it), still(PhotoStage.CAPTURING)))
        }
        listOf(PhotoStage.CAPTURING, PhotoStage.SAVING).forEach {
            assertFalse(shutterPressAccepted(still(it), still(PhotoStage.CAPTURING)))
        }
        PhotoStage.entries.filter { it != PhotoStage.CAPTURING }.forEach {
            assertFalse(shutterPressAccepted(still(PhotoStage.IDLE), still(it)))
        }
    }

    @Test fun cineAcknowledgesAcceptedStartAndStopOnly() {
        assertTrue(shutterPressAccepted(cine(RecordingStage.IDLE), cine(RecordingStage.STARTING)))
        listOf(RecordingStage.STARTING, RecordingStage.RECORDING).forEach {
            assertTrue(shutterPressAccepted(cine(it), cine(RecordingStage.STOPPING)))
        }
        RecordingStage.entries.forEach { before ->
            RecordingStage.entries.forEach { after ->
                val accepted = before == RecordingStage.IDLE && after == RecordingStage.STARTING ||
                    before in setOf(RecordingStage.STARTING, RecordingStage.RECORDING) && after == RecordingStage.STOPPING
                if (!accepted) assertFalse(shutterPressAccepted(cine(before), cine(after)))
            }
        }
    }

    @Test fun modeChangesNeverCountAsShutterAcceptance() {
        assertFalse(shutterPressAccepted(still(PhotoStage.IDLE), cine(RecordingStage.STARTING)))
        assertFalse(shutterPressAccepted(cine(RecordingStage.IDLE), still(PhotoStage.CAPTURING)))
    }
}
