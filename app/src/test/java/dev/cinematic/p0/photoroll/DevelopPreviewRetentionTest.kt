package dev.cinematic.p0.photoroll

import org.junit.Assert.*
import org.junit.Test

class DevelopPreviewRetentionTest {
    @Test fun onlyAnAdjustmentOfTheSameVisiblePhotoRetainsPixels() {
        assertTrue(retainDevelopPreview(true, "a", "a", true, true))
        assertFalse(retainDevelopPreview(false, "a", "a", true, true))
        assertFalse(retainDevelopPreview(true, null, "a", true, true))
        assertFalse(retainDevelopPreview(true, "b", "a", true, true))
        assertFalse(retainDevelopPreview(true, "a", "a", false, true))
        assertFalse(retainDevelopPreview(true, "a", "a", true, false))
    }
}
