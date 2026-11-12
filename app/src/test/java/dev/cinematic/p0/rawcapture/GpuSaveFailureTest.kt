package dev.cinematic.p0.rawcapture

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class GpuSaveFailureTest {
    @Test fun failureCountMustBePositiveAndSupportsTheSaturatedValue() {
        for (count in listOf(0L, -1L, Long.MIN_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) {
                GpuSaveFailure(GpuSaveFailureReason.RENDER_FAILED, count)
            }
        }
        val saturated = GpuSaveFailure(GpuSaveFailureReason.RENDER_FAILED, Long.MAX_VALUE)
        assertEquals(Long.MAX_VALUE, saturated.count)
        assertTrue(saturated.description.contains(Long.MAX_VALUE.toString()))
    }

    @Test fun everyReasonHasAUniqueFixedCodeAndTheDisplayDisclosesNoCpuFallback() {
        assertEquals(setOf("gpu_unavailable", "gpu_context_lost", "gpu_memory_budget", "gpu_save_failed", "gpu_render_failed"),
            GpuSaveFailureReason.entries.map { it.code }.toSet())
        assertEquals(GpuSaveFailureReason.entries.size, GpuSaveFailureReason.entries.map { it.code }.toSet().size)
        for (reason in GpuSaveFailureReason.entries) {
            val failure = GpuSaveFailure(reason, 3)
            assertTrue(failure.description.contains("3回"))
            assertTrue(failure.description.contains(reason.code))
            assertTrue(failure.description.contains(reason.description))
            assertTrue(failure.description.contains("CPU には切り替えていません"))
        }
    }

    @Test fun publicFailureDescriptionDoesNotIncludeCauseMessagesOrSourceIdentity() {
        val privateDetails = "content://test/private/original.dng?capture=private-name"
        val cause = IOException(privateDetails)
        for (cleanupConfirmed in listOf(false, true)) {
            val failure = GpuSaveRenderException(GpuSaveFailureReason.CONTEXT_LOST, cleanupConfirmed, cause)
            assertSame(cause, failure.cause)
            assertEquals(cleanupConfirmed, failure.cleanupConfirmed)
            assertEquals("gpu_context_lost", failure.message)
            val display = GpuSaveFailure(failure.reason, 1).description
            assertFalse(display.contains(privateDetails))
            assertFalse(display.contains("content://"))
            assertFalse(display.contains("original.dng"))
        }
    }
}
