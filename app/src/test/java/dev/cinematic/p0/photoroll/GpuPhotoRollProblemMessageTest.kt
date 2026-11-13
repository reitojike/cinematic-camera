package dev.cinematic.p0.photoroll

import dev.cinematic.p0.jpegrender.original.*
import dev.cinematic.p0.gpu.GpuMemoryBudgetException
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class GpuPhotoRollProblemMessageTest {
    @Test fun rejectedMemoryIsSpecificWithoutLeakingObservationOrExceptionText() {
        val observation = Original24MemoryObservation(Original24MemoryStage.ORIGINAL_DECODE, 123456L, false, 456L)
        val failure = Original24MemoryUnavailable(IOException("private-source"), observation)
        val message = requireNotNull(gpuPhotoRollProblemMessage(failure))
        assertTrue(message.contains("空きメモリ"))
        assertTrue(message.contains("受付条件"))
        assertTrue(message.contains("原本は変更していません"))
        assertTrue(message.contains("再試行"))
        assertFalse(message.contains("123456"))
        assertFalse(message.contains("private-source"))
        assertSame(observation, failure.observation)
    }
    @Test fun allocationFailureDoesNotClaimAnObservedAdmissionRejection() {
        val message = requireNotNull(gpuPhotoRollProblemMessage(Original24MemoryUnavailable(OutOfMemoryError())))
        assertTrue(message.contains("メモリを確保できません"))
        assertFalse(message.contains("受付条件"))
    }
    @Test fun unknownAndWrappedErrorsAreNotInferredAsMemory() {
        for (failure in listOf(IOException("memory unavailable private-source"),
            IOException(Original24MemoryUnavailable()), IllegalStateException())) {
            val message = requireNotNull(gpuPhotoRollProblemMessage(failure))
            assertFalse(message.contains("メモリ"))
            assertFalse(message.contains("private-source"))
            assertTrue(message.contains("現像を完了できません"))
        }
    }
    @Test fun otherKnownFailuresAndNoticeKeepTheirPresentation() {
        assertTrue(requireNotNull(gpuPhotoRollProblemMessage(SecurityException())).contains("読み取り権限"))
        assertEquals("source-failure", gpuPhotoRollProblemMessage(PhotoRollSourceFailure("source-failure")))
        assertTrue(requireNotNull(gpuPhotoRollProblemMessage(GpuMemoryBudgetException("private-source"))).contains("GPU メモリ"))
        assertEquals("notice", gpuPhotoRollProblemMessage(null, "notice"))
        assertNull(gpuPhotoRollProblemMessage(null))
    }
    @Test fun rejectedBoundaryDiagnosticHasOnlyStageClassAndActualOsFields() {
        assertEquals("ORIGINAL_MEMORY_REJECTED stage=ORIGINAL_SAVE exception=Original24MemoryUnavailable availMemBytes=499 lowMemory=true thresholdBytes=100 requiredAvailableBytes=0 admissionPolicy=OS_LOW_MEMORY outputPixels=unknown estimatedAdditionalBytes=unknown rgb16PayloadBytes=unknown",
            Original24MemoryObservation(Original24MemoryStage.ORIGINAL_SAVE, 499, true, 100).diagnosticMessage())
    }
    @Test fun pixelsAndPayloadAreNotClaimedAsMeasuredWorkingSet() {
        val message = Original24MemoryObservation(Original24MemoryStage.ORIGINAL_DECODE,
            123, false, 100, 24_000_000).diagnosticMessage()
        assertTrue(message.contains("outputPixels=24000000"))
        assertTrue(message.contains("rgb16PayloadBytes=144000000"))
        assertTrue(message.contains("requiredAvailableBytes=0"))
        assertTrue(message.contains("estimatedAdditionalBytes=unknown"))
        assertTrue(Original24MemoryObservation(Original24MemoryStage.BOUNDED_PREVIEW,
            0, false, 100).diagnosticMessage().contains("requiredAvailableBytes=0"))
    }

    @Test fun osPressureControlsEveryStageWithoutFixedAllowance() {
        val allowance = Original24MemoryPolicy.ORIGINAL_REQUIRED_BYTES
        assertEquals(768L * 1024 * 1024, allowance)
        for (stage in Original24MemoryStage.values()) {
            assertFalse(Original24MemoryPolicy.permits(stage, true, allowance * 2))
            assertFalse(Original24MemoryPolicy.permits(stage, null, allowance * 2))
            assertTrue(Original24MemoryPolicy.permits(stage, false, null))
            assertTrue(Original24MemoryPolicy.permits(stage, false, 0))
            assertTrue(Original24MemoryPolicy.permits(stage, false, allowance))
            assertTrue(Original24MemoryPolicy.permits(stage, false, allowance - 1))
        }
    }
    @Test fun inputAllocationFailuresBeforeAndAfterDecodeBecomeTypedErrors() {
        for (point in 0..2) {
            val oom = OutOfMemoryError("private-input")
            val result = gpuPhotoRollInputResult {
                if (point == 0) throw oom // preflight / provider preparation
                if (point == 1) throw Original24MemoryUnavailable(oom) // existing decode normalization
                throw oom // returned-input / recipe preparation
            }
            val failure = result.exceptionOrNull()
            assertTrue(failure is Original24MemoryUnavailable)
            assertSame(oom, failure!!.cause)
            assertTrue(requireNotNull(gpuPhotoRollProblemMessage(failure)).contains("メモリ"))
        }
    }
    @Test fun otherErrorsCancellationAndSuccessKeepIdentity() {
        val error = AssertionError("not allocation")
        assertSame(error, gpuPhotoRollInputResult<Unit> { throw error }.exceptionOrNull())
        val cancelled = java.util.concurrent.CancellationException()
        assertSame(cancelled, gpuPhotoRollInputResult<Unit> { throw cancelled }.exceptionOrNull())
        assertEquals(17, gpuPhotoRollInputResult { 17 }.getOrThrow())
    }
    @Test fun onlyExactNativeMemoryCodeAtNativeBoundaryIsNormalized() {
        val native = IOException("DNG_MEMORY")
        val converted = runCatching { originalNativeMemoryCall<Unit> { throw native } }.exceptionOrNull()
        assertTrue(converted is Original24MemoryUnavailable)
        assertSame(native, converted!!.cause)
        for (failure in listOf(IOException("DNG_CANCELLED"), IOException("DNG_MEMORY extra"), IOException(native))) {
            assertSame(failure, runCatching { originalNativeMemoryCall<Unit> { throw failure } }.exceptionOrNull())
        }
        assertFalse(requireNotNull(gpuPhotoRollProblemMessage(native)).contains("メモリ")) // arbitrary IO is not JNI provenance
    }
    @Test fun diagnosticExceptionOrOomCannotReplacePrimaryOrObservation() {
        val observation = Original24MemoryObservation(Original24MemoryStage.ORIGINAL_SAVE, 0, true, 100)
        val primary = Original24MemoryUnavailable(observation = observation)
        for (diagnosticFailure in listOf(IllegalStateException(), OutOfMemoryError())) {
            val thrown = runCatching {
                reportOriginalMemoryRejection(primary) { throw diagnosticFailure }
                throw primary
            }.exceptionOrNull()
            assertSame(primary, thrown)
            assertSame(observation, (thrown as Original24MemoryUnavailable).observation)
        }
    }

}
