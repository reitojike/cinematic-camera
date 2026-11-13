package dev.cinematic.p0.photoroll

import dev.cinematic.p0.*
import org.junit.Assert.*
import org.junit.Test

class DevelopMessagesTest {
    private fun state(phase: PhotoRollSavePhase? = PhotoRollSavePhase.READY) =
        DevelopMessageState(PhotoRollPhase.DISPLAYED, savePhase = phase)

    @Test fun allSavePhasesHaveDistinctProductPresentation() {
        val labels = mapOf(
            PhotoRollSavePhase.READY to "未保存", PhotoRollSavePhase.SAVING to "保存中",
            PhotoRollSavePhase.PUBLISHED to "保存済み", PhotoRollSavePhase.CANCELLED to "保存取消",
            PhotoRollSavePhase.UNCONFIRMED to "保存未確認", PhotoRollSavePhase.GPU_FAILED to "保存失敗",
            PhotoRollSavePhase.HOLD to "保存保留")
        labels.forEach { (phase, label) -> assertEquals(label, developSavePresentation(state(phase)).label) }
        assertEquals("保存準備中", developSavePresentation(state(null)).label)
        assertEquals(CaptureChromeTone.WARNING, developSavePresentation(state(PhotoRollSavePhase.CANCELLED)).tone)
    }

    @Test fun recipePersistenceIsNotJpegSaving() {
        val result = developSavePresentation(state().copy(savingRecipe = true))
        assertEquals("設定保存中", result.label)
        assertEquals("調整設定を保存中", result.description)
        assertEquals("調整中", developSavePresentation(state().copy(editing = true)).label)
    }

    @Test fun saveProgressAndPausedPreviewAreDifferentCoexistingFacts() {
        val foreground = UiMessage(MessageSlot.BANNER, CaptureChromeTone.WARNING,
            "JPEGを保存中です。アプリを離れても続きます。", "save.foreground_progress",
            primary = MessageControl(MessageAction.CANCEL_SAVE, "保存を取り消す"), inProgress = true)
        val result = developMessages(state(PhotoRollSavePhase.SAVING).copy(exportPaused = true), listOf(foreground))
        assertEquals("保存中 · プレビューを止めています", result.center?.body)
        assertEquals("preview.export_paused", result.center?.fact)
        assertEquals("save.foreground_progress", result.banner?.fact)
        assertEquals(MessageAction.CANCEL_SAVE, result.banner?.primary?.action)
    }

    @Test fun cancelledSaveKeepsResumeActionAndWarning() {
        val result = developMessages(state(PhotoRollSavePhase.CANCELLED).copy(exportPaused = true, canResumePreview = true))
        assertEquals(CaptureChromeTone.WARNING, result.banner?.tone)
        assertEquals(MessageAction.RESUME_PREVIEW, result.banner?.primary?.action)
        assertEquals(true, result.banner?.primary?.enabled)
        assertNull(result.center?.primary) // no duplicate resume action
    }

    @Test fun saveFailureDoesNotTurnInputRetryIntoSaveRetry() {
        val result = developMessages(state(PhotoRollSavePhase.GPU_FAILED).copy(exportPaused = true))
        assertEquals(MessageSlot.PROBLEM, result.center?.slot)
        assertEquals(MessageAction.RESUME_PREVIEW, result.center?.primary?.action)
        assertEquals(false, result.center?.primary?.enabled)
        assertEquals("同じ設定で保存を再試行", developSavePresentation(state(PhotoRollSavePhase.GPU_FAILED)).description)
    }

    @Test fun uncertainPublicationAlwaysKeepsExplicitRecoveryButDoesNotGrantIt() {
        for (phase in listOf(PhotoRollSavePhase.HOLD, PhotoRollSavePhase.UNCONFIRMED)) {
            val result = developMessages(state(phase).copy(canRecover = false))
            val recovery = result.center ?: result.banner
            assertEquals(MessageAction.RECHECK_SAVE, recovery?.primary?.action)
            assertEquals(false, recovery?.primary?.enabled)
            val enabled = developMessages(state(phase).copy(canRecover = true))
            assertEquals(true, (enabled.center ?: enabled.banner)?.primary?.enabled)
        }
    }

    private fun memory() = UiMessage(MessageSlot.PROBLEM, CaptureChromeTone.FAILURE,
        "メモリ不足。原本は変更していません。", "develop.memory", title = "メモリ不足",
        primary = MessageControl(MessageAction.RETRY, "再試行", false))

    @Test fun memoryCopyNormalizesHeadingAndNoteWithoutChangingAction() {
        val original = memory()
        val result = developMessages(state().copy(memoryProblem = original)).center!!
        assertEquals("処理を続けられません", result.title)
        assertTrue(result.body.contains("メモリ"))
        assertFalse(result.body.contains("原本"))
        assertEquals("原本は変更していません。", result.note)
        assertEquals(original.primary, result.primary)
    }

