package dev.cinematic.p0.gpu

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.cinematic.p0.rawcapture.AndroidAutoDevelopQueue
import dev.cinematic.p0.gpu.preview.GpuPreviewRenderer
import dev.cinematic.p0.gpu.preview.GpuPreviewSnapshot
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TestName
import org.junit.Test
import org.junit.runner.RunWith

/** Fake SAF result only, never opens Files or reads a DNG. Explicit lifecycle transitions model
 * backgrounding; this is not a device/Files UI test or the native AS_SHOT success-path test.
 * Blocking the existing IO queue makes cancellation delivery deterministic without a new loader.
 */
@RunWith(AndroidJUnit4::class)
class GpuDiagnosticPickerLifecycleTest {
    @get:Rule val testName = TestName()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val pickerText = "試験用DNGをFilesから選択"
    private var waitingFor = "not waiting"

    @Before fun isolatedEmulatorOnly() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish")) { "Synthetic emulator only" }
        instrumentation.uiAutomation
    }

    @Test fun repeatedSelectionsThenPauseRejectAllLateResultsAfterResume() = withBlockedInput { scenario, monitor, drain ->
        repeat(3) { selection ->
            click(pickerText)
            await("picker result ${selection + 1}") { monitor.hits == selection + 1 }
            awaitText("DNG loading status") { it == "DNG原本を照合して入力を準備中…" }
        }
        scenario.moveToState(Lifecycle.State.CREATED)
        scenario.moveToState(Lifecycle.State.RESUMED)
        val paused = "DNG表示を中断しました。再開するには選び直してください。"
        awaitText("paused DNG status") { it == paused }
        drain()
        // The previous IO callbacks are now dispatched. None may restore loading, failure or input.
        assertTrue(nodes().any { it.text?.toString() == paused })
        assertEquals(3, monitor.hits)
    }

    @Test fun syntheticChoiceWinsOverCancelledPickerWork() = withBlockedInput { _, monitor, drain ->
        click(pickerText)
        await("picker result") { monitor.hits == 1 }
        awaitText("DNG loading status") { it == "DNG原本を照合して入力を準備中…" }
        clickMatching { it.startsWith("合成入力:") }
        click("MIXED") // Same fixture must still switch the mode back from DNG.
        awaitText("synthetic input status") { it.startsWith("1024 × 768 / 固定seedの合成入力") }
        drain()
        assertTrue(nodes().any { it.text?.toString()?.startsWith("1024 × 768 / 固定seedの合成入力") == true })
        assertTrue(nodes().none { it.text?.toString()?.startsWith("DNGを読み込めません") == true })
    }

    @Test fun failedGpuKeepsRetryVisibleUntilSelectedInputActuallyRenders() = withBlockedInput { scenario, monitor, drain ->
        // A rejected synthetic preview size drives the renderer's real non-context-loss failure path.
        // It is not a device photo or a forced context-loss recovery.
        val selectedRenderer = AtomicReference<GpuPreviewRenderer>()
        scenario.onActivity { activity ->
            selectedRenderer.set(activity.renderer)
            activity.renderer.openPhoto(dev.cinematic.p0.raw.LinearSrgbImage(1025, 1, FloatArray(1025 * 3) { .2f }))
        }
        val renderer = checkNotNull(selectedRenderer.get())
        awaitText("GPU failure status") { it.startsWith("GPU診断エラー:") }
        click(pickerText)
        await("picker result after GPU failure") { monitor.hits == 1 }
        awaitText("DNG loading status") { it == "DNG原本を照合して入力を準備中…" }
        // The selection resets the failed GPU after closing the old photo, but does not hide failure
        // before any new frame exists. This was previously cleared while renderer.failed stayed true.
        awaitText("visible GPU retry control") { it == "GPUを再作成して再試行" }
        val failedDrawCount = snapshot(renderer).drawCount
        clickMatching { it.startsWith("合成入力:") }
        click("MIXED")
        awaitText("synthetic input status") { it.startsWith("1024 × 768 / 固定seedの合成入力") }
        awaitFrameAfter(renderer, failedDrawCount, "a real new frame after GPU failure")
        assertNoGpuFailureAtBottom()
        drain()
        // Re-check the actual selected input after delayed callbacks, not just offscreen absence.
        revealText(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) { it.startsWith("1024 × 768 / 固定seedの合成入力") }
        assertNoGpuFailureAtBottom()
    }

    private fun withBlockedInput(test: (scenario: ActivityScenario<GpuPreviewDiagnosticActivity>, monitor: Instrumentation.ActivityMonitor,
        drain: () -> Unit) -> Unit) {
        val result = Instrumentation.ActivityResult(Activity.RESULT_OK,
            Intent().setData(Uri.parse("content://invalid.example/document/test.dng")))
        // OpenDocument launches a typed intent. An action-only filter does not match its MIME type.
        val filter = IntentFilter(Intent.ACTION_OPEN_DOCUMENT).apply {
            addDataType("*/*")
        }
        val contractIntent = ActivityResultContracts.OpenDocument().createIntent(instrumentation.targetContext,
            arrayOf("image/x-adobe-dng", "image/dng", "application/octet-stream"))
        val match = filter.match(instrumentation.targetContext.contentResolver, contractIntent, true, "GpuPickerTest")
        assertTrue("Fake SAF monitor must match the real OpenDocument contract; match=$match", match >= 0)
        val oldMatch = IntentFilter(Intent.ACTION_OPEN_DOCUMENT).match(
            instrumentation.targetContext.contentResolver, contractIntent, true, "GpuPickerTest")
        Log.i("GpuPickerTest", "OpenDocument action-only match=$oldMatch corrected match=$match type=${contractIntent.type}")
        val monitor = instrumentation.addMonitor(filter, result, true)
        val queue = AndroidAutoDevelopQueue.get(instrumentation.targetContext)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocker = queue.manualOriginalIo {
            entered.countDown()
            check(release.await(45, TimeUnit.SECONDS)) { "Test IO barrier timed out" }
        }
        var testFailure: Throwable? = null
        try {
            assertTrue("Existing IO owner did not start", entered.await(10, TimeUnit.SECONDS))
            ActivityScenario.launch(GpuPreviewDiagnosticActivity::class.java).use { scenario ->
                try {
                    awaitText("synthetic input status") { it.startsWith("1024 × 768 / 固定seedの合成入力") }
                    val currentRenderer = AtomicReference<GpuPreviewRenderer>()
                    scenario.onActivity { currentRenderer.set(it.renderer) }
                    awaitFrameAfter(checkNotNull(currentRenderer.get()), 0L, "initial synthetic frame")
                    test(scenario, monitor) {
                        release.countDown()
                        blocker.get(10, TimeUnit.SECONDS)
                        // Runs after all earlier load/cleanup futures, then drains their main callbacks.
                        queue.manualOriginalIo { Unit }.get(10, TimeUnit.SECONDS)
                        awaitUiFrames()
                    }
                } catch (failure: Throwable) {
                    // Capture while the failing Activity/window still exists, before use closes it.
                    runCatching { captureUiEvidence() }.exceptionOrNull()?.let(failure::addSuppressed)
                    throw failure
                }
            }
        } catch (failure: Throwable) {
            testFailure = failure
            throw failure
        } finally {
            release.countDown()
            instrumentation.removeMonitor(monitor)
            try {
                blocker.get(10, TimeUnit.SECONDS)
                queue.manualOriginalIo { Unit }.get(10, TimeUnit.SECONDS)
                instrumentation.waitForIdleSync()
            } catch (cleanupFailure: Throwable) {
                // Preserve the original failed wait, together with its captured UI evidence.
                if (testFailure != null) testFailure.addSuppressed(cleanupFailure) else throw cleanupFailure
            }
        }
    }

    private fun captureUiEvidence() {
        val root = checkNotNull(instrumentation.targetContext.getExternalFilesDir("gpu-diagnostics"))
        val folder = File(root, "picker-ui/${testName.methodName}-${SystemClock.elapsedRealtime()}")
        check(folder.mkdirs() || folder.isDirectory)
        val device = UiDevice.getInstance(instrumentation)
        var collectionFailure: Throwable? = null
        fun collect(block: () -> Unit) {
            try { block() } catch (failure: Throwable) {
                if (collectionFailure == null) collectionFailure = failure else collectionFailure!!.addSuppressed(failure)
            }
        }
        File(folder, "wait-state.txt").writeText("test=${testName.methodName}\nwaitingFor=$waitingFor\nconditionBoundMs=10000\n")
        collect { device.dumpWindowHierarchy(File(folder, "window.xml")) }
        collect { check(device.takeScreenshot(File(folder, "screen.png"))) { "Unable to capture the failing UI" } }
        // Raw synthetic-test diagnostics stay in the app's private external files. Publish only
        // redacted ANR/launch summaries, never photo names or host-specific paths.
        collect { File(folder, "logcat.txt").writeText(device.executeShellCommand(
            "logcat -d -v brief -t 300 ActivityTaskManager:I ActivityManager:I GpuPickerTest:I AndroidRuntime:E *:S")) }
        Log.i("GpuPickerTest", "Failure UI evidence saved for ${testName.methodName}; waitingFor=$waitingFor")
        collectionFailure?.let { throw it }
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

    private fun awaitText(label: String, matches: (String) -> Boolean) =
        revealText(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, label, matches)

    private fun revealText(scrollAction: Int, label: String = "selected synthetic input after drain",
        matches: (String) -> Boolean) = await(label) {
        val current = nodes()
        val target = current.firstOrNull { matches(it.text?.toString().orEmpty()) }
        if (target != null) {
            target.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
            instrumentation.waitForIdleSync()
            true
        } else {
            current.firstOrNull { it.isScrollable }?.performAction(scrollAction)
            false
        }
    }

    private fun snapshot(renderer: GpuPreviewRenderer): GpuPreviewSnapshot {
        val value = AtomicReference<GpuPreviewSnapshot>()
        renderer.snapshot { value.set(it) }
        await("renderer snapshot") { value.get() != null }
        return checkNotNull(value.get())
    }

    private fun awaitFrameAfter(renderer: GpuPreviewRenderer, drawCount: Long, label: String) {
        val latest = AtomicReference<GpuPreviewSnapshot>()
        await(label) {
            renderer.snapshot { latest.set(it) }
            latest.get()?.let { it.drawCount > drawCount && it.windowCount == 1 && it.allocatedBytes > 0 } == true
        }
    }

    private fun assertNoGpuFailureAtBottom() {
        // Error/retry controls are below the diagnostic footer. Absence near the input picker
        // would be a false positive when those controls are only offscreen.
        awaitText("diagnostic footer") { it.startsWith("CPUは描画提出時間、GPUは取得") }
        nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        awaitUiFrames()
        // A successful swap increments drawCount before a timer-query result can deliver onFrame.
        // Wait for the actual UI recovery callback, rather than assuming two UI frames are enough.
        await("GPU recovery controls cleared at visible diagnostic footer") {
            val current = nodes()
            instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() == instrumentation.targetContext.packageName &&
                current.any { it.text?.toString()?.startsWith("CPUは描画提出時間、GPUは取得") == true } &&
                current.none { it.text?.toString()?.startsWith("GPU診断エラー:") == true } &&
                current.none { it.text?.toString() == "GPUを再作成して再試行" }
        }
    }

    private fun await(label: String, condition: () -> Boolean) {
        // The existing 10s bound is for a local synthetic UI/GL transition, not arbitrary I/O.
        // Poll the required state; scrolling and frame completion must not be replaced by delay.
        waitingFor = label
        Log.i("GpuPickerTest", "WAIT $label")
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
