package dev.cinematic.p0.photoroll

import org.junit.Assert.*
import org.junit.Test

class CameraRollDngThumbnailTest {
    @Test fun sampledRawStaysWithinExistingPreviewLimit() {
        assertEquals(16, rollDngThumbnailSample(4000, 3000, "image/x-adobe-dng"))
        assertEquals(16, rollDngThumbnailSample(4000, 4000, "image/x-adobe-dng"))
        assertNull(rollDngThumbnailSample(4000, 4001, "image/x-adobe-dng"))
        assertNull(rollDngThumbnailSample(6000, 4000, null))
    }
    @Test fun embeddedJpegUsesItsOwnSampledDimensions() {
        assertEquals(1, rollDngThumbnailSample(256, 192, "image/jpeg"))
        assertEquals(32, rollDngThumbnailSample(6000, 4000, "image/jpeg"))
    }
    @Test fun invalidAndOverflowingDimensionsAreRejectedForRaw() {
        assertNull(rollDngThumbnailSample(-1, 3000, "image/x-adobe-dng"))
        assertNull(rollDngThumbnailSample(4000, 0, "image/x-adobe-dng"))
        assertNull(rollDngThumbnailSample(Int.MAX_VALUE, Int.MAX_VALUE, "image/x-adobe-dng"))
    }
}
