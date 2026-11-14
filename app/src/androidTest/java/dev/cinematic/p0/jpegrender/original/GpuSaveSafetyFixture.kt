package dev.cinematic.p0.jpegrender.original

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.BuildConfig

/** Explicit ordinary-test opt-in, never a device permission or a skipped success. */
internal fun requireGpuSaveSafetyFixture() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val args = InstrumentationRegistry.getArguments()
    check(BuildConfig.BUILD_TYPE == "debug" && BuildConfig.DEBUG)
    check(instrumentation.targetContext.packageName == "dev.cinematic.p0")
    check(args.getString("isolated") == "true" && args.getString("gpuSaveSafety") == "true")
    check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Synthetic emulator only" }
}