    @Test fun memoryFailureCannotReplaceUncertainPublicationRecovery() {
        for (phase in listOf(PhotoRollSavePhase.HOLD, PhotoRollSavePhase.UNCONFIRMED)) {
            val result = developMessages(state(phase).copy(memoryProblem = memory(), canRecover = true))
            assertEquals("save.uncertain", (result.center ?: result.banner)?.fact)
        }
    }

    @Test fun recheckingIsNotWritingAndDoesNotShowResaveAction() {
        val result = developMessages(state(PhotoRollSavePhase.UNCONFIRMED)
            .copy(rechecking = true, saving = true, exportPaused = true))
        assertEquals("保存結果を確認しています。", result.banner?.body)
        assertNull(result.banner?.primary)
        assertEquals("保存のためプレビューを止めました", result.center?.body)
        assertEquals("保存照合中", developSavePresentation(state().copy(rechecking = true)).label)
    }

    @Test fun restoredPublicationIsNotNewSuccess() {
        assertNull(developMessages(state(PhotoRollSavePhase.PUBLISHED)).toast)
        assertNotNull(developMessages(state(PhotoRollSavePhase.PUBLISHED).copy(justPublished = true)).toast)
        assertNull(developMessages(state(PhotoRollSavePhase.READY).copy(justPublished = true)).toast)
    }

    @Test fun backgroundAndDisabledFlagsAreNotRecomputedIntoEligibility() {
        val result = developMessages(state().copy(exportPaused = true, foreground = false, canResumePreview = false))
        assertEquals(false, result.center?.primary?.enabled)
        val blocked = developMessages(state().copy(phase = PhotoRollPhase.BLOCKED, retryEnabled = false))
        assertEquals(false, blocked.center?.primary?.enabled)
    }

    @Test fun pureAssemblerDoesNotMutateForegroundCandidates() {
        val candidates = mutableListOf(UiMessage(MessageSlot.INLINE, CaptureChromeTone.WARNING,
            "通知はオフです。", "save.notifications_disabled"))
        val snapshot = candidates.toList()
        assertEquals(developMessages(state(), candidates), developMessages(state(), candidates))
        assertEquals(snapshot, candidates)
    }

    private fun otherPhotoSaving() = UiMessage(MessageSlot.BANNER, CaptureChromeTone.WARNING,
        "JPEGを保存中です。アプリを離れても続きます。", "save.foreground_progress",
        primary = MessageControl(MessageAction.CANCEL_SAVE, "保存を取り消す"), inProgress = true)

    @Test fun cancellationBannerKeepsSingleVisibleResumeActionAfterSelection() {
        val result = developMessages(state(PhotoRollSavePhase.CANCELLED)
            .copy(exportPaused = true, canResumePreview = true))
        assertEquals("save.cancelled", result.banner?.fact)
        assertEquals(MessageAction.RESUME_PREVIEW, result.banner?.primary?.action)
        assertEquals(true, result.banner?.primary?.enabled)
        assertEquals("preview.export_paused", result.center?.fact)
        assertNull(result.center?.primary)
    }

    @Test fun otherPhotoProgressCannotHideCancelledPhotoResume() {
        val result = developMessages(state(PhotoRollSavePhase.CANCELLED)
            .copy(exportPaused = true, canResumePreview = true), listOf(otherPhotoSaving()))
        assertEquals("save.foreground_progress", result.banner?.fact)
        assertEquals(MessageAction.CANCEL_SAVE, result.banner?.primary?.action)
        assertEquals(MessageAction.RESUME_PREVIEW, result.center?.primary?.action)
        assertEquals(true, result.center?.primary?.enabled)
    }

    @Test fun otherPhotoProgressDoesNotEnableIneligibleResume() {
        val result = developMessages(state(PhotoRollSavePhase.CANCELLED)
            .copy(exportPaused = true, canResumePreview = false), listOf(otherPhotoSaving()))
        assertEquals("save.foreground_progress", result.banner?.fact)
        assertEquals(MessageAction.RESUME_PREVIEW, result.center?.primary?.action)
        assertEquals(false, result.center?.primary?.enabled)
    }

    @Test fun selectedActiveSaveBannerKeepsNotificationActionInEveryForegroundState() {
        for ((held, stopping) in listOf(false to false, false to true, true to false)) {
            for (foreground in listOf(true, false)) {
                val candidates = foregroundSaveMessages(ForegroundSaveMessageState(
                    supported = true, active = true, held = held, stopping = stopping,
                    foreground = foreground, notificationsAllowed = false, canRequestNotifications = true,
                ), activeOnly = true)
                val selected = developMessages(state(PhotoRollSavePhase.SAVING).copy(exportPaused = true), candidates)
                assertEquals("save.foreground_progress", selected.banner?.fact)
                assertEquals(MessageAction.ALLOW_NOTIFICATIONS, selected.banner?.secondary?.action)
                assertEquals(foreground, selected.banner?.secondary?.enabled)
                assertEquals(MessageAction.CANCEL_SAVE, selected.banner?.primary?.action)
                assertEquals(foreground && !stopping, selected.banner?.primary?.enabled)
            }
        }
    }
}
