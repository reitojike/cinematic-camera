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

/** Text is measured along the axis it occupies after a quarter turn, before ellipsis is decided. */
internal fun controlScaleTextConstraints(cell: Constraints, quarterTurn: Boolean): Constraints =
    if (!quarterTurn) cell else Constraints(
        minWidth = cell.minHeight, maxWidth = cell.maxHeight,
        minHeight = cell.minWidth, maxHeight = cell.maxWidth,
    )

internal data class ControlScaleTextPlacement(val size: IntSize, val offset: IntOffset)

/** Keep the text's centre fixed while reporting its rotated footprint to the parent. */
internal fun controlScaleTextPlacement(text: IntSize, quarterTurn: Boolean): ControlScaleTextPlacement =
    if (!quarterTurn) ControlScaleTextPlacement(text, IntOffset.Zero)
    else ControlScaleTextPlacement(IntSize(text.height, text.width),
        IntOffset((text.height - text.width) / 2, (text.width - text.height) / 2))
