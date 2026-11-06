package dev.cinematic.p0.photoroll

import org.junit.Assert.*
import org.junit.Test

class DevelopSavePresentationTest {
    @Test fun uncertainAndPublishedRemainDifferentFromReady() {
        val mode = PhotoRollPreviewMode.MANUAL_TRIAL
        assertEquals("保存前", developSaveLabel(mode, PhotoRollSavePhase.READY, false, false))
        assertTrue(developSaveLabel(mode, PhotoRollSavePhase.PUBLISHED, false, false).contains("開く前に照合"))
        assertTrue(developSaveLabel(mode, PhotoRollSavePhase.UNCONFIRMED, false, false).contains("再照合"))
        assertEquals("保存保留", developSaveLabel(mode, PhotoRollSavePhase.HOLD, false, false))
    }
    @Test fun privateAndActiveEditingNeverPresentReadySave() {
        assertEquals("共有保存なし", developSaveLabel(PhotoRollPreviewMode.PRIVATE_TRIAL, null, false, true))
        assertTrue(developSaveLabel(PhotoRollPreviewMode.MANUAL_TRIAL, PhotoRollSavePhase.READY, false, true).startsWith("調整中"))
        assertEquals("保存確認中", developSaveLabel(PhotoRollPreviewMode.MANUAL_TRIAL, PhotoRollSavePhase.READY, true, false))
    }
}
