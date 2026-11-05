package dev.cinematic.p0.photoroll

import org.junit.Assert.*
import org.junit.Test

class CameraRollMediaTest {
    @Test fun backBeforeLifecyclePauseRejectsLateViewerPreparation() {
        val navigation = CameraRollNavigation()
        assertTrue(navigation.canOpenViewer(foreground = true, cancelled = false))
        navigation.exit()
        // Provider completion may already be posted to main; cancellation/ON_PAUSE need not have arrived.
        assertFalse(navigation.canOpenViewer(foreground = true, cancelled = false))
        assertFalse(CameraRollNavigation().canOpenViewer(foreground = false, cancelled = false))
        assertFalse(CameraRollNavigation().canOpenViewer(foreground = true, cancelled = true))
    }

    @Test fun routesDngPhotosAndVideosWithoutTreatingAThumbnailAsDecodeSupport() {
        assertEquals(CameraRollKind.DNG, cameraRollKind("original.DNG", "application/octet-stream", false))
        assertEquals(CameraRollKind.DNG, cameraRollKind("original", "image/x-adobe-dng", false))
        assertEquals(CameraRollKind.PHOTO, cameraRollKind("photo.jpg", "image/jpeg", false))
        assertEquals(CameraRollKind.VIDEO, cameraRollKind("video.mp4", "video/mp4", true))
        assertNull(cameraRollKind("not-a-photo.txt", "text/plain", false))
        assertNull(cameraRollKind("mislabelled.dng", "image/x-adobe-dng", true))
    }

    @Test fun mergedCollectionsKeepDistinctUrisAndOneSharedTwoHundredItemLimit() {
        val images = (1..150).map { PhotoRollItem("content://media/external_primary/images/media/$it", "image",
            CameraRollKind.PHOTO, "image/jpeg", addedSeconds = it.toLong()) }
        val videos = (1..150).map { PhotoRollItem("content://media/external_primary/video/media/$it", "video",
            CameraRollKind.VIDEO, "video/mp4", addedSeconds = it.toLong()) }
        val result = newestCameraRollItems(images + videos)
        assertEquals(200, result.size)
        assertEquals(200, result.map { it.uri }.distinct().size)
        assertTrue(result.all { it.addedSeconds >= 51 })
        assertEquals(newestCameraRollItems(videos + images), result)
        assertTrue(result.zipWithNext().all { (a, b) -> a.addedSeconds >= b.addedSeconds })
    }

    @Test fun showsVideoDurationWithoutInventingAFrameRateOrRoundingUp() {
        assertEquals("0:00", cameraRollDuration(-1))
        assertEquals("1:02", cameraRollDuration(62_999))
        assertEquals("1:01:01", cameraRollDuration(3_661_000))
    }
}
