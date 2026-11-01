package dev.cinematic.p0

import org.junit.Assert.*
import org.junit.Test

class ExposureTest {
    @Test fun completeAcceptedPairIsRestoredWithoutReplacingItsValues() {
        val accepted = Exposure.savedManual("200", "8.333333", 100..800, 1L..30_000_000L)
        assertEquals(ManualExposureInput("200", "8.333333"), accepted)
        assertEquals(ManualExposure(200, 8_333_333),
            Exposure.parse(accepted.iso, accepted.milliseconds, 100..800, 1L..30_000_000L))
    }

    @Test fun partialOrMissingAcceptedPairCannotBeRestoredFromInputDrafts() {
        listOf("" to "", "" to "10", "200" to "", " " to "10", "200" to " ").forEach { (iso, ms) ->
            val failure = assertThrows(IllegalArgumentException::class.java) {
                Exposure.savedManual(iso, ms, 100..800, 1L..30_000_000L)
            }
            assertTrue(failure.message!!.contains("incomplete"))
        }
    }

    @Test fun invalidSavedPairIsBlockedWithoutClampingOrRewritingTheRequest() {
        listOf("801" to "10", "200" to "31", "NaN" to "10", "200" to "NaN").forEach { (iso, ms) ->
            assertTrue(runCatching { Exposure.savedManual(iso, ms, 100..800, 1L..30_000_000L) }.isFailure)
        }
        assertTrue(runCatching { Exposure.savedManual("200", "10", null, null) }.isFailure)
    }

    @Test fun sensorPeriodConversionKeepsUnknownSeparateFromReportedRate() {
        assertEquals(60.0, Exposure.sensorPeriodFps(16_666_667L)!!, .00001)
        assertEquals(30.0, Exposure.sensorPeriodFps(33_333_333L)!!, .00001)
        assertEquals(.5, Exposure.sensorPeriodFps(2_000_000_000L)!!, .00001)
        assertNull(Exposure.sensorPeriodFps(null))
        assertNull(Exposure.sensorPeriodFps(0))
        assertNull(Exposure.sensorPeriodFps(-1))
    }

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
