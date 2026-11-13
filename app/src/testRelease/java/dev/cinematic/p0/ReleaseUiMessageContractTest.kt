package dev.cinematic.p0

import org.junit.Assert.*
import org.junit.Test

/** Exercises the actual release BuildConfig, not only an injected policy in a debug test. */
class ReleaseUiMessageContractTest {
    @Test fun malformedMessageConstructionDoesNotCrashAndAssemblerDropsIt() {
        assertFalse(BuildConfig.DEBUG)
        val malformed = UiMessage(MessageSlot.PROBLEM, CaptureChromeTone.NORMAL,
            "", "", primary = MessageControl(MessageAction.RETRY, ""))
        val records = mutableListOf<String>()
        assertEquals(ScreenMessages(), screenMessages(listOf(malformed), report = records::add))
        assertEquals(listOf("invalid_message"), records)
    }
}
