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
}
