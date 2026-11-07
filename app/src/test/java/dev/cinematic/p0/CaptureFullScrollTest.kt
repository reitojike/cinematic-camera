package dev.cinematic.p0

import org.junit.Assert.*
import org.junit.Test

class CaptureFullScrollTest {
    private fun diagnostic(width: Float, height: Float, scale: Float) =
        captureNeedsFullScroll(width, height, scale.coerceAtLeast(1f), 520f)

    @Test fun compactWindowRetainsScrollableBodyAtNormalAndLargeText() {
        assertTrue(diagnostic(412f, 320f, 1f))
        assertTrue(diagnostic(412f, 320f, 1.3f))
        assertTrue(diagnostic(924f, 412f, 1f))
        assertTrue(diagnostic(320f, 924f, 1f))
    }
    @Test fun largeTextWrapsFixedSectionsWhileNormalPortraitKeepsFooter() {
        assertFalse(diagnostic(412f, 900f, 1f))
        assertTrue(diagnostic(412f, 900f, 1.3f))
        assertTrue(diagnostic(412f, 900f, 2f))
    }
    @Test fun thresholdsStayStrictAndPerScreen() {
        for (height in listOf(520f, 560f)) {
            assertFalse(captureNeedsFullScroll(360f, height, 1f, height))
            assertTrue(captureNeedsFullScroll(359.9f, height, 1f, height))
            assertTrue(captureNeedsFullScroll(360f, height - .1f, 1f, height))
        }
    }
    @Test fun settingsKeepHeightOnlyAndNoFontScaling() {
        assertFalse(captureNeedsFullScroll(100f, 240f, 1f, 240f, 0f))
        assertTrue(captureNeedsFullScroll(100f, 239.9f, 1f, 240f, 0f))
    }
    @Test fun callerRetainsSmallFontPolicy() {
        assertFalse(captureNeedsFullScroll(300f, 450f, .8f, 520f))
        assertTrue(diagnostic(300f, 450f, .8f))
        assertFalse(captureNeedsFullScroll(360f * 1.3f, 560f * 1.3f, 1.3f, 560f))
    }
}