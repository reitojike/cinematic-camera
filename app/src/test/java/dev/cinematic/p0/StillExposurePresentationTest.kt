package dev.cinematic.p0

import org.junit.Assert.*
import org.junit.Test

class StillExposurePresentationTest {
    private val capture = ManualExposure(100, 1_000_000_000L)
    private val preview = ManualExposure(3200, 31_250_000L)
    private fun state(stage: SimulationStage = SimulationStage.COMPENSATED): StillExposureState {
        val target = StillExposureTarget(1, 1, SimulationPlan(capture, preview, stage, 33_333_333L, 0.0),
            ControlSnapshot(exposure = capture), null)
        return StillExposureState(target)
    }

    @Test fun previewReceiptDoesNotBecomeCaptureReceiptOrSensorEvidence() {
        val shown = state().copy(previewReceipt = ControlReceipt(epoch = 1, sequence = 1,
            phase = ControlPhase.ACCEPTED, accepted = ControlSnapshot(exposure = preview), acceptedAtMs = 1)).presentation()
        assertEquals("ISO 100 · 1 s", shown.captureTarget)
        assertTrue(shown.previewApi.contains("3200"))
        assertEquals("未受付", shown.captureApi)
        assertEquals("未観測", shown.previewActual)
        assertEquals("未観測", shown.stillActual)
    }

    @Test fun twoPlanningStagesAreSeparateFromControlPhases() {
        assertTrue(state().presentation().stage.startsWith("第1段階"))
        assertTrue(state(SimulationStage.LONGER_PREVIEW).presentation().stage.startsWith("第2段階"))
        assertEquals("撮影設定の受付待ち", state().copy(phase = StillExposurePhase.CAPTURE_APPLYING).presentation().phase)
    }

    @Test fun unknownCaptureOwnerHasExplicitRecoveryAndKeepsFileOutcomeSeparate() {
        val start = state()
        val unknown = start.copy(phase = StillExposurePhase.UNKNOWN, captureTarget = start.target,
            filesTerminal = true, filesSaved = true).presentation()
        assertTrue(unknown.recoveryRequired)
        assertEquals("保存成功", unknown.files)
        assertFalse(start.copy(phase = StillExposurePhase.UNKNOWN).presentation().recoveryRequired)
        assertEquals("保存失敗", start.copy(filesTerminal = true, filesSaved = false).presentation().files)
    }
}
