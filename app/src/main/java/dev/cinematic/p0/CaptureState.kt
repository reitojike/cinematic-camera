package dev.cinematic.p0

/** Accepted API settings are history, never proof of sensor output. */
data class ControlSnapshot(
    val exposure: ManualExposure? = null,
    val evIndex: Int? = null,
    val overrides: SensorOverrides = SensorOverrides(),
    val locks: AutoLocks = AutoLocks(),
)

enum class ControlPhase { UNBOUND, PENDING, ACCEPTED, REJECTED, UNKNOWN }
data class ControlReceipt(
    val epoch: Int = 0, val sequence: Long = 0,
    val phase: ControlPhase = ControlPhase.UNBOUND,
    val requested: ControlSnapshot? = null, val accepted: ControlSnapshot? = null,
    val acceptedAtMs: Long? = null, val detail: String = "未接続",
)

fun ControlReceipt.failure(detail: String, validation: Boolean): ControlReceipt = when {
    phase == ControlPhase.PENDING || phase == ControlPhase.UNKNOWN -> copy(
        phase = ControlPhase.UNKNOWN, acceptedAtMs = null, detail = "適用状態不明: $detail")
    validation -> copy(phase = ControlPhase.REJECTED, detail = "入力拒否: $detail")
    else -> this
}

data class SensorValues(
    val iso: Int? = null, val exposureNs: Long? = null, val aeMode: Int? = null,
    val focus: Float? = null, val afMode: Int? = null, val wbMode: Int? = null,
    val evIndex: Int? = null, val antiBanding: Int? = null,
    val aeLock: Boolean? = null, val wbLock: Boolean? = null,
)
data class CaptureSample(
    val epoch: Int, val frame: Long, val receivedAtMs: Long,
    val request: SensorValues, val actual: SensorValues, val frameDurationNs: Long? = null,
) {
    /** Correspondence is deliberately conservative; missing request keys cannot prove it. */
    fun corresponds(receipt: ControlReceipt): Boolean {
        val at = receipt.acceptedAtMs ?: return false
        val accepted = receipt.accepted ?: return false
        if (epoch != receipt.epoch || receivedAtMs <= at || receipt.phase !in setOf(ControlPhase.ACCEPTED, ControlPhase.REJECTED)) return false
        val manual = accepted.exposure
        val exposureMatches = if (manual != null) request.aeMode == 0 &&
            request.iso == manual.iso && request.exposureNs == manual.exposureNs else request.aeMode != null && request.aeMode != 0
        return exposureMatches &&
            (accepted.evIndex == null || manual != null || request.evIndex == accepted.evIndex) &&
            (accepted.overrides.whiteBalance?.let { request.wbMode == it } ?: true) &&
            (accepted.overrides.focusDistance?.let { request.afMode == 0 && request.focus == it } ?: true) &&
            (accepted.overrides.antiBanding?.let { request.antiBanding == it } ?: true) &&
            request.aeLock == accepted.locks.exposure && request.wbLock == accepted.locks.whiteBalance
    }
}

fun newerSample(previous: CaptureSample?, candidate: CaptureSample, epoch: Int): CaptureSample? =
    if (candidate.epoch != epoch || (previous?.epoch == epoch && candidate.frame <= previous.frame)) previous else candidate

enum class CaptureKey { ISO, SHUTTER, EV, FOCUS, WB, LOOK;
    val group get() = if (this == SHUTTER) ISO else this
}
data class ExposureDraft(val iso: String, val milliseconds: String)
/** Main-thread immutable editor: drafts never mutate accepted camera state. */
data class CaptureEditor(
    val epoch: Int = 0, val selected: CaptureKey? = null,
    val exposure: ExposureDraft? = null, val focus: String? = null,
    val submitting: Long? = null, val note: String? = null,
) {
    val dirty get() = exposure != null || focus != null
    fun select(key: CaptureKey): CaptureEditor = when {
        submitting != null -> this
        selected == key -> close()
        selected?.group == key.group -> copy(selected = key)
        else -> copy(selected = key, exposure = null, focus = null, note = if (dirty) "未適用の編集を破棄" else null)
    }
    fun cancel() = if (submitting != null) this else copy(exposure = null, focus = null, note = "受付値へ戻しました")
    fun close() = if (submitting != null) this else copy(selected = null, exposure = null, focus = null,
        note = if (dirty) "未適用の編集を破棄" else null)
    fun session(value: Int) = if (value == epoch) this else CaptureEditor(epoch = value, note = if (dirty) "再接続: 編集を破棄" else null)
    fun sent(id: Long) = copy(submitting = id, note = "送信中")
    fun complete(receipt: ControlReceipt): CaptureEditor {
        if (receipt.epoch != epoch || submitting != receipt.sequence || receipt.phase == ControlPhase.PENDING) return this
        return if (receipt.phase == ControlPhase.ACCEPTED) copy(exposure = null, focus = null, submitting = null, note = "受付済・実測は別表示")
        else copy(submitting = null, note = receipt.detail)
    }
    fun recording(stage: RecordingStage) = if (stage == RecordingStage.STARTING) copy(
        selected = null, exposure = null, focus = null, submitting = null, note = if (dirty) "録画開始: 未適用の編集を破棄" else null) else this
}

/** Removing manual exposure preserves independent controls; reset deliberately clears them. */
fun autoControlSnapshot(previous: ControlSnapshot, savedEv: Int, reset: Boolean): ControlSnapshot =
    if (reset) ControlSnapshot(evIndex = 0) else previous.copy(exposure = null, evIndex = savedEv,
        locks = previous.locks.forControls(false, previous.overrides.whiteBalance))

enum class PhotoStage { IDLE, CAPTURING, SAVING, SAVED, FAILED }

enum class FocusPhase { PENDING, COMPLETED, FAILED }
data class FocusFeedback(val point: Pair<Float, Float>, val phase: FocusPhase, val afSucceeded: Boolean? = null)

enum class CapturePanel { NONE, SETTINGS, DIAGNOSTICS }
data class CaptureBack(val panel: CapturePanel, val closeRuler: Boolean = false, val exit: Boolean = false,
    val armExit: Boolean = false, val note: String? = null)
fun captureBack(panel: CapturePanel, ruler: Boolean, pending: Boolean, recording: Boolean, armed: Boolean): CaptureBack = when {
    recording -> CaptureBack(panel, note = "録画を停止し、保存完了を待ってください")
    pending -> CaptureBack(panel, note = "設定の送信完了を待ってください")
    panel == CapturePanel.DIAGNOSTICS -> CaptureBack(CapturePanel.SETTINGS)
    panel == CapturePanel.SETTINGS -> CaptureBack(CapturePanel.NONE)
    ruler -> CaptureBack(panel, closeRuler = true)
    armed -> CaptureBack(panel, exit = true)
    else -> CaptureBack(panel, armExit = true, note = "もう一度戻ると終了")
}
