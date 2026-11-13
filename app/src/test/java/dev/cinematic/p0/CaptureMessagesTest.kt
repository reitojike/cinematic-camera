package dev.cinematic.p0

import org.junit.Assert.*
import org.junit.Test

class CaptureMessagesTest {
    private fun message(slot: MessageSlot, tone: CaptureChromeTone = CaptureChromeTone.NORMAL,
        fact: String = slot.name, progress: Boolean = false) = UiMessage(
        slot, tone, "利用者向けの説明", fact,
        title = if (slot == MessageSlot.PROBLEM) "続行できません" else null,
        inProgress = progress,
    )

    private val problem = message(MessageSlot.PROBLEM, CaptureChromeTone.FAILURE)
    private val failure = message(MessageSlot.BANNER, CaptureChromeTone.FAILURE, "failure")
    private val progress = message(MessageSlot.BANNER, CaptureChromeTone.WARNING, "progress", true)
    private val warning = message(MessageSlot.BANNER, CaptureChromeTone.WARNING, "warning")
    private val toast = message(MessageSlot.TOAST, CaptureChromeTone.ACCEPTED)

    @Test fun everyPriorityPairWinsRegardlessOfCandidateOrder() {
        val priority = listOf(problem, failure, progress, warning, toast)
        for (high in priority.indices) for (low in high + 1 until priority.size) {
            for (candidates in listOf(listOf(priority[high], priority[low]), listOf(priority[low], priority[high]))) {
                val result = screenMessages(candidates)
                assertEquals(listOf(priority[high]), listOfNotNull(result.center, result.banner, result.toast))
            }
        }
    }

    @Test fun persistentImageDescriptionAndChipSurviveEveryPriority() {
        val chip = message(MessageSlot.CHIP, fact = "mode")
        val caption = message(MessageSlot.CAPTION, fact = "image")
        for (transient in listOf(problem, failure, progress, warning, toast)) {
            val result = screenMessages(listOf(chip, caption, transient))
            assertEquals(listOf(chip), result.chips)
            assertEquals(caption, result.caption)
        }
    }

    @Test fun problemReplacesEmptyCenterAndSuppressesBannerAndToast() {
        val result = screenMessages(listOf(message(MessageSlot.CENTER), warning, toast, problem))
        assertEquals(problem, result.center)
        assertNull(result.banner)
        assertNull(result.toast)
    }

    @Test fun separateCenterRemainsButRepeatedFactDoesNot() {
        val repeated = message(MessageSlot.CENTER, fact = progress.fact)
        assertNull(screenMessages(listOf(repeated, progress)).center)
        val empty = message(MessageSlot.CENTER, fact = "empty")
        assertEquals(empty, screenMessages(listOf(empty, progress)).center)
        assertEquals(listOf(progress.copy(slot = MessageSlot.CHIP, inProgress = false)),
            screenMessages(listOf(progress, progress.copy(slot = MessageSlot.CHIP, inProgress = false))).chips)
    }

    @Test fun inlineFactsAreUniqueAndDoNotRepeatTheProminentMessage() {
        val inline = message(MessageSlot.INLINE, fact = "setting")
        assertEquals(listOf(inline), screenMessages(listOf(warning,
            message(MessageSlot.INLINE, fact = warning.fact), inline, inline)).inline)
    }

    @Test fun samePriorityPreservesScreenOrderingWithoutMutatingInput() {
        val second = warning.copy(body = "別の注意", fact = "second")
        val input = listOf(warning, second)
        assertEquals(warning, screenMessages(input).banner)
        assertEquals(second, screenMessages(input.reversed()).banner)
        assertEquals(listOf(warning, second), input)
        assertEquals(ScreenMessages(), screenMessages(emptyList()))
    }

    @Test fun invalidToneAndActionContractsAreRejected() {
        for (slot in MessageSlot.entries) {
            assertThrows(IllegalArgumentException::class.java) { message(slot, CaptureChromeTone.RECORDING) }
        }
        assertThrows(IllegalArgumentException::class.java) { message(MessageSlot.TOAST, CaptureChromeTone.FAILURE) }
        assertThrows(IllegalArgumentException::class.java) { message(MessageSlot.PROBLEM) }
        assertThrows(IllegalArgumentException::class.java) { message(MessageSlot.BANNER) }
        assertThrows(IllegalArgumentException::class.java) { warning.copy(inProgress = true, tone = CaptureChromeTone.FAILURE) }
        val action = MessageControl(MessageAction.RETRY, "再試行")
        for (slot in listOf(MessageSlot.CHIP, MessageSlot.CAPTION, MessageSlot.TOAST)) {
            assertThrows(IllegalArgumentException::class.java) { message(slot).copy(primary = action) }
        }
        assertThrows(IllegalArgumentException::class.java) { warning.copy(secondary = action) }
        assertThrows(IllegalArgumentException::class.java) { message(MessageSlot.INLINE).copy(primary = action, secondary = action) }
    }

    @Test fun assemblerRetainsActionStateAndUserCancellationWarning() {
        val action = MessageControl(MessageAction.RESUME_PREVIEW, "プレビューを再開", enabled = false,
            description = "プレビューを再開", stateDescription = "終了を確認中")
        val cancelled = warning.copy(body = "保存を取り消しました。", primary = action)
        assertEquals(cancelled, screenMessages(listOf(cancelled, toast)).banner)
        assertEquals(CaptureChromeTone.WARNING, cancelled.tone)
        assertFalse(cancelled.primary!!.enabled)
    }

    @Test fun captionCannotDisguiseDuplicateStatusAsImageDescription() {
        assertThrows(IllegalArgumentException::class.java) {
            screenMessages(listOf(warning, message(MessageSlot.CAPTION, fact = warning.fact)))
        }
    }

    @Test fun releaseSuppressesDuplicateCaptionAndChipAndRecordsOnlyReason() {
        val records = mutableListOf<String>()
        val center = message(MessageSlot.CENTER, fact = "image")
        val result = screenMessages(listOf(center,
            message(MessageSlot.CAPTION, fact = "image"),
            message(MessageSlot.CHIP, fact = "image")), strict = false, report = records::add)
        assertEquals(center, result.center)
        assertNull(result.caption)
        assertTrue(result.chips.isEmpty())
        assertEquals(listOf("duplicate_caption", "duplicate_chip"), records)
    }

    @Test fun releaseContractRecordsButDebugContractThrows() {
        val records = mutableListOf<String>()
        assertFalse(messageContract(false, "invalid_message", false, records::add))
        assertEquals(listOf("invalid_message"), records)
        assertThrows(IllegalArgumentException::class.java) {
            messageContract(false, "invalid_message", true, records::add)
        }
        assertTrue(messageContract(true, "unused", false, records::add))
        assertEquals(1, records.size)
    }

    @Test fun designTokensRetainExistingTypeAndCentralizeOverlayOpacity() {
        assertEquals(CaptureDesign.Explanation, CaptureDesign.Body)
        assertEquals(.9f, CaptureDesign.Scrim.alpha, .005f)
        assertEquals(.7f, CaptureDesign.ScrimLight.alpha, .005f)
        assertEquals(.08f, CaptureDesign.messageTint(CaptureChromeTone.WARNING).alpha, .005f)
    }
}
