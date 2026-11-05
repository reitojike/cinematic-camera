package dev.cinematic.p0.raw

import org.junit.Assert.*
import org.junit.Test

class RawBufferOwnershipTest {
    @Test fun externalArraysAndReturnedCopiesCannotChangeImages() {
        val rgb = floatArrayOf(-.2f, .5f, 1.7f)
        val values = floatArrayOf(-.2f, 1.7f)
        val image = LinearSrgbImage(1, 1, rgb)
        val plane = FloatPlane(2, 1, values)
        rgb.fill(0f); values.fill(0f)
        image.copyPixels().fill(0f); plane.copyValues().fill(0f)
        assertArrayEquals(floatArrayOf(-.2f, .5f, 1.7f), image.copyPixels(), 0f)
        assertArrayEquals(floatArrayOf(-.2f, 1.7f), plane.copyValues(), 0f)
    }

    @Test fun transferredBuffersKeepHdrAndCopiesAreStillIndependent() {
        val image = LinearSrgbImage.takeOwnership(1, 1, floatArrayOf(-.2f, .5f, 1.7f))
        val plane = FloatPlane.takeOwnership(2, 1, floatArrayOf(-.2f, 1.7f))
        image.copyPixels().fill(0f); plane.copyValues().fill(0f)
        assertArrayEquals(floatArrayOf(-.2f, .5f, 1.7f), image.copyPixels(), 0f)
        assertArrayEquals(floatArrayOf(-.2f, 1.7f), plane.copyValues(), 0f)
        val scaled = image.scaled(2.0)
        assertArrayEquals(floatArrayOf(-.4f, 1f, 3.4f), scaled.copyPixels(), 0f)
        assertEquals(-.2f, image[0])
    }

    @Test fun bothOwnershipPathsRejectInvalidShapeAndNonfiniteValues() {
        for (factory in listOf<(Int, Int, FloatArray) -> LinearSrgbImage>(::LinearSrgbImage, LinearSrgbImage::takeOwnership)) {
            assertThrows(IllegalArgumentException::class.java) { factory(0, 1, floatArrayOf()) }
            assertThrows(IllegalArgumentException::class.java) { factory(Int.MAX_VALUE, 2, floatArrayOf()) }
            for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
                assertThrows(IllegalArgumentException::class.java) { factory(1, 1, floatArrayOf(0f, bad, 0f)) }
            }
        }
        for (factory in listOf<(Int, Int, FloatArray) -> FloatPlane>(::FloatPlane, FloatPlane::takeOwnership)) {
            assertThrows(IllegalArgumentException::class.java) { factory(2, 1, floatArrayOf(1f)) }
            for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
                assertThrows(IllegalArgumentException::class.java) { factory(1, 1, floatArrayOf(bad)) }
            }
        }
    }
}
