package dev.cinematic.p0

import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize

import org.junit.Assert.*
import org.junit.Test

class ControlScaleTest {
    @Test fun middleAndEdgesKeepSelectionInCentre() {
        assertEquals(listOf(0, 1, 2, 3, 4), controlScaleWindow(5, 2).slots)
        assertEquals(listOf(null, null, 0, 1, 2), controlScaleWindow(5, 0).slots)
        assertEquals(listOf(2, 3, 4, null, null), controlScaleWindow(5, 4).slots)
    }
    @Test fun emptyAndInvalidStatesCannotGenerateRequests() {
        listOf(0 to 0, -1 to 0, 5 to -1, 5 to 5).forEach { (count, index) ->
            assertEquals(listOf(null, null, null, null, null), controlScaleWindow(count, index).slots)
            assertNull(controlScaleMove(count, index, 1))
        }
    }
    @Test fun singleChoiceHasNoInventedNeighbours() {
        assertEquals(listOf(null, null, 0, null, null), controlScaleWindow(1, 0).slots)
        assertEquals(0, controlScaleMove(1, 0, 100))
        assertEquals(0, controlScaleMove(1, 0, -100))
    }
    @Test fun dragClampsToRangeWithoutIntegerOverflow() {
        assertEquals(4, controlScaleMove(5, 3, Int.MAX_VALUE))
        assertEquals(0, controlScaleMove(5, 3, Int.MIN_VALUE))
        assertEquals(3, controlScaleMove(5, 2, 1))
    }

    @Test fun detentsAccumulateDistanceAndEmitEachRealCandidateTransition() {
        val first = controlDetentDrag(6, ControlDetentState(1), 10f, 24f)
        assertTrue(first.selections.none())
        val second = controlDetentDrag(6, first.state, 13f, 24f)
        assertTrue(second.selections.none())
        val third = controlDetentDrag(6, second.state, 50f, 24f)
        assertEquals(listOf(2, 3, 4), third.selections.toList())
        assertEquals(ControlDetentState(4, 1f), third.state)
        val reverse = controlDetentDrag(6, third.state, -25f, 24f)
        assertEquals(listOf(3), reverse.selections.toList())
        assertEquals(ControlDetentState(3), reverse.state)
    }

    @Test fun endpointsDiscardGhostDistanceAndOnlyRealMovesCanRequestHaptics() {
        for ((start, direction) in listOf(0 to -1f, 4 to 1f)) {
            val outward = controlDetentDrag(5, ControlDetentState(start), 1000f * direction, 24f)
            assertTrue(outward.selections.none())
            assertEquals(ControlDetentState(start), outward.state)
            val reverse = controlDetentDrag(5, outward.state, -24f * direction, 24f)
            assertEquals(listOf(start - direction.toInt()), reverse.selections.toList())
        }
        val reachEnd = controlDetentDrag(5, ControlDetentState(2), 200f, 24f)
        assertEquals(listOf(3, 4), reachEnd.selections.toList())
        assertEquals(ControlDetentState(4), reachEnd.state)
        assertNull(controlScaleSelection(5, 4, 4, true))
        assertNull(controlScaleSelection(5, 4, 5, true))
        assertNull(controlScaleSelection(5, 4, 3, false))
        assertEquals(3, controlScaleSelection(5, 4, 3, true))
    }

    @Test fun detentsIgnoreInvalidStateAndDoNotUsePresentationLabels() {
        assertTrue(controlDetentDrag(0, ControlDetentState(0), 50f, 24f).selections.none())
        assertTrue(controlDetentDrag(4, ControlDetentState(-1), 50f, 24f).selections.none())
        assertTrue(controlDetentDrag(4, ControlDetentState(1), Float.NaN, 24f).selections.none())
        assertTrue(controlDetentDrag(4, ControlDetentState(1), 50f, 0f).selections.none())
        assertTrue(controlDetentDrag(1, ControlDetentState(0), 1000f, 24f).selections.none())
        // Selection takes only canonical indices, never rounded/observed ISO or shutter text.
        assertEquals(2, controlScaleSelection(4, 1, 2, true))
        val end = controlDetentDrag(4, ControlDetentState(1), Float.MAX_VALUE, .01f)
        assertEquals(listOf(2, 3), end.selections.toList())
        assertEquals(ControlDetentState(3), end.state)
    }

    @Test fun wholeModeBadgeIncludingPaddingAndBorderKeepsItsCentreDuringEitherQuarterTurn() {
        for (density in 1..3) {
            val parent = Constraints(maxWidth = 200 * density, maxHeight = 44 * density)
            val measured = controlScaleTextConstraints(parent, true)
            // AUTO text and its horizontal padding fit as one group before rotation.
            val badge = IntSize(39 * density, 22 * density)
            assertTrue(badge.width <= measured.maxWidth)
            val placement = controlScaleTextPlacement(badge, true)
            assertEquals(22 * density, placement.size.width)
            assertEquals(39 * density, placement.size.height)
            assertEquals(placement.size.width / 2f, placement.offset.x + badge.width / 2f, .5f)
            assertEquals(placement.size.height / 2f, placement.offset.y + badge.height / 2f, .5f)
        }
    }

    @Test fun narrowHorizontalRulerMeasuresLongestStandardShutterAgainstItsHeight() {
        val longestLabel = ExposureStops.shutters.map { it.label.removeSuffix(" s") }.maxBy { it.length }
        assertEquals(7, longestLabel.length) // e.g. 1/32000, the short shutter label most at risk.
        for (density in listOf(1, 2, 3)) {
            val cellWidth = ((320 - 2 * 48) * density / 5f).toInt()
            val cellHeight = 56 * density
            val cell = Constraints(maxWidth = cellWidth, maxHeight = cellHeight)
            val horizontal = controlScaleTextConstraints(cell, quarterTurn = true)
            // A 52dp long label fits the rotated 56dp axis, but was truncated at the old 44.8dp axis.
            val text = IntSize(52 * density, 16 * density)
            assertTrue(text.width > cell.maxWidth)
            assertTrue(text.width <= horizontal.maxWidth)
            assertTrue(text.height <= horizontal.maxHeight)
            val placement = controlScaleTextPlacement(text, quarterTurn = true)
            assertTrue(placement.size.width <= cellWidth)
            assertTrue(placement.size.height <= cellHeight)
            assertEquals(placement.size.width / 2f, placement.offset.x + text.width / 2f, .5f)
            assertEquals(placement.size.height / 2f, placement.offset.y + text.height / 2f, .5f)
        }
    }

    @Test fun portraitKeepsMeasurementAndPlacementWhileQuarterTurnSwapsBothConstraints() {
        val cell = Constraints(minWidth = 8, maxWidth = 44, minHeight = 4, maxHeight = 56)
        assertEquals(cell, controlScaleTextConstraints(cell, quarterTurn = false))
        assertEquals(Constraints(minWidth = 4, maxWidth = 56, minHeight = 8, maxHeight = 44),
            controlScaleTextConstraints(cell, quarterTurn = true))
        val text = IntSize(40, 16)
        val portrait = controlScaleTextPlacement(text, quarterTurn = false)
        assertEquals(text, portrait.size)
        assertEquals(0, portrait.offset.x)
        assertEquals(0, portrait.offset.y)
    }
}
