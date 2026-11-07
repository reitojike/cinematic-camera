package dev.cinematic.p0.photoedit

import org.junit.Assert.*
import org.junit.Test

class PhotoEditScaleTest {
    private val scale = PhotoEditScale(0.0, 1.0, 0.1) // Fixture step, not a product default.

    private inner class Driver(start: Double = .5, x: Float = 50f) {
        var value = start
        val gesture = PhotoEditBarGesture(scale, start, x, 24f, 10f, 100f, 5f, 8f)
        private val key = Any()
        fun send(x: Float, y: Float = 24f, pressed: Boolean = true, allowed: Boolean = true): PhotoEditBarUpdate {
            val update = gesture.update(x, y, pressed, allowed)
            dispatchPhotoEditBarInput(scale, key, key, allowed, { value }, update.input) { value = it }
            return update
        }
    }

    @Test fun aDistantBarTapChangesOnlyOneStepRatherThanSeekingTheFinger() {
        val right = Driver(x = 105f)
        right.send(105f, pressed = false)
        assertEquals(.6, right.value, 1e-12)
        val left = Driver(x = 15f)
        left.send(15f, pressed = false)
        assertEquals(.4, left.value, 1e-12)
    }

    @Test fun tapUsesTheContinuousValueWithoutSnappingItToADifferentGrid() {
        val tap = Driver(.337, 100f)
        tap.send(100f, pressed = false)
        assertEquals(.437, tap.value, 1e-12)
    }

    @Test fun tappingTheThumbDoesNotChangeTheValue() {
        for (x in listOf(53f, 60f, 67f)) {
            val tap = Driver(x = x)
            assertNull(tap.send(x, pressed = false).input)
            assertEquals(.5, tap.value, 0.0)
        }
    }

    @Test fun dragStartsWithoutAJumpAndKeepsSubStepChangesBeforeFingerUp() {
        val drag = Driver(x = 90f)
        assertNull(drag.send(94f).input)
        drag.send(96.3f)
        assertEquals(.437, drag.value, 1e-7)
        drag.send(97.8f)
        assertEquals(.422, drag.value, 1e-7)
        assertFalse(drag.gesture.ended)
        val released = drag.send(97.8f, pressed = false)
        assertNull(released.input)
        assertEquals(.422, drag.value, 1e-7)
        assertTrue(released.consume)
        assertTrue(drag.gesture.ended)
        assertNull(drag.send(40f, pressed = false).input)
    }

    @Test fun draggedValueClampsAtBothEndpointsAndReversesWithoutRetracingOutwardMotion() {
        val drag = Driver()
        drag.send(-150f)
        assertEquals(1.0, drag.value, 0.0)
        drag.send(-145f)
        assertEquals(.95, drag.value, 1e-12)
        drag.send(350f)
        assertEquals(0.0, drag.value, 0.0)
        drag.send(345f)
        assertEquals(.05, drag.value, 1e-12)
    }

    @Test fun verticalScrollNeverEmitsATapOnRelease() {
        val scroll = Driver(x = 100f)
        assertEquals(PhotoEditBarUpdate(), scroll.send(101f, y = 40f))
        assertTrue(scroll.gesture.ended)
        assertNull(scroll.send(100f, pressed = false).input)
        assertEquals(.5, scroll.value, 0.0)
    }

    @Test fun cancelledConsumedDisabledOrExtraPointerInputCannotBecomeATap() {
        for (pressed in listOf(true, false)) {
            val input = Driver(x = 100f)
            assertEquals(PhotoEditBarUpdate(), input.send(100f, pressed = pressed, allowed = false))
            assertTrue(input.gesture.ended)
            assertNull(input.send(100f, pressed = false).input)
            assertEquals(.5, input.value, 0.0)
        }
        val drag = Driver()
        assertNotNull(drag.send(75f).input)
        val accepted = drag.value
        assertNull(drag.send(80f, allowed = false).input)
        assertNull(drag.send(90f, pressed = false).input)
        assertEquals("Pointer cancel must not roll accepted edits back", accepted, drag.value, 0.0)
    }

    @Test fun endpointTapsDoNotWrapAndRemainingDistanceMayBeLessThanAStep() {
        assertNull(scale.step(0.0, -1))
        assertNull(scale.step(1.0, 1))
        assertEquals(1.0, scale.step(.97, 1)!!, 0.0)
        assertEquals(0.0, scale.step(.03, -1)!!, 0.0)
    }

    @Test fun invalidValuesDirectionsAndUnrepresentableRangesAreRejected() {
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, -.1, 1.1)) assertNull(scale.step(value, 1))
        for (direction in listOf(Int.MIN_VALUE, 0, 2)) assertNull(scale.step(.5, direction))
        for (factory in listOf<() -> PhotoEditScale>(
            { PhotoEditScale(1.0, 1.0, .1) }, { PhotoEditScale(0.0, 1.0, 0.0) },
            { PhotoEditScale(0.0, 1.0, Double.NaN) }, { PhotoEditScale(0.0, 1.0, 1.1) },
            { PhotoEditScale(-Double.MAX_VALUE, Double.MAX_VALUE, 1.0) },
            { PhotoEditScale(1e20, 2e20, 1e-20) },
        )) assertThrows(IllegalArgumentException::class.java) { factory() }
    }

    @Test fun anotherCallersRangeAndStepDoNotAcquireStrengthOrTemperatureDefaults() {
        val supplied = PhotoEditScale(-25.0, 75.0, 2.0)
        assertEquals(14.34, supplied.step(12.34, 1)!!, 1e-12)
        assertEquals(-25.0, supplied.atFraction(0.0), 0.0)
        assertEquals(75.0, supplied.atFraction(1.0), 0.0)
        assertEquals(.3734, supplied.fraction(12.34), 1e-12)
    }

    @Test fun normalizedEndpointsRemainExactEvenWhenTheSpanRoundsAwayTheSmallEndpoint() {
        val wide = PhotoEditScale(-1e16, 1.0, 2.0)
        assertEquals(wide.minimum, wide.atFraction(0.0), 0.0)
        assertEquals(wide.maximum, wide.atFraction(1.0), 0.0)
    }
}
