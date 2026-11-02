package dev.cinematic.p0

import androidx.camera.video.AudioStats
import org.junit.Assert.*
import org.junit.Test

class CaptureChromeRegressionTest {
    @Test fun retainedActiveAudioDoesNotClaimRecordingAfterFinalize() {
        val input = CaptureChromeInput(CaptureMode.CINE, audioWanted = true, audioState = AudioStats.AUDIO_STATE_ACTIVE)
        for (stage in listOf(RecordingStage.IDLE, RecordingStage.STARTING, RecordingStage.STOPPING, RecordingStage.FINALIZING)) {
            val audio = captureStatusChips(input.copy(recordingStage = stage)).first { it.id == "audio" }
            assertFalse(audio.text.contains("収録中"))
        }
        assertTrue(captureStatusChips(input.copy(recordingStage = RecordingStage.RECORDING)).first { it.id == "audio" }.text.contains("収録中"))
    }

    @Test fun preparationAndActualCaptureFailureRemainDistinctAfterRealPreviewRestoration() {
        val capture = ManualExposure(100, 1_000_000_000)
        val preview = ManualExposure(1000, 100_000_000)
        val target = StillExposureTarget(3, 1,
            SimulationPlan(capture, preview, SimulationStage.LONGER_PREVIEW, 33_333_333, 0.0),
            ControlSnapshot(capture), null)
        fun receipt(pair: ManualExposure, sequence: Long, at: Long, phase: ControlPhase = ControlPhase.ACCEPTED) =
            ControlReceipt(3, sequence, phase, ControlSnapshot(pair),
                if (phase == ControlPhase.ACCEPTED) ControlSnapshot(pair) else null,
                if (phase == ControlPhase.ACCEPTED) at else null)
        fun frame(number: Long, time: Long) = SimulationFrame(3, number, time, SimulationIntent.PREVIEW,
            SensorValues(preview.iso, preview.exposureNs, 0), SensorValues(990, 99_000_000, 0),
            100_000_000, time, null, null)
        val ready = StillExposureState(target).previewReceipt(1, receipt(preview, 1, 10)).observe(frame(1, 11))
        assertTrue(ready.canCapture)
        val aborted = ready.beginCapture().captureReceipt(receipt(capture, 2, 12, ControlPhase.UNKNOWN)).preparationAborted("API timeout")
        val shotFailed = ready.beginCapture().captureReceipt(receipt(capture, 2, 12)).singleStarted().filesFinished(false)
        for ((state, expected) in listOf(aborted to "撮影未開始", shotFailed to "撮影/保存失敗")) {
            val restored = state.previewReceipt(1, receipt(preview, 3, 20)).observe(frame(2, 21))
            assertTrue(restored.canCapture)
            assertFalse(restored.singleIssued) // Transient ownership alone cannot classify failure history.
            val input = CaptureChromeInput(CaptureMode.STILL, bound = true, captureReady = true,
                photoStage = PhotoStage.FAILED, stillPhase = restored.phase,
                stillFilesTerminal = restored.filesTerminal, stillFilesSaved = restored.filesSaved,
                stillCapturePhase = restored.captureReceipt.phase)
            assertEquals(expected, captureShutterModel(input).caption)
            assertFalse(captureStatusChips(input).any { it.id == "save" || it.id == "failure" })
        }
    }

    @Test fun stillSavingKeepsTheNewWhiteShapeAndDoesNotBecomeInteractive() {
        val input = CaptureChromeInput(CaptureMode.STILL, bound = true, photoStage = PhotoStage.SAVING,
            ownsStill = true, stillPhase = StillExposurePhase.CAPTURING)
        val shown = captureShutterModel(input)
        assertEquals(68, shown.innerDp)
        assertEquals(ShutterColor.CONTROL, shown.inner)
        assertFalse(shown.enabled)
        assertEquals("保存中", shown.caption)
    }

    @Test fun activeOperationPrecedesScrollableConfigurationAndUnknownExposureIsNotAuto() {
        val chips = captureStatusChips(CaptureChromeInput(CaptureMode.CINE, recordingStage = RecordingStage.RECORDING))
        assertEquals("operation", chips[1].id)
        assertEquals("—", chips.first { it.id == "exposure" }.text)
    }
}
