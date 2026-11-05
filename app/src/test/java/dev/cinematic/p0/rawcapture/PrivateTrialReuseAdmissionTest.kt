package dev.cinematic.p0.rawcapture

import dev.cinematic.p0.jpegrender.JpegGenerationRecipe
import org.junit.Assert.*
import org.junit.Test

class PrivateTrialReuseAdmissionTest {
    private val allowed = AutoDevelopConditions(true, false, false, false, true, true, true, true)
    private fun session(): PrivateTrialReuseSession {
        val source = SharedOriginalReference("content://example.test/source/1", OriginalFingerprint(3, "a".repeat(64)), OriginalGrant.USER_SELECTED)
        val request = AutoDevelopRequest("synthetic_reuse_admission", 1, source, JpegGenerationRecipe.trial().snapshot,
            JpegGenerationRecipe.TRIAL_SCHEMA, 1024, 1024, AutoRenderQuality.PRIVATE_TRIAL_1024,
            RenderedFaceState.NOT_RUN, RenderedExposureState.DIAGNOSTIC, AutoInputProfile.UNKNOWN)
        return PrivateTrialReuseRegistry.openSession().also { assertNotNull(PrivateTrialReuseRegistry.bind(it, request)) }
    }

    @Test fun unconfirmedMemoryOrBackgroundObservationClosesTheWholeSessionBeforeAdmission() {
        try {
            for (conditions in listOf(allowed.copy(memoryBudgetConfirmed = null), allowed.copy(foreground = false),
                allowed.copy(cameraSessionInUse = true), allowed.copy(thermalAllowed = false))) {
                val session = session()
                DevelopExecution.updateConditions(conditions)
                assertTrue(session.closed)
            }
        } finally { PrivateTrialReuseRegistry.invalidateAll(); DevelopExecution.updateConditions(allowed) }
    }

    @Test fun captureReservationInvalidatesIdleReuseWithoutClaimingAnyResourcesHaveClosed() {
        DevelopExecution.updateConditions(allowed)
        val session = session()
        val capture = DevelopExecution.reserveCapture()
        try { assertTrue(session.closed) }
        finally { DevelopExecution.releaseCapture(capture); PrivateTrialReuseRegistry.invalidateAll() }
    }
}
