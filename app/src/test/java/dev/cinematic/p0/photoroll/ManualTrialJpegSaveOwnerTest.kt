package dev.cinematic.p0.photoroll

import dev.cinematic.p0.rawcapture.ManualOriginalTrialSavePhase
import dev.cinematic.p0.rawcapture.ManualOriginalTrialSaveState
import org.junit.Assert.assertEquals
import org.junit.Test

class ManualTrialJpegSaveOwnerTest {
    @Test fun confirmedCancellationStaysVisibleAndAllowsEditingAfterForegroundWorkStops() {
        val cancelled = ManualOriginalTrialSaveState(ManualOriginalTrialSavePhase.CANCELLED)
        assertEquals(PhotoRollSavePhase.CANCELLED, manualSaveSnapshot(cancelled).phase)
        assertEquals(true, canBeginStrengthEdit(PhotoRollPreviewMode.MANUAL_TRIAL,
            foreground = true, savePending = false, savePhase = PhotoRollSavePhase.CANCELLED))
        for (local in listOf(null, PhotoRollSaveSnapshot(PhotoRollSavePhase.UNCONFIRMED))) {
            val result = manualSaveVisibleSnapshot(cancelled, local)
            assertEquals(PhotoRollSavePhase.CANCELLED, result.phase)
            assertEquals(null, result.uri)
            assertEquals(null, result.gpuFailure)
        }
    }

    @Test fun gpuFailureReasonAndCountStayVisibleWithoutPretendingPublication() {
        val failure = dev.cinematic.p0.rawcapture.GpuSaveFailure(
            dev.cinematic.p0.rawcapture.GpuSaveFailureReason.CONTEXT_LOST, 2)
        for (phase in listOf(ManualOriginalTrialSavePhase.GPU_FAILED, ManualOriginalTrialSavePhase.HOLD,
            ManualOriginalTrialSavePhase.UNCONFIRMED)) {
            val result = manualSaveSnapshot(ManualOriginalTrialSaveState(phase, gpuFailure = failure))
            assertEquals(failure, result.gpuFailure)
            assertEquals(null, result.uri)
        }
        val result = manualSaveVisibleSnapshot(
            ManualOriginalTrialSaveState(ManualOriginalTrialSavePhase.GPU_FAILED, gpuFailure = failure),
            PhotoRollSaveSnapshot(PhotoRollSavePhase.UNCONFIRMED))
        assertEquals(PhotoRollSavePhase.GPU_FAILED, result.phase)
        assertEquals(failure, result.gpuFailure)
    }

    @Test fun onlyPublicationWithAUriCanBePresentedAsSaved() {
        val published = manualSaveSnapshot(ManualOriginalTrialSaveState(ManualOriginalTrialSavePhase.PUBLISHED,
            "content://media/external/images/media/1"))
        assertEquals(PhotoRollSavePhase.PUBLISHED, published.phase)
        assertEquals("content://media/external/images/media/1", published.uri)
        assertEquals(PhotoRollSavePhase.HOLD,
            manualSaveSnapshot(ManualOriginalTrialSaveState(ManualOriginalTrialSavePhase.PUBLISHED)).phase)
    }

    @Test fun uncertainAndHeldCoreStatesNeverBecomePublicationReceipts() {
        assertEquals(PhotoRollSavePhase.UNCONFIRMED,
            manualSaveSnapshot(ManualOriginalTrialSaveState(ManualOriginalTrialSavePhase.UNCONFIRMED)).phase)
        assertEquals(PhotoRollSavePhase.HOLD,
            manualSaveSnapshot(ManualOriginalTrialSaveState(ManualOriginalTrialSavePhase.HOLD)).phase)
        assertEquals(PhotoRollSavePhase.HOLD, manualSaveVisibleSnapshot(
            ManualOriginalTrialSaveState(ManualOriginalTrialSavePhase.HOLD),
            PhotoRollSaveSnapshot(PhotoRollSavePhase.UNCONFIRMED)).phase)
    }
}
