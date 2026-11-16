package dev.cinematic.p0.photoroll

import dev.cinematic.p0.*
import dev.cinematic.p0.saveprogress.*
import org.junit.Assert.*
import org.junit.Test

class SaveProgressMessagesTest {
    private val active = ForegroundSaveMessageState(true, true, false, false, true, true, true)
    @Test fun fourPhasesUseExistingBannerAndOnlyDevelopmentIsDeterminate() {
        val labels = listOf("保存の準備中", "画像を現像中", "JPEGを作成中", "保存しています")
        SaveProgressPhase.entries.forEachIndexed { index, phase ->
            val message = screenMessages(foregroundSaveMessages(active.copy(progress = SaveProgress(phase,
                if (phase == SaveProgressPhase.DEVELOPING) .42f else null)))).banner!!
            assertEquals(labels[index], message.body)
            assertEquals("アプリを離れても続きます。", message.note)
            assertEquals("save.foreground_progress", message.fact)
            assertEquals(CaptureChromeTone.WARNING, message.tone)
            assertEquals(MessageAction.CANCEL_SAVE, message.primary?.action)
            assertNotNull(message.progress)
            assertEquals(if (phase == SaveProgressPhase.DEVELOPING) .42f else null, message.progress?.fraction)
            assertFalse(message.body.contains("%"))
        }
    }
    @Test fun cancelAndHoldOverrideLatestBarImmediatelyWithoutChangingCancelEligibility() {
        val developing = active.copy(progress = SaveProgress(SaveProgressPhase.DEVELOPING, .999f))
        val stopping = screenMessages(foregroundSaveMessages(developing.copy(stopping = true))).banner!!
        assertEquals("保存を止めています", stopping.body)
        assertFalse(stopping.primary!!.enabled)
        assertNull(stopping.progress)
        assertNull(stopping.note)
        val held = screenMessages(foregroundSaveMessages(developing.copy(held = true))).banner!!
        assertEquals(CaptureChromeTone.FAILURE, held.tone)
        assertTrue(held.primary!!.enabled)
        assertNull(held.progress)
    }
    @Test fun stoppingCenterReflectsTheReadOnlySaveSnapshot() {
        val state = DevelopMessageState(PhotoRollPhase.DISPLAYED,
            PhotoRollSavePhase.SAVING, exportPaused = true, saveStopping = true)
        val result = developMessages(state, foregroundSaveMessages(active.copy(stopping = true)))
        assertEquals("保存を取り消しています · プレビューを止めています", result.center?.body)
        assertNull(result.banner?.progress)
        assertEquals("保存を止めています", result.banner?.body)
        assertFalse(result.banner!!.primary!!.enabled)
    }
    @Test fun terminalErrorAndCancellationNeverRenderACompletedBar() {
        for (phase in listOf(PhotoRollSavePhase.CANCELLED, PhotoRollSavePhase.GPU_FAILED,
            PhotoRollSavePhase.UNCONFIRMED, PhotoRollSavePhase.HOLD)) {
            val result = developMessages(DevelopMessageState(PhotoRollPhase.DISPLAYED, phase, exportPaused = true),
                foregroundSaveMessages(active.copy(active = false), activeOnly = true))
            assertNull(result.banner?.progress)
            assertNull(result.center?.progress)
            assertNull(result.toast)
        }
        val finished = screenMessages(foregroundSaveMessages(active.copy(
            progress = SaveProgress(SaveProgressPhase.SAVING, running = false)))).banner!!
        assertNull(finished.progress)
    }
    @Test fun progressCannotTurnIntoSaveSuccessAndPreviewPauseIsSeparate() {
        val result = developMessages(DevelopMessageState(PhotoRollPhase.DISPLAYED,
            PhotoRollSavePhase.SAVING, exportPaused = true), foregroundSaveMessages(active.copy(
                progress = SaveProgress(SaveProgressPhase.SAVING))))
        assertEquals("preview.export_paused", result.center?.fact)
        assertEquals("save.foreground_progress", result.banner?.fact)
        assertNull(result.toast)
    }
}
