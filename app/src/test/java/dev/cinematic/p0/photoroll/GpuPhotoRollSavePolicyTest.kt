package dev.cinematic.p0.photoroll

import org.junit.Assert.*
import org.junit.Test

class GpuPhotoRollSavePolicyTest {
    @Test fun recreatedEditorsPauseUnresolvedOwnersBeforeOpeningTheGpuPhoto() {
        for (phase in PhotoRollSavePhase.entries) {
            assertEquals(phase.name, phase !in setOf(PhotoRollSavePhase.READY, PhotoRollSavePhase.PUBLISHED, PhotoRollSavePhase.CANCELLED),
                gpuSaveMustPauseLoadedPreview(phase))
        }
    }

    @Test fun closedAcceptedPreviewCanRetryGpuOrReconcileTheSamePublication() {
        for (phase in PhotoRollSavePhase.entries) {
            assertEquals(phase.name, phase in setOf(PhotoRollSavePhase.GPU_FAILED, PhotoRollSavePhase.CANCELLED,
                PhotoRollSavePhase.PUBLISHED, PhotoRollSavePhase.UNCONFIRMED), gpuSaveCanContinueWithoutPreview(phase))
        }
        assertFalse(gpuSaveCanContinueWithoutPreview(null))
    }
    @Test fun alreadyResumedPreviewNeverRestartsForRetainedSaveHistory() {
        for (phase in PhotoRollSavePhase.entries) {
            assertFalse(phase.name, gpuSaveCanResumePreview(phase, exportPaused = false))
        }
        assertFalse(gpuSaveCanResumePreview(null, exportPaused = false))
    }
    @Test fun activeHeldOrUncertainSaveCannotStartAnotherPreview() {
        for (phase in PhotoRollSavePhase.entries) {
            assertEquals(phase.name, phase in setOf(PhotoRollSavePhase.READY, PhotoRollSavePhase.PUBLISHED, PhotoRollSavePhase.CANCELLED), gpuSaveCanResumePreview(phase, exportPaused = true))
        }
        assertFalse(gpuSaveCanResumePreview(null, exportPaused = true))
    }
}
