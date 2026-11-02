package dev.cinematic.p0

import org.junit.Assert.*
import org.junit.Test

class StillExposureStateTest {
    private val capture = ManualExposure(100, 15_000_000_000)
    private val preview = ManualExposure(3200, 468_750_000)
    private fun target(epoch: Int = 3, revision: Long = 1) = StillExposureTarget(epoch, revision,
        SimulationPlan(capture, preview, SimulationStage.LONGER_PREVIEW, 33_333_333, 0.0),
        ControlSnapshot(capture, overrides = SensorOverrides(whiteBalance = 5)), 100)
    private fun receipt(pair: ManualExposure = preview, sequence: Long = 1, at: Long = 10,
        phase: ControlPhase = ControlPhase.ACCEPTED, epoch: Int = 3) = ControlReceipt(epoch, sequence, phase,
        ControlSnapshot(pair), ControlSnapshot(pair), at)
    private fun frame(number: Long = 20, time: Long = 11, intent: SimulationIntent = SimulationIntent.PREVIEW) = SimulationFrame(
        3, number, time, intent, SensorValues(preview.iso, preview.exposureNs, 0),
        SensorValues(3100, 460_000_000, 0), 470_000_000, number * 1_000_000, 100, 100)
    private fun ready() = StillExposureState(target()).previewReceipt(1, receipt()).observe(frame())
    private fun issued() = ready().beginCapture().captureReceipt(receipt(capture, 2, 12)).singleStarted()

    @Test fun previewReadinessRequiresCurrentReceiptAndFreshCompleteMetadataNotExactActualPair() {
        val state = StillExposureState(target())
        assertFalse(state.observe(frame()).canCapture)
        val accepted = state.previewReceipt(1, receipt())
        assertFalse(accepted.observe(frame(time = 10)).canCapture)
        assertFalse(accepted.observe(frame().copy(actual = SensorValues())).canCapture)
        assertFalse(accepted.observe(frame().copy(actual = frame().actual.copy(aeMode = 1))).canCapture)
        assertFalse(accepted.observe(frame().copy(actualBoost = null)).canCapture)
        assertFalse(accepted.observe(frame().copy(requestedBoost = 200)).canCapture)
        assertTrue(accepted.observe(frame()).canCapture)
        assertEquals(3100, accepted.observe(frame()).previewFrame!!.actual.iso)
        assertEquals(capture, accepted.target.controls.exposure)
    }

    @Test fun latestRevisionRejectsOldReceiptAndCannotUseOldPreviewForNewAck() {
        val latest = ready().retarget(target(revision = 2))
        assertEquals(StillExposurePhase.PREVIEW_APPLYING, latest.phase)
        assertEquals(latest, latest.previewReceipt(1, receipt(sequence = 100)))
        val acknowledged = latest.previewReceipt(2, receipt(sequence = 2, at = 12))
        assertFalse(acknowledged.canCapture)
        assertTrue(acknowledged.observe(frame(21, 13)).canCapture)
        assertEquals(latest, latest.retarget(target(revision = 1)))
    }

    @Test fun shutterFreezesTargetAndOnlyNewCaptureReceiptCanIssueSingleOnce() {
        val fixed = ready().beginCapture()
        assertTrue(fixed.ownsCapture); assertFalse(fixed.canCapture); assertFalse(fixed.canIssueCapture)
        assertEquals(fixed.target, fixed.captureTarget)
        assertEquals(fixed, fixed.retarget(target(revision = 2)))
        assertEquals(fixed, fixed.captureReceipt(receipt(capture, 1)))
        assertEquals(fixed, fixed.captureReceipt(receipt(capture, 2, epoch = 2)))
        assertFalse(fixed.captureReceipt(receipt(preview, 2)).canIssueCapture)
        val accepted = fixed.captureReceipt(receipt(capture, 2))
        assertTrue(accepted.canIssueCapture)
        val single = accepted.singleStarted()
        assertTrue(single.singleIssued); assertFalse(single.canIssueCapture)
        assertEquals(single, single.singleStarted())
    }

    @Test fun pendingReceiptCanCompleteButDuplicateOrOlderReceiptCannotUndoIt() {
        val fixed = ready().beginCapture()
        val pending = fixed.captureReceipt(receipt(capture, 2, phase = ControlPhase.PENDING))
        assertFalse(pending.canIssueCapture)
        assertTrue(pending.captureReceipt(receipt(capture, 2)).canIssueCapture)
        val accepted = pending.captureReceipt(receipt(capture, 2))
        assertEquals(accepted, accepted.captureReceipt(receipt(capture, 2, phase = ControlPhase.PENDING)))
        assertEquals(accepted, accepted.captureReceipt(receipt(capture, 1)))
    }

    @Test fun saveSuccessAndFailureBothRequireNewPreviewAckAndFrameBeforeOwnershipRelease() {
        for (success in listOf(true, false)) {
            val capture = issued().observe(frame(21, 14, SimulationIntent.STILL))
            val finished = capture.filesFinished(success)
            assertEquals(success, finished.filesSaved)
            assertTrue(finished.filesTerminal); assertTrue(finished.ownsCapture)
            assertEquals(StillExposurePhase.RESTORING, finished.phase)
            assertFalse(finished.observe(frame(22, 15)).canCapture)
            assertEquals(finished, finished.previewReceipt(1, receipt(sequence = 2, at = 15)))
            val restore = finished.previewReceipt(1, receipt(sequence = 3, at = 15))
            assertFalse(restore.canCapture)
            val returned = restore.observe(frame(22, 16))
            assertTrue(returned.canCapture); assertFalse(returned.ownsCapture)
            assertEquals(success, returned.filesSaved)
            assertEquals(returned, returned.filesFinished(!success))
        }
    }

