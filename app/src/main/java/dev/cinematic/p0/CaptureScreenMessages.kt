package dev.cinematic.p0

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Display-only projection. Camera guards and recovery eligibility remain owned by the caller. */
internal fun captureMessages(
    waitingForConnection: Boolean = false,
    cameraPermissionMissing: Boolean = false,
    controlRecovery: Boolean = false,
    captureRecovery: Boolean = false,
    canReconnect: Boolean = false,
    diagnosticsEnabled: Boolean = true,
    thermalWarning: Boolean = false,
    thermalRejected: Boolean = false,
    microphoneWarning: Boolean = false,
    captureFailed: Boolean = false,
    recordingFailed: Boolean = false,
    notice: CaptureNotice? = null,
    phase: String? = null,
): ScreenMessages = screenMessages(buildList {
    if (captureRecovery) add(UiMessage(MessageSlot.PROBLEM, CaptureChromeTone.FAILURE,
        "停止して接続をやり直してください。", "capture.recovery",
        title = "撮影状態を確認できません",
        primary = MessageControl(MessageAction.STOP_AND_RECOVER, "停止して復旧")))
    if (controlRecovery) add(UiMessage(MessageSlot.PROBLEM, CaptureChromeTone.FAILURE,
        "再接続して設定を確認してください。", "controls.recovery",
        title = "設定状態を確認できません",
        primary = if (canReconnect) MessageControl(MessageAction.RECONNECT, "再接続") else null))
    if (cameraPermissionMissing) add(UiMessage(MessageSlot.CENTER, CaptureChromeTone.NORMAL,
        "カメラの許可が必要です。許可しない場合は、端末のアプリ設定からも変更できます。", "camera.permission",
        primary = MessageControl(MessageAction.GRANT_ACCESS, "カメラを許可")))
    if (waitingForConnection) add(UiMessage(MessageSlot.CENTER, CaptureChromeTone.NORMAL,
        "接続待ち・プレビュー停止中", "preview.connection"))
    val diagnostics = MessageControl(MessageAction.OPEN_DIAGNOSTICS, "詳細は診断へ", enabled = diagnosticsEnabled)
    if (captureFailed) add(UiMessage(MessageSlot.BANNER, CaptureChromeTone.FAILURE,
        "撮影または設定を完了できませんでした。診断を確認してください。", "capture.failure", primary = diagnostics))
    if (recordingFailed) add(UiMessage(MessageSlot.BANNER, CaptureChromeTone.FAILURE,
        "録画を完了できませんでした。診断を確認してください。", "recording.failure", primary = diagnostics))
    if (phase != null) add(UiMessage(MessageSlot.BANNER, CaptureChromeTone.WARNING,
        phase, "capture.processing", inProgress = true))
    if (thermalRejected) add(UiMessage(MessageSlot.BANNER, CaptureChromeTone.FAILURE,
        "端末が熱いため録画を開始できませんでした。温度が下がるまでお待ちください。", "recording.thermal"))
    else if (thermalWarning) add(UiMessage(MessageSlot.BANNER, CaptureChromeTone.WARNING,
        "端末が熱いため録画を開始できません。温度が下がるまでお待ちください。", "recording.thermal"))
    if (microphoneWarning) add(UiMessage(MessageSlot.BANNER, CaptureChromeTone.WARNING,
        "マイクが許可されていません。権限を許可するか、設定で音声をOFFにしてください。", "recording.microphone"))
    if (notice != null) add(UiMessage(MessageSlot.TOAST,
        notice.tone,
        notice.text, "capture.notice"))
}, failureFallback = captureFailureFallback(diagnosticsEnabled))

/** Shared by the actual preview and synthetic visual regression. Does not own any state or action. */
@Composable
internal fun BoxScope.CaptureMessageOverlay(messages: ScreenMessages, horizontal: Boolean,
    captureRecovery: Boolean, onAction: (MessageAction) -> Unit, rotation: Modifier = Modifier,
    failureFallback: UiMessage? = null) {
    if (horizontal && messages.center != null && messages.banner != null) {
        // Both describe different facts. Stack in the existing central area instead of overlapping.
        Column(Modifier.align(Alignment.Center).widthIn(max = 220.dp).then(rotation),
            verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            CaptureCenter(messages.center, onAction)
            CaptureBanner(messages.banner, onAction, failureFallback = failureFallback)
        }
    } else {
        messages.center?.let { message ->
            val placement = Modifier.align(Alignment.Center)
                .then(if (captureRecovery) Modifier.widthIn(max = 300.dp) else Modifier).then(rotation)
            if (message.slot == MessageSlot.PROBLEM) CaptureProblem(message, onAction, placement, failureFallback = failureFallback)
            else CaptureCenter(message, onAction, placement)
        }
        messages.banner?.let { message ->
            CaptureBanner(message, onAction, Modifier.align(if (horizontal) Alignment.Center else Alignment.TopCenter)
                .padding(top = if (horizontal) 0.dp else 48.dp)
                .widthIn(max = if (horizontal) 220.dp else 380.dp).then(rotation), failureFallback = failureFallback)
        }
    }
    messages.toast?.let { message ->
        CaptureToast(message, Modifier.align(Alignment.BottomCenter).padding(bottom = 180.dp)
            .widthIn(max = if (horizontal) 220.dp else 380.dp).then(rotation))
    }
}

internal fun captureSettingsMessages(busy: Boolean = false, applying: String? = null): ScreenMessages =
    screenMessages(buildList {
        if (busy && applying == null) add(UiMessage(MessageSlot.INLINE, CaptureChromeTone.WARNING,
            "撮影・録画・設定処理中です。設定変更は完了後に行えます。", "settings.busy"))
        if (applying != null) add(UiMessage(MessageSlot.INLINE, CaptureChromeTone.WARNING,
            applying, "settings.applying"))
    })

/** No camera mutation: use the same diagnostics gate as the existing settings sheet. */
internal fun captureFailureFallback(diagnosticsEnabled: Boolean = true): UiMessage = UiMessage(
    MessageSlot.PROBLEM, CaptureChromeTone.FAILURE,
    "画面の表示を確認できません。診断を確認してください。", "capture.display_failure",
    title = "状態を表示できません",
    primary = MessageControl(MessageAction.OPEN_DIAGNOSTICS, "詳細は診断へ", enabled = diagnosticsEnabled),
)
