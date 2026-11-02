package dev.cinematic.p0

import org.junit.Assert.*
import org.junit.Test

class NormalExposurePolicyTest {
    private fun choices(range: LongRange = 1..30_000_000_000, frame: Long? = 30_000_000_000) =
        ExposureStops.choices(100..6400, range, frame, null)

    @Test fun ceilingKeepsShortestShutterAndAllIsoChoicesWithoutMutatingCapabilities() {
        val hardware = 1L..30_000_000_000L
        val selected = choices(hardware)
        assertEquals(ExposureStops.shutters.first().exposureNs, selected.shutter.first().exposureNs)
        assertEquals(1_000_000_000L, selected.shutter.last().exposureNs)
        assertEquals(30_000_000_000L, hardware.last)
        assertEquals(listOf(100, 200, 400, 800, 1600, 3200, 6400), selected.iso)
    }

    @Test fun onlyHistoricalLongValuesAreMigratedAndIsoIsNotCompensated() {
        val previous = ManualExposure(200, 15_000_000_000)
        val restored = NormalExposurePolicy.restore(previous, choices())
        assertTrue(restored.migrated)
        assertEquals(previous, restored.original)
        assertEquals(ManualExposure(200, 1_000_000_000), restored.target)
        assertEquals(ManualExposureInput("200", "1000.000000"), restored.input)
        assertThrows(IllegalArgumentException::class.java) { NormalExposurePolicy.validate(previous) }
        assertEquals(restored.target, NormalExposurePolicy.validate(restored.target))
    }

    @Test fun migrationUsesTheLongestLegalStandardValueInsteadOfInventingOneSecond() {
        val restored = NormalExposurePolicy.restore(ManualExposure(400, 2_000_000_000), choices(frame = 700_000_000))
        assertEquals(600_000_000L, restored.target.exposureNs)
        assertEquals(400, restored.target.iso)
    }

    @Test fun acceptedShortValuesArePreservedRatherThanQuantizedAgain() {
        for (ns in listOf(31_250L, 8_333_333L, 1_000_000_000L)) {
            val value = ManualExposure(220, ns)
            assertFalse(NormalExposurePolicy.restore(value, choices()).migrated)
            assertEquals(value, NormalExposurePolicy.restore(value, choices()).target)
        }
    }

    @Test fun noLegalCandidateBlocksMigrationInsteadOfSendingOutOfRangeValues() {
        val long = ManualExposure(100, 2_000_000_000)
        assertTrue(choices(1_100_000_000..30_000_000_000).shutter.isEmpty())
        assertThrows(IllegalStateException::class.java) { NormalExposurePolicy.restore(long, choices(1_100_000_000..30_000_000_000)) }
        assertThrows(IllegalArgumentException::class.java) { NormalExposurePolicy.restore(long, choices().copy(iso = emptyList())) }
    }

    @Test fun cineFramePeriodAndFastEndpointRemainUnchanged() {
        for (fps in listOf(24, 30, 60, 120)) {
            val selected = ExposureStops.choices(100..6400, 1..30_000_000_000, 30_000_000_000, fps)
            val expected = ExposureStops.shutters.filter { it.exposureNs <= 1_000_000_000L / fps }
            assertEquals(expected, selected.shutter)
        }
    }

    @Test fun restorationIsIdempotentAcrossRebindAndResumeAndInputStillRejectsLongValues() {
        val migrated = NormalExposurePolicy.restore(ManualExposure(100, 30_000_000_000), choices())
        val parsed = Exposure.parse(migrated.input.iso, migrated.input.milliseconds, 100..6400, 1..30_000_000_000)
        assertEquals(migrated.target, parsed)
        assertFalse(NormalExposurePolicy.restore(parsed, choices()).migrated)
        assertThrows(IllegalArgumentException::class.java) { NormalExposurePolicy.validate(ManualExposure(100, 1_000_000_001)) }
    }

    @Test fun oldLongSaveCanMigrateWhenCurrentDeviceRangeIsShorterButInvalidIsoOrMissingPairCannot() {
        val range = 1L..500_000_000L
        val current = choices(range)
        val migrated = NormalExposurePolicy.restoreSaved("200", "15000", 100..6400, range, current)
        assertEquals(15_000_000_000L, migrated.original.exposureNs)
        assertEquals(ManualExposure(200, 500_000_000), migrated.target)
        for ((iso, ms) in listOf("" to "15000", "200" to "", "99" to "15000", "200" to "0", "NaN" to "15000")) {
            assertTrue(runCatching { NormalExposurePolicy.restoreSaved(iso, ms, 100..6400, range, current) }.isFailure)
        }
        assertTrue(runCatching { NormalExposurePolicy.restoreSaved("200", "800", 100..6400, range, current) }.isFailure)
    }
}
