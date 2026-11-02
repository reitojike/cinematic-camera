package dev.cinematic.p0

import androidx.camera.video.AudioStats
import org.junit.Assert.*
import org.junit.Test

class CaptureChromeModelTest {
    private fun ready(mode: CaptureMode) = CaptureChromeInput(mode, bound = true, captureReady = true)

    @Test fun idleShapesMatchStillAndCineWithoutMovingCaptionFootprint() {
        val still = captureShutterModel(ready(CaptureMode.STILL))
        assertEquals(58, still.innerDp)
        assertEquals(ShutterColor.CONTROL, still.inner)
        assertFalse(still.square)
        assertEquals("", still.caption)
        val cine = captureShutterModel(ready(CaptureMode.CINE))
        assertEquals(28, cine.innerDp)
        assertEquals(ShutterColor.CONTROL, cine.ring)
        assertEquals(ShutterColor.RECORDING, cine.inner)
        assertTrue(cine.enabled)
    }

    @Test fun everyRecordingStageHasTheExpectedStopOrPublicationAction() {
        RecordingStage.entries.forEach { stage ->
            val model = captureShutterModel(ready(CaptureMode.CINE).copy(recordingStage = stage))
            when (stage) {
                RecordingStage.IDLE -> assertFalse(model.square)
                RecordingStage.STARTING, RecordingStage.RECORDING -> {
                    assertTrue(model.enabled)
                    assertTrue(model.square)
                    assertEquals(26, model.innerDp)
                    assertEquals("停止", model.caption)
                    assertEquals(ShutterColor.RECORDING, model.ring)
                }
                RecordingStage.STOPPING, RecordingStage.FINALIZING -> {
                    assertFalse(model.enabled)
                    assertTrue(model.square)
                    assertEquals(ShutterColor.INACTIVE, model.inner)
                    assertEquals(.6f, model.alpha, 0f)
                }
            }
        }
    }

    @Test fun recordingStopSurvivesBusyHeatFailureAndNotReadyInputs() {
        for (stage in listOf(RecordingStage.STARTING, RecordingStage.RECORDING)) {
            val input = CaptureChromeInput(CaptureMode.CINE, recordingStage = stage, busy = true,
                thermalSeverity = 6, recordingFailure = "encoder warning")
            assertTrue(captureShutterModel(input).enabled)
            assertFalse(captureShutterModel(input.copy(interactable = false)).enabled)
        }
        val idleHot = captureShutterModel(ready(CaptureMode.CINE).copy(thermalSeverity = 3))
        assertFalse(idleHot.enabled)
        assertEquals("録画不可", idleHot.caption)
        assertEquals(.35f, idleHot.alpha, 0f)
    }

    @Test fun stillCaptureSaveFailureAndExclusiveRecoveryRemainDistinct() {
        val still = ready(CaptureMode.STILL)
        val capturing = captureShutterModel(still.copy(photoStage = PhotoStage.CAPTURING))
        assertEquals(48, capturing.innerDp)
        assertEquals(ShutterColor.EXPOSING, capturing.inner)
        assertFalse(capturing.enabled)
        val saving = captureShutterModel(still.copy(photoStage = PhotoStage.SAVING))
        assertEquals("保存中", saving.caption)
        assertFalse(saving.enabled)
        val failed = captureShutterModel(still.copy(photoStage = PhotoStage.FAILED))
        assertEquals("撮影/保存失敗", failed.caption)
        assertEquals(CaptureChromeTone.FAILURE, failed.captionTone)
        assertTrue(failed.enabled) // A known save failure does not hide an otherwise legal retry.
        val unknown = captureShutterModel(still.copy(ownsStill = true, busy = true, stillPhase = StillExposurePhase.UNKNOWN))
        assertEquals("復旧が必要", unknown.caption)
        assertFalse(unknown.enabled)
        assertEquals(CaptureChromeTone.FAILURE, unknown.captionTone)
        assertEquals("撮影準備中", captureShutterModel(still.copy(ownsStill = true, stillPhase = StillExposurePhase.CAPTURE_APPLYING)).caption)
        assertEquals("復帰待ち", captureShutterModel(still.copy(ownsStill = true, stillPhase = StillExposurePhase.RESTORING)).caption)
        val ownedSaving = still.copy(ownsStill = true, stillPhase = StillExposurePhase.CAPTURING, photoStage = PhotoStage.SAVING)
        assertEquals("保存中", captureShutterModel(ownedSaving).caption)
        assertEquals("保存中", captureStatusChips(ownedSaving).first { it.id == "operation" }.text)
    }

