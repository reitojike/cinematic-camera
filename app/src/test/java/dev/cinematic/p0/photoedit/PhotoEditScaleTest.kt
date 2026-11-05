package dev.cinematic.p0.photoedit

import org.junit.Assert.*
import org.junit.Test

class PhotoEditScaleTest {
    private val scale = PhotoEditScale(0.0, 1.0, 0.1) // Fixture step, not a product default.

    private fun gesture(value: Double = .5, x: Float = 50f, y: Float = 24f) =
        PhotoEditBarGesture(scale, value, x, y, trackLeft = 10f, trackWidth = 100f, touchSlop = 5f, thumbRadius = 8f)

    @Test fun aDistantBarTapChangesOnlyOneStepRatherThanSeekingTheFinger() {
        assertEquals(1, gesture(x = 105f).update(105f, 24f, false, true).stepDirection)
        assertEquals(-1, gesture(x = 15f).update(15f, 24f, false, true).stepDirection)
    }

    @Test fun tapUsesTheContinuousValueWithoutSnappingItToADifferentGrid() {
        val tap = gesture(.337, 100f).update(100f, 24f, false, true)
        assertEquals(.437, scale.step(.337, tap.stepDirection)!!, 1e-12)
    }

    @Test fun tappingTheThumbDoesNotChangeTheValue() {
        for (x in listOf(53f, 60f, 67f)) {
            val tap = gesture(x = x).update(x, 24f, false, true)
            assertNull(tap.value)
            assertEquals(0, tap.stepDirection)
        }
    }

    @Test fun dragStartsWithoutAJumpAndKeepsSubStepChangesBeforeFingerUp() {
        val drag = gesture(x = 90f)
        assertNull(drag.update(94f, 24f, true, true).value)
        assertEquals(.563, drag.update(96.3f, 24f, true, true).value!!, 1e-7)
        assertEquals(.578, drag.update(97.8f, 24f, true, true).value!!, 1e-7)
        assertFalse(drag.ended)
        val released = drag.update(97.8f, 24f, false, true)
        assertEquals(.578, released.value!!, 1e-7)
        assertTrue(released.consume)
        assertTrue(drag.ended)
        assertNull(drag.update(40f, 24f, false, true).value)
    }

    @Test fun draggedValueClampsAtBothEndpointsAndReversesWithoutRetracingOutwardMotion() {
        val drag = gesture()
        assertEquals(1.0, drag.update(250f, 24f, true, true).value!!, 0.0)
        assertEquals(.95, drag.update(245f, 24f, true, true).value!!, 1e-12)
        assertEquals(0.0, drag.update(-250f, 24f, true, true).value!!, 0.0)
        assertEquals(.05, drag.update(-245f, 24f, true, true).value!!, 1e-12)
    }

    @Test fun verticalScrollNeverEmitsATapOnRelease() {
        val scroll = gesture(x = 100f)
        assertEquals(PhotoEditBarUpdate(), scroll.update(101f, 40f, true, true))
        assertTrue(scroll.ended)
        assertNull(scroll.update(100f, 24f, false, true).value)
    }

    @Test fun cancelledConsumedDisabledOrExtraPointerInputCannotBecomeATap() {
        // Compose maps all four interruptions to allowed=false.
        for (pressed in listOf(true, false)) {
            val input = gesture(x = 100f)
            assertEquals(PhotoEditBarUpdate(), input.update(100f, 24f, pressed, false))
            assertTrue(input.ended)
            assertNull(input.update(100f, 24f, false, true).value)
        }
        val drag = gesture()
        assertNotNull(drag.update(75f, 24f, true, true).value)
        assertNull(drag.update(80f, 24f, true, false).value)
        assertNull(drag.update(90f, 24f, false, true).value)
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
}
