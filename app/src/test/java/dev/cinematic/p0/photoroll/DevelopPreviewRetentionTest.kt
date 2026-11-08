package dev.cinematic.p0.photoroll

import org.junit.Assert.*
import org.junit.Test

class DevelopPreviewRetentionTest {
    @Test fun samePhotoRetainsPixelsIncludingSameValueReleaseCancelAndRetry() {
        assertTrue(retainDevelopPreview(true, "a", "a", true))
        assertFalse(retainDevelopPreview(false, "a", "a", true))
        assertFalse(retainDevelopPreview(true, null, "a", true))
        assertFalse(retainDevelopPreview(true, "b", "a", true))
        assertFalse(retainDevelopPreview(true, "a", "a", false))
    }
}
