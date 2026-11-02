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
    if (draft != null) {
        val text = focusDistanceText(draft.trim().toFloatOrNull())
            ?: return SensorControlValue("—", "LU", "MF", "入力不正", SensorControlTone.FAILED)
        val state = when (receipt.phase) {
            ControlPhase.UNKNOWN -> "未適用・不明" to SensorControlTone.UNKNOWN
            ControlPhase.PENDING -> "送信中" to SensorControlTone.PENDING
            ControlPhase.REJECTED -> "入力拒否" to SensorControlTone.FAILED
            else -> "編集中" to SensorControlTone.EDITING
        }
        return SensorControlValue(text, "LU", "MF", state.first, state.second)
    }
    if (receipt.phase == ControlPhase.UNKNOWN) {
        return SensorControlValue("—", stateTag = "不明", tone = SensorControlTone.UNKNOWN)
    }
    if (receipt.phase == ControlPhase.UNBOUND) {
        return SensorControlValue("—", stateTag = "未確認", tone = SensorControlTone.MUTED)
    }
    val focus = if (receipt.phase == ControlPhase.PENDING && receipt.requested != null)
        receipt.requested.overrides.focusDistance else acceptedFocus
    val state = sensorReceiptState(receipt.phase, receipt.accepted != null)
    if (focus == null) return SensorControlValue("AF", modeTag = "AF", stateTag = state.first, tone = state.second)
    val text = focusDistanceText(focus)
        ?: return SensorControlValue("—", "LU", "MF", "不明", SensorControlTone.UNKNOWN)
    return SensorControlValue(text, "LU", "MF", state.first, state.second)
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
    /** Overall snapshot receipt state; this does not assert sensor WB or colour accuracy. */
    val stateTag: String,
    val tone: SensorControlTone,
)

fun whiteBalancePresentation(currentMode: Int?, receipt: ControlReceipt): WhiteBalancePresentation {
    val requested = receipt.requested
    val accepted = receipt.accepted
    val selected = if (receipt.phase in setOf(ControlPhase.PENDING, ControlPhase.UNKNOWN) && requested != null)
        requested.overrides.whiteBalance else currentMode
    val state = sensorReceiptState(receipt.phase, accepted != null)
    return WhiteBalancePresentation(
        selected, requested != null || accepted != null || currentMode != null,
        whiteBalanceShortLabel(selected),
        if (requested == null) "—" else whiteBalanceShortLabel(requested.overrides.whiteBalance),
        if (accepted == null) "—" else whiteBalanceShortLabel(accepted.overrides.whiteBalance),
        state.first, state.second,
    )
}

private fun sensorReceiptState(phase: ControlPhase, acceptedKnown: Boolean): Pair<String, SensorControlTone> = when (phase) {
    ControlPhase.UNBOUND -> "未確認" to SensorControlTone.MUTED
    ControlPhase.PENDING -> "送信中" to SensorControlTone.PENDING
    ControlPhase.UNKNOWN -> "不明" to SensorControlTone.UNKNOWN
    ControlPhase.REJECTED -> "入力拒否" to SensorControlTone.FAILED
    ControlPhase.ACCEPTED -> if (acceptedKnown) "受付済" to SensorControlTone.ACCEPTED
        else "未確認" to SensorControlTone.MUTED
}
