package dev.cinematic.p0.photoroll

import dev.cinematic.p0.rawcapture.ManualOriginalTrialSavePhase
import dev.cinematic.p0.rawcapture.ManualOriginalTrialSaveState
import org.junit.Assert.assertEquals
import org.junit.Test

class ManualTrialJpegSaveOwnerTest {
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
    }
}
