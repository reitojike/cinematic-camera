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
        assertEquals(58, shown.innerDp)
        assertEquals(ShutterColor.CONTROL, shown.inner)
        assertFalse(shown.enabled)
        assertEquals("保存中", shown.caption)
    }

    @Test fun activeOperationPrecedesScrollableConfigurationAndUnknownExposureIsNotAuto() {
        val chips = captureStatusChips(CaptureChromeInput(CaptureMode.CINE, recordingStage = RecordingStage.RECORDING))
        assertEquals("operation", chips[1].id)
        assertEquals("—", chips.first { it.id == "exposure" }.text)
    }
    @Test fun autoEditorKeepsItsStartingChoiceAcrossLiveObservations() {
        val initial = ManualExposure(100, 10_000_000)
        val next = ManualExposure(400, 20_000_000)
        val seed = captureAutoRulerSeed(null, initial)
        assertEquals(initial, captureAutoRulerSeed(seed, next))
        assertEquals(initial, captureAutoRulerSeed(seed, null))
        // A new editor/session starts without the previous session's seed.
        assertNull(captureAutoRulerSeed(null, null))
        assertEquals(next, captureAutoRulerSeed(null, next))
    }

    @Test fun autoCurrentChoiceCanEnterManualButManualDuplicateIsIgnored() {
        val received = mutableListOf<Int>()
        assertNull(dispatchControlScaleSelection(5, 2, 2, true, { true }) { received.add(it) })
        assertEquals(2, dispatchControlScaleSelection(5, 2, 2, true, { true }, allowCurrent = true) { received.add(it) })
        assertNull(dispatchControlScaleSelection(5, 2, 2, true, { false }, allowCurrent = true) { received.add(it) })
        assertEquals(listOf(2), received)
    }

    @Test fun keyRailKeepsSixKeysInOneRowAndUsesOnlyVisualShutterAbbreviation() {
        val rows = captureKeyRows()
        assertEquals(listOf(6), rows.map { it.size })
        assertEquals(CaptureKey.entries.toList(), rows.single())
        assertEquals("SS", captureKeyVisualLabel(CaptureKey.SHUTTER))
        assertEquals("ISO", captureKeyVisualLabel(CaptureKey.ISO))
        assertEquals("SHUTTER", CaptureKey.SHUTTER.name)
    }

    @Test fun keyRailReservesSpaceForRulerInShortPreview() {
        assertEquals(136f, captureKeyMaxHeight(240f, 1, true), 0f)
        assertEquals(196f, captureKeyMaxHeight(300f, 1, true), 0f)
        assertEquals(0f, captureKeyMaxHeight(80f, 1, true), 0f)
    }
    @Test fun modeSwitchAccessibilityReportsCurrentMode() {
        assertEquals("STILL", captureModeSwitchState(CaptureMode.STILL))
        assertEquals("CINE", captureModeSwitchState(CaptureMode.CINE))
    }

    @Test fun shutterAccessibilityRetainsSecondsForFractionAndLongExposure() {
        assertEquals("1/125 秒", captureShutterDescription("1/125"))
        assertEquals("1 秒", captureShutterDescription("1"))
        assertEquals("30 秒", captureShutterDescription("30"))
    }

    @Test fun switchLabelRoomTracksQuarterTurnAndNarrowSegments() {
        val segment = androidx.compose.ui.unit.IntSize(42, 80)
        assertEquals(segment, captureModeLabelRoom(segment, false))
        assertEquals(androidx.compose.ui.unit.IntSize(80, 42), captureModeLabelRoom(segment, true))
        assertEquals(androidx.compose.ui.unit.IntSize(48, 20),
            captureModeLabelRoom(androidx.compose.ui.unit.IntSize(20, 48), true))
    }

    @Test fun rulerTitlesUseTheSameCompactPolicyAndKeepFreshnessWarning() {
        for (key in listOf(CaptureKey.ISO, CaptureKey.SHUTTER, CaptureKey.EV, CaptureKey.FOCUS, CaptureKey.WB)) {
            val label = captureKeyVisualLabel(key)
            assertEquals(label, captureRulerTitle(label, false))
            assertEquals("$label 待機", captureRulerTitle(label, true))
            assertEquals(label, captureRulerDescription(label, false))
            assertEquals("$label AUTO実測待ち、選択一時停止", captureRulerDescription(label, true))
        }
        assertEquals("SS 待機", captureRulerTitle(captureKeyVisualLabel(CaptureKey.SHUTTER), true))
    }

    @Test fun landscapeKeysUseTheWidestMeasuredSideInEitherRotation() {
        // Values stand for measured natural heights at different text scales, not assumed device measurements.
        for (natural in listOf(listOf(72f, 92f, 72f, 80f, 108f, 72f),
            listOf(90f, 130f, 85f, 100f, 160f, 84f), listOf(140f, 200f, 130f, 150f, 240f, 130f))) {
            val common = captureKeyCommonHeight(natural)
            assertEquals(natural.maxOrNull()!!, common, 0f)
            for (rotation in listOf(90, -90)) {
                val sizes = natural.map { captureKeyHeight(it, common, horizontal = true) }
                assertTrue("rotation $rotation", sizes.all { it == common })
            }
            assertEquals(natural, natural.map { captureKeyHeight(it, common, horizontal = false) })
        }
        assertEquals(0f, captureKeyCommonHeight(emptyList()), 0f)
        assertEquals(80f, captureKeyCommonHeight(listOf(72f, 80f)), 0f) // Shrinks after a long label disappears.
    }

}
