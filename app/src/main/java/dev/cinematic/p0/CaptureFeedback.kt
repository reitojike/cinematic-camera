package dev.cinematic.p0

enum class FeedbackTone { INFORMATION, WARNING, FAILURE }
data class CaptureMessage(val text: String, val tone: FeedbackTone)

data class EditorReceiptFeedback(val editor: CaptureEditor, val notice: String?)

/** A guard/no-op must not turn the last successful request into this edit's receipt. */
fun beginEditorReceipt(editor: CaptureEditor, before: ControlReceipt, after: ControlReceipt): EditorReceiptFeedback {
    if (before.epoch != after.epoch || editor.epoch != after.epoch || after.sequence <= before.sequence)
        return EditorReceiptFeedback(editor, "新しい設定要求は未送信です・編集は保持しています")
    return completeEditorReceipt(editor.sent(after.sequence), after)
}

/** One matched MF submission may announce API completion; duplicate or old receipts cannot. */
fun completeEditorReceipt(editor: CaptureEditor, receipt: ControlReceipt): EditorReceiptFeedback {
    val accepted = editor.submitting != null && editor.epoch == receipt.epoch &&
        editor.submitting == receipt.sequence && receipt.phase == ControlPhase.ACCEPTED
    val completed = editor.complete(receipt)
    return if (accepted) EditorReceiptFeedback(completed.copy(note = null), "設定API受付完了・実測は診断で確認")
        else EditorReceiptFeedback(completed, null)
}

/** The tone belongs to the displayed message, never to an unrelated retained failure. */
fun captureMessage(sharedSaveSupported: Boolean, thermalWarning: String?, microphoneWarning: String?,
    failure: String?, recordingFailure: String?, notice: String?, phase: String?): CaptureMessage? = when {
    !sharedSaveSupported -> CaptureMessage("共有画像・動画の保存にはAndroid 10以降が必要です", FeedbackTone.WARNING)
    thermalWarning != null -> CaptureMessage(thermalWarning, FeedbackTone.WARNING)
    microphoneWarning != null -> CaptureMessage(microphoneWarning, FeedbackTone.WARNING)
    failure != null -> CaptureMessage(failure, FeedbackTone.FAILURE)
    recordingFailure != null -> CaptureMessage(recordingFailure, FeedbackTone.FAILURE)
    notice != null -> CaptureMessage(notice, FeedbackTone.INFORMATION)
    phase != null -> CaptureMessage(phase, FeedbackTone.WARNING)
    else -> null
}

data class CaptureAccess(
    val bound: Boolean, val busy: Boolean, val recording: Boolean, val ownsCapture: Boolean,
    val ready: Boolean, val exposurePending: Boolean, val manualExposure: Boolean,
    val manualBlockReason: String? = null, val evSupported: Boolean = false, val focusSupported: Boolean = false,
    val liveSelectable: Boolean = false,
    val exposureSelectable: Boolean? = null,
)
data class ControlAvailability(val enabled: Boolean, val reason: String? = null)

/** Disabled keys may explain their reason, but must never dispatch a camera mutation. */
fun controlAvailability(key: CaptureKey, access: CaptureAccess): ControlAvailability {
    val reason = when {
        key == CaptureKey.LOOK -> "LOOKは未実装です・色処理なし"
        access.recording -> "録画・停止処理中は設定を変更できません"
        access.ownsCapture -> "撮影・保存・プレビュー復帰の完了を待ってください"
        !access.bound -> "カメラの接続を待つか、設定の診断からPreviewを開始してください"
        access.busy && !access.liveSelectable && !(key.group == CaptureKey.ISO && access.exposurePending) -> "設定処理中です・完了を待ってください"
        key.group == CaptureKey.ISO && access.manualBlockReason != null -> access.manualBlockReason
        key.group == CaptureKey.ISO && access.exposureSelectable == false -> "設定状態が未確認です・診断で確認または復旧してください"
        key.group != CaptureKey.ISO && !access.ready && !access.liveSelectable -> "設定状態が未確認です・診断で確認または復旧してください"
        key == CaptureKey.EV && access.manualExposure -> "EVは露出AUTO時のみ操作できます"
        key == CaptureKey.EV && !access.evSupported -> "このカメラではEV補正に対応していません"
        key == CaptureKey.FOCUS && !access.focusSupported -> "このカメラではMFに対応していません"
        else -> null
    }
    return ControlAvailability(reason == null, reason)
}

data class DiagnosticControlRow(val label: String, val requested: String, val acceptedHistory: String, val observed: String)
data class ControlDiagnosticTable(val rows: List<DiagnosticControlRow>, val sampleCurrent: Boolean,
    val corresponds: Boolean, val receiptCurrent: Boolean)

fun controlDiagnosticTable(receipt: ControlReceipt, sample: CaptureSample?, epoch: Int): ControlDiagnosticTable {
    val current = receipt.takeIf { it.epoch == epoch }
    val requested = current?.requested
    val accepted = current?.accepted
    val observation = sample?.takeIf { it.epoch == epoch && it.intent in setOf(CaptureSampleIntent.PREVIEW, CaptureSampleIntent.VIDEO_RECORD) }
    val actual = observation?.actual
    fun exposure(snapshot: ControlSnapshot?, iso: Boolean): String = if (snapshot == null) "—"
        else snapshot.exposure?.let { if (iso) it.iso.toString() else ExposureStops.label(it.exposureNs) } ?: "AUTO"
    fun focus(snapshot: ControlSnapshot?): String = if (snapshot == null) "—" else snapshot.overrides.focusDistance?.let { "$it LU" } ?: "AF/既定"
    fun wb(snapshot: ControlSnapshot?): String = if (snapshot == null) "—" else snapshot.overrides.whiteBalance?.let(SensorModeNames::whiteBalance) ?: "既定"
    fun band(snapshot: ControlSnapshot?): String = if (snapshot == null) "—" else snapshot.overrides.antiBanding?.let(SensorModeNames::antiBanding) ?: "既定"
    val rows = listOf(
        DiagnosticControlRow("ISO", exposure(requested, true), exposure(accepted, true), actual?.iso?.toString() ?: "—"),
        DiagnosticControlRow("SHUTTER", exposure(requested, false), exposure(accepted, false), ExposureStops.label(actual?.exposureNs)),
        DiagnosticControlRow("MF / LU", focus(requested), focus(accepted), actual?.focus?.let { "$it LU" } ?: "—"),
        DiagnosticControlRow("WB", wb(requested), wb(accepted), actual?.wbMode?.let(SensorModeNames::whiteBalance) ?: "—"),
        DiagnosticControlRow("EV index（MAN中は無効）", requested?.evIndex?.toString() ?: "—", accepted?.evIndex?.toString() ?: "—", actual?.evIndex?.toString() ?: "—"),
        DiagnosticControlRow("照明対策", band(requested), band(accepted), actual?.antiBanding?.let(SensorModeNames::antiBanding) ?: "—"),
        DiagnosticControlRow("AE / WB LOCK", requested?.locks?.let { "${it.exposure} / ${it.whiteBalance}" } ?: "—",
            accepted?.locks?.let { "${it.exposure} / ${it.whiteBalance}" } ?: "—", actual?.let { "${it.aeLock ?: "—"} / ${it.wbLock ?: "—"}" } ?: "—"),
    )
    return ControlDiagnosticTable(rows, observation != null, current != null && observation?.corresponds(current) == true, current != null)
}
