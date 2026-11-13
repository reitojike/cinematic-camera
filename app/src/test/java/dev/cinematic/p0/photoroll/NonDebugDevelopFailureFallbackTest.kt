package dev.cinematic.p0.photoroll

import dev.cinematic.p0.*
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test

/** Runs on release/non-debug variants: malformed constructors must still fail in debug. */
class NonDebugDevelopFailureFallbackTest {
    @Before fun nonDebugOnly() { assumeFalse(BuildConfig.DEBUG) }
    private fun malformed(slot: MessageSlot, tone: CaptureChromeTone) =
        UiMessage(slot, tone, "", "invalid.private.fact", title = if (slot == MessageSlot.PROBLEM) "" else null)
    private fun state() = DevelopMessageState(PhotoRollPhase.DISPLAYED, PhotoRollSavePhase.READY)

    @Test fun malformedCriticalMessagesUseOnlyExistingBackAction() {
        for (invalid in listOf(malformed(MessageSlot.PROBLEM, CaptureChromeTone.FAILURE),
            malformed(MessageSlot.BANNER, CaptureChromeTone.FAILURE))) {
            for (canLeave in listOf(true, false)) {
                val reports = mutableListOf<String>()
                val result = developMessages(state().copy(canLeave = canLeave), listOf(invalid), reports::add)
                assertEquals("現像を続けられません", result.center?.title)
                assertEquals(MessageAction.BACK_TO_LIST, result.center?.primary?.action)
                assertEquals(canLeave, result.center?.primary?.enabled)
                assertNull(result.center?.secondary)
                assertTrue(reports.isNotEmpty())
                assertTrue(reports.none { it.contains("invalid.private.fact") })
            }
        }
    }

    @Test fun validHeldProblemWinsAndRetainsExistingRecoveryAuthority() {
        val held = state().copy(savePhase = PhotoRollSavePhase.HOLD, canRecover = false)
        val result = developMessages(held, listOf(malformed(MessageSlot.PROBLEM, CaptureChromeTone.FAILURE)), {})
        assertEquals("save.uncertain", result.center?.fact)
        assertEquals(MessageAction.RECHECK_SAVE, result.center?.primary?.action)
        assertFalse(result.center!!.primary!!.enabled)
        assertEquals(PhotoRollSavePhase.HOLD, held.savePhase)
    }

    @Test fun malformedOrdinaryMessageDoesNotCreateProblem() {
        val result = developMessages(state(), listOf(malformed(MessageSlot.BANNER, CaptureChromeTone.WARNING)), {})
        assertNull(result.center)
        assertNull(result.banner)
    }

    @Test fun malformedFailureBeatsNormalProgressWithoutCopyingItsActions() {
        val progress = UiMessage(MessageSlot.BANNER, CaptureChromeTone.WARNING, "保存中", "save.foreground_progress",
            primary = MessageControl(MessageAction.CANCEL_SAVE, "保存を取り消す"), inProgress = true)
        val result = developMessages(state(), listOf(progress,
            malformed(MessageSlot.BANNER, CaptureChromeTone.FAILURE)), {})
        assertEquals(MessageSlot.PROBLEM, result.center?.slot)
        assertEquals(MessageAction.BACK_TO_LIST, result.center?.primary?.action)
        assertNull(result.banner)
    }
}
