package dev.cinematic.p0

import kotlin.math.abs
import kotlin.math.ln

enum class ExposureValueFreshness { FRESH, MISSING, WRONG_EPOCH, NOT_PREVIEW, STALE, INVALID, NO_CHOICES }
enum class ExposureValueSource { PREVIEW_ACTUAL, CAPTURE_TARGET }

/** Display-only projection. It deliberately exposes no request or setting to send back to the camera. */
data class ExposureValuePresentation(
    val value: String,
    val unit: String,
    val contentDescription: String,
    val freshness: ExposureValueFreshness,
    val source: ExposureValueSource = ExposureValueSource.PREVIEW_ACTUAL,
    /** Approximation never consumes the numeric value's reading width. */
    val qualifier: String = "",
)

fun exposureValuePresentation(
    key: CaptureKey, frame: CaptureSample?, currentEpoch: Int, nowMs: Long,
    choices: ExposureChoices, compensatedPreview: Boolean = false,
    captureTarget: ManualExposure? = null,
): ExposureValuePresentation {
    fun unavailable(reason: ExposureValueFreshness, detail: String) =
        ExposureValuePresentation("—", if (key == CaptureKey.SHUTTER) "s" else "", "${key.name} プレビュー実測 $detail", reason,
            qualifier = when (reason) {
                ExposureValueFreshness.STALE -> "期限切れ"
                ExposureValueFreshness.WRONG_EPOCH -> "旧接続"
                ExposureValueFreshness.NOT_PREVIEW -> "対象外"
                ExposureValueFreshness.NO_CHOICES -> "候補なし"
                else -> "実測未確認"
            })
    if (key !in setOf(CaptureKey.ISO, CaptureKey.SHUTTER)) return unavailable(ExposureValueFreshness.INVALID, "対象外")
    if (captureTarget != null) {
        if (captureTarget.iso <= 0 || captureTarget.exposureNs <= 0L) return unavailable(ExposureValueFreshness.INVALID, "撮影設定未確認")
        val value = if (key == CaptureKey.ISO) captureTarget.iso.toString() else
            (ExposureStops.shutters.firstOrNull { it.exposureNs == captureTarget.exposureNs }?.label?.removeSuffix(" s")
                ?: java.math.BigDecimal.valueOf(captureTarget.exposureNs, 9).stripTrailingZeros().toPlainString())
        val unit = if (key == CaptureKey.SHUTTER) "s" else ""
        return ExposureValuePresentation(value, unit, "${key.name} $value $unit、撮影設定値。実測・保存ファイルの露出とは別",
            ExposureValueFreshness.FRESH, ExposureValueSource.CAPTURE_TARGET, "設定値")
    }
    if (frame == null) return unavailable(ExposureValueFreshness.MISSING, "未取得")
    if (frame.epoch != currentEpoch) return unavailable(ExposureValueFreshness.WRONG_EPOCH, "旧世代")
    if (frame.intent !in setOf(CaptureSampleIntent.PREVIEW, CaptureSampleIntent.VIDEO_RECORD)) {
        return unavailable(ExposureValueFreshness.NOT_PREVIEW, "待機・録画の実測ではありません")
    }
    if (frame.receivedAtMs < 0 || nowMs < frame.receivedAtMs) return unavailable(ExposureValueFreshness.INVALID, "時刻未確認")
    if (nowMs - frame.receivedAtMs > 2_000L) return unavailable(ExposureValueFreshness.STALE, "2秒を超える旧値")
    val value: String
    val unit: String
    if (key == CaptureKey.ISO) {
        val actual = frame.actual.iso?.takeIf { it > 0 }
            ?: return unavailable(ExposureValueFreshness.INVALID, "ISO未確認")
        val nearest = choices.iso.filter { it > 0 }.minByOrNull { abs(ln(it.toDouble() / actual)) }
            ?: return unavailable(ExposureValueFreshness.NO_CHOICES, "標準ISO候補なし")
        value = nearest.toString(); unit = ""
    } else {
        val actual = frame.actual.exposureNs?.takeIf { it > 0L }
            ?: return unavailable(ExposureValueFreshness.INVALID, "露光時間未確認")
        val nearest = choices.shutter.filter { it.exposureNs > 0L && it.label.isNotBlank() }
            .minByOrNull { abs(ln(it.exposureNs.toDouble() / actual)) }
            ?: return unavailable(ExposureValueFreshness.NO_CHOICES, "標準シャッター候補なし")
        value = nearest.label.removeSuffix(" s"); unit = "s"
    }
    val origin = if (compensatedPreview) "補償プレビューの実測" else "待機・録画プレビューの実測"
    return ExposureValuePresentation(value, unit,
        "${key.name} 約$value $unit、${origin}を標準候補へ近似表示。撮影targetではなく表示だけの丸め", ExposureValueFreshness.FRESH,
        qualifier = "近似実測")
}

