package dev.cinematic.p0.raw

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class PhotoAdjustmentsTest {
    @Test fun neutralUsesExactlyTheExistingInputWithoutAllocatingAnotherFrame() {
        val image = LinearSrgbImage(1, 1, floatArrayOf(.1f, .2f, 1.3f))
        assertSame(image, PhotoAdjustments().applyWarmth(image))
        assertSame(image, PhotoAdjustments(exposureEv = 2.0).applyWarmth(image))
    }

    @Test fun warmAndCoolAreDirectionalWhilePreservingNeutralGreyLuminance() {
        val image = LinearSrgbImage(1, 1, floatArrayOf(.2f, .2f, .2f))
        val warm = PhotoAdjustments(100.0).applyWarmth(image).copyPixels()
        val cool = PhotoAdjustments(-100.0).applyWarmth(image).copyPixels()
        assertTrue(warm[0] > warm[1] && warm[1] > warm[2])
        assertTrue(cool[0] < cool[1] && cool[1] < cool[2])
        for (pixel in listOf(warm, cool)) assertEquals(.2, pixel[0] * .2126 + pixel[1] * .7152 + pixel[2] * .0722, 1e-7)
        assertArrayEquals(floatArrayOf(.2f, .2f, .2f), image.copyPixels(), 0f)
    }

    @Test fun transformDoesNotClipHdrOrNegativeLinearChannels() {
        val result = PhotoAdjustments(100.0).applyWarmth(LinearSrgbImage(1, 1, floatArrayOf(2f, -.1f, .2f)))
        assertTrue(result[0] > 1f)
        assertTrue(result[1] < 0f)
    }

    @Test fun cancellationAndInvalidInputsAreRejected() {
        val image = LinearSrgbImage(1, 1, floatArrayOf(.1f, .1f, .1f))
        assertThrows(CancellationException::class.java) { PhotoAdjustments().applyWarmth(image) { true } }
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, -100.01, 100.01))
            assertThrows(IllegalArgumentException::class.java) { PhotoAdjustments(bad) }
        for (bad in listOf(Double.NaN, Double.NEGATIVE_INFINITY, -2.01, 2.01))
            assertThrows(IllegalArgumentException::class.java) { PhotoAdjustments(exposureEv = bad) }
    }
}
