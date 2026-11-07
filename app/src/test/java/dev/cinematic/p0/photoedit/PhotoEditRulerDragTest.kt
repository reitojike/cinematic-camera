package dev.cinematic.p0.photoedit

import org.junit.Assert.*
import org.junit.Test

class PhotoEditRulerDragTest {
    private val scale = PhotoEditScale(0.0, 1.0, .01)
    @Test fun slowContinuousTravelCanEscapeTheBaselineInBothDirections() {
        for (direction in listOf(-1, 1)) {
            val drag = PhotoEditRulerDrag(scale, .5, .5, .01)
            var result = .5
            repeat(20) { result = drag.move(direction * .001) }
            assertTrue(direction * (result - .5) > 0)
            assertEquals(.5 + direction * .02, result, 1e-12)
        }
    }
    @Test fun roundedBaselineArrivalAlsoHoldsBeforeReversal() {
        val drag = PhotoEditRulerDrag(scale, .49, .5, .01)
        assertEquals(.5, drag.move(.006), 0.0)
        assertEquals(.5, drag.move(-.002), 0.0)
        assertEquals(.49, drag.move(-.01), 1e-12)
    }
    @Test fun concurrentOwnerChangeIsNotOverwrittenByCapturedRawValue() {
        val drag = PhotoEditRulerDrag(scale, .2, .5, .01)
        assertEquals(.21, drag.move(.01), 1e-12)
        assertEquals(.81, drag.move(.01, ownerValue = .8), 1e-12)
    }
    @Test fun integerStrengthRetainsSmallMovesAndClampsAtEndpoints() {
        val drag = PhotoEditRulerDrag(scale, .2, .5, .01)
        repeat(4) { assertEquals(.2, drag.move(.001), 1e-12) }
        assertEquals(.21, drag.move(.002), 1e-12)
        assertEquals(.5, drag.move(2.0), 0.0)
        assertEquals(1.0, drag.move(2.0), 0.0)
        assertEquals(.99, drag.move(-.01), 1e-12)
    }
    @Test fun baselineCatchesCrossingAndRequiresExtraTravelBeforeRelease() {
        val drag = PhotoEditRulerDrag(scale, .4, .5)
        assertEquals(.5, drag.move(.3), 0.0)
        assertEquals(.5, drag.move(.002), 0.0)
        assertTrue(drag.move(.01) > .5)
        assertEquals(.5, drag.move(-.1), 0.0)
        assertEquals(.5, drag.move(-.002), 0.0)
        assertTrue(drag.move(-.01) < .5)
    }
    @Test fun allParametersCatchTheirBaselineInEitherDirection() {
        for ((s, base) in listOf(scale to .5, PhotoEditScale(-100.0, 100.0, 1.0) to 0.0,
            PhotoEditScale(-2.0, 2.0, .1) to 0.0)) {
            for (direction in listOf(-1, 1)) {
                val drag = PhotoEditRulerDrag(s, base + direction * s.tapStep * 2, base)
                assertEquals(base, drag.move(-direction * s.tapStep * 4 / s.span), 0.0)
                assertEquals(base, drag.move(direction * s.tapStep / 4 / s.span), 0.0)
            }
        }
    }
    @Test fun cancelledGestureCannotCarryBaselineHoldIntoANewGesture() {
        val old = PhotoEditRulerDrag(scale, .4, .5)
        assertEquals(.5, old.move(.3), 0.0)
        val replacement = PhotoEditRulerDrag(scale, .7, .5)
        assertEquals(.71, replacement.move(.01), 1e-12)
    }
    @Test fun centredLabelsRetainContinuousValuesAndBlankInvalidNeighbours() {
        assertEquals(listOf(null, null, 0.0, .01, .02), photoEditRulerValues(scale, 0.0, 5))
        assertEquals(listOf(1.0), photoEditRulerValues(scale, 1.0, 1))
        assertEquals(listOf(.99, 1.0, null), photoEditRulerValues(scale, 1.0, 3))
        val values = photoEditRulerValues(scale, .337, 5)
        assertEquals(.337, values[2]!!, 0.0)
        assertEquals(.347, values[3]!!, 1e-12)
    }
}
