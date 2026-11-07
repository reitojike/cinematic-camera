package dev.cinematic.p0

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

fun sensorControlColor(tone: SensorControlTone): Color = when (tone) {
    SensorControlTone.NORMAL -> CaptureDesign.Text
    SensorControlTone.MUTED -> CaptureDesign.Muted
    SensorControlTone.EDITING, SensorControlTone.PENDING -> CaptureDesign.Amber
    SensorControlTone.ACCEPTED -> CaptureDesign.Accepted
    SensorControlTone.FAILED, SensorControlTone.UNKNOWN -> CaptureDesign.Failed
}

internal fun evZeroShortcutSupported(range: IntRange?, step: Double?): Boolean =
    range != null && 0 in range && step != null && step.isFinite() && step > 0.0

/** Recheck session/capture ownership at the click, including changes since the last composition. */
internal fun dispatchSensorShortcut(enabled: Boolean, canSelect: () -> Boolean, select: () -> Unit): Boolean {
    if (!enabled || !canSelect()) return false
    select()
    return true
}

internal fun dispatchFocusAuto(enabled: Boolean, canSelect: () -> Boolean, manualNow: () -> Boolean,
    onAutofocus: () -> Unit): Boolean = dispatchSensorShortcut(enabled, canSelect) {
    if (manualNow()) onAutofocus()
}

/** A rotated word occupies the header's height; fit measured glyphs without changing the tap target. */
@Composable
internal fun SensorShortcutText(label: String, modifier: Modifier, horizontal: Boolean) {
    val measurer = rememberTextMeasurer()
    val style = LocalTextStyle.current
    BoxWithConstraints {
        val size = if (horizontal) controlValueFontSize(12f) { candidate ->
            val measured = measurer.measure(label, style.copy(fontSize = candidate.sp), softWrap = false, maxLines = 1).size
            measured.width <= constraints.maxHeight && measured.height <= constraints.maxWidth
        } else 12f
        Text(label, modifier, style = style.copy(fontSize = size.sp), maxLines = 1, softWrap = false)
    }
}

/** Direct index 0 request through the same live path as the ruler; no detent iteration. */
@Composable
fun ExposureZeroAction(
    selectedIndex: Int?, range: IntRange?, step: Double?, enabled: Boolean, onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier, textModifier: Modifier = Modifier, horizontal: Boolean = false,
    canSelect: () -> Boolean = { true },
) {
    val supported = evZeroShortcutSupported(range, step)
    val available = enabled && supported && selectedIndex != 0
    TextButton(onClick = { dispatchSensorShortcut(available, canSelect) { onSelect(0) } },
        enabled = available, modifier = modifier.heightIn(min = 44.dp).semantics {
            contentDescription = if (supported) "EVを0に戻す。実測は診断で確認" else "EV 0への復帰非対応"
        }, contentPadding = PaddingValues(horizontal = 8.dp)) {
        SensorShortcutText(if (supported) "RESET" else "EV非対応", textModifier, horizontal)
    }
}

/** Null releases the MF override; this requests autofocus without claiming that focus was reached. */
@Composable
fun FocusAutofocusAction(
    selectedFocus: Float?, autofocusSupported: Boolean, enabled: Boolean, onAutofocus: () -> Unit,
    modifier: Modifier = Modifier, textModifier: Modifier = Modifier, horizontal: Boolean = false,
    canSelect: () -> Boolean = { true },
    manualNow: () -> Boolean = { selectedFocus != null },
) {
    val available = enabled && autofocusSupported
    TextButton(onClick = { dispatchFocusAuto(available, canSelect, manualNow, onAutofocus) },
        enabled = available, modifier = modifier.heightIn(min = 44.dp).semantics {
            contentDescription = if (autofocusSupported) "AFに戻す。合焦の実測結果とは別" else "AF非対応"
        }, contentPadding = PaddingValues(horizontal = 8.dp)) {
        SensorShortcutText(if (autofocusSupported) "AUTO" else "AF非対応", textModifier, horizontal)
    }
}

