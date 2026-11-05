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
        assertEquals(CameraRollKind.RAW, cameraRollKind("original.ARW", "application/octet-stream", false))
        assertEquals(CameraRollKind.RAW, cameraRollKind("original", "image/x-nikon-nef", false))
        assertEquals(CameraRollKind.PHOTO, cameraRollKind("photo.jpg", "image/jpeg", false))
        assertEquals(CameraRollKind.VIDEO, cameraRollKind("video.mp4", "video/mp4", true))
        assertNull(cameraRollKind("not-a-photo.txt", "text/plain", false))
        assertNull(cameraRollKind("mislabelled.dng", "image/x-adobe-dng", true))
    }

    @Test fun mergedPageUsesNumericIdsAndStableCrossCollectionVolumeTies() {
        fun item(id: Long, video: Boolean, volume: String = "external_primary", missing: Boolean = false) = PhotoRollItem(
            "content://media/$volume/${if (video) "video" else "images"}/media/$id", "synthetic",
            if (video) CameraRollKind.VIDEO else CameraRollKind.PHOTO, addedSeconds = 100,
            pageCursor = PhotoRollPageCursor(100, id, volume, video, missing))
        val rows = listOf(item(9, false), item(100, true), item(100, false), item(100, false, "abcd-1234"), item(200, false, missing = true))
        val page = cameraRollPage(rows, 3, emptyList())
        assertEquals(listOf(rows[3], rows[2], rows[1]), page.items)
        assertTrue(page.hasMore)
        assertEquals(rows[1].pageCursor, page.nextCursor)
        assertEquals(page, cameraRollPage(rows.reversed(), 3, emptyList()))
        val final = cameraRollPage(listOf(rows[0], rows[4]), 3, emptyList())
        assertFalse(final.hasMore); assertNull(final.nextCursor)
        assertEquals(rows[4], final.items.last())
    }

    @Test fun queryBindsFolderAndKindBeforeSharedStrictPageBoundary() {
        val cursor = PhotoRollPageCursor(100, 9, "external_primary", false)
        val query = PhotoRollQuery("external_primary|Pictures/quote'_%/", PhotoRollKindFilter.RAW, 3, cursor)
        val image = requireNotNull(cameraRollSql(query, false, 37))
        assertFalse(image.selection.contains("owner_package_name"))
        assertFalse(image.selection.contains("quote"))
        assertTrue(image.selection.contains("is_trashed = 0"))
        assertTrue(image.arguments.contains("Pictures/quote'_%/"))
        assertTrue(image.arguments.contains("%.dng"))
        assertEquals(image.selection.count { it == '?' }, image.arguments.size)
        assertNull(cameraRollSql(query, true, 37))
        val video = requireNotNull(cameraRollSql(PhotoRollQuery(after = cursor), true, 29))
        assertFalse(video.selection.contains("is_trashed"))
        assertTrue(video.selection.contains("volume_name = ? AND 1"))
        assertEquals(video.selection.count { it == '?' }, video.arguments.size)
        assertThrows(IllegalArgumentException::class.java) { PhotoRollQuery(pageSize = 0) }
    }

    @Test fun showsVideoDurationWithoutInventingAFrameRateOrRoundingUp() {
        assertEquals("0:00", cameraRollDuration(-1))
        assertEquals("1:02", cameraRollDuration(62_999))
        assertEquals("1:01:01", cameraRollDuration(3_661_000))
    }
}
