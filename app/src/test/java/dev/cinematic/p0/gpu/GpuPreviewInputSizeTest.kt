package dev.cinematic.p0.gpu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.floor

class GpuPreviewInputSizeTest {
    @Test fun supportedInputsKeepTheirExactDimensions() {
        for ((width, height) in listOf(1 to 1, 64 to 48, 1024 to 1, 1 to 1024,
            1024 to 768, 768 to 1024, 1024 to 576, 576 to 1024, 886 to 886, 900 to 800)) {
            assertEquals(GpuPreviewInputSize.Size(width, height), GpuPreviewInputSize.fit(width, height))
            GpuPreviewInputSize.checkSupported(width, height)
        }
    }

    @Test fun cameraAspectRatiosKeepThePreviousLongEdgeReduction() {
        for ((width, height, expectedWidth, expectedHeight) in listOf(
            listOf(4000, 3000, 1024, 768), listOf(3000, 4000, 768, 1024),
            listOf(3840, 2160, 1024, 576), listOf(2160, 3840, 576, 1024))) {
            assertEquals(GpuPreviewInputSize.Size(expectedWidth, expectedHeight),
                GpuPreviewInputSize.fit(width, height))
        }
        for (factor in (1..10000).toList() + listOf(Int.MAX_VALUE / 16, Int.MAX_VALUE / 4)) {
            for ((long, short) in listOf(4 to 3, 16 to 9)) {
                if (factor > Int.MAX_VALUE / long) continue
                val width = factor * long; val height = factor * short
                val scale = minOf(1.0, 1024.0 / width)
                val expected = GpuPreviewInputSize.Size(floor(width * scale).toInt(), floor(height * scale).toInt())
                assertEquals(expected, GpuPreviewInputSize.fit(width, height))
                assertEquals(GpuPreviewInputSize.Size(expected.height, expected.width),
                    GpuPreviewInputSize.fit(height, width))
            }
        }
    }

    @Test fun nearSquareInputsUseOneUniformPixelLimitedScale() {
        for (edge in listOf(887, 1024, 1152, 4096, Int.MAX_VALUE)) {
            assertEquals(GpuPreviewInputSize.Size(886, 886), GpuPreviewInputSize.fit(edge, edge))
        }
        assertEquals(GpuPreviewInputSize.Size(1023, 768), GpuPreviewInputSize.fit(1024, 769))
        assertEquals(GpuPreviewInputSize.Size(768, 1023), GpuPreviewInputSize.fit(769, 1024))
        assertEquals(GpuPreviewInputSize.Size(1023, 768), GpuPreviewInputSize.fit(1025, 769))
        assertEquals(GpuPreviewInputSize.Size(887, 885), GpuPreviewInputSize.fit(1024, 1022))
    }

    @Test fun dimensionsAndPixelProductsDoNotOverflow() {
        for ((width, height) in listOf(Int.MAX_VALUE to Int.MAX_VALUE,
            Int.MAX_VALUE to (Int.MAX_VALUE - 1), 65536 to 65536,
            Int.MAX_VALUE to 1, 1 to Int.MAX_VALUE, Int.MAX_VALUE to 1024, 1024 to Int.MAX_VALUE)) {
            val fitted = GpuPreviewInputSize.fit(width, height)
            assertTrue(fitted.width in 1..minOf(width, GpuPreviewInputSize.MAX_LONG_EDGE))
            assertTrue(fitted.height in 1..minOf(height, GpuPreviewInputSize.MAX_LONG_EDGE))
            assertTrue(fitted.width.toLong() * fitted.height <= GpuPreviewInputSize.MAX_PIXELS)
            GpuPreviewInputSize.checkSupported(fitted.width, fitted.height)
            assertThrows(IllegalStateException::class.java) { GpuPreviewInputSize.checkSupported(width, height) }
        }
        assertEquals(GpuPreviewInputSize.Size(1024, 1), GpuPreviewInputSize.fit(Int.MAX_VALUE, 1))
        assertEquals(GpuPreviewInputSize.Size(1, 1024), GpuPreviewInputSize.fit(1, Int.MAX_VALUE))
    }

    @Test fun eitherExceededLimitIsRejectedAtTheGpuBoundary() {
        for ((width, height) in listOf(1025 to 1, 1 to 1025, 1024 to 769,
            769 to 1024, 887 to 887, 1024 to 1024)) {
            assertThrows(IllegalStateException::class.java) { GpuPreviewInputSize.checkSupported(width, height) }
        }
    }

    @Test fun nonPositiveDimensionsAreRejected() {
        for ((width, height) in listOf(0 to 1, 1 to 0, -1 to 1, 1 to -1,
            Int.MIN_VALUE to Int.MIN_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { GpuPreviewInputSize.fit(width, height) }
            assertThrows(IllegalStateException::class.java) { GpuPreviewInputSize.checkSupported(width, height) }
        }
    }
}
