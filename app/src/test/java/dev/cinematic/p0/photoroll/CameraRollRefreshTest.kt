package dev.cinematic.p0.photoroll

import org.junit.Assert.*
import org.junit.Test

class CameraRollRefreshTest {
    @Test fun refreshKeepsSameAuthorizedCollectionEvenAfterPagination() {
        val first = PhotoRollQuery(folderKey = "folder")
        val paged = first.copy(after = PhotoRollPageCursor(1, 2, "external_primary", false), includeFolders = false)
        assertTrue(canKeepCameraRollTiles(first, 3, first, 3))
        assertTrue(canKeepCameraRollTiles(paged, 3, first, 3))
    }

    @Test fun changedCollectionOrGrantCannotKeepOldTiles() {
        val first = PhotoRollQuery()
        assertFalse(canKeepCameraRollTiles(null, 3, first, 3))
        assertFalse(canKeepCameraRollTiles(first, 3, first, 4))
        assertFalse(canKeepCameraRollTiles(first, 3, first.copy(folderKey = "new"), 3))
        assertFalse(canKeepCameraRollTiles(first, 3, first.copy(kindFilter = PhotoRollKindFilter.VIDEOS), 3))
    }
}