    @Test fun unknownOrUnboundConfigurationCannotAdvertiseRequestedValuesAsBound() {
        val video = VideoChoice("requested", 4, "HLG10", 24, 30, "UHD HLG10 24–30 fps")
        val unbound = captureStatusChips(CaptureChromeInput(CaptureMode.CINE, cameraId = "rear", boundVideoChoice = video))
        assertEquals("CAM —", unbound.first { it.id == "camera" }.text)
        assertEquals("— fps", unbound.first { it.id == "video_fps" }.text)
        val format = captureStatusChips(CaptureChromeInput(CaptureMode.STILL, photoFormat = "RAW+JPEG"))
        assertEquals("—", format.first { it.id == "format" }.text)
        val thermal = unbound.first { it.id == "thermal" }
        assertEquals("熱 —", thermal.text)
        assertEquals(CaptureChromeTone.NORMAL, thermal.tone)
    }

    @Test fun boundVideoReportsTheWholeRangeAsACombinationRatherThanMeasuredFps() {
        val video = VideoChoice("bound", 3, "SDR", 24, 60, "FHD SDR 24–60 fps")
        val chips = captureStatusChips(ready(CaptureMode.CINE).copy(boundVideoChoice = video, cameraId = "1"))
        val fps = chips.first { it.id == "video_fps" }
        assertEquals("24–60 fps", fps.text)
        assertTrue(fps.detail.contains("bind組合せ"))
        assertTrue(fps.detail.contains("実測FPS・実出力の証明ではありません"))
        assertEquals("24–\n60", fps.compact)
        assertEquals("CAM 1", chips.first { it.id == "camera" }.text)
    }

    @Test fun recordedDurationAndAudioUseObservedValuesAndPreserveUnknown() {
        val active = ready(CaptureMode.CINE).copy(recordingStage = RecordingStage.RECORDING,
            recordingDurationMs = 83_000, audioWanted = true)
        val unknown = captureStatusChips(active)
        assertEquals("● REC 1:23", unknown.first { it.id == "operation" }.text)
        assertEquals("MIC —", unknown.first { it.id == "audio" }.text)
        assertEquals("AMP —", unknown.first { it.id == "audio_amplitude" }.text)
        val observed = captureStatusChips(active.copy(audioState = AudioStats.AUDIO_STATE_ACTIVE, audioAmplitude = .25))
        assertEquals("MIC 収録中", observed.first { it.id == "audio" }.text)
        assertEquals("AMP 0.25", observed.first { it.id == "audio_amplitude" }.text)
        assertEquals("AMP —", captureStatusChips(active.copy(audioAmplitude = Double.NaN)).first { it.id == "audio_amplitude" }.text)
        assertEquals("AMP —", captureStatusChips(active.copy(audioAmplitude = 2.0)).first { it.id == "audio_amplitude" }.text)
        assertEquals("MIC —", captureStatusChips(active.copy(audioState = 99)).first { it.id == "audio" }.text)
        assertEquals("● REC —", captureStatusChips(active.copy(recordingDurationMs = null)).first { it.id == "operation" }.text)
    }

    @Test fun audioErrorsAndSilenceAreMappedFromActualAudioStats() {
        val input = ready(CaptureMode.CINE).copy(audioWanted = true, recordingStage = RecordingStage.RECORDING)
        for (state in listOf(AudioStats.AUDIO_STATE_ENCODER_ERROR, AudioStats.AUDIO_STATE_SOURCE_ERROR)) {
            assertEquals(CaptureChromeTone.FAILURE, captureStatusChips(input.copy(audioState = state)).first { it.id == "audio" }.tone)
        }
        assertEquals("MIC 消音", captureStatusChips(input.copy(audioState = AudioStats.AUDIO_STATE_SOURCE_SILENCED)).first { it.id == "audio" }.text)
        assertEquals("MIC OFF", captureStatusChips(input.copy(audioState = AudioStats.AUDIO_STATE_DISABLED)).first { it.id == "audio" }.text)
    }

    @Test fun saveFailureHeatAndMicPermissionStayVisibleAlongsideRecordingState() {
        val input = ready(CaptureMode.CINE).copy(recordingStage = RecordingStage.RECORDING,
            audioWanted = true, audioPermissionDenied = true, recordingFailure = "failure",
            recordingSaveSucceeded = false, thermalSeverity = 4)
        val chips = captureStatusChips(input)
        assertEquals(CaptureChromeTone.RECORDING, chips.first { it.id == "operation" }.tone)
        for (id in listOf("audio", "failure", "save", "thermal")) {
            assertEquals(CaptureChromeTone.FAILURE, chips.first { it.id == id }.tone)
        }
        assertTrue(captureShutterModel(input).enabled)
    }

    @Test fun savedFilesDoNotClaimThePreviewHasRecovered() {
        val chips = captureStatusChips(ready(CaptureMode.STILL).copy(ownsStill = true,
            stillPhase = StillExposurePhase.RESTORING, stillFilesTerminal = true, stillFilesSaved = true))
        assertEquals("プレビュー復帰中", chips.first { it.id == "operation" }.text)
        assertEquals("保存済", chips.first { it.id == "save" }.text)
        assertEquals(CaptureChromeTone.ACCEPTED, chips.first { it.id == "save" }.tone)
    }
}
