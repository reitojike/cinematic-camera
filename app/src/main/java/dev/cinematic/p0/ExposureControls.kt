package dev.cinematic.p0

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
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
    label: String, information: String,
    modifier: Modifier = Modifier, horizontal: Boolean = false,
    informationColor: Color = ScaleMuted,
    actions: @Composable RowScope.() -> Unit,
) {
    Row(modifier.fillMaxWidth().height(48.dp).padding(start = 14.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            if (!horizontal) {
                Text("$label $information", fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                    color = informationColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    canSelect: () -> Boolean = { true },
    descriptions: List<String> = labels,
    allowCurrentSelection: Boolean = false,
) {
    val interactive = enabled && selectedIndex in labels.indices
    val currentIndex by rememberUpdatedState(selectedIndex)
    val currentCount by rememberUpdatedState(labels.size)
    val currentSelect by rememberUpdatedState(onSelect)
    val currentCanSelect by rememberUpdatedState(canSelect)
    val currentAllowSelection by rememberUpdatedState(allowCurrentSelection)
    val interactiveNow by rememberUpdatedState(interactive)
    val view = LocalView.current
    val stepPx = with(LocalDensity.current) { 24.dp.toPx() }
    var detent by remember(label, labels) { mutableStateOf(ControlDetentState(selectedIndex)) }
    var dispatchedIndex by remember(label, labels) { mutableIntStateOf(selectedIndex) }
    var dragging by remember(label, labels) { mutableStateOf(false) }
    val shownIndex = if (dragging) dispatchedIndex else selectedIndex
    val window = controlScaleWindow(labels.size, shownIndex)
    LaunchedEffect(selectedIndex, label, labels) {
        if (!dragging) { dispatchedIndex = selectedIndex; detent = ControlDetentState(selectedIndex) }
    }
    fun dispatch(target: Int): Boolean {
        val next = dispatchControlScaleSelection(currentCount, dispatchedIndex, target, interactiveNow,
            currentCanSelect, currentAllowSelection, currentSelect) ?: return false
        dispatchedIndex = next
        // Flags 0 preserve both the View's and the user's haptic settings.
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK, 0)
        return true
    }
    val drag = rememberDraggableState { delta ->
        val update = controlDetentDrag(currentCount, detent, -delta, stepPx)
        detent = update.state
        for (next in update.selections) if (!dispatch(next)) {
            detent = ControlDetentState(dispatchedIndex)
            break
        }
    }
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val scaleStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    val labelSizes = remember(labels, density, textMeasurer) {
        labels.map { textMeasurer.measure(it, style = scaleStyle, maxLines = 1, softWrap = false).size }
    }
    val largestLabel = IntSize(labelSizes.maxOfOrNull { it.width } ?: 0, labelSizes.maxOfOrNull { it.height } ?: 0)
    val rulerHeight = with(density) {
        maxOf(56.dp, ((if (horizontal) largestLabel.width else largestLabel.height) + 12.dp.roundToPx()).toDp())
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val available = with(density) { (maxWidth - 96.dp).coerceAtLeast(1.dp).roundToPx() }
        val labelAxis = if (horizontal) largestLabel.height else largestLabel.width
        val visibleCount = controlScaleVisibleCount(available, labelAxis + with(density) { 8.dp.roundToPx() })
        val slots = controlScaleVisibleSlots(labels.size, shownIndex, visibleCount)
        Row(Modifier.fillMaxWidth().height(rulerHeight).alpha(if (interactive) 1f else .45f)
            .draggable(drag, Orientation.Horizontal, enabled = interactive,
                onDragStarted = { dragging = true; dispatchedIndex = currentIndex; detent = ControlDetentState(currentIndex) },
                onDragStopped = { dragging = false })
            .semantics {
                if (interactive && labels.size > 1) {
                    progressBarRangeInfo = ProgressBarRangeInfo(selectedIndex.toFloat(), 0f..(labels.size - 1).toFloat(), labels.size - 2)
                    setProgress { position ->
                        if (!interactiveNow || currentCount <= 0 || !position.isFinite()) false else {
                            dispatch(kotlin.math.round(position).toInt().coerceIn(0, currentCount - 1))
                        }
                    }
                }
            },
            verticalAlignment = Alignment.CenterVertically) {
            ScaleArrow("‹", "$label 前の値", window.previous, interactive) { dispatch(it) }
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
                    slots.forEach { index ->
                        val isCurrent = index != null && index == shownIndex
                        Box(Modifier.weight(1f).fillMaxHeight()
                            .background(if (isCurrent) Color(0xFF1E2225) else Color.Transparent)
                            .clickable(enabled = interactive && index != null, role = Role.RadioButton,
                                onClick = { index?.let { dispatch(it) } })
                            .semantics {
                                selected = isCurrent
                                if (index != null) contentDescription = "$label ${descriptions.getOrElse(index) { labels[index] }}"
                            }, contentAlignment = Alignment.Center) {
                            if (isCurrent) Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(3.dp).background(valueColor))
                            if (index != null) BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                val textRoom = with(density) {
                                    val width = (maxWidth - 8.dp).coerceAtLeast(1.dp).roundToPx()
                                    val height = (maxHeight - 8.dp).coerceAtLeast(1.dp).roundToPx()
                                    if (horizontal) IntSize(height, width) else IntSize(width, height)
                                }
                                val readableSize = remember(labels[index], textRoom, density, textMeasurer) {
                                    controlValueFontSize(18f) { size ->
                                        val measured = textMeasurer.measure(labels[index], style = scaleStyle.copy(fontSize = size.sp),
                                            maxLines = 1, softWrap = false).size
                                        measured.width <= textRoom.width && measured.height <= textRoom.height
                                    }
                                }
                                Text(labels[index], Modifier.layout { measurable, constraints ->
                                    val text = measurable.measure(controlScaleTextConstraints(constraints, horizontal))
                                    val placement = controlScaleTextPlacement(IntSize(text.width, text.height), horizontal)
                                    layout(placement.size.width, placement.size.height) {
                                        text.place(placement.offset.x, placement.offset.y)
                                    }
                                }.graphicsLayer { rotationZ = rotation },
                                    color = if (isCurrent) valueColor else ScaleMuted, fontSize = readableSize.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    fontFamily = FontFamily.Monospace, maxLines = 1, softWrap = false,
                                    overflow = TextOverflow.Visible, textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
            }
            ScaleArrow("›", "$label 次の値", window.next, interactive) { dispatch(it) }
        }
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
    statusDescription: String = "",
    widthSamples: List<String> = listOf(value),
    displayLabel: String = label,
    unitInValue: Boolean = false,
) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val heading = if (unit.isEmpty() || unitInValue) displayLabel else "$displayLabel · $unit"
    val visibleValue = captureControlValueText(value, unit, unitInValue)
    val visibleSamples = if (unitInValue && unit.isNotEmpty()) widthSamples.map { "$it $unit" } else widthSamples
    val accessibleLabel = if (label == "SHUTTER") "シャッタースピード" else label
    val smallStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp)
    val valueStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    val headingSize = textMeasurer.measure(heading, style = smallStyle, maxLines = 1, softWrap = false).size
    val tagSize = textMeasurer.measure(tag.ifEmpty { " " }, style = smallStyle, maxLines = 1, softWrap = false).size
    val values = remember(visibleSamples, visibleValue, density, textMeasurer) {
        (visibleSamples + visibleValue).map { textMeasurer.measure(it, style = valueStyle, maxLines = 1, softWrap = false).size }
    }
    val valueWidth = values.maxOfOrNull { it.width } ?: 0
    val valueHeight = values.maxOfOrNull { it.height } ?: 0
    val natural = with(density) { IntSize(maxOf(headingSize.width, tagSize.width, valueWidth) + 16.dp.roundToPx(),
        headingSize.height + tagSize.height + valueHeight + 16.dp.roundToPx()) }
    val footprint = if (horizontal) IntSize(natural.height, natural.width) else natural
    val height = with(density) { footprint.height.toDp().coerceAtLeast(72.dp) }
    BoxWithConstraints(modifier.height(height).background(if (selected) Color(0xFF1E2225) else Color.Transparent)
        .alpha(if (enabled) 1f else .45f)
        .clickable(enabled = enabled || onUnavailable != null, role = Role.Button) { if (enabled) onClick() else onUnavailable?.invoke() }
        .semantics { this.selected = selected; contentDescription = "$accessibleLabel $value $unit $tag${if (statusDescription.isNotBlank()) " $statusDescription" else ""}${if (!enabled) " 無効: ${unavailableReason ?: "操作不可"}" else ""}" }) {
        if (selected) Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(3.dp).background(ScaleText))
        Box(Modifier.align(Alignment.CenterEnd).width(1.dp).fillMaxHeight().background(Color.White.copy(alpha = .1f)))
        val availableSize = with(density) { IntSize(maxWidth.roundToPx(), maxHeight.roundToPx()) }
        val logicalRoom = if (horizontal) IntSize(availableSize.height, availableSize.width) else availableSize
        // Fit each line separately so a long status tag cannot make the numeric value smaller.
        val sizes = remember(logicalRoom, heading, tag, visibleValue, density, textMeasurer) {
            val padding = with(density) { 8.dp.roundToPx() }
            val roomWidth = (logicalRoom.width - padding).coerceAtLeast(0)
            val roomHeight = (logicalRoom.height - padding).coerceAtLeast(0)
            fun fit(text: String, style: TextStyle, preferred: Float, maxHeight: Int): Float =
                controlValueFontSize(preferred) { size ->
                    val measured = textMeasurer.measure(text, style = style.copy(fontSize = size.sp), maxLines = 1, softWrap = false).size
                    measured.width <= roomWidth && measured.height <= maxHeight
                }
            val smallHeight = roomHeight / 4
            val headingSp = fit(heading, smallStyle, 11f, smallHeight)
            val tagSp = fit(tag.ifEmpty { " " }, smallStyle, 11f, smallHeight)
            val headingHeight = textMeasurer.measure(heading, style = smallStyle.copy(fontSize = headingSp.sp), maxLines = 1, softWrap = false).size.height
            val tagHeight = textMeasurer.measure(tag.ifEmpty { " " }, style = smallStyle.copy(fontSize = tagSp.sp), maxLines = 1, softWrap = false).size.height
            val valueSp = fit(visibleValue, valueStyle, 16f, (roomHeight - headingHeight - tagHeight).coerceAtLeast(0))
            Triple(headingSp, valueSp, tagSp)
        }
        Column(Modifier.align(Alignment.Center).layout { measurable, constraints ->
            val content = measurable.measure(controlScaleTextConstraints(constraints, horizontal))
            val placement = controlScaleTextPlacement(IntSize(content.width, content.height), horizontal)
            layout(placement.size.width, placement.size.height) { content.place(placement.offset.x, placement.offset.y) }
        }.graphicsLayer { rotationZ = rotation }.padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(heading, color = ScaleMuted, style = smallStyle.copy(fontSize = sizes.first.sp), maxLines = 1, softWrap = false, overflow = TextOverflow.Visible)
            Text(visibleValue, color = valueColor, style = valueStyle.copy(fontSize = sizes.second.sp), maxLines = 1, softWrap = false, overflow = TextOverflow.Visible)
            Text(tag.ifEmpty { " " }, color = tagColor, style = smallStyle.copy(fontSize = sizes.third.sp), maxLines = 1, softWrap = false, overflow = TextOverflow.Visible)
        }
    }
}
