package dev.cinematic.p0

import org.junit.Assert.*
import org.junit.Test

class ExposureStopsTest {
    @Test fun manufacturerNominalLabelsUseExactIntegerRequests() {
        assertEquals(8_000_000L, ExposureStops.shutters.first { it.label == "1/125 s" }.exposureNs)
        assertEquals(6_250_000L, ExposureStops.shutters.first { it.label == "1/160 s" }.exposureNs)
        assertEquals(300_000_000L, ExposureStops.shutters.first { it.label == "0.3 s" }.exposureNs)
        assertEquals("1/125 s", ExposureStops.label(8_000_000))
        assertEquals("1.3 s", ExposureStops.label(1_300_000_000))
        assertEquals("≈1/125 s", ExposureStops.label(7_999_999))
        assertEquals(listOf(100, 200, 400, 800), ExposureStops.choices(100..800, 1L..1_000_000_000L, null, null).iso)
    }
    @Test fun limitsAreIntersectionAndNeverInventNonstandardEndpoints() {
        val result = ExposureStops.choices(64..900, 7_000_000L..31_000_000L, 25_000_000, null)
        assertEquals(listOf(100, 200, 400, 800), result.iso)
        assertTrue(result.shutter.all { it.exposureNs in 7_000_000..25_000_000 })
        assertFalse(result.shutter.any { it.exposureNs == 7_000_000L })
        assertFalse(result.shutter.any { it.exposureNs == 31_000_000L })
        assertTrue(ExposureStops.choices(13..24, 1L..10L, null, null).iso.isEmpty())
        assertTrue(ExposureStops.choices(null, null, null, null).shutter.isEmpty())
    }
    @Test fun videoPolicyKeepsTheRequestedFpsAndStillIsTemporarilyCappedAtOneSecond() {
        val video = ExposureStops.choices(100..800, 1..30_000_000_000, 30_000_000_000, 60)
        assertTrue(video.shutter.all { it.exposureNs <= 1_000_000_000 / 60 })
        assertTrue(video.shutter.any { it.label == "1/60 s" })
        assertFalse(video.shutter.any { it.label == "1/50 s" })
        val still = ExposureStops.choices(100..800, 1..30_000_000_000, 4_000_000_000, null)
        assertTrue(still.shutter.any { it.label == "1 s" })
        assertFalse(still.shutter.any { it.label == "1.3 s" })
        assertFalse(still.shutter.any { it.label == "30 s" })
    }
    @Test fun seedQuantizesBothPartsToTheNearestLegalStandardPair() {
        val choices = ExposureStops.choices(100..800, 1_000_000L..1_000_000_000L, null, null)
        assertEquals(ManualExposure(200, 8_000_000), choices.pair(220, 8_100_000))
        assertNull(choices.pair(0, 8_000_000))
        assertNull(ExposureChoices(emptyList(), choices.shutter).pair(200, 8_000_000))
    }
}
