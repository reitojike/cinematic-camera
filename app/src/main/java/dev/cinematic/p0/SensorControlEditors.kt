package dev.cinematic.p0

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val SensorText = Color(0xFFE4E6E8)
private val SensorMuted = Color(0xFFA3AAB1)
private val SensorAmber = Color(0xFFE6A35C)
private val SensorAccepted = Color(0xFF7CC4A5)
private val SensorFailed = Color(0xFFEF8A74)

fun sensorControlColor(tone: SensorControlTone): Color = when (tone) {
    SensorControlTone.NORMAL -> SensorText
    SensorControlTone.MUTED -> SensorMuted
    SensorControlTone.EDITING, SensorControlTone.PENDING -> SensorAmber
    SensorControlTone.ACCEPTED -> SensorAccepted
    SensorControlTone.FAILED, SensorControlTone.UNKNOWN -> SensorFailed
}

internal fun evZeroShortcutSupported(range: IntRange?, step: Double?): Boolean =
    range != null && 0 in range && step != null && step.isFinite() && step > 0.0

/** Recheck session/capture ownership at the click, including changes since the last composition. */
internal fun dispatchSensorShortcut(enabled: Boolean, canSelect: () -> Boolean, select: () -> Unit): Boolean {
    if (!enabled || !canSelect()) return false
    select()
    return true
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
) {
    val available = enabled && autofocusSupported && selectedFocus != null
    TextButton(onClick = { dispatchSensorShortcut(available, canSelect, onAutofocus) },
        enabled = available, modifier = modifier.heightIn(min = 44.dp).semantics {
            contentDescription = if (autofocusSupported) "AFに戻す。合焦の実測結果とは別" else "AF非対応"
        }, contentPadding = PaddingValues(horizontal = 8.dp)) {
        SensorShortcutText(if (autofocusSupported) "AUTO" else "AF非対応", textModifier, horizontal)
    }
}

/** AF and MF lens-unit choices use the same live selection path; no display text is sent back. */
@Composable
fun FocusControlEditor(
    selectedFocus: Float?, maximum: Float?, enabled: Boolean,
    onSelect: (Float) -> Unit, onAutofocus: () -> Unit,
    modifier: Modifier = Modifier, valueModifier: Modifier = Modifier,
    rotation: Float = 0f, horizontal: Boolean = false,
    epoch: Int = 0, canSelect: () -> Boolean = { true },
) {
    val legalMaximum = maximum?.takeIf { it.isFinite() && it > 0f }
    // ACKs and actual AF motion cannot insert/remove stops while a gesture is using their indices.
    val options = remember(epoch, legalMaximum) { focusControlOptions(legalMaximum, selectedFocus) }
    val selected = if (selectedFocus == null) 0 else focusControlIndex(options, selectedFocus).takeIf { it >= 0 }?.plus(1) ?: -1
    Column(modifier.fillMaxWidth()) {
        if (options.isEmpty()) Text("MF非対応", color = SensorMuted, fontSize = 12.sp)
        else ExposureSelectionScale("FOCUS", listOf("AF") + options.map { if (it == 0f) "∞" else focusDistanceText(it) ?: "—" },
            selected, enabled, { if (it == 0) onAutofocus() else onSelect(options[it - 1]) },
            modifier = valueModifier, rotation = rotation, horizontal = horizontal, canSelect = canSelect,
            descriptions = listOf("AF 自動フォーカス指定") + options.map {
                if (it == 0f) "MF 無限遠指定 0 LU" else "MF ${focusDistanceText(it)} LU指定。実測は診断で確認"
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
