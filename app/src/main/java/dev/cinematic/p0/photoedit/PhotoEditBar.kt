package dev.cinematic.p0.photoedit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import dev.cinematic.p0.CaptureDesign
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
 * Caller-owned editor control: continuous relative drag, +/- one supplied step on either side of the thumb.
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
) {
    require(scale.contains(value))
    val currentReadValue by rememberUpdatedState(valueNow)
    val currentKey by rememberUpdatedState(interactionKey)
    val currentEnabled by rememberUpdatedState(enabled)
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentOnFinished by rememberUpdatedState(onValueChangeFinished)
    val pressedKeys = remember(interactionKey, enabled) { mutableSetOf<Key>() }
    val color = if (enabled) valueColor else CaptureDesign.Disabled
    val trackColor = if (enabled) CaptureDesign.Border else CaptureDesign.Disabled
    fun dispatch(input: PhotoEditBarInput?, key: Any) = dispatchPhotoEditBarInput(scale, key, currentKey,
        currentEnabled, currentReadValue, input, currentOnValueChange)
    fun step(direction: Int) = dispatch(PhotoEditBarInput.Step(direction), interactionKey)
    fun finish(key: Any): Boolean {
        if (!currentEnabled || currentKey != key) return false
        currentOnFinished()
        return true
    }
    fun stepAndFinish(direction: Int): Boolean = step(direction).also { if (it) finish(interactionKey) }

    Column(modifier) {
        Row(Modifier.fillMaxWidth()) {
            repeat(5) { index ->
                Text(formatValue(scale.atFraction(index / 4.0)), Modifier.weight(1f),
                    style = CaptureDesign.Label, color = if (enabled) CaptureDesign.Muted else CaptureDesign.Disabled, textAlign = TextAlign.Center)
            }
        }
        Canvas(Modifier.fillMaxWidth().height(48.dp)
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
            .pointerInput(interactionKey, scale, enabled) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = true)
                    val key = interactionKey
                    val inset = 12.dp.toPx()
                    val width = size.width - 2 * inset
                    if (!enabled || !currentEnabled || currentKey != key || width <= 0f ||
                        currentEvent.changes.any { it.id != down.id && it.pressed }) return@awaitEachGesture
                    val startValue = currentReadValue()
                    if (!scale.contains(startValue)) return@awaitEachGesture
                    val gesture = PhotoEditBarGesture(scale, startValue, down.position.x, down.position.y,
                        inset, width, viewConfiguration.touchSlop, inset)
                    while (!gesture.ended) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        val allowed = currentEnabled && currentKey == key && !change.isConsumed &&
                            event.changes.none { it.id != down.id && it.pressed }
                        val update = gesture.update(change.position.x, change.position.y, change.pressed, allowed)
                        if (update.consume) change.consume()
                        dispatch(update.input, key)
                        if (update.completed) finish(key)
                    }
                }
            }) {
            val inset = 12.dp.toPx()
            val width = (size.width - 2 * inset).coerceAtLeast(0f)
            val centerY = size.height / 2
            val thumbX = inset + (scale.fraction(value) * width).toFloat()
            val spacing = 9.dp.toPx()
            var tick = inset
            var index = 0
            while (tick <= inset + width) {
                val length = if (index % 5 == 0) 20.dp.toPx() else 10.dp.toPx()
                drawLine(trackColor, Offset(tick, centerY - length / 2), Offset(tick, centerY + length / 2), 1.dp.toPx())
                tick += spacing; index++
            }
            drawLine(color, Offset(thumbX, centerY - 16.dp.toPx()), Offset(thumbX, centerY + 16.dp.toPx()), 2.dp.toPx())
            // Arrows are visual hints; the existing one-step tap geometry remains authoritative.
            drawLine(color, Offset(inset + 4.dp.toPx(), centerY - 5.dp.toPx()), Offset(inset, centerY), 1.dp.toPx())
            drawLine(color, Offset(inset, centerY), Offset(inset + 4.dp.toPx(), centerY + 5.dp.toPx()), 1.dp.toPx())
            drawLine(color, Offset(inset + width - 4.dp.toPx(), centerY - 5.dp.toPx()), Offset(inset + width, centerY), 1.dp.toPx())
            drawLine(color, Offset(inset + width, centerY), Offset(inset + width - 4.dp.toPx(), centerY + 5.dp.toPx()), 1.dp.toPx())
        }
    }
}
