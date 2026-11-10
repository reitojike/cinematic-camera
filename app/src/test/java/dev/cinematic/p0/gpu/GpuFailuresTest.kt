package dev.cinematic.p0.gpu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
}
