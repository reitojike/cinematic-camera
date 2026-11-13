package dev.cinematic.p0.photoroll

import dev.cinematic.p0.*
import dev.cinematic.p0.rawcapture.DngImportItem
import dev.cinematic.p0.rawcapture.DngImportMessageKind
import dev.cinematic.p0.rawcapture.DngImportScreenState

internal fun importMessages(state: DngImportScreenState, preparationNotice: String?, awaitingSelection: Boolean): ScreenMessages =
    screenMessages(buildList {
        state.message?.takeIf { it.isNotBlank() }?.let {
            val tone = when (state.messageKind) {
                DngImportMessageKind.NORMAL -> CaptureChromeTone.NORMAL
                DngImportMessageKind.PROGRESS, DngImportMessageKind.WARNING -> CaptureChromeTone.WARNING
                DngImportMessageKind.FAILURE -> CaptureChromeTone.FAILURE
                DngImportMessageKind.ACCEPTED -> CaptureChromeTone.ACCEPTED
            }
            add(UiMessage(MessageSlot.INLINE, tone, it, "import.result"))
        }
        preparationNotice?.takeIf { it.isNotBlank() }?.let {
            add(UiMessage(MessageSlot.INLINE, CaptureChromeTone.FAILURE, it, "import.preparation_failure"))
        }
        if (awaitingSelection && !state.busy) add(UiMessage(MessageSlot.INLINE, CaptureChromeTone.WARNING,
            "取り込みの受付状態を確認中…", "import.awaiting_admission"))
    })

internal fun importItemLabel(index: Int, item: DngImportItem): String =
    "取り込み ${index + 1} · " + when {
        item.grantPending -> "権限の解放待ち"
        item.ready -> "完了"
        item.cancelled -> "中断"
        else -> "未完了"
    }
