package dev.cinematic.p0

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
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

/** Value selection only: caller retains the existing MF Apply/Cancel/AF action semantics. */
@Composable
fun FocusControlEditor(
    acceptedFocus: Float?, measuredFocus: Float?, draft: String?, maximum: Float?, enabled: Boolean,
    onDraft: (String) -> Unit, onAutofocus: () -> Unit,
    modifier: Modifier = Modifier, valueModifier: Modifier = Modifier,
    rotation: Float = 0f, horizontal: Boolean = false,
) {
    val number = if (draft != null) draft.trim().toFloatOrNull() else acceptedFocus ?: measuredFocus
    val legalMaximum = maximum?.takeIf { it.isFinite() && it > 0f }
    val legal = number != null && number.isFinite() && legalMaximum != null && number in 0f..legalMaximum
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = onAutofocus, enabled = enabled, modifier = Modifier.heightIn(min = 44.dp)) {
                SensorRotated(rotation, horizontal) { SensorLabel("AF", SensorText) }
            }
            if (legal) {
                SensorRotated(rotation, horizontal, valueModifier) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        SensorLabel("LU", SensorMuted)
                        SensorLabel(if (draft != null || acceptedFocus != null) "MF" else "候補", SensorMuted,
                            Modifier.border(1.dp, SensorMuted).padding(horizontal = 4.dp))
                    }
                }
            } else {
                Text(if (legalMaximum == null) "MF非対応" else "MF値の確認待ち", color = SensorMuted,
                    fontSize = 12.sp, modifier = Modifier.weight(1f))
            }
        }
        if (legal) {
            // Keep the exact opening value as a stable extra stop throughout this edit session.
            val options = remember(legalMaximum, acceptedFocus) { focusControlOptions(legalMaximum, number) }
            ExposureSelectionScale("FOCUS", options.map { focusDistanceText(it) ?: "—" },
                focusControlIndex(options, number), enabled, { onDraft(options[it].toString()) },
                rotation = rotation, horizontal = horizontal,
                valueColor = if (draft != null) SensorAmber else SensorText,
                descriptions = options.map { "${focusDistanceText(it)} LU MF候補" })
        }
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

/** Report the quarter-turned footprint before placing the element, rather than rotating clipped text. */
@Composable
private fun SensorRotated(rotation: Float, horizontal: Boolean, modifier: Modifier = Modifier,
    content: @Composable () -> Unit) {
    Box(modifier.layout { measurable, constraints ->
        val child = measurable.measure(controlScaleTextConstraints(constraints, horizontal))
        val placement = controlScaleTextPlacement(IntSize(child.width, child.height), horizontal)
        layout(placement.size.width, placement.size.height) { child.place(placement.offset.x, placement.offset.y) }
    }.graphicsLayer { rotationZ = rotation }, contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun SensorLabel(value: String, color: Color, modifier: Modifier = Modifier) {
    Text(value, modifier, color = color, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
        maxLines = 1, overflow = TextOverflow.Ellipsis)
}
