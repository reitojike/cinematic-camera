package dev.cinematic.p0

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.util.Locale

/** UI intervals in lens units, not a claim that the lens has discrete hardware positions. */
fun focusControlOptions(maximum: Float?, anchor: Float?): List<Float> {
    val limit = maximum?.takeIf { it.isFinite() && it > 0f } ?: return emptyList()
    val stops = (0..100).map { (limit.toDouble() * it / 100).toFloat() }
    val exact = anchor?.takeIf { it.isFinite() && it in 0f..limit }
    return (if (exact == null) stops else stops + exact).distinct().sorted()
}

/** Choosing the nearest display slot never writes it back to the camera or draft. */
fun focusControlIndex(options: List<Float>, value: Float?): Int =
    if (value == null || !value.isFinite() || options.isEmpty() || value < options.first() || value > options.last()) -1
    else options.indices.minBy { kotlin.math.abs(options[it].toDouble() - value) }

/** A preview observation starts the ruler once; later frames do not move an in-progress gesture. */
internal fun focusAutoSeed(seed: Float?, fresh: Float?): Float? = seed ?: fresh
internal fun focusDialLabels(options: List<Float>): List<String> =
    options.map { if (it == 0f) "∞" else focusDistanceText(it) ?: "?" }
internal fun focusDialIndex(options: List<Float>, manual: Float?, autoSeed: Float?): Int =
    focusControlIndex(options, manual ?: autoSeed)

/** After the first detent, synchronous logical MF intent replaces the need for a fresh AF seed. */
internal fun focusDialAutoActive(intent: ControlSnapshot?, acceptedFocus: Float?): Boolean =
    (if (intent != null) intent.overrides.focusDistance else acceptedFocus) == null

internal fun focusDialInputAllowed(intent: ControlSnapshot?, acceptedFocus: Float?, freshAuto: Boolean): Boolean =
    !focusDialAutoActive(intent, acceptedFocus) || freshAuto

data class ExposureCompensationOption(val index: Int, val label: String)

fun exposureCompensationOptions(range: IntRange?, step: Double?): List<ExposureCompensationOption> {
    if (range == null || range.isEmpty() || step == null || !step.isFinite() || step <= 0.0) return emptyList()
    return range.map { ExposureCompensationOption(it, String.format(Locale.ROOT, "%+.1f", it * step)) }
}

enum class SensorControlTone { NORMAL, MUTED, EDITING, PENDING, ACCEPTED, FAILED, UNKNOWN }

data class SensorControlValue(
    val value: String,
    val unit: String = "",
    val modeTag: String = "",
    val stateTag: String = "",
    val tone: SensorControlTone = SensorControlTone.NORMAL,
    val affected: Boolean = false,
)

/** The latest logical intent is independent of an older API acknowledgement and sensor results. */
fun liveFocusPresentation(acceptedFocus: Float?, intent: ControlSnapshot?, receipt: ControlReceipt,
    pending: Boolean): SensorControlValue {
    if (intent == null) return focusPresentation(acceptedFocus, null, receipt).let {
        if (acceptedFocus == 0f && it.modeTag == "MF") it.copy(value = "∞", unit = "") else it
    }
    val focus = intent.overrides.focusDistance
    val changed = receipt.accepted == null || focus != receipt.accepted.overrides.focusDistance ||
        (pending || receipt.phase == ControlPhase.UNKNOWN) && receipt.requested != null && focus != receipt.requested.overrides.focusDistance
    val state = sensorFieldState(if (pending) ControlPhase.PENDING else receipt.phase, changed, receipt.accepted != null)
    if (focus == null) return SensorControlValue("AF", modeTag = "AF", stateTag = state.first, tone = state.second, affected = changed)
    val text = focusDistanceText(focus)
        ?: return SensorControlValue("—", stateTag = "不明", tone = SensorControlTone.UNKNOWN, affected = changed)
    return SensorControlValue(if (focus == 0f) "∞" else text, if (focus == 0f) "" else "LU", "MF", state.first, state.second, changed)
}

/** AF values are observations rounded only for display; they never become a request implicitly. */
internal fun focusAutoPresentation(base: SensorControlValue, observed: Float?, maximum: Float?): SensorControlValue {
    if (base.modeTag != "AF") return base
    val options = focusControlOptions(maximum, null)
    if (options.isEmpty()) return base.copy(value = "?", unit = "", modeTag = "AF 候補なし")
    val index = focusControlIndex(options, observed)
    if (index < 0) return base.copy(value = "?", unit = "", modeTag = "AF 待機")
    val nearest = options[index]
    return base.copy(value = if (nearest == 0f) "∞" else focusDistanceText(nearest) ?: "?",
        unit = if (nearest == 0f) "" else "LU", modeTag = "AF 近似")
}

fun liveEvPresentation(acceptedIndex: Int?, step: Double?, intent: ControlSnapshot?, receipt: ControlReceipt,
    pending: Boolean): SensorControlValue {
    val index = intent?.evIndex ?: acceptedIndex
    val text = if (index == null || step == null || !step.isFinite() || step <= 0.0) "—"
        else String.format(Locale.ROOT, "%+.1f", index * step)
    val changed = intent?.evIndex != null && (intent.evIndex != acceptedIndex ||
        (pending || receipt.phase == ControlPhase.UNKNOWN) && receipt.requested != null && intent.evIndex != receipt.requested.evIndex)
    val state = sensorFieldState(if (pending) ControlPhase.PENDING else receipt.phase, changed, acceptedIndex != null)
    return SensorControlValue(text, stateTag = state.first, tone = state.second, affected = changed)
}

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

fun liveWhiteBalancePresentation(currentMode: Int?, intent: ControlSnapshot?, receipt: ControlReceipt,
    pending: Boolean): WhiteBalancePresentation {
    if (intent == null) return whiteBalancePresentation(currentMode, receipt)
    val changed = receipt.accepted == null || intent.overrides.whiteBalance != receipt.accepted.overrides.whiteBalance ||
        (pending || receipt.phase == ControlPhase.UNKNOWN) && receipt.requested != null && intent.overrides.whiteBalance != receipt.requested.overrides.whiteBalance
    val selected = intent.overrides.whiteBalance
    val state = sensorFieldState(if (pending) ControlPhase.PENDING else receipt.phase, changed, receipt.accepted != null)
    return WhiteBalancePresentation(selected, true, whiteBalanceShortLabel(selected), whiteBalanceShortLabel(selected),
        receipt.accepted?.let { whiteBalanceShortLabel(it.overrides.whiteBalance) } ?: "—",
        state.first, state.second, changed)
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
