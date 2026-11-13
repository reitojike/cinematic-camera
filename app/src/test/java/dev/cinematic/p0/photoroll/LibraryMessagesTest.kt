package dev.cinematic.p0.photoroll

import dev.cinematic.p0.*
import org.junit.Assert.*
import org.junit.Test

class LibraryMessagesTest {
    private val save = ForegroundSaveMessageState(true, true, false, false, true, false, true)
    private val access = CameraRollAccess(true, false, false, true)

    @Test fun unsupportedInactiveAndActiveOnlyIdleStayHidden() {
        assertTrue(foregroundSaveMessages(save.copy(supported = false, active = false)).isEmpty())
        assertTrue(foregroundSaveMessages(save.copy(active = false), activeOnly = true).isEmpty())
    }

    @Test fun saveProgressWinsOverNotificationWarningAndKeepsCancelGuard() {
        val result = screenMessages(foregroundSaveMessages(save))
        assertEquals("save.foreground_progress", result.banner?.fact)
        assertEquals(true, result.banner?.primary?.enabled)
        assertEquals(false, screenMessages(foregroundSaveMessages(save.copy(stopping = true))).banner?.primary?.enabled)
        assertEquals(false, screenMessages(foregroundSaveMessages(save.copy(foreground = false))).banner?.primary?.enabled)
    }

    @Test fun heldFailureWinsWithoutInventingAnotherCancellationCondition() {
        val result = screenMessages(foregroundSaveMessages(save.copy(held = true)))
        assertEquals(CaptureChromeTone.FAILURE, result.banner?.tone)
        assertEquals(true, result.banner?.primary?.enabled)
        assertFalse(result.banner!!.inProgress)
    }

    @Test fun idleNoticeRetainsNotificationActionAndBackgroundPolicy() {
        val result = screenMessages(foregroundSaveMessages(save.copy(active = false)))
        assertEquals(MessageAction.ALLOW_NOTIFICATIONS, result.banner?.primary?.action)
        assertEquals("save.background_policy", result.inline.single().fact)
    }

    @Test fun emptyStateAndSaveAreDistinctFacts() {
        val result = libraryMessages(access, null, null, true, true, true, true, foregroundSaveMessages(save))
        assertEquals("library.empty", result.center?.fact)
        assertEquals("save.foreground_progress", result.banner?.fact)
        assertEquals(MessageAction.OPEN_FILES, result.center?.secondary?.action)
    }

    @Test fun failureHasPriorityOverSavingAndAccessWarning() {
        val result = libraryMessages(access, "開けませんでした", "一覧補足", false, false, true, false,
            foregroundSaveMessages(save))
        assertEquals("library.failure", result.banner?.fact)
        assertEquals("一覧補足", result.inline.single().body)
        assertNull(result.center)
    }

    @Test fun permissionGuardsAndFullAccessArePreserved() {
        val result = libraryMessages(access, null, null, false, false, true, false)
        assertEquals(false, result.banner?.primary?.enabled)
        assertEquals(false, result.banner?.secondary?.enabled)
        assertNull(libraryMessages(access.copy(images = true, videos = true), null, null,
            false, true, true, true).banner)
    }
}
