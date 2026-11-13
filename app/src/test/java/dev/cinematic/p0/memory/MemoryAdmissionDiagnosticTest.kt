package dev.cinematic.p0.memory

import dev.cinematic.p0.BuildConfig
import dev.cinematic.p0.photoroll.PhotoRollResources
import org.junit.Assert.*
import org.junit.Test

class MemoryAdmissionDiagnosticTest {
    @Test fun modeRequiresAllThreeBuildFacts() {
        assertTrue(isolatedMemoryDiagnostic(true, "memoryDiagnostic", "dev.cinematic.p0.memorydiagnostic"))
        for (type in listOf("debug", "release", "internal", "gpuDiagnostic", "rawDiagnostic")) {
            assertFalse(isolatedMemoryDiagnostic(true, type, "dev.cinematic.p0.memorydiagnostic"))
            assertFalse(isolatedMemoryDiagnostic(false, type, "dev.cinematic.p0"))
        }
        assertFalse(isolatedMemoryDiagnostic(false, "memoryDiagnostic", "dev.cinematic.p0.memorydiagnostic"))
        assertFalse(isolatedMemoryDiagnostic(true, "memoryDiagnostic", "dev.cinematic.p0"))
        assertEquals(BuildConfig.BUILD_TYPE == "memoryDiagnostic", MemoryAdmissionDiagnostic.enabled)
        assertEquals(BuildConfig.BUILD_TYPE == "memoryDiagnostic", BuildConfig.MEMORY_ADMISSION_RECORD_ONLY)
    }

    @Test fun recordsObservedValuesAndWouldRejectWithoutClaimingMeasuredNeed() {
        val text = memoryAdmissionDiagnosticMessage("ORIGINAL_DECODE", 100, false, 200,
            805306368, false, null, 1234, 9)
        assertTrue(text.contains("availMemBytes=100 lowMemory=false thresholdBytes=200"))
        assertTrue(text.contains("wouldPermit=false reason=BELOW_ADMISSION_REQUIREMENT"))
        assertTrue(text.contains("outputPixels=unknown estimatedAdditionalBytes=unknown rgb16PayloadBytes=unknown"))
        assertTrue(memoryAdmissionDiagnosticMessage("ROLL_SAVE", 900000000, true, 200,
            536870912, false, 24000000, 1, 2).contains("reason=OS_LOW_MEMORY"))
        assertTrue(memoryAdmissionDiagnosticMessage("ROLL_PREVIEW", null, null, null,
            1, false, null, 1, 2).contains("reason=UNKNOWN"))
    }

    @Test fun memoryOnlyOverridePreservesOtherAdmissionConditionsAndRawObservation() {
        val source = PhotoRollResources(false, 6, true, 100, foreground = false, privateStorageAllowed = false)
        val normal = source.developConditions(false, memoryRecordOnly = false)
        val diagnostic = source.developConditions(false, memoryRecordOnly = true)
        assertEquals(false, normal.previewMemoryAvailable)
        assertEquals(false, normal.memoryBudgetConfirmed)
        assertEquals(normal.copy(previewMemoryAvailable = true, memoryBudgetConfirmed = true), diagnostic)
        assertEquals(true, source.lowMemory)
        assertEquals(100L, source.availableBytes)
    }

    @Test fun freeFormStageCannotEnterLog() {
        try {
            memoryAdmissionDiagnosticMessage("private/source", 1, false, 2, 3, false, null, 4, 5)
            fail("free form stage accepted")
        } catch (_: IllegalArgumentException) { }
    }
}
