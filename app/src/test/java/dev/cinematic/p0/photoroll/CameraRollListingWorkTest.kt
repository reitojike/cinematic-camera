package dev.cinematic.p0.photoroll

import org.junit.Assert.*
import org.junit.Test

class CameraRollListingWorkTest {
    @Test fun lateFailureFromCancelledFolderCannotEraseNewPageAndFolders() {
        val work = CameraRollListingWork()
        val oldQuery = PhotoRollQuery(folderKey = "external_primary|Pictures/Old/")
        val newQuery = PhotoRollQuery(folderKey = "external_primary|Pictures/New/")
        val old = work.begin(oldQuery, 1)
        val current = work.begin(newQuery, 1)
        val visible = PhotoRollListing(listOf(PhotoRollItem("content://media/2", "new")), folders = listOf(PhotoRollFolder("new", "New")),
            hasMore = true, nextCursor = PhotoRollPageCursor(1, 2, "external_primary", false))
        var listing = PhotoRollListing(emptyList())
        assertTrue(work.commit(current, newQuery, 1, true, false) { listing = visible })
        // Even a non-cancellation exception from the old provider is not allowed to commit.
        assertFalse(work.commit(old, newQuery, 1, true, false) { listing = PhotoRollListing(emptyList(), "late failure") })
        work.finish(old)
        assertEquals(visible, listing)
        assertTrue(work.commit(current, newQuery, 1, true, false) { })
    }

    @Test fun grantChangeExitAndCancellationSuppressBothSuccessAndFailure() {
        val work = CameraRollListingWork()
        val query = PhotoRollQuery()
        val request = work.begin(query, 4)
        var commits = 0
        assertFalse(work.commit(request, query, 5, true, false) { commits++ })
        assertFalse(work.commit(request, query, 4, false, false) { commits++ })
        assertFalse(work.commit(request, query, 4, true, true) { commits++ })
        work.finish(request)
        assertFalse(work.commit(request, query, 4, true, false) { commits++ })
        assertEquals(0, commits)
    }
}
