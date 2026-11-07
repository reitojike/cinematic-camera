package dev.cinematic.p0.photoroll

import dev.cinematic.p0.CaptureDesign
import org.junit.Assert.*
import org.junit.Test

class DevelopModePresentationTest {
    @Test fun onlyPrivateTrialModeIsAmberAndStageClearHidesFaceWarning() {
        assertEquals(CaptureDesign.Amber, developModeColor(PhotoRollPreviewMode.PRIVATE_TRIAL))
        assertEquals(CaptureDesign.Text, developModeColor(PhotoRollPreviewMode.MANUAL_TRIAL))
        assertEquals(CaptureDesign.Text, developModeColor(PhotoRollPreviewMode.STAGE_CLEAR))
        assertTrue(developShowsFaceWarning(PhotoRollPreviewMode.PRIVATE_TRIAL))
        assertTrue(developShowsFaceWarning(PhotoRollPreviewMode.MANUAL_TRIAL))
        assertFalse(developShowsFaceWarning(PhotoRollPreviewMode.STAGE_CLEAR))
        assertTrue(developShowsFaceWarning(PhotoRollPreviewMode.DNG_TRIAL))
        assertTrue(developShowsFaceWarning(PhotoRollPreviewMode.STAGE_CLEAR_TRIAL))
    }
}
