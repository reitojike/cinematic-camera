package dev.cinematic.p0.photoedit

import android.view.HapticFeedbackConstants
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import dev.cinematic.p0.ControlRulerTicks
import dev.cinematic.p0.controlScaleVisibleCount
import dev.cinematic.p0.controlValueFontSize
import androidx.compose.ui.semantics.clearAndSetSemantics
import dev.cinematic.p0.CaptureDesign
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/**
 * Caller-owned editor control: continuous relative drag, +/- one supplied step on either side of the fixed centre.
 * [interactionKey] must change on photo/session/reset/exit. The caller owns values and validates input tokens.
 * [value] is display-only; [valueNow] must read the owner's current draft even before recomposition.
 * The callback must update the owner's current Session with its captured input token, synchronously.
 * Do not read a captured display value or write a captured old Session snapshot back into the owner.
 * No default range, step, temperature unit, persistence or render scheduling is selected by this component.
 */
@Composable
internal fun PhotoEditBar(
    label: String,
    value: Double,
    valueNow: () -> Double,
    scale: PhotoEditScale,
    interactionKey: Any,
    enabled: Boolean,
    formatValue: (Double) -> String,
    onValueChange: (Double) -> Unit,
    modifier: Modifier = Modifier,
    onValueChangeFinished: () -> Unit = {},
    valueColor: Color = CaptureDesign.Text,
    baseline: Double? = null,
    quantization: Double? = null,
) {
    require(scale.contains(value))
    val currentReadValue by rememberUpdatedState(valueNow)
    val currentKey by rememberUpdatedState(interactionKey)
    val currentEnabled by rememberUpdatedState(enabled)
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentOnFinished by rememberUpdatedState(onValueChangeFinished)
    val pressedKeys = remember(interactionKey, enabled) { mutableSetOf<Key>() }
    val color = if (enabled) valueColor else CaptureDesign.Disabled
    val view = LocalView.current
    fun dispatch(input: PhotoEditBarInput?, key: Any): Boolean {
        val before = currentReadValue()
        val changed = dispatchPhotoEditBarInput(scale, key, currentKey,
            currentEnabled, currentReadValue, input, { next ->
                currentOnValueChange(quantization?.let { kotlin.math.round(next / it) * it }?.coerceIn(scale.minimum, scale.maximum) ?: next)
            })
        if (changed && baseline != null && before != baseline && currentReadValue() == baseline) {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK, 0)
        }
        return changed
    }
    fun step(direction: Int) = dispatch(PhotoEditBarInput.Step(direction), interactionKey)
    fun finish(key: Any): Boolean {
        if (!currentEnabled || currentKey != key) return false
        currentOnFinished()
        return true
    }
    fun stepAndFinish(direction: Int): Boolean = step(direction).also { if (it) finish(interactionKey) }

    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val candidates = photoEditRulerValues(scale, value, 5)
        val largest = candidates.filterNotNull().map { measurer.measure(formatValue(it), textStyle, maxLines = 1, softWrap = false).size }
        val available = with(density) { (maxWidth - 96.dp).coerceAtLeast(1.dp).roundToPx() }
        val visibleCount = controlScaleVisibleCount(available, (largest.maxOfOrNull { it.width } ?: 0) + with(density) { 8.dp.roundToPx() })
        val currentVisibleCount by rememberUpdatedState(visibleCount)
        val values = photoEditRulerValues(scale, value, visibleCount)
        val height = with(density) { maxOf(56.dp.roundToPx(), (largest.maxOfOrNull { it.height } ?: 0) + 12.dp.roundToPx()).toDp() }
        Row(Modifier.fillMaxWidth().height(height)
            .semantics {
                contentDescription = label
                stateDescription = formatValue(value)
                // Normalized range avoids inventing physical units for the unagreed temperature policy.
                progressBarRangeInfo = ProgressBarRangeInfo(scale.fraction(value).toFloat(), 0f..1f)
                if (!enabled) disabled()
                else {
                    setProgress { fraction ->
                        (fraction.isFinite() && dispatch(PhotoEditBarInput.SetValue(scale.atFraction(fraction.toDouble())), interactionKey))
                            .also { if (it) finish(interactionKey) }
                    }
                    customActions = listOfNotNull(
                        scale.step(value, -1)?.let { CustomAccessibilityAction("$label を1段下げる") { stepAndFinish(-1) } },
                        scale.step(value, 1)?.let { CustomAccessibilityAction("$label を1段上げる") { stepAndFinish(1) } },
                    )
                }
            }
            .onKeyEvent { event ->
                val direction = when (event.key) { Key.DirectionLeft -> -1; Key.DirectionRight -> 1; else -> 0 }
                when {
                    direction == 0 -> false
                    event.type == KeyEventType.KeyDown -> {
                        val changed = step(direction)
                        if (changed) pressedKeys.add(event.key)
                        // A repeat at the endpoint must not move focus before the accepted key's up.
                        changed || event.key in pressedKeys
                    }
                    event.type == KeyEventType.KeyUp && pressedKeys.remove(event.key) -> finish(interactionKey)
                    else -> false
                }
            }
            .onFocusChanged { if (!it.isFocused) pressedKeys.clear() }
            .focusable(enabled)
            .pointerInput(interactionKey, scale, enabled, baseline, quantization) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = true)
                    val key = interactionKey
                    val width = size.width.toFloat()
                    val dragWidth = (24.dp.toPx() * scale.span / scale.tapStep).toFloat()
                    if (!enabled || !currentEnabled || currentKey != key || width <= 0f ||
                        currentEvent.changes.any { it.id != down.id && it.pressed }) return@awaitEachGesture
                    val startValue = currentReadValue()
                    if (!scale.contains(startValue)) return@awaitEachGesture
                    val gesture = PhotoEditBarGesture(scale, startValue, down.position.x, down.position.y,
                        (width - dragWidth) / 2, dragWidth, viewConfiguration.touchSlop,
                        (width - 96.dp.toPx()).coerceAtLeast(0f) / currentVisibleCount / 2)
                    val rulerDrag = PhotoEditRulerDrag(scale, startValue, baseline, quantization)
                    while (!gesture.ended) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        val allowed = currentEnabled && currentKey == key && !change.isConsumed &&
                            event.changes.none { it.id != down.id && it.pressed }
                        val update = gesture.update(change.position.x, change.position.y, change.pressed, allowed)
                        if (update.consume) change.consume()
                        val input = update.input
                        val ownerValue = currentReadValue()
                        dev.cinematic.p0.diagnostics.PreviewPresentationTrace.input(change.uptimeMillis, !change.pressed) {
                            dispatch(if (input is PhotoEditBarInput.RelativeDrag) {
                                if (allowed && scale.contains(ownerValue))
                                    PhotoEditBarInput.SetValue(rulerDrag.move(input.fractionDelta, ownerValue)) else null
                            } else input, key)
                            if (update.completed) finish(key)
                        }
                    }
                }
            }, verticalAlignment = Alignment.CenterVertically) {
            Text("‹", Modifier.width(48.dp).clearAndSetSemantics {}, color = if (scale.step(value, -1) != null) color else CaptureDesign.Disabled,
                fontSize = 22.sp, fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center)
            Box(Modifier.weight(1f).fillMaxHeight().clearAndSetSemantics {}) {
                ControlRulerTicks(Modifier.fillMaxSize())
                Row(Modifier.fillMaxSize()) {
                    values.forEachIndexed { index, candidate ->
                        val selected = index == visibleCount / 2
                        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()
                            .background(if (selected) CaptureDesign.Selected else Color.Transparent), contentAlignment = Alignment.Center) {
                            if (selected) Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(3.dp).background(color))
                            if (candidate != null) {
                                val text = formatValue(candidate)
                                val roomWidth = with(density) { (maxWidth - 8.dp).coerceAtLeast(1.dp).roundToPx() }
                                val roomHeight = with(density) { (maxHeight - 8.dp).coerceAtLeast(1.dp).roundToPx() }
                                val fontSize = controlValueFontSize(18f) { size ->
                                    val measured = measurer.measure(text, textStyle.copy(fontSize = size.sp), maxLines = 1, softWrap = false).size
                                    measured.width <= roomWidth && measured.height <= roomHeight
                                }
                                Text(text, color = if (selected) color else if (enabled) CaptureDesign.Muted else CaptureDesign.Disabled,
                                    style = textStyle.copy(fontSize = fontSize.sp), maxLines = 1, softWrap = false, textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
            }
            Text("›", Modifier.width(48.dp).clearAndSetSemantics {}, color = if (scale.step(value, 1) != null) color else CaptureDesign.Disabled,
                fontSize = 22.sp, fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center)
        }
    }
}
