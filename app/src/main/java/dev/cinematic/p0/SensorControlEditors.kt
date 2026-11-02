package dev.cinematic.p0

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
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
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButton(onClick = onAutofocus, enabled = enabled, modifier = Modifier.heightIn(min = 44.dp)) {
            SensorRotated(rotation, horizontal) { SensorLabel("AF", SensorText) }
        }
        if (legal) {
            Slider(number, onValueChange = { onDraft(it.toString()) }, valueRange = 0f..legalMaximum,
                enabled = enabled, modifier = Modifier.weight(1f))
            SensorRotated(rotation, horizontal, valueModifier) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(focusDistanceText(number) ?: "—", color = if (draft != null) SensorAmber else SensorText,
                        fontFamily = FontFamily.Monospace, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
}

/** Only supported Camera2 presets and removal of the override are selectable. No Kelvin mode. */
@Composable
fun WhiteBalanceControlEditor(
    modes: Set<Int>, presentation: WhiteBalancePresentation, enabled: Boolean, onSelect: (Int?) -> Unit,
    modifier: Modifier = Modifier, itemModifier: Modifier = Modifier,
    rotation: Float = 0f, horizontal: Boolean = false,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            whiteBalanceOptions(modes).forEach { option ->
                val current = presentation.hasSelection && presentation.selectedMode == option.mode
                val border = if (current) sensorControlColor(presentation.tone) else Color(0xFF3A3F44)
                TextButton(onClick = { onSelect(option.mode) }, enabled = enabled,
                    border = BorderStroke(if (current) 2.dp else 1.dp, border),
                    modifier = itemModifier.heightIn(min = 44.dp).semantics {
                        selected = current
                        contentDescription = "WB ${option.fullLabel}${if (current) " 選択 ${presentation.stateTag}" else ""}"
                    }) {
                    SensorRotated(rotation, horizontal) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            SensorLabel(option.shortLabel, if (current) sensorControlColor(presentation.tone) else SensorText)
                        }
                    }
                }
            }
        }
    }
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
