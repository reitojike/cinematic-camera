package dev.cinematic.p0.gpu

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.SystemClock
import android.view.Choreographer
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.rawcapture.AndroidAutoDevelopQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Fake SAF result only, never opens Files or reads a DNG. Explicit lifecycle transitions model
 * backgrounding; this is not a device/Files UI test or the native AS_SHOT success-path test.
 * Blocking the existing IO queue makes cancellation delivery deterministic without a new loader.
 */
@RunWith(AndroidJUnit4::class)
class GpuDiagnosticPickerLifecycleTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val pickerText = "試験用DNGをFilesから選択"

    @Before fun isolatedEmulatorOnly() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish")) { "Synthetic emulator only" }
        instrumentation.uiAutomation
    }

    @Test fun repeatedSelectionsThenPauseRejectAllLateResultsAfterResume() = withBlockedInput { scenario, monitor, drain ->
        repeat(3) { selection ->
            click(pickerText)
            await("picker result ${selection + 1}") { monitor.hits == selection + 1 }
            awaitText { it == "DNG原本を照合して入力を準備中…" }
        }
        scenario.moveToState(Lifecycle.State.CREATED)
        scenario.moveToState(Lifecycle.State.RESUMED)
        val paused = "DNG表示を中断しました。再開するには選び直してください。"
        awaitText { it == paused }
        drain()
        // The previous IO callbacks are now dispatched. None may restore loading, failure or input.
        assertTrue(nodes().any { it.text?.toString() == paused })
        assertEquals(3, monitor.hits)
    }

    @Test fun syntheticChoiceWinsOverCancelledPickerWork() = withBlockedInput { _, monitor, drain ->
        click(pickerText)
        await("picker result") { monitor.hits == 1 }
        awaitText { it == "DNG原本を照合して入力を準備中…" }
        clickMatching { it.startsWith("合成入力:") }
        click("MIXED") // Same fixture must still switch the mode back from DNG.
        awaitText { it.startsWith("1024 × 768 / 固定seedの合成入力") }
        drain()
        assertTrue(nodes().any { it.text?.toString()?.startsWith("1024 × 768 / 固定seedの合成入力") == true })
        assertTrue(nodes().none { it.text?.toString()?.startsWith("DNGを読み込めません") == true })
    }

    @Test fun failedGpuKeepsRetryVisibleUntilSelectedInputActuallyRenders() = withBlockedInput { scenario, monitor, drain ->
        // A rejected synthetic preview size drives the renderer's real non-context-loss failure path.
        // It is not a device photo or a forced context-loss recovery.
        scenario.onActivity { activity ->
            activity.renderer.openPhoto(dev.cinematic.p0.raw.LinearSrgbImage(1025, 1, FloatArray(1025 * 3) { .2f }))
        }
        awaitText { it.startsWith("GPU診断エラー:") }
        click(pickerText)
        await("picker result after GPU failure") { monitor.hits == 1 }
        awaitText { it == "DNG原本を照合して入力を準備中…" }
        // The selection resets the failed GPU after closing the old photo, but does not hide failure
        // before any new frame exists. This was previously cleared while renderer.failed stayed true.
        assertTrue(nodes().any { it.text?.toString() == "GPUを再作成して再試行" })
        clickMatching { it.startsWith("合成入力:") }
        click("MIXED")
        awaitText { it.startsWith("1024 × 768 / 固定seedの合成入力") }
        await("a real new frame clears the GPU failure") {
            nodes().none { it.text?.toString()?.startsWith("GPU診断エラー:") == true }
        }
        drain()
        assertTrue(nodes().none { it.text?.toString()?.startsWith("DNGを読み込めません") == true })
        assertTrue(nodes().none { it.text?.toString() == "GPUを再作成して再試行" })
    }

    private fun withBlockedInput(test: (scenario: ActivityScenario<GpuPreviewDiagnosticActivity>, monitor: Instrumentation.ActivityMonitor,
        drain: () -> Unit) -> Unit) {
        val result = Instrumentation.ActivityResult(Activity.RESULT_OK,
            Intent().setData(Uri.parse("content://invalid.example/document/test.dng")))
        val monitor = instrumentation.addMonitor(IntentFilter(Intent.ACTION_OPEN_DOCUMENT), result, true)
        val queue = AndroidAutoDevelopQueue.get(instrumentation.targetContext)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocker = queue.manualOriginalIo {
            entered.countDown()
            check(release.await(45, TimeUnit.SECONDS)) { "Test IO barrier timed out" }
        }
        try {
            assertTrue("Existing IO owner did not start", entered.await(10, TimeUnit.SECONDS))
            ActivityScenario.launch(GpuPreviewDiagnosticActivity::class.java).use { scenario ->
                awaitText { it.startsWith("1024 × 768 / 固定seedの合成入力") }
                test(scenario, monitor) {
                    release.countDown()
                    blocker.get(10, TimeUnit.SECONDS)
                    // Runs after all earlier load/cleanup futures, then drains their main callbacks.
                    queue.manualOriginalIo { Unit }.get(10, TimeUnit.SECONDS)
                    awaitUiFrames()
                }
            }
        } finally {
            release.countDown()
            instrumentation.removeMonitor(monitor)
            blocker.get(10, TimeUnit.SECONDS)
            queue.manualOriginalIo { Unit }.get(10, TimeUnit.SECONDS)
            instrumentation.waitForIdleSync()
        }
    }

    private fun awaitUiFrames() {
        instrumentation.waitForIdleSync()
        val frames = CountDownLatch(1)
        instrumentation.runOnMainSync {
            Choreographer.getInstance().postFrameCallback {
                Choreographer.getInstance().postFrameCallback { frames.countDown() }
            }
        }
        assertTrue("Compose did not receive presentation frames", frames.await(5, TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
    }

    private fun click(text: String) = clickMatching { it == text }

    private fun clickMatching(matches: (String) -> Boolean) {
        var found: AccessibilityNodeInfo? = null
        await("visible click target") {
            val current = nodes()
            val target = current.firstOrNull { matches(it.text?.toString().orEmpty()) }
            if (target != null) {
                target.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                found = target
                true
            } else {
                current.firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                false
            }
        }
        instrumentation.waitForIdleSync()
        var target = nodes().firstOrNull { matches(it.text?.toString().orEmpty()) } ?: requireNotNull(found)
        while (!target.isClickable) target = requireNotNull(target.parent)
        assertTrue("Control must accept click", target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }

    private fun awaitText(matches: (String) -> Boolean) = await("diagnostic input status") {
        nodes().any { matches(it.text?.toString().orEmpty()) }
    }

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        do {
            if (condition()) return
            SystemClock.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AssertionError("Timed out: $label")
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        val automation = instrumentation.uiAutomation
        automation.clearCache()
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            result += node
            repeat(node.childCount) { node.getChild(it)?.let(::visit) }
        }
        automation.rootInActiveWindow?.let(::visit)
        return result
    }
}
