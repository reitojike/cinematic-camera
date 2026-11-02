package dev.cinematic.p0

import org.junit.Assert.*
import org.junit.Test

class ExposureSimulationRunTest {
    private val capture = ManualExposure(100, 15_000_000_000)
    private val preview = ManualExposure(3200, 468_750_000)
    private val settings = SimulationFixedSettings(
        SimulationPlan(capture, preview, SimulationStage.LONGER_PREVIEW, 33_333_333, 0.0),
        ControlSnapshot(capture, overrides = SensorOverrides(whiteBalance = 5)), 100,
    )
    private fun receipt(sequence: Long = 1, phase: ControlPhase = ControlPhase.ACCEPTED) = ControlReceipt(
        epoch = 3, sequence = sequence, phase = phase,
        requested = ControlSnapshot(preview), accepted = ControlSnapshot(preview), acceptedAtMs = 10,
    )
    private fun frame(number: Long = 20, intent: SimulationIntent = SimulationIntent.PREVIEW) = SimulationFrame(
        epoch = 3, frame = number, receivedAtMs = 11, intent = intent,
        requested = SensorValues(preview.iso, preview.exposureNs, 0),
        actual = SensorValues(3100, 460_000_000, 0), frameDurationNs = 470_000_000,
        sensorTimestampNs = number * 1_000_000, requestedBoost = 100, actualBoost = 100,
    )
    private fun ready() = ExposureSimulationRun(settings, 3).receipt(receipt()).observe(frame())

    @Test fun acceptedRequestAndFreshPreviewAreRequiredWithoutInventingExactActualValues() {
        val initial = ExposureSimulationRun(settings, 3)
        assertFalse(initial.observe(frame()).canCapture)
        val accepted = initial.receipt(receipt())
        assertFalse(accepted.observe(frame().copy(receivedAtMs = 10)).canCapture)
        assertFalse(accepted.observe(frame().copy(requested = SensorValues(100, capture.exposureNs, 0))).canCapture)
        assertFalse(accepted.observe(frame().copy(actual = SensorValues())).canCapture)
        assertFalse(accepted.observe(frame().copy(actual = SensorValues(0, 1, 0))).canCapture)
        val result = accepted.observe(frame())
        assertTrue(result.canCapture)
        assertEquals(3100, result.previewFrame!!.actual.iso)
        assertEquals(capture, result.settings.normalSnapshot.exposure)
        assertEquals(preview, result.previewReceipt.accepted!!.exposure)
    }

    @Test fun boostMustBeObservedWhenExplicitlyRequestedButCanBeUnrequested() {
        val accepted = ExposureSimulationRun(settings, 3).receipt(receipt())
        assertFalse(accepted.observe(frame().copy(actualBoost = null)).canCapture)
        assertFalse(accepted.observe(frame().copy(requestedBoost = null)).canCapture)
        assertFalse(accepted.observe(frame().copy(actualBoost = 200)).canCapture)
        val noBoost = ExposureSimulationRun(settings.copy(boostPercent = null), 3).receipt(receipt())
        assertTrue(noBoost.observe(frame().copy(requestedBoost = null, actualBoost = null)).canCapture)
    }

    @Test fun actualAeMustReportOffRatherThanBeMissingOrAutomatic() {
        val accepted = ExposureSimulationRun(settings, 3).receipt(receipt())
        for (mode in listOf(null, 1)) {
            val observed = accepted.observe(frame().copy(actual = frame().actual.copy(aeMode = mode)))
            assertFalse(observed.canCapture)
            assertEquals(SimulationPhase.ENTERING, observed.phase)
        }
    }

    @Test fun latestPreviewCannotBorrowTrustFromAnOlderCompleteFrame() {
        val ready = ready()
        val incomplete = ready.observe(frame(21).copy(actualBoost = null))
        assertFalse(incomplete.canCapture)
        assertEquals(SimulationPhase.ENTERING, incomplete.phase)
        assertNull(incomplete.previewFrame!!.actualBoost)
        assertTrue(incomplete.observe(frame(22)).canCapture)
        assertFalse(ready.observe(frame(21).copy(requested = SensorValues(100, capture.exposureNs, 0))).canCapture)
    }

