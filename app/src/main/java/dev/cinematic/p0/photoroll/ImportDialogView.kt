package dev.cinematic.p0.photoroll

import androidx.compose.foundation.layout.*
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.cinematic.p0.*
import dev.cinematic.p0.rawcapture.DngImportScreenState

/** Rendering only; callbacks and eligibility are supplied from the existing import controller. */
@Composable
internal fun ImportDialogView(state: DngImportScreenState, preparationNotice: String?, awaitingSelection: Boolean,
    pickerPending: Boolean, foreground: Boolean, close: () -> Unit, select: () -> Unit,
    cancelPreparation: () -> Unit, cancel: () -> Unit, resume: (String) -> Unit, cancelItem: (String) -> Unit) {
    val messages = importMessages(state, preparationNotice, awaitingSelection)
    CaptureDialog(
        MessageDialog("DNGを端末に取り込む", "DNGを共有フォルダへコピーしてから開きます。",
            close = MessageControl(MessageAction.CLOSE, "閉じる", !state.busy && !pickerPending),
            confirm = MessageControl(MessageAction.CONFIRM, "DNGを選ぶ・選び直す",
                foreground && !state.busy && !pickerPending && !awaitingSelection)),
        onDismissRequest = { if (!state.busy && !pickerPending) close() },
        onAction = { when (it) { MessageAction.CLOSE -> close(); MessageAction.CONFIRM -> select(); else -> Unit } },
    ) {
        CaptureInlineNote(UiMessage(MessageSlot.INLINE, CaptureChromeTone.NORMAL,
            "64MiB以下のDNGを共有フォルダへ変更せずコピーします。完了するまでSDを抜かないでください。",
            "import.copy_explanation"), {})
        CaptureInlineNote(UiMessage(MessageSlot.INLINE, CaptureChromeTone.NORMAL,
            "Google Photos等の設定によりバックアップされる場合があります。現像できないDNGも取り込み済み原本は保持します。",
            "import.backup_explanation"), {})
        messages.inline.forEach { CaptureInlineNote(it, {}) }
        if (awaitingSelection && !state.busy) {
            CaptureTextAction(MessageControl(MessageAction.CANCEL_SAVE, "取り込みを取り消す"), { cancelPreparation() })
        }
        if (state.busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = CaptureDesign.Amber, trackColor = CaptureDesign.Line)
            CaptureTextAction(MessageControl(MessageAction.CANCEL_SAVE, "取り込みを取り消す"),
                { cancel() })
        }
        state.items.forEachIndexed { index, item ->
            Column(Modifier.fillMaxWidth()) {
                Text(importItemLabel(index, item), style = CaptureDesign.Body, color = CaptureDesign.Text)
                FlowRow {
                    if (!item.cancelled) CaptureTextAction(
                        MessageControl(MessageAction.CONFIRM, if (item.ready) "編集へ" else "再開", foreground && !state.busy),
                        { resume(item.id) })
                    if (item.grantPending || !item.ready) CaptureTextAction(
                        MessageControl(MessageAction.CANCEL_SAVE, if (item.grantPending) "権限を解放" else "取消", foreground && !state.busy),
                        { cancelItem(item.id) })
                }
            }
        }
    }
}
