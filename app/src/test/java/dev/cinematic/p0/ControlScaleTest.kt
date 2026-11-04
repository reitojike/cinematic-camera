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

    @Test fun immediateControlDispatchRechecksBusyForEveryDragStageBeforeFeedback() {
        var busy = false
        var current = 1
        val requests = mutableListOf<Int>()
        val feedback = mutableListOf<Int>()
        val update = controlDetentDrag(6, ControlDetentState(current), 100f, 24f)
        for (target in update.selections) {
            val next = dispatchControlScaleSelection(6, current, target, true, { !busy }) {
                requests += it
                busy = true
            } ?: break
            current = next
            feedback += next
        }
        assertEquals(listOf(2), requests)
        assertEquals(requests, feedback)
        assertEquals(2, current)
        busy = false
        val next = dispatchControlScaleSelection(6, current, 3, true, { !busy }) { requests += it }
        assertEquals(3, next)
        assertEquals(listOf(2, 3), requests)
    }

    @Test fun invalidSameValueOrDisabledDispatchCannotReachTheLiveGateOrIssueRequests() {
        var gateCalls = 0
        var requests = 0
        val dispatch: (Int, Boolean) -> Int? = { target, enabled ->
            dispatchControlScaleSelection(3, 2, target, enabled, { gateCalls++; true }) { requests++ }
        }
        assertNull(dispatch(2, true))
        assertNull(dispatch(3, true))
        assertNull(dispatch(1, false))
        assertEquals(0, gateCalls)
        assertEquals(0, requests)
        assertNull(dispatchControlScaleSelection(3, 2, 1, true, { false }) { requests++ })
        assertEquals(0, requests)
        assertEquals(1, dispatch(1, true))
        assertEquals(1, gateCalls)
        assertEquals(1, requests)
    }

    @Test fun pendingContinuousDetentsAndReverseStepsAreAcceptedBeforeAnyAcknowledgement() {
        var intentIndex = 0
        val acceptedIndex = 0
        var pending = false
        var captureOwned = false
        val offered = mutableListOf<Int>()
        val haptics = mutableListOf<Int>()
        fun dispatch(target: Int) {
            val next = dispatchControlScaleSelection(6, intentIndex, target, true, { !captureOwned }) {
                offered += it
                intentIndex = it
                pending = true
            }
            if (next != null) haptics += next
        }
        val forward = controlDetentDrag(6, ControlDetentState(intentIndex), 72f, 24f)
        forward.selections.forEach(::dispatch)
        assertTrue(pending)
        assertEquals(listOf(1, 2, 3), offered)
        assertEquals(0, acceptedIndex)
        val reverse = controlDetentDrag(6, forward.state, -48f, 24f)
        reverse.selections.forEach(::dispatch)
        assertEquals(listOf(1, 2, 3, 2, 1), offered)
        assertEquals(offered, haptics)
        dispatch(1) // Same intent is not a step even while pending.
        captureOwned = true
        dispatch(2)
        assertEquals(listOf(1, 2, 3, 2, 1), offered)
        assertEquals(offered, haptics)
    }

    @Test fun measuredLabelsReduceNeighboursBeforeChangingTypeSizeAtNarrowWidths() {
        assertEquals(5, controlScaleVisibleCount(300, 60))
        assertEquals(3, controlScaleVisibleCount(299, 60))
        assertEquals(1, controlScaleVisibleCount(179, 60))
        assertEquals(listOf(4), controlScaleVisibleSlots(8, 4, 1))
        assertEquals(listOf(3, 4, 5), controlScaleVisibleSlots(8, 4, 3))
        assertEquals(listOf(null, 0, 1), controlScaleVisibleSlots(8, 0, 3))
        assertEquals(listOf(6, 7, null), controlScaleVisibleSlots(8, 7, 3))
        assertEquals(listOf(null), controlScaleVisibleSlots(0, 0, 1))
    }

    @Test fun fontFitUsesBothMeasuredAxesAndRechecksNonlinearAccessibilityScaling() {
        // A nonlinear measurement predicate stands in for Android's fontScale-aware TextMeasurer.
        // This verifies the fitting policy, not a device font rasterization or a screenshot.
        for (density in listOf(1f, 2f, 3f)) for (fontScale in listOf(1f, 1.3f, 2f, 3f)) {
            for (horizontal in listOf(false, true)) for (longest in listOf(false, true)) {
                val cell = IntSize((72 * density).toInt(), (100 * density).toInt())
                val room = if (horizontal) IntSize(cell.height, cell.width) else cell
                fun measured(size: Float) = IntSize(
                    ((if (longest) 4.8f else 1.7f) * size * density * fontScale + size * size * .02f).toInt(),
                    (size * 1.4f * density * fontScale).toInt())
                var measurements = 0
                val size = controlValueFontSize(18f) {
                    measurements++
                    val actual = measured(it)
                    actual.width <= room.width && actual.height <= room.height
                }
                val actual = measured(size)
                assertTrue(actual.width <= room.width)
                assertTrue(actual.height <= room.height)
                assertTrue(size > 0f && size <= 18f)
                if (measured(18f).width > room.width || measured(18f).height > room.height) assertTrue(measurements > 1)
                else assertEquals(18f, size, 0f)
            }
        }
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
    @Test fun landscapeDialSizingCoversAllSupportedFamiliesAndExactFocusAnchors() {
        val choices = ExposureChoices(listOf(100, 3200), listOf(ShutterStop("1/32000 s", 31250), ShutterStop("30 s", 30_000_000_000)))
        val labels = landscapeDialSizingLabels(choices, -2..2, 0.5, 4f, listOf(1.234f, -1f, 9f), setOf(4, 6))
        for (expected in listOf("100", "3200", "1/32000", "30", "+1.0", "-1.0", "AF", "∞", "1.23", "W.FLUOR", "CLOUDY")) {
            assertTrue("missing $expected", expected in labels)
        }
        assertFalse("unsupported WB mode", "INCAND" in labels)
        assertFalse("invalid focus anchor", "9" in labels)
        assertFalse("unit must stay hidden", "1/32000 s" in labels)
    }

    @Test fun commonLandscapeDialHeightUsesLargestMeasuredLabelAndPortraitRemainsIndividual() {
        assertEquals(56, landscapeDialMeasuredHeightPx(56, 12, emptyList()))
        for (widths in listOf(listOf(22, 64, 31, 40, 71), listOf(44, 128, 62, 80, 142), listOf(68, 196, 95, 124, 218))) {
            val shared = landscapeDialMeasuredHeightPx(56, 12, widths)
            assertEquals(widths.maxOrNull()!! + 12, shared)
            for (angle in listOf(-90, 90)) for (individual in widths) {
                assertEquals("angle $angle", shared, controlDialHeightPx(true, individual, shared))
                assertEquals(individual, controlDialHeightPx(false, individual, shared))
            }
            // The parent constrains the same requested body height in every ruler, including unavailable states.
            val available = 100
            assertTrue(widths.all { minOf(shared, available) == minOf(controlDialHeightPx(true, it, shared), available) })
        }
    }

}
