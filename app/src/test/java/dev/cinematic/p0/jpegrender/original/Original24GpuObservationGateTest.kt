package dev.cinematic.p0.jpegrender.original

import dev.cinematic.p0.BuildConfig
import org.junit.Assert.assertThrows
import org.junit.Test

class Original24GpuObservationGateTest {
    @Test fun registrationIsBuildRestrictedAndCannotReplaceALiveObserver() {
        val observer = object : Original24GpuOutput.DiagnosticObserver {}
        if (BuildConfig.BUILD_TYPE !in setOf("debug", "gpuDiagnostic")) {
            assertThrows(IllegalStateException::class.java) { Original24GpuOutput.observeForDiagnostics(observer) }
            return
        }
        Original24GpuOutput.observeForDiagnostics(observer).use {
            assertThrows(IllegalStateException::class.java) {
                Original24GpuOutput.observeForDiagnostics(object : Original24GpuOutput.DiagnosticObserver {})
            }
        }
        // A completed scope releases the registration for the next attempt.
        Original24GpuOutput.observeForDiagnostics(observer).close()
    }
}
