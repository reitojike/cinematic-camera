package dev.cinematic.p0

import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/** Null edge slots keep the current choice centred without inventing device capabilities. */
data class ControlScaleWindow(val previous: Int?, val current: Int?, val next: Int?,
    val beforePrevious: Int? = null, val afterNext: Int? = null) {
    val slots get() = listOf(beforePrevious, previous, current, next, afterNext)
}

fun controlScaleWindow(count: Int, selectedIndex: Int): ControlScaleWindow =
    if (count <= 0 || selectedIndex !in 0 until count) ControlScaleWindow(null, null, null)
    else ControlScaleWindow((selectedIndex - 1).takeIf { it >= 0 }, selectedIndex,
        (selectedIndex + 1).takeIf { it < count }, (selectedIndex - 2).takeIf { it >= 0 },
        (selectedIndex.toLong() + 2).takeIf { it < count }?.toInt())

/** Clamp relative gestures to reported choices; invalid initial state cannot issue a selection. */
fun controlScaleMove(count: Int, selectedIndex: Int, delta: Int): Int? =
    if (count <= 0 || selectedIndex !in 0 until count) null
    else (selectedIndex.toLong() + delta).coerceIn(0L, count.toLong() - 1).toInt()

/** One action policy for pointer, button and accessibility selection; labels are never parsed. */
internal fun controlScaleSelection(count: Int, current: Int, target: Int, enabled: Boolean): Int? =
    target.takeIf { enabled && current in 0 until count && it in 0 until count && it != current }

/** A synchronous gate prevents feedback for input refused while an immediate request is in flight. */
internal fun dispatchControlScaleSelection(
    count: Int, current: Int, target: Int, enabled: Boolean,
    canSelect: () -> Boolean, select: (Int) -> Unit,
): Int? {
    val next = controlScaleSelection(count, current, target, enabled) ?: return null
    if (!canSelect()) return null
    select(next)
    return next
}

internal data class ControlDetentState(val index: Int, val remainderPx: Float = 0f)
internal data class ControlDetentUpdate(val state: ControlDetentState, val selections: IntProgression = IntRange.EMPTY)

/** Positive distance advances the canonical choice index. Outward distance at an endpoint is discarded. */
internal fun controlDetentDrag(count: Int, state: ControlDetentState, distancePx: Float, stepPx: Float): ControlDetentUpdate {
    if (state.index !in 0 until count || !distancePx.isFinite() || !state.remainderPx.isFinite() ||
        !stepPx.isFinite() || stepPx <= 0f) return ControlDetentUpdate(state.copy(remainderPx = 0f))
    val distance = state.remainderPx.toDouble() + distancePx
    if (state.index == 0 && distance < 0 || state.index == count - 1 && distance > 0) {
        return ControlDetentUpdate(state.copy(remainderPx = 0f))
    }
    val requestedSteps = (distance / stepPx).toLong()
    val actualSteps = requestedSteps.coerceIn(-state.index.toLong(), (count - 1 - state.index).toLong()).toInt()
    val next = state.index + actualSteps
    val remaining = distance - actualSteps.toDouble() * stepPx
    val remainder = if (next == 0 && remaining < 0 || next == count - 1 && remaining > 0) 0f else remaining.toFloat()
    val changes = when {
        next > state.index -> (state.index + 1)..next
        next < state.index -> (state.index - 1) downTo next
        else -> IntRange.EMPTY
    }
    return ControlDetentUpdate(ControlDetentState(next, remainder), changes)
}

/** Text is measured along the axis it occupies after a quarter turn, before ellipsis is decided. */
internal fun controlScaleTextConstraints(cell: Constraints, quarterTurn: Boolean): Constraints =
    if (!quarterTurn) cell else Constraints(
        minWidth = cell.minHeight, maxWidth = cell.maxHeight,
        minHeight = cell.minWidth, maxHeight = cell.maxWidth,
    )

internal data class ControlScaleTextPlacement(val size: IntSize, val offset: IntOffset)

/** Preserve complete labels by reducing neighbours before reducing the selected value's type size. */
internal fun controlScaleVisibleCount(availablePx: Int, labelPx: Int): Int =
    listOf(5, 3, 1).first { it == 1 || labelPx.toLong() * it <= availablePx }

internal fun controlScaleVisibleSlots(count: Int, selectedIndex: Int, visibleCount: Int): List<Int?> {
    if (visibleCount !in listOf(1, 3, 5)) return emptyList()
    val radius = visibleCount / 2
    return (-radius..radius).map { offset ->
        (selectedIndex.toLong() + offset).takeIf { selectedIndex in 0 until count && it in 0L until count.toLong() }?.toInt()
    }
}

/** Re-measure candidate sizes: Android's accessibility sp scaling need not be linear. */
internal fun controlValueFontSize(preferredSp: Float, fits: (Float) -> Boolean): Float {
    if (fits(preferredSp)) return preferredSp
    var low = 0f
    var high = preferredSp
    repeat(16) {
        val middle = (low + high) / 2f
        if (fits(middle)) low = middle else high = middle
    }
    return low
}

/** Keep the text's centre fixed while reporting its rotated footprint to the parent. */
internal fun controlScaleTextPlacement(text: IntSize, quarterTurn: Boolean): ControlScaleTextPlacement =
    if (!quarterTurn) ControlScaleTextPlacement(text, IntOffset.Zero)
    else ControlScaleTextPlacement(IntSize(text.height, text.width),
        IntOffset((text.height - text.width) / 2, (text.width - text.height) / 2))