    @Test fun captureTimeoutKeepsOwnershipUntilTerminalAndDoesNotIssueFromLateAck() {
        val preparing = ready().beginCapture().unknown("capture ACK timeout")
        assertTrue(preparing.ownsCapture); assertFalse(preparing.canIssueCapture)
        assertEquals(preparing, preparing.captureReceipt(receipt(capture, 2)))
        assertEquals(preparing, preparing.filesFinished(false)) // No single was issued.
        val timedOut = issued().unknown("single result timeout")
        assertTrue(timedOut.ownsCapture)
        assertEquals(timedOut, timedOut.retarget(target(revision = 2)))
        assertEquals(StillExposurePhase.UNKNOWN, timedOut.observe(frame(30, 20)).phase)
        val terminal = timedOut.filesFinished(true)
        assertTrue(terminal.ownsCapture); assertTrue(terminal.filesSaved == true)
        assertEquals(StillExposurePhase.RESTORING, terminal.phase)
        assertEquals(terminal, terminal.filesFinished(false))
    }

    @Test fun preparationAbortRestoresOnlyAfterNewPreviewAckAndFrameAndIgnoresLateCaptureAck() {
        val preparing = ready().beginCapture()
            .captureReceipt(receipt(capture, 2, phase = ControlPhase.PENDING)).unknown("capture ACK timeout")
        val aborted = preparing.preparationAborted("capture ACK timeout")
        assertEquals(StillExposurePhase.RESTORING, aborted.phase)
        assertTrue(aborted.ownsCapture); assertFalse(aborted.singleIssued)
        assertTrue(aborted.filesTerminal); assertFalse(aborted.filesSaved!!)
        assertTrue(aborted.detail.contains("撮影未開始"))
        assertFalse(aborted.detail.contains("保存失敗"))
        assertEquals(aborted, aborted.captureReceipt(receipt(capture, 2)))
        assertEquals(aborted, aborted.preparationAborted("duplicate"))
        assertEquals(aborted, aborted.previewReceipt(1, receipt(sequence = 2, at = 20)))
        assertFalse(aborted.observe(frame(21, 21)).canCapture)
        val acknowledged = aborted.previewReceipt(1, receipt(sequence = 3, at = 20))
        assertFalse(acknowledged.canCapture)
        val returned = acknowledged.observe(frame(21, 21))
        assertTrue(returned.canCapture); assertFalse(returned.ownsCapture)
        assertFalse(returned.filesSaved!!)
    }

    @Test fun preparationAbortCannotReleaseIssuedCaptureOrAStateWithoutCaptureOwnership() {
        val known = ready()
        assertEquals(known, known.preparationAborted("no capture"))
        val single = issued().unknown("single result timeout")
        assertEquals(single, single.preparationAborted("must await terminal"))
        assertTrue(single.ownsCapture); assertFalse(single.filesTerminal)
        val terminal = single.filesFinished(true)
        assertEquals(terminal, terminal.preparationAborted("already terminal"))
    }

    @Test fun stillAndOtherFramesNeverContaminatePreviewAndOlderEpochCannotChangeState() {
        val known = ready()
        val still = known.observe(frame(21, 12, SimulationIntent.STILL))
        assertEquals(known.previewFrame, still.previewFrame)
        assertEquals(SimulationIntent.STILL, still.stillFrame!!.intent)
        val other = still.observe(frame(22, 13, SimulationIntent.OTHER))
        assertEquals(still.previewFrame, other.previewFrame); assertEquals(still.stillFrame, other.stillFrame)
        assertEquals(other, other.observe(frame(21, 14)))
        assertEquals(other, other.observe(frame(100, 14).copy(epoch = 2)))
        assertEquals(other, other.previewReceipt(1, receipt(sequence = 100, epoch = 2)))
        val rebound = known.retarget(target(epoch = 4))
        assertNull(rebound.previewFrame); assertNull(rebound.stillFrame)
        assertEquals(-1L, rebound.latestFrame)
    }

    @Test fun suspendedSingleRetainsTerminalFactsAndDoesNotResumeFromLateMetadata() {
        val suspended = issued().suspend()
        assertTrue(suspended.ownsCapture)
        assertEquals(suspended, suspended.observe(frame(30, 30)))
        assertEquals(suspended, suspended.previewReceipt(1, receipt(sequence = 100)))
        assertEquals(suspended, suspended.retarget(target(epoch = 4)))
        val terminal = suspended.filesFinished(false)
        assertFalse(terminal.filesSaved!!); assertTrue(terminal.filesTerminal)
        assertTrue(terminal.ownsCapture); assertEquals(StillExposurePhase.SUSPENDED, terminal.phase)
    }

    @Test fun unknownOrIncompleteLatestPreviewCannotBorrowEarlierReadiness() {
        val known = ready()
        val missing = known.observe(frame(21, 12).copy(actualBoost = null))
        assertFalse(missing.canCapture)
        assertTrue(missing.observe(frame(22, 13)).canCapture)
        val unknown = known.unknown("preview failure")
        assertEquals(unknown, unknown.previewReceipt(1, receipt(sequence = 2)))
        assertFalse(unknown.observe(frame(30, 30)).canCapture)
        val noBoost = target().copy(boostPercent = null)
        assertTrue(StillExposureState(noBoost).previewReceipt(1, receipt()).observe(
            frame().copy(requestedBoost = null, actualBoost = null)).canCapture)
    }
}
