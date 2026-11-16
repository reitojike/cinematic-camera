package dev.cinematic.p0.gpu.export

import org.junit.Assert.*
import org.junit.Test

class GpuExportProgressTest {
    @Test fun countsBothQuantileScansAndUnevenLastBandsWithoutReturningToZero() {
        val progress = GpuExportProgress(513, .5)
        var previous = 0f
        repeat(8) { scan ->
            for (rows in listOf(256, 256, 1)) {
                val next = progress.bandCompleted(rows)
                assertTrue(next > previous)
                previous = next
            }
            assertEquals((scan + 1) / 8f, previous, .000001f)
            if (scan < 7) assertTrue(previous < 1f)
        }
        assertEquals(1f, previous, 0f)
    }
    @Test fun zeroStrengthCountsOnlyFiveExecutedScans() {
        val progress = GpuExportProgress(32, 0.0)
        repeat(5) { scan -> assertEquals((scan + 1) / 5f, progress.bandCompleted(32), .000001f) }
    }
}
