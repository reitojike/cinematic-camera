package dev.cinematic.p0.photoroll

import org.junit.Assert.*
import org.junit.Test

class CameraRollDngThumbnailTest {
    @Test fun landscapeUnrotatedOnly() {
        assertEquals(90, rollThumbnailRotation(RollDngOrientation(6, 4000, 3000), 256, 192))
        assertEquals(270, rollThumbnailRotation(RollDngOrientation(8, 4000, 3000), 256, 192))
        assertEquals(0, rollThumbnailRotation(RollDngOrientation(6, 4000, 3000), 192, 256))
    }
    @Test fun portraitSourceAndSquareAreNeverGuessed() {
        assertEquals(0, rollThumbnailRotation(RollDngOrientation(6, 3000, 4000), 256, 192))
        assertEquals(0, rollThumbnailRotation(RollDngOrientation(8, 3000, 4000), 192, 256))
        assertEquals(0, rollThumbnailRotation(RollDngOrientation(6, 3000, 3000), 256, 192))
        assertEquals(0, rollThumbnailRotation(RollDngOrientation(6, 4000, 3000), 256, 256))
    }
    @Test fun unknownCropHalfTurnAndMirrorsAreOutOfScope() {
        for (orientation in listOf(0, 1, 2, 3, 4, 5, 7, 9))
            assertEquals(0, rollThumbnailRotation(RollDngOrientation(orientation, 4000, 3000), 256, 192))
        assertEquals(0, rollThumbnailRotation(null, 256, 192))
        assertEquals(0, rollThumbnailRotation(RollDngOrientation(6, 0, 0), 256, 192))
        assertEquals(0, rollThumbnailRotation(RollDngOrientation(6, 4000, 3000), 256, 150))
    }
}
