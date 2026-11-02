package dev.cinematic.p0

import kotlin.math.abs
import kotlin.math.ln

enum class ExposureValueFreshness { FRESH, MISSING, WRONG_EPOCH, NOT_PREVIEW, STALE, INVALID, NO_CHOICES }

/** Display-only projection. It deliberately exposes no request or setting to send back to the camera. */
data class ExposureValuePresentation(
    val value: String,
    val unit: String,
    val contentDescription: String,
    val freshness: ExposureValueFreshness,
)

fun exposureValuePresentation(
    key: CaptureKey, frame: CaptureSample?, currentEpoch: Int, nowMs: Long,
    choices: ExposureChoices, compensatedPreview: Boolean = false,
): ExposureValuePresentation {
    fun unavailable(reason: ExposureValueFreshness, detail: String) =
        ExposureValuePresentation("—", "", "${key.name} プレビュー実測 $detail", reason)
    if (key !in setOf(CaptureKey.ISO, CaptureKey.SHUTTER)) return unavailable(ExposureValueFreshness.INVALID, "対象外")
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
        value = "≈$nearest"; unit = ""
    } else {
        val actual = frame.actual.exposureNs?.takeIf { it > 0L }
            ?: return unavailable(ExposureValueFreshness.INVALID, "露光時間未確認")
        val nearest = choices.shutter.filter { it.exposureNs > 0L && it.label.isNotBlank() }
            .minByOrNull { abs(ln(it.exposureNs.toDouble() / actual)) }
            ?: return unavailable(ExposureValueFreshness.NO_CHOICES, "標準シャッター候補なし")
        value = "≈${nearest.label.removeSuffix(" s")}"; unit = "s"
    }
    val origin = if (compensatedPreview) "補償プレビューの実測" else "待機・録画プレビューの実測"
    return ExposureValuePresentation(value, unit,
        "${key.name} $value $unit、${origin}を標準候補へ近似表示。撮影targetではなく表示だけの丸め", ExposureValueFreshness.FRESH)
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
