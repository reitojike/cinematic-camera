package dev.cinematic.p0.gpu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertFalse
import org.junit.Test

class GpuFailuresTest {
    @Test fun failureTypesRetainDiagnosticMessageAndCause() {
        val cause = IllegalStateException("synthetic cause")
        val failures = listOf(
            GpuUnavailableException("capability", cause),
            GpuContextLostException("context", cause),
            GpuMemoryBudgetException("budget", cause),
        )
        assertEquals(listOf("capability", "context", "budget"), failures.map { it.message })
        failures.forEach { assertSame(cause, it.cause) }
    }

    @Test fun causesAreOptional() {
        listOf(
            GpuUnavailableException("capability"),
            GpuContextLostException("context"),
            GpuMemoryBudgetException("budget"),
        ).forEach { assertNull(it.cause) }
    }

    @Test fun policyKeepsGpuAndExportsOnlyBoundedReasonCodes() {
        val unsafeMessage = "synthetic-source-name"
        val failures = listOf(
            GpuUnavailableException(unsafeMessage),
            GpuContextLostException(unsafeMessage),
            GpuMemoryBudgetException(unsafeMessage),
        )
        val policies = failures.map { requireNotNull(gpuFailurePolicy(it)) }
        assertEquals(listOf("gpu_unavailable", "gpu_context_lost", "gpu_memory_budget"),
            policies.map { it.kind.exportReason })
        policies.forEach {
            assertEquals(DevelopEngine.GPU, it.retryEngine)
            assertEquals("GPU を使えません", it.title)
            assertFalse(it.reason.contains(unsafeMessage))
            assertFalse(it.kind.exportReason.contains(unsafeMessage))
        }
    }

    @Test fun cancellationAndUnrelatedFailuresAreNotGpuFailures() {
        assertNull(gpuFailurePolicy(java.util.concurrent.CancellationException()))
        assertNull(gpuFailurePolicy(java.io.IOException("synthetic source")))
        assertNull(gpuFailurePolicy(IllegalArgumentException()))
        assertNull(gpuFailurePolicy(OutOfMemoryError()))
    }
}
