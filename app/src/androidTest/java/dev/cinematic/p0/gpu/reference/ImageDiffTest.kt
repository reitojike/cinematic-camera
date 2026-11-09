package dev.cinematic.p0.gpu.reference

import dev.cinematic.p0.photoroll.TrialDisplayPixels
import dev.cinematic.p0.raw.FloatPlane
import dev.cinematic.p0.raw.LinearSrgbImage
import org.junit.Assert.*
import org.junit.Test

class ImageDiffTest {
    @Test fun identicalAndOneStepDisplay() {
        val ref = IntArray(1000) { 0xff404040.toInt() }
        val same = ImageDiff.display(ref, ref, 100, 10, "same")
        assertEquals(0, same.maxDiff)
        assertEquals(0.0, same.p999, 0.0)
        assertEquals(0.0, same.meanDiff, 0.0)
        assertEquals(0, same.changedPixels)
        same.assertDisplayWithin(0, 0.0)
        val shifted = ImageDiff.display(ref, IntArray(1000) { 0xff414141.toInt() }, 100, 10, "one-step")
        assertEquals(1, shifted.maxDiff)
        assertEquals(listOf(1, 1, 1), shifted.maxDiffByChannel)
        assertEquals(1.0, shifted.p999, 0.0)
        assertEquals(1.0, shifted.meanDiff, 0.0)
        assertEquals(1000, shifted.changedPixels)
        assertEquals(1.0, shifted.changedFraction, 0.0)
        shifted.assertDisplayWithin()
    }

    @Test fun percentileInterpolatesAtPoint999AndCountsPixelsOnce() {
        val ref = IntArray(1000)
        val actual = ref.copyOf().apply { this[999] = 0x020202 }
        val diff = ImageDiff.display(ref, actual, 100, 10, "SHOULDER")
        assertEquals(2, diff.maxDiff)
        assertEquals(.002, diff.p999, 1e-10) // rank=2996.001; three outlying channels
        assertEquals(.002, diff.meanDiff, 1e-12)
        assertEquals(1, diff.changedPixels)
        assertEquals(.001, diff.changedFraction, 0.0)
        assertEquals(ImageDiff.Location(99, 9, 0, 0.0, 2.0), diff.worst)
        diff.assertDisplayWithin()
        val failure = assertThrows(AssertionError::class.java) { diff.assertDisplayWithin(1, 1.0) }
        assertTrue(failure.message!!.contains("SHOULDER"))
        assertTrue(failure.message!!.contains("x=99, y=9"))
        assertTrue(failure.message!!.contains("reference=0.0, actual=2.0"))
        val widespread = actual.copyOf().apply { this[998] = 0x020202 }
        assertEquals(2.0, ImageDiff.display(ref, widespread, 100, 10, "p999").p999, 0.0)
        assertThrows(AssertionError::class.java) { ImageDiff.display(ref, widespread, 100, 10, "p999").assertDisplayWithin() }
    }

    @Test fun displayEncodingMatchesProductionIncludingTransferBoundaryAndClipping() {
        val values = floatArrayOf(-1f, 0f, .0031308f, .0031309f, .18f, .5f, 1f, 4f, .0001f)
        val image = LinearSrgbImage(3, 1, values)
        assertArrayEquals(TrialDisplayPixels.encode(image, 3) { false }, ImageDiff.encodeDisplay(image))
        val equal = ImageDiff.display(image, image, "linear-display")
        equal.assertDisplayWithin(0, 0.0)
    }

    @Test fun linearAndScalarDifferences() {
        val reference = LinearSrgbImage(2, 1, floatArrayOf(0f, 1f, -2f, 4f, 0f, .5f))
        val actual = LinearSrgbImage(2, 1, floatArrayOf(.0001f, 1.25f, -1f, 4f, 0f, .5f))
        val result = ImageDiff.linear(reference, actual, "R7_TONE")
        assertEquals(1.0, result.maxAbsolute, 0.0)
        assertEquals(.0001f.toDouble() / 1e-4, result.maxRelative, 0.0)
        assertEquals(2, result.worstAbsolute.channel)
        assertEquals(0, result.worstRelative.channel)
        val plane = FloatPlane(2, 1, floatArrayOf(0f, 1f))
        assertEquals(0.0, ImageDiff.linear(plane, plane, "SKIN_WEIGHT").maxAbsolute, 0.0)
        assertEquals(.5, ImageDiff.scalar(.25, .75), 0.0)
        ImageDiff.assertScalarWithin(.25, .75, .5, "mist")
        assertThrows(AssertionError::class.java) { ImageDiff.assertScalarWithin(.25, .75, .49, "mist") }
        assertThrows(AssertionError::class.java) { ImageDiff.assertScalarWithin(.25, Double.NaN, 1.0, "mist") }
        assertEquals(Math.ulp(1f).toDouble(), GpuTolerances.localToneQuantile(1f), 0.0)
    }

    @Test fun rejectsMismatchedGeometryAndIgnoresAlpha() {
        assertThrows(IllegalArgumentException::class.java) { ImageDiff.display(intArrayOf(0), intArrayOf(0), 2, 1, "size") }
        val a = LinearSrgbImage(2, 1, FloatArray(6))
        val b = LinearSrgbImage(1, 2, FloatArray(6))
        assertThrows(IllegalArgumentException::class.java) { ImageDiff.linear(a, b, "shape") }
        ImageDiff.display(intArrayOf(0), intArrayOf(0xff000000.toInt()), 1, 1, "alpha").assertDisplayWithin(0, 0.0)
    }
}
