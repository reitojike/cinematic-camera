package dev.cinematic.p0

import org.junit.Assert.*
import org.junit.Test

class DiagnosticLayoutTest {
    @Test fun compactWindowRetainsScrollableBodyAtNormalAndLargeText() {
        assertTrue(diagnosticNeedsFullScroll(412f, 320f, 1f))
        assertTrue(diagnosticNeedsFullScroll(412f, 320f, 1.3f))
        assertTrue(diagnosticNeedsFullScroll(924f, 412f, 1f))
        assertTrue(diagnosticNeedsFullScroll(320f, 924f, 1f))
    }
    @Test fun largeTextWrapsFixedSectionsWhileNormalPortraitKeepsFooter() {
        assertFalse(diagnosticNeedsFullScroll(412f, 900f, 1f))
        assertTrue(diagnosticNeedsFullScroll(412f, 900f, 1.3f))
        assertTrue(diagnosticNeedsFullScroll(412f, 900f, 2f))
    }
}
