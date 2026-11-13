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
        assertEquals(MessageAction.CANCEL_SAVE, result.banner?.primary?.action)
        assertEquals(MessageAction.ALLOW_NOTIFICATIONS, result.banner?.secondary?.action)
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
    @Test fun accessActionsSurviveEveryHigherPriorityBannerAndPreserveEnabledFlags() {
        val saves = listOf(save.copy(active = false), save, save.copy(stopping = true), save.copy(held = true))
        val accesses = listOf(access, access.copy(images = true), access.copy(videos = true), access.copy(selected = false))
        for (partial in accesses) for (saving in saves) for (notice in listOf(null, "一覧を開けませんでした")) {
            for (empty in listOf(false, true)) for (permission in listOf(false, true)) for (settings in listOf(false, true)) {
                val result = libraryMessages(partial, notice, null, empty, permission, true, settings,
                    foregroundSaveMessages(saving))
                val fallback = libraryAccessActions(partial, permission, settings, result.banner?.fact)
                val visible = listOfNotNull(result.banner?.primary, result.banner?.secondary) + fallback
                assertEquals(permission, visible.single { it.action == MessageAction.CHOOSE_AGAIN }.enabled)
                assertEquals(settings, visible.single { it.action == MessageAction.OPEN_SETTINGS }.enabled)
            }
        }
    }

    @Test fun accessFallbackDoesNotDuplicateVisibleAccessBannerOrInventFullAccessActions() {
        assertTrue(libraryAccessActions(access, true, true, "library.access_limited").isEmpty())
        assertTrue(libraryAccessActions(access.copy(images = true, videos = true), true, true, "library.failure").isEmpty())
    }

}
