package dev.cinematic.p0.raw

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** 負荷確認は小型回帰から分離。通常CIや通常合成UIでは実行しない。 */
class GpuDiagnosticLoadTest {
    @Before fun optIn() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("gpuPixelLoadProbe") == "true")
        check(args.getString("isolated") == "true")
    }
    @Test fun syntheticEntrypointsComplete() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(android.content.Intent(context, GpuDiagnosticActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        fun await(label: String, timeout: Long, predicate: () -> Boolean) {
            val deadline = android.os.SystemClock.elapsedRealtime() + timeout
            while (!predicate() && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
            assertTrue(label, predicate())
        }
        try {
            for (mode in listOf("preview", "blur12", "orton12")) {
                instrumentation.runOnMainSync {
                    context.startForegroundService(android.content.Intent(context, GpuDiagnosticService::class.java)
                        .setAction(mode).putExtra("observeMemory", true))
                }
                await("開始 $mode", 10000) { GpuDiagnosticService.active() }
                await("完了 $mode", 180000) { !GpuDiagnosticService.active() }
                assertEquals("診断失敗を完走に数えない $mode", "completed", GpuDiagnosticService.lastOutcome)
                val manager = context.getSystemService(android.app.ActivityManager::class.java)
                @Suppress("DEPRECATION")
                fun running() = manager.getRunningServices(100).any { it.service.className == GpuDiagnosticService::class.java.name }
                await("FGS終了 $mode", 5000) { !running() }
            }
        } finally {
            GpuDiagnosticService.cancel()
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}