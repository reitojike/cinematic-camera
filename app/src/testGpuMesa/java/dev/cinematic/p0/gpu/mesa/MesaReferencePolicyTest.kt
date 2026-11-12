package dev.cinematic.p0.gpu.mesa

import dev.cinematic.p0.gpu.reference.ImageDiff
import org.junit.Assert.*
import org.junit.Test

class MesaReferencePolicyTest {
    @Test fun largeCpuDifferencesRemainReferenceOnly() {
        assertTrue(ImageDiff.referenceScalar(0.0, 100.0, "Mist/quantile").contains("CPU_REFERENCE_ONLY"))
        assertTrue(ImageDiff.referenceFloats(floatArrayOf(0f), floatArrayOf(100f), "linear")
            .contains("maxAbsolute=100.0"))
        val display = ImageDiff.display(intArrayOf(0xff000000.toInt()), intArrayOf(0xffffffff.toInt()), 1, 1, "display")
        assertEquals(255, display.maxDiff)
        assertEquals(255.0, display.p999, 0.0)
        // An explicit assertion remains useful for copy/GPU agreement and is not a no-op.
        assertThrows(AssertionError::class.java) { display.assertDisplayWithin(0, 0.0) }
    }

    @Test fun referenceReportingStillRejectsNonFiniteAndMalformedInputs() {
        for (invalid in floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) {
                ImageDiff.referenceFloats(floatArrayOf(0f), floatArrayOf(invalid), "gpu")
            }
            assertThrows(IllegalArgumentException::class.java) {
                ImageDiff.referenceFloats(floatArrayOf(invalid), floatArrayOf(0f), "cpu")
            }
            assertThrows(IllegalArgumentException::class.java) { ImageDiff.referenceScalar(0.0, invalid.toDouble(), "scalar GPU") }
            assertThrows(IllegalArgumentException::class.java) { ImageDiff.referenceScalar(invalid.toDouble(), 0.0, "scalar CPU") }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ImageDiff.referenceFloats(floatArrayOf(0f), floatArrayOf(0f, 1f), "shape")
        }
        assertThrows(IllegalArgumentException::class.java) { ImageDiff.referenceFloats(floatArrayOf(), floatArrayOf(), "empty") }
        assertThrows(IllegalArgumentException::class.java) {
            ImageDiff.display(intArrayOf(0), intArrayOf(0, 0), 1, 1, "display shape")
        }
    }
}