/** AF observation seeds the numeric ruler; only an explicit selection requests MF. */
@Composable
fun FocusControlEditor(
    selectedFocus: Float?, maximum: Float?, enabled: Boolean,
    observedAutoFocus: Float?, onSelect: (Float) -> Unit,
    modifier: Modifier = Modifier, valueModifier: Modifier = Modifier,
    rotation: Float = 0f, horizontal: Boolean = false,
    epoch: Int = 0, canSelect: () -> Boolean = { true }, autoCanSelect: () -> Boolean = { true },
    autoStillActive: () -> Boolean = { true },
) {
    val legalMaximum = maximum?.takeIf { it.isFinite() && it > 0f }
    // ACKs and actual AF motion cannot insert/remove stops while a gesture uses their indices.
    val options = remember(epoch, legalMaximum) { focusControlOptions(legalMaximum, selectedFocus) }
    var autoSeed by remember(epoch, legalMaximum, selectedFocus == null) { androidx.compose.runtime.mutableStateOf<Float?>(null) }
    if (selectedFocus == null && autoSeed == null && observedAutoFocus != null) autoSeed = focusAutoSeed(autoSeed, observedAutoFocus)
    val selected = focusDialIndex(options, selectedFocus, autoSeed)
    Column(modifier.fillMaxWidth()) {
        if (options.isEmpty()) Text("MF非対応", color = CaptureDesign.Muted, fontSize = 12.sp)
        else if (selected < 0) Text(if (selectedFocus == null) "AF実測待ち・値は未確認" else "MF指定値を確認できません", color = CaptureDesign.Muted, fontSize = 12.sp)
        else ExposureSelectionScale("FOCUS", focusDialLabels(options),
            selected, enabled && (selectedFocus != null || observedAutoFocus != null), { onSelect(options[it]) },
            modifier = valueModifier, rotation = rotation, horizontal = horizontal,
            canSelect = { canSelect() && autoCanSelect() },
            allowCurrentSelection = selectedFocus == null, allowCurrentSelectionNow = autoStillActive,
            descriptions = options.mapIndexed { index, value ->
                val candidate = if (value == 0f) "無限遠 0 LU" else "${focusDistanceText(value)} LU"
                when {
                    selectedFocus == null && index == selected && observedAutoFocus == null ->
                        "AF実測待ち、前回の候補 $candidate。選択一時停止"
                    selectedFocus == null && index == selected ->
                        "AF開始時の近似候補 $candidate。選択するとMFに指定"
                    else -> "MF $candidate 指定。実際は診断で確認"
                }
            })
    }
}

/** Only supported Camera2 presets and removal of the override are selectable. No Kelvin mode. */
@Composable
fun WhiteBalanceControlEditor(
    modes: Set<Int>, presentation: WhiteBalancePresentation, enabled: Boolean, onSelect: (Int?) -> Unit,
    modifier: Modifier = Modifier, itemModifier: Modifier = Modifier,
    rotation: Float = 0f, horizontal: Boolean = false,
    canSelect: () -> Boolean = { true },
) {
    val options = whiteBalanceOptions(modes)
    val selected = if (presentation.hasSelection) options.indexOfFirst { it.mode == presentation.selectedMode } else -1
    ExposureSelectionScale("WB", options.map { it.shortLabel }, selected, enabled,
        { onSelect(options[it].mode) }, modifier.then(itemModifier), rotation, horizontal,
        sensorControlColor(presentation.tone), canSelect,
        descriptions = options.map { "${it.fullLabel}${if (presentation.hasSelection && it.mode == presentation.selectedMode) " 選択 ${presentation.stateTag}" else ""}" })
}

/** Reported EV indices remain immediate selections; MANUAL does not gain EV support. */
@Composable
fun ExposureCompensationControlEditor(
    acceptedIndex: Int?, range: IntRange?, step: Double?, enabled: Boolean, onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier, rotation: Float = 0f, horizontal: Boolean = false,
    canSelect: () -> Boolean = { true },
) {
    val options = exposureCompensationOptions(range, step)
    ExposureSelectionScale("EV", options.map { it.label }, options.indexOfFirst { it.index == acceptedIndex },
        enabled, { onSelect(options[it].index) }, modifier, rotation, horizontal, canSelect = canSelect)
}
