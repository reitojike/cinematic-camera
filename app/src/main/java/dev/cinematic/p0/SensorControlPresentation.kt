package dev.cinematic.p0

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

enum class SensorControlTone { NORMAL, MUTED, EDITING, PENDING, ACCEPTED, FAILED, UNKNOWN }

data class SensorControlValue(
    val value: String,
    val unit: String = "",
    val modeTag: String = "",
    val stateTag: String = "",
    val tone: SensorControlTone = SensorControlTone.NORMAL,
    val affected: Boolean = false,
)

/** Lens units only. A tiny positive value must never round to the infinity endpoint, 0. */
fun focusDistanceText(value: Float?): String? {
    if (value == null || !value.isFinite() || value < 0f) return null
    if (value == 0f) return "0"
    val decimal = BigDecimal(value.toString())
    val rounded = decimal.setScale(2, RoundingMode.HALF_UP)
    return (if (rounded.signum() == 0) decimal.round(MathContext(3, RoundingMode.HALF_UP)) else rounded)
        .stripTrailingZeros().toPlainString()
}

fun focusPresentation(acceptedFocus: Float?, draft: String?, receipt: ControlReceipt): SensorControlValue {
    val changed = receipt.requested != null && receipt.accepted != null &&
        receipt.requested.overrides.focusDistance != receipt.accepted.overrides.focusDistance
    if (draft != null) {
        val text = focusDistanceText(draft.trim().toFloatOrNull())
            ?: return SensorControlValue("—", "LU", "MF", "入力不正", SensorControlTone.FAILED)
        val state = when (if (changed) receipt.phase else null) {
            ControlPhase.UNKNOWN -> "未適用・不明" to SensorControlTone.UNKNOWN
            ControlPhase.PENDING -> "送信中" to SensorControlTone.PENDING
            ControlPhase.REJECTED -> "入力拒否" to SensorControlTone.FAILED
            else -> "編集中" to SensorControlTone.EDITING
        }
        return SensorControlValue(text, "LU", "MF", state.first, state.second, changed)
    }
    if (receipt.phase == ControlPhase.UNKNOWN && changed) {
        return SensorControlValue("—", stateTag = "不明", tone = SensorControlTone.UNKNOWN, affected = true)
    }
    if (receipt.phase == ControlPhase.UNBOUND) {
        return SensorControlValue("—", stateTag = "未確認", tone = SensorControlTone.MUTED)
    }
    if (receipt.accepted == null && !changed) {
        return SensorControlValue("—", stateTag = "未確認", tone = SensorControlTone.MUTED)
    }
    val focus = if (receipt.phase == ControlPhase.PENDING && changed)
        receipt.requested.overrides.focusDistance else acceptedFocus
    val state = sensorFieldState(receipt.phase, changed, acceptedKnown = true)
    if (focus == null) return SensorControlValue("AF", modeTag = "AF", stateTag = state.first, tone = state.second, affected = changed)
    val text = focusDistanceText(focus)
        ?: return SensorControlValue("—", "LU", "MF", "不明", SensorControlTone.UNKNOWN)
    return SensorControlValue(text, "LU", "MF", state.first, state.second, changed)
}

data class WhiteBalanceOption(val mode: Int?, val shortLabel: String, val fullLabel: String)

fun whiteBalanceShortLabel(mode: Int?): String = when (mode) {
    null -> "標準"; 1 -> "AUTO"; 2 -> "INCAND"; 3 -> "FLUOR"; 4 -> "W.FLUOR"
    5 -> "DAYLT"; 6 -> "CLOUDY"; 7 -> "TWILT"; 8 -> "SHADE"; else -> "—"
}

/** Null removes the override; it is distinct from requesting Camera2 AWB_MODE_AUTO (1). */
fun whiteBalanceOptions(modes: Set<Int>): List<WhiteBalanceOption> =
    listOf(WhiteBalanceOption(null, "標準", "CameraX標準")) + modes.filter { it in 1..8 }.sorted().map {
        WhiteBalanceOption(it, whiteBalanceShortLabel(it), SensorModeNames.whiteBalance(it))
    }

data class WhiteBalancePresentation(
    val selectedMode: Int?,
    val hasSelection: Boolean,
    val selectedLabel: String,
    val requestedLabel: String,
    val acceptedLabel: String,
    /** Only this override's changed request is attributed pending/error feedback. */
    val stateTag: String,
    val tone: SensorControlTone,
    val affected: Boolean = false,
)

fun whiteBalancePresentation(currentMode: Int?, receipt: ControlReceipt): WhiteBalancePresentation {
    val requested = receipt.requested
    val accepted = receipt.accepted
    val changed = requested != null && accepted != null &&
        requested.overrides.whiteBalance != accepted.overrides.whiteBalance
    val selected = if ((changed || accepted == null) && requested != null &&
        receipt.phase in setOf(ControlPhase.PENDING, ControlPhase.UNKNOWN)) requested.overrides.whiteBalance else currentMode
    val state = sensorFieldState(receipt.phase, changed, accepted != null)
    return WhiteBalancePresentation(
        selected, requested != null || accepted != null || currentMode != null,
        whiteBalanceShortLabel(selected),
        if (requested == null) "—" else whiteBalanceShortLabel(requested.overrides.whiteBalance),
        if (accepted == null) "—" else whiteBalanceShortLabel(accepted.overrides.whiteBalance),
        state.first, state.second, changed,
    )
}

private fun sensorFieldState(phase: ControlPhase, changed: Boolean, acceptedKnown: Boolean): Pair<String, SensorControlTone> {
    if (changed) when (phase) {
        ControlPhase.PENDING -> return "送信中" to SensorControlTone.PENDING
        ControlPhase.UNKNOWN -> return "不明" to SensorControlTone.UNKNOWN
        ControlPhase.REJECTED -> return "入力拒否" to SensorControlTone.FAILED
        else -> Unit
    }
    return if (acceptedKnown && phase != ControlPhase.UNBOUND) "履歴" to SensorControlTone.MUTED
        else "未確認" to SensorControlTone.MUTED
}
