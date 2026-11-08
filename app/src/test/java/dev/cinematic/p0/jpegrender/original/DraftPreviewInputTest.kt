package dev.cinematic.p0.jpegrender.original

import dev.cinematic.p0.raw.LinearSrgbImage
import org.junit.Assert.*
import org.junit.Test

class DraftPreviewInputTest {
    @Test fun nonsquareAndPortraitBoxReductionStayBoundedAndKeepSourceUnchanged() {
        for ((width, height) in listOf(1024 to 684, 683 to 1024, 64 to 48, 1024 to 4)) {
            val input = LinearSrgbImage(width, height, FloatArray(width * height * 3) { .25f })
            val before = input.copyPixels()
            val result = reduceDraftInput(input) { false }
            val scale = minOf(1.0, 256.0 / maxOf(width, height))
            assertEquals(kotlin.math.floor(width * scale).toInt().coerceAtLeast(1), result.width)
            assertEquals(kotlin.math.floor(height * scale).toInt().coerceAtLeast(1), result.height)
            assertArrayEquals(FloatArray(result.pixelCount * 3) { .25f }, result.copyPixels(), 0f)
            assertArrayEquals(before, input.copyPixels(), 0f)
            if (width <= 256 && height <= 256) assertSame(input, result)
        }
    }
    @Test fun reductionKeepsCancellationCheckpoint() {
        val input = LinearSrgbImage(1024, 20, FloatArray(1024 * 20 * 3))
        assertThrows(java.util.concurrent.CancellationException::class.java) { reduceDraftInput(input) { true } }
    }
}
