package dev.cinematic.p0.photoroll

import dev.cinematic.p0.CaptureChromeTone
import dev.cinematic.p0.rawcapture.*
import org.junit.Assert.*
import org.junit.Test

class ImportMessagesTest {
    @Test fun displayKindNotFreeTextDeterminesTone() {
        val tones = mapOf(DngImportMessageKind.NORMAL to CaptureChromeTone.NORMAL,
            DngImportMessageKind.PROGRESS to CaptureChromeTone.WARNING,
            DngImportMessageKind.WARNING to CaptureChromeTone.WARNING,
            DngImportMessageKind.FAILURE to CaptureChromeTone.FAILURE,
            DngImportMessageKind.ACCEPTED to CaptureChromeTone.ACCEPTED)
        for ((kind, tone) in tones) {
            val result = importMessages(DngImportScreenState(message = "同じ表示文", messageKind = kind), null, false)
            assertEquals(tone, result.inline.single().tone)
        }
    }

    @Test fun preparationAndPendingSelectionRemainDistinct() {
        val result = importMessages(DngImportScreenState(), "受付を確認できません", true)
        assertEquals(2, result.inline.size)
        assertEquals(CaptureChromeTone.FAILURE, result.inline.first().tone)
        assertEquals(CaptureChromeTone.WARNING, result.inline.last().tone)
        assertTrue(importMessages(DngImportScreenState(busy = true), null, true).inline.isEmpty())
    }

    @Test fun terminalStateDoesNotHidePendingGrant() {
        assertEquals("取り込み 1 · 権限の解放待ち", importItemLabel(0, DngImportItem("synthetic", true, false, true)))
        assertEquals("取り込み 2 · 完了", importItemLabel(1, DngImportItem("synthetic", true, false, false)))
        assertEquals("取り込み 3 · 中断", importItemLabel(2, DngImportItem("synthetic", false, true, false)))
        assertEquals("取り込み 4 · 未完了", importItemLabel(3, DngImportItem("synthetic", false, false, false)))
    }
}
