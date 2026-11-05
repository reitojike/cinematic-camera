package dev.cinematic.p0.photoedit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
 * Unconnected editor control: continuous relative drag, +/- one supplied step on either side of the thumb.
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
) {
    require(scale.contains(value))
    val currentReadValue by rememberUpdatedState(valueNow)
    val currentKey by rememberUpdatedState(interactionKey)
    val currentEnabled by rememberUpdatedState(enabled)
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val color = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else .38f)
    val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) .24f else .12f)
    fun dispatch(input: PhotoEditBarInput?, key: Any) = dispatchPhotoEditBarInput(scale, key, currentKey,
        currentEnabled, currentReadValue, input, currentOnValueChange)
    fun step(direction: Int) = dispatch(PhotoEditBarInput.Step(direction), interactionKey)

    Column(modifier) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, Modifier.weight(1f))
            Text(formatValue(value))
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
                        fraction.isFinite() && dispatch(PhotoEditBarInput.SetValue(scale.atFraction(fraction.toDouble())), interactionKey)
                    }
                    customActions = listOfNotNull(
                        scale.step(value, -1)?.let { CustomAccessibilityAction("$label を1段下げる") { step(-1) } },
                        scale.step(value, 1)?.let { CustomAccessibilityAction("$label を1段上げる") { step(1) } },
                    )
                }
            }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) false
                else when (event.key) {
                    Key.DirectionLeft -> step(-1)
                    Key.DirectionRight -> step(1)
                    else -> false
                }
            }
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
                    }
                }
            }) {
            val inset = 12.dp.toPx()
            val width = (size.width - 2 * inset).coerceAtLeast(0f)
            val centerY = size.height / 2
            val thumbX = inset + (scale.fraction(value) * width).toFloat()
            drawLine(trackColor, Offset(inset, centerY), Offset(inset + width, centerY), 4.dp.toPx())
            drawLine(color, Offset(inset, centerY), Offset(thumbX, centerY), 4.dp.toPx())
            drawCircle(color, 8.dp.toPx(), Offset(thumbX, centerY))
        }
    }
}
