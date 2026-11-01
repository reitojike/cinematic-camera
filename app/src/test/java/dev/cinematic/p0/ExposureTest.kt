package dev.cinematic.p0

import org.junit.Assert.*
import org.junit.Test

class ExposureTest {
    @Test fun convertsDecimalMillisecondsWithoutFloatLoss() {
        assertEquals(8_333_333L, Exposure.millisecondsToNs("8.333333"))
        assertEquals(1_000L, Exposure.millisecondsToNs("0.001"))
    }
    @Test fun acceptsRangeEndpoints() {
        assertEquals(ManualExposure(100, 1_000_000), Exposure.parse("100", "1", 100..800, 1_000_000L..30_000_000L))
        assertEquals(ManualExposure(800, 30_000_000), Exposure.parse("800", "30", 100..800, 1_000_000L..30_000_000L))
    }
    @Test fun rejectsInvalidAndOutOfRangeWithoutClamping() {
        listOf("0", "801", "NaN", "2147483648").forEach { iso ->
            assertTrue(runCatching { Exposure.parse(iso, "10", 100..800, 1L..30_000_000L) }.isFailure)
        }
        listOf("-1", "31", "NaN", "0.0000001", "1e99").forEach { ms ->
            assertTrue(runCatching { Exposure.parse("100", ms, 100..800, 1L..30_000_000L) }.isFailure)
        }
    }
    @Test fun manualSupportRequiresAllEvidence() {
        assertNull(Exposure.manualBlockReason(true, true, true, 100..800, 1L..99L))
        assertNotNull(Exposure.manualBlockReason(false, true, true, 100..800, 1L..99L))
        assertNotNull(Exposure.manualBlockReason(true, false, true, 100..800, 1L..99L))
        assertNotNull(Exposure.manualBlockReason(true, true, false, 100..800, 1L..99L))
        assertNotNull(Exposure.manualBlockReason(true, true, true, null, 1L..99L))
    }
}
