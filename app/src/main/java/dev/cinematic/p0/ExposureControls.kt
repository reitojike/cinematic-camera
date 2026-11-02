package dev.cinematic.p0

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp

private val ScaleText = Color(0xFFE4E6E8)
private val ScaleMuted = Color(0xFFA3AAB1)
private val ScaleLine = Color(0xFF3A3F44)

@Composable
fun CaptureRulerHeading(
    label: String, information: String, value: String, tag: String,
    modifier: Modifier = Modifier, horizontal: Boolean = false,
    valueColor: Color = ScaleText, informationColor: Color = ScaleMuted,
    actions: @Composable RowScope.() -> Unit,
) {
    Row(modifier.fillMaxWidth().height(48.dp).padding(start = 14.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            if (!horizontal) {
                Text("$label $information", fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                    color = informationColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(value, Modifier.weight(1f, fill = false), color = valueColor, fontFamily = FontFamily.Monospace,
                        fontSize = 22.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (tag.isNotEmpty()) Text(tag, Modifier.border(1.dp, informationColor).padding(horizontal = 4.dp),
                        color = informationColor, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
                }
            }
        }
        actions()
    }
}

/** Presentation only: callers own camera requirements, queueing, and acceptance. */
@Composable
fun ExposureSelectionScale(
    label: String,
    labels: List<String>,
    selectedIndex: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    rotation: Float = 0f,
    horizontal: Boolean = false,
    valueColor: Color = ScaleText,
) {
    val window = controlScaleWindow(labels.size, selectedIndex)
    val interactive = enabled && window.current != null
    val currentIndex by rememberUpdatedState(selectedIndex)
    val currentCount by rememberUpdatedState(labels.size)
    val currentSelect by rememberUpdatedState(onSelect)
    val stepPx = with(LocalDensity.current) { 24.dp.toPx() }
    var dragIndex by remember { mutableIntStateOf(selectedIndex) }
    var remainder by remember { mutableFloatStateOf(0f) }
    val drag = rememberDraggableState { delta ->
        remainder -= delta
        val steps = (remainder / stepPx).toInt()
        if (steps != 0) {
            remainder -= steps * stepPx
            controlScaleMove(currentCount, dragIndex, steps)?.let { next ->
                if (next != dragIndex) { dragIndex = next; currentSelect(next) }
            }
        }
    }
    Row(modifier.fillMaxWidth().height(56.dp).alpha(if (interactive) 1f else .45f)
        .draggable(drag, Orientation.Horizontal, enabled = interactive,
            onDragStarted = { dragIndex = currentIndex; remainder = 0f }),
        verticalAlignment = Alignment.CenterVertically) {
        ScaleArrow("‹", "$label 前の値", window.previous, interactive, onSelect)
        Box(Modifier.weight(1f).fillMaxHeight()) {
            Canvas(Modifier.fillMaxSize()) {
                val spacing = 9.dp.toPx()
                var x = 0f
                while (x < size.width) {
                    drawLine(ScaleLine, androidx.compose.ui.geometry.Offset(x, 0f),
                        androidx.compose.ui.geometry.Offset(x, 6.dp.toPx()), 1.dp.toPx())
                    x += spacing
                }
            }
            Row(Modifier.fillMaxSize()) {
                window.slots.forEach { index ->
                    val isCurrent = index != null && index == selectedIndex
                    Box(Modifier.weight(1f).fillMaxHeight()
                        .background(if (isCurrent) Color(0xFF1E2225) else Color.Transparent)
                        .clickable(enabled = interactive && index != null, role = Role.RadioButton,
                            onClick = { index?.let(onSelect) })
                        .semantics {
                            selected = isCurrent
                            if (index != null) contentDescription = "$label ${labels[index]}"
                        }, contentAlignment = Alignment.Center) {
                        if (isCurrent) Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(3.dp).background(valueColor))
                        if (index != null) BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            val desiredSize = if (horizontal) if (isCurrent) 14f else 12f else if (isCurrent) 18f else 14f
                            val space = if (horizontal) maxHeight.value - 4 else maxWidth.value - 4
                            val readableSize = (space / (labels[index].length.coerceAtLeast(1) * .62f)).coerceIn(11f, desiredSize)
                            Text(labels[index], Modifier.layout { measurable, constraints ->
                                val text = measurable.measure(controlScaleTextConstraints(constraints, horizontal))
                                val placement = controlScaleTextPlacement(IntSize(text.width, text.height), horizontal)
                                layout(placement.size.width, placement.size.height) {
                                    text.place(placement.offset.x, placement.offset.y)
                                }
                            }.graphicsLayer { rotationZ = rotation },
                                color = if (isCurrent) valueColor else ScaleMuted, fontSize = readableSize.sp,
                                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                fontFamily = FontFamily.Monospace, maxLines = 1, softWrap = false,
                                overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }
        ScaleArrow("›", "$label 次の値", window.next, interactive, onSelect)
    }
}

@Composable
private fun ScaleArrow(text: String, description: String, target: Int?, enabled: Boolean, select: (Int) -> Unit) {
    Box(Modifier.width(48.dp).fillMaxHeight().alpha(if (target != null) 1f else .3f)
        .clickable(enabled = enabled && target != null, role = Role.Button) { target?.let(select) }
        .semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Text(text, color = ScaleText, fontSize = 22.sp, fontFamily = FontFamily.Monospace)
    }
}

/** The rail stays fixed; only its contents rotate with the device. */
@Composable
fun CaptureControlKey(
    label: String, value: String, tag: String, selected: Boolean, enabled: Boolean,
    onClick: () -> Unit, modifier: Modifier = Modifier, unit: String = "",
    rotation: Float = 0f, horizontal: Boolean = false, valueColor: Color = ScaleText,
    tagColor: Color = ScaleMuted, unavailableReason: String? = null, onUnavailable: (() -> Unit)? = null,
) {
    Box(modifier.height(72.dp).background(if (selected) Color(0xFF1E2225) else Color.Transparent)
        .alpha(if (enabled) 1f else .45f)
        .clickable(enabled = enabled || onUnavailable != null, role = Role.Button) { if (enabled) onClick() else onUnavailable?.invoke() }
        .semantics { this.selected = selected; contentDescription = "$label $value $unit $tag${if (!enabled) " 無効: ${unavailableReason ?: "操作不可"}" else ""}" }) {
        if (selected) Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(3.dp).background(ScaleText))
        Box(Modifier.align(Alignment.CenterEnd).width(1.dp).fillMaxHeight().background(Color.White.copy(alpha = .1f)))
        Column(Modifier.align(Alignment.Center).graphicsLayer { rotationZ = rotation },
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, color = ScaleMuted, fontFamily = FontFamily.Monospace, fontSize = 11.sp, letterSpacing = .66.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(value, Modifier.weight(1f, fill = false), color = valueColor, fontSize = (if (horizontal) 15 else 16).sp,
                    fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (unit.isNotEmpty()) Text(unit, Modifier.padding(start = 2.dp), color = ScaleMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
            Text(tag, Modifier.border(1.dp, tagColor).padding(horizontal = 4.dp), color = tagColor,
                fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
        }
    }
}
