package dev.cinematic.p0

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureChoiceVisualTest {
    @Test fun pendingWinsAndUnselectedPaintStaysCallerOwned() {
        assertEquals(CaptureDesign.Amber.copy(alpha = .12f), captureChoiceBackground(true, true))
        assertEquals(CaptureDesign.Amber.copy(alpha = .12f), captureChoiceBackground(false, true))
        assertEquals(CaptureDesign.Inverted, captureChoiceBackground(true))
        assertEquals(Color.Transparent, captureChoiceBackground(false))
        assertEquals(CaptureDesign.Ink, captureChoiceBackground(false, unselectedBackground = CaptureDesign.Ink))
    }
}