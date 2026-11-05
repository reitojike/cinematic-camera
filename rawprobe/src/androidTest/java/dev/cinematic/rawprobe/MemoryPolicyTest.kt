package dev.cinematic.rawprobe

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.PowerManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Small read-only policy measurement. This is the probe process, not the production camera process. */
@RunWith(AndroidJUnit4::class)
class MemoryPolicyTest {
    @Test fun reportNormalAppMemoryPolicy() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        check(args.getString("rawProbe") == "true")
        val emulator = Build.HARDWARE in setOf("goldfish", "ranchu")
        check(emulator || args.getString("allowPhysical") == "true")
        val context = instrumentation.targetContext
        check(context.packageName == "dev.cinematic.rawprobe")
        val activity = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val system = ActivityManager.MemoryInfo().also(activity::getMemoryInfo)
        val process = Debug.MemoryInfo().also(Debug::getMemoryInfo)
        val runtime = Runtime.getRuntime()
        val report = JSONObject()
            .put("schema", "raw-probe-memory-policy-v1")
            .put("runId", args.getString("runId") ?: "manual")
            .put("process", "diagnostic-app-not-production-camera")
            .put("applicationId", context.packageName)
            .put("sdk", Build.VERSION.SDK_INT).put("hardware", Build.HARDWARE)
            .put("abi", Build.SUPPORTED_ABIS.firstOrNull()).put("emulator", emulator)
            .put("memoryClassMiB", activity.memoryClass).put("largeMemoryClassMiB", activity.largeMemoryClass)
            .put("lowRamDevice", activity.isLowRamDevice)
            .put("maxHeapBytes", runtime.maxMemory()).put("committedHeapBytes", runtime.totalMemory())
            .put("usedHeapBytes", runtime.totalMemory() - runtime.freeMemory())
            .put("systemTotalMemBytes", system.totalMem).put("systemAvailableMemBytes", system.availMem)
            .put("systemLowMemory", system.lowMemory).put("systemThresholdBytes", system.threshold)
            .put("nativeAllocatedBytes", Debug.getNativeHeapAllocatedSize()).put("processPssKiB", process.totalPss)
            .put("thermalStatus", (context.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus)
        // Generic sizes/counters only. No serial, account, photo, package inventory or private path.
        File(context.filesDir, "memory-policy.json").writeText(report.toString(2))
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nRawProbeMemory $report\n") })
        assertTrue(runtime.maxMemory() > 0 && system.totalMem > 0)
    }
}
