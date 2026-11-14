package dev.cinematic.p0.jpegrender.original

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.BuildConfig

/** Synthetic private input only. Reject a missing opt-in instead of reporting a skipped migration. */
internal fun requireGpuInputSafetyFixture() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val arguments = InstrumentationRegistry.getArguments()
    check(BuildConfig.BUILD_TYPE == "debug" && instrumentation.targetContext.packageName == "dev.cinematic.p0") {
        "GPU input safety fixtures require the ordinary debug app"
    }
    check(arguments.getString("isolated") == "true" && arguments.getString("gpuInputSafety") == "true") {
        "Explicit isolated=true and gpuInputSafety=true are required"
    }
    check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Synthetic emulator execution only" }
}
