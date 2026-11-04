package dev.cinematic.p0.photoroll

import dev.cinematic.p0.raw.LinearSrgbImage
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class TrialDisplayPixelsTest {
    @Test fun standardTransferEncodesLinearGrayAndClipsOnlyDisplay() {
        val values = floatArrayOf(0f, 0.18f, 1f, 1f, 0.5f, -1f)
        val input = LinearSrgbImage(2, 1, values)
        assertArrayEquals(intArrayOf(0xff0076ff.toInt(), 0xffffbc00.toInt()), TrialDisplayPixels.encode(input, 2) { false })
        assertArrayEquals(values, input.copyPixels(), 0f)
    }

    @Test fun transferBreakpointAndAboveWhiteHaveOpaqueSrgbBytes() {
        val input = LinearSrgbImage(1, 1, floatArrayOf(0.0031308f, 2f, 0f))
        assertEquals(0xff0aff00.toInt(), TrialDisplayPixels.encode(input, 1) { false }.single())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDimensionsBeyondRequestedPreview() {
        TrialDisplayPixels.encode(LinearSrgbImage(2, 1, FloatArray(6)), 1) { false }
    }

    @Test(expected = CancellationException::class)
    fun cancellationBeforeAllocationDoesNotReturnAnImage() {
        TrialDisplayPixels.encode(LinearSrgbImage(1, 1, FloatArray(3)), 1) { true }
    }

    @Test(expected = CancellationException::class)
    fun cancellationDuringRowsDoesNotReturnPartialDisplay() {
        var checks = 0
        TrialDisplayPixels.encode(LinearSrgbImage(2, 2, FloatArray(12)), 2) { ++checks >= 3 }
    }

    @Test fun resourcesRequireKnownCameraTemperatureAndMemory() {
        val ready = PhotoRollResources(true, 0, false, PhotoRollResources.REQUIRED_AVAILABLE_BYTES)
        assertNull(ready.blockedReason())
        assertNull(ready.copy(thermalStatus = 2).blockedReason())
        listOf(ready.copy(camerasIdle = false), ready.copy(camerasIdle = null),
            ready.copy(thermalStatus = null), ready.copy(thermalStatus = -1), ready.copy(thermalStatus = 3),
            ready.copy(thermalStatus = 7), ready.copy(lowMemory = true), ready.copy(lowMemory = null),
            ready.copy(availableBytes = null), ready.copy(availableBytes = PhotoRollResources.REQUIRED_AVAILABLE_BYTES - 1))
            .forEach { assertNotNull(it.blockedReason()) }
    }

    private class Token { var cancelled = false }

    @Test fun replacingARequestCancelsOldNativeTokenWithoutCancellingTheNewOne() {
        val owner = PhotoRollWorkOwner<Token> { it.cancelled = true }
        val first = Token()
        val second = Token()
        owner.begin(first)
        owner.begin(second)
        assertTrue(first.cancelled)
        assertFalse(second.cancelled)
        owner.finish(first) // Late non-cooperative completion may not clear the current native token.
        owner.cancel()
        assertTrue(second.cancelled)
    }

    @Test fun completedRequestIsNotRetainedOrCancelledOnNextBackground() {
        val owner = PhotoRollWorkOwner<Token> { it.cancelled = true }
        val token = Token()
        owner.begin(token)
        owner.finish(token)
        owner.cancel()
        assertFalse(token.cancelled)
    }
}
