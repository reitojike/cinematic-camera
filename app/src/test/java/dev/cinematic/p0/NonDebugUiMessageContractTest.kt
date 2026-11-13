package dev.cinematic.p0

import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeFalse

/** Runs in existing non-debug diagnostic variants; debug verifies strict validation separately. */
class NonDebugUiMessageContractTest {
    @Test fun malformedMessageConstructionDoesNotCrashAndAssemblerDropsIt() {
        assumeFalse(BuildConfig.DEBUG)
        val malformed = UiMessage(MessageSlot.PROBLEM, CaptureChromeTone.NORMAL,
            "", "", primary = MessageControl(MessageAction.RETRY, ""))
        val records = mutableListOf<String>()
        assertEquals(ScreenMessages(), screenMessages(listOf(malformed), report = records::add))
        assertEquals(listOf("invalid_message"), records)
    }
    @Test fun invalidFailuresUseOnlyScreenProvidedSafeFallback() {
        assumeFalse(BuildConfig.DEBUG)
        val records = mutableListOf<String>()
        val fallback = UiMessage(MessageSlot.PROBLEM, CaptureChromeTone.FAILURE,
            "一覧に戻ってください。", "screen.fallback", title = "続行できません",
            primary = MessageControl(MessageAction.BACK_TO_LIST, "一覧に戻る", enabled = false))
        val brokenProblem = UiMessage(MessageSlot.PROBLEM, CaptureChromeTone.FAILURE,
            "broken sensitive text", "broken", title = null,
            primary = MessageControl(MessageAction.CONFIRM, "unsafe old action"))
        val brokenFailure = UiMessage(MessageSlot.BANNER, CaptureChromeTone.FAILURE, "", "broken.failure")
        val progress = UiMessage(MessageSlot.BANNER, CaptureChromeTone.WARNING, "処理中", "progress", inProgress = true)
        val actualProblem = fallback.copy(fact = "actual", title = "読み込めません")
        val replaced = screenMessages(listOf(brokenProblem, brokenFailure, progress), report = records::add,
            failureFallback = fallback)
        assertEquals(fallback, replaced.center)
        assertNull(replaced.banner)
        assertEquals(MessageAction.BACK_TO_LIST, replaced.center!!.primary!!.action)
        assertFalse(replaced.center.primary!!.enabled)
        assertEquals(listOf("invalid_message", "invalid_message"), records)
        assertEquals(actualProblem, screenMessages(listOf(brokenProblem, actualProblem),
            report = {}, failureFallback = fallback).center)
        assertEquals(fallback, screenMessages(listOf(brokenFailure, progress),
            report = {}, failureFallback = fallback).center)
        val invalidNormal = UiMessage(MessageSlot.TOAST, CaptureChromeTone.NORMAL, "", "notice")
        assertEquals(ScreenMessages(), screenMessages(listOf(invalidNormal), report = {}, failureFallback = fallback))
        assertEquals(progress, screenMessages(listOf(progress), report = {}, failureFallback = fallback).banner)
    }
}