    @Test fun stillAndOtherIntentsCannotReplacePreviewAndOldFramesCannotOverwriteMetadata() {
        val ready = ready()
        val still = ready.observe(frame(21, SimulationIntent.STILL))
        assertEquals(ready.previewFrame, still.previewFrame)
        assertEquals(SimulationIntent.STILL, still.stillFrame!!.intent)
        val other = still.observe(frame(22, SimulationIntent.OTHER))
        assertEquals(still.previewFrame, other.previewFrame)
        assertEquals(still.stillFrame, other.stillFrame)
        assertEquals(other, other.observe(frame(22)))
        assertEquals(other, other.observe(frame(20)))
        assertEquals(other, other.observe(frame(100).copy(epoch = 2)))
    }

    @Test fun publicationResultAndPostCapturePreviewRecoveryRemainIndependent() {
        for (success in listOf(true, false)) {
            val capturing = ready().captureStarted().observe(frame(21, SimulationIntent.STILL))
            assertEquals(SimulationPhase.CAPTURING, capturing.phase)
            assertFalse(capturing.canCapture)
            val saved = capturing.filesFinished(success)
            assertEquals(success, saved.filesSaved)
            assertEquals(21L, saved.returnAfterFrame)
            assertFalse(saved.canCapture)
            assertFalse(saved.observe(frame(22, SimulationIntent.OTHER)).canCapture)
            val returned = saved.observe(frame(22))
            assertTrue(returned.canCapture)
            assertEquals(success, returned.filesSaved)
            assertEquals(returned, returned.filesFinished(!success))
        }
    }

    @Test fun exitAndSuspensionPreventFurtherCaptureAndLateCallbacksCannotResumeIt() {
        val exit = ready().requestExit()
        assertFalse(exit.canCapture)
        assertEquals(exit, exit.captureStarted())
        val suspended = ready().captureStarted().suspend()
        assertEquals(suspended, suspended.observe(frame(100)))
        assertEquals(suspended, suspended.receipt(receipt(2)))
        val lateSaved = suspended.filesFinished(true)
        assertTrue(lateSaved.filesSaved == true)
        assertEquals(SimulationPhase.SUSPENDED, lateSaved.phase)
        assertTrue(lateSaved.exitPending)
    }

    @Test fun failureNeverBecomesReadyFromLateReceiptsOrMetadataButKeepsFileOutcome() {
        val failed = ready().captureStarted().unknown("preview recovery timeout").filesFinished(true)
        assertEquals(SimulationPhase.UNKNOWN, failed.observe(frame(30)).phase)
        assertFalse(failed.observe(frame(30)).canCapture)
        assertEquals(failed, failed.receipt(receipt(2)))
        assertTrue(failed.filesSaved == true)
        assertEquals(SimulationPhase.UNKNOWN, ready().receipt(receipt(2, ControlPhase.REJECTED)).phase)
        assertEquals(SimulationPhase.UNKNOWN, ready().receipt(receipt(2, ControlPhase.UNKNOWN)).phase)
    }

    @Test fun rebindClearsOldObservationsAndReceiptWhileRetainingFixedSettingsAndExitRequest() {
        val rebound = ready().requestExit().rebound(4)
        assertEquals(settings, rebound.settings)
        assertNull(rebound.previewFrame); assertNull(rebound.stillFrame)
        assertEquals(-1L, rebound.latestFrame); assertEquals(-1L, rebound.returnAfterFrame)
        assertEquals(4, rebound.previewReceipt.epoch)
        assertEquals(SimulationPhase.ENTERING, rebound.phase)
        assertTrue(rebound.exitPending)
        assertEquals(rebound, rebound.receipt(receipt(100)))
        assertEquals(rebound, rebound.observe(frame(100)))
    }

    @Test fun newPendingBlocksCaptureAndOldOrDuplicateReceiptsCannotUndoIt() {
        val ready = ready()
        val pending = ready.receipt(receipt(2, ControlPhase.PENDING))
        assertFalse(pending.canCapture)
        assertEquals(pending, pending.receipt(receipt(1)))
        val accepted = pending.receipt(receipt(2).copy(acceptedAtMs = 12))
        assertFalse(accepted.canCapture)
        assertEquals(accepted, accepted.receipt(receipt(2, ControlPhase.PENDING)))
        assertTrue(accepted.observe(frame(21).copy(receivedAtMs = 13)).canCapture)
    }
}
