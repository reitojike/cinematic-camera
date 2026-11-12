package dev.cinematic.p0.photoroll

import org.junit.Assert.*
import org.junit.Test

class PhotoRollStateTest {
    @Test fun completedEditCannotReplaceAnInFlightOrUncertainSaveOwner() {
        for (mode in listOf(PhotoRollPreviewMode.STAGE_CLEAR, PhotoRollPreviewMode.MANUAL_TRIAL)) {
            for (phase in listOf(null, PhotoRollSavePhase.SAVING, PhotoRollSavePhase.UNCONFIRMED, PhotoRollSavePhase.HOLD))
                assertFalse(canBeginStrengthEdit(mode, true, false, phase))
            for (phase in listOf(PhotoRollSavePhase.READY, PhotoRollSavePhase.PUBLISHED)) {
                assertTrue(canBeginStrengthEdit(mode, true, false, phase))
                assertFalse(canBeginStrengthEdit(mode, true, true, phase))
                assertFalse(canBeginStrengthEdit(mode, false, false, phase))
            }
            assertEquals(mode == PhotoRollPreviewMode.MANUAL_TRIAL,
                canBeginStrengthEdit(mode, true, false, PhotoRollSavePhase.CANCELLED))
            assertFalse(canBeginStrengthEdit(mode, true, true, PhotoRollSavePhase.CANCELLED))
            assertFalse(canBeginStrengthEdit(mode, false, false, PhotoRollSavePhase.CANCELLED))
        }
        assertTrue(canBeginStrengthEdit(PhotoRollPreviewMode.PRIVATE_TRIAL, true, false, null))
        assertFalse(canBeginStrengthEdit(PhotoRollPreviewMode.PRIVATE_TRIAL, false, false, null))
    }

    @Test fun captureSaveRecordingFinalizeAndUnknownBlockEntry() {
        assertTrue(canOpenPhotoRoll(false, false, false, false))
        for (flag in 0..3) {
            val flags = List(4) { it == flag }
            assertFalse(canOpenPhotoRoll(flags[0], flags[1], flags[2], flags[3]))
        }
    }

    @Test fun selectingAnotherOriginalRejectsOldCompletion() {
        val first = PhotoRollSelection().select("content://media/1")
        val second = first.select("content://media/2")
        assertEquals(second, second.finish(first.ticket!!, true, "old"))
        assertEquals(PhotoRollPhase.CHECKING, second.phase)
        assertFalse(second.accepts(first.ticket!!))
    }

    @Test fun sameUriRetryIsStillANewRequest() {
        val first = PhotoRollSelection().select("content://media/1")
        val retry = first.select(first.source!!)
        assertEquals(retry, retry.rendering(first.ticket!!))
        assertEquals(retry, retry.finish(first.ticket!!, true, "old"))
        assertNotEquals(first.ticket, retry.ticket)
    }

    @Test fun backAndBackgroundInvalidateInFlightResults() {
        val selected = PhotoRollSelection().select("content://media/1")
        val ticket = selected.ticket!!
        for (stopped in listOf(selected.pause(), selected.clear())) {
            assertFalse(stopped.accepts(ticket))
            assertEquals(stopped, stopped.finish(ticket, true, "late"))
        }
        assertEquals(PhotoRollPhase.PAUSED, selected.pause().phase)
        assertEquals(PhotoRollPhase.EMPTY, selected.clear().phase)
    }

    @Test fun successRequiresCurrentTicketAndTerminalResultCannotBeOverwritten() {
        val checked = PhotoRollSelection().select("content://media/1")
        val ticket = checked.ticket!!
        val rendering = checked.rendering(ticket)
        assertEquals(PhotoRollPhase.RENDERING, rendering.phase)
        val done = rendering.finish(ticket, true, "preview only")
        assertEquals(PhotoRollPhase.DISPLAYED, done.phase)
        assertEquals(done, done.finish(ticket, false, "late failure"))
        assertEquals(done, done.rendering(ticket))
    }

    @Test fun unsupportedNeverBecomesDisplayedAndCanRetry() {
        val selected = PhotoRollSelection().select("content://media/1")
        val blocked = selected.finish(selected.ticket!!, false, "not connected")
        assertEquals(PhotoRollPhase.BLOCKED, blocked.phase)
        assertEquals("not connected", blocked.message)
        assertEquals(PhotoRollPhase.CHECKING, blocked.select(blocked.source!!).phase)
    }

    @Test fun filesOnlyAcceptLocalDeviceDocuments() {
        assertTrue(isLocalDngDocument("com.android.externalstorage.documents", "primary:Pictures/sample.dng"))
        // MediaDocuments can represent removable volumes too; require a primary document instead.
        assertFalse(isLocalDngDocument("com.android.providers.media.documents", "image:42"))
        assertFalse(isLocalDngDocument("com.android.externalstorage.documents", "ABCD-1234:sample.dng"))
        assertFalse(isLocalDngDocument("com.android.providers.media.documents", "video:42"))
        assertFalse(isLocalDngDocument("com.android.providers.media.documents", "image:../42"))
        assertFalse(isLocalDngDocument("cloud.example", "primary:sample.dng"))
        assertFalse(isLocalDngDocument(null, null))
    }
}