/** Compact diagnostics shared by the screen and verification report; never stores new observations. */
fun exposureDisplayDiagnostic(key: CaptureKey, samples: PreviewExposureSamples, currentEpoch: Int, nowMs: Long,
    choices: ExposureChoices, bound: Boolean, captureTarget: ManualExposure? = null): String {
    val sample = samples.sample(key)
    val observation = exposureValuePresentation(key, sample.takeIf { bound }, currentEpoch, nowMs, choices)
    val display = exposureValuePresentation(key, sample.takeIf { bound }, currentEpoch, nowMs, choices,
        captureTarget = captureTarget.takeIf { bound })
    val age = sample?.receivedAtMs?.takeIf { it >= 0 && nowMs >= it }?.let { (nowMs - it).toString() } ?: "—"
    return "display ${key.name}: source=${display.source} freshness=${display.freshness} " +
        "frame=${sample?.frame ?: "—"} epoch=${sample?.epoch ?: "—"}/$currentEpoch " +
        "sourceAtMs=${sample?.receivedAtMs ?: "—"} ageMs=$age observation=${observation.freshness}"
}

/** Latest user intent wins over older API acceptance; runtime preview compensation is never a target. */
fun logicalCaptureTarget(currentEpoch: Int, receipt: ControlReceipt, liveTarget: ManualExposure?,
    stillTarget: StillExposureTarget?, appliedTarget: ManualExposure?): ManualExposure? {
    if (receipt.epoch != currentEpoch) return null
    if (liveTarget != null) return liveTarget
    if (stillTarget != null) return stillTarget.takeIf { it.epoch == currentEpoch }?.plan?.capture
    return if (receipt.phase in setOf(ControlPhase.PENDING, ControlPhase.UNKNOWN)) receipt.requested?.exposure else appliedTarget
}

/** Compare logical capture pairs, never the internally compensated preview request. */
fun logicalExposureChanged(key: CaptureKey, requestedCapture: ManualExposure?, appliedCapture: ManualExposure?): Boolean = when (key) {
    CaptureKey.ISO -> requestedCapture?.iso != appliedCapture?.iso
    CaptureKey.SHUTTER -> requestedCapture?.exposureNs != appliedCapture?.exposureNs
    else -> false
}

data class ExposureKeyFeedback(val affected: Boolean, val stateTag: String = "", val tone: SensorControlTone = SensorControlTone.MUTED)

fun exposureKeyFeedback(key: CaptureKey, requestedCapture: ManualExposure?, appliedCapture: ManualExposure?,
    phase: ControlPhase): ExposureKeyFeedback {
    val changed = logicalExposureChanged(key, requestedCapture, appliedCapture)
    return if (!changed) ExposureKeyFeedback(false) else when (phase) {
        ControlPhase.PENDING -> ExposureKeyFeedback(true, "送信中", SensorControlTone.PENDING)
        ControlPhase.UNKNOWN -> ExposureKeyFeedback(true, "不明", SensorControlTone.UNKNOWN)
        ControlPhase.REJECTED -> ExposureKeyFeedback(true, "入力拒否", SensorControlTone.FAILED)
        else -> ExposureKeyFeedback(true)
    }
}
