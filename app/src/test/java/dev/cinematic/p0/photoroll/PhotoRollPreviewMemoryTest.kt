package dev.cinematic.p0.photoroll

import org.junit.Assert.*
import org.junit.Test

class PhotoRollPreviewMemoryTest {
    private fun resources(low: Boolean?, available: Long?) = PhotoRollResources(true, 0, low, available)

    @Test fun belowSaveAllowanceHasIndependentPreviewObservation() {
        for (mib in listOf(256L, 383L, 384L, 511L)) {
            val conditions = resources(false, mib * 1024 * 1024).developConditions(true)
            assertEquals(true, conditions.previewMemoryAvailable)
            assertEquals(false, conditions.memoryBudgetConfirmed)
        }
        assertEquals(true, resources(false, 512L * 1024 * 1024).developConditions(true).memoryBudgetConfirmed)
    }

    @Test fun lowUnknownOrInvalidAvailableMemoryNeverGrantsPreviewAdmission() {
        for (low in listOf(true, null))
            assertNotEquals(true, resources(low, Long.MAX_VALUE).developConditions(true).previewMemoryAvailable)
        for (bytes in listOf(null, 0L, -1L))
            assertNotEquals(true, resources(false, bytes).developConditions(true).previewMemoryAvailable)
    }
}
