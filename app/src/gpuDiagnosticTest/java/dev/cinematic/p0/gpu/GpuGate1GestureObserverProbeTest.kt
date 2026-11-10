package dev.cinematic.p0.gpu

import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.Window
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.gpu.diag.GpuStageTap
import dev.cinematic.p0.gpu.preview.GpuPreviewFrame
import dev.cinematic.p0.gpu.preview.GpuPreviewRenderer
import dev.cinematic.p0.raw.LinearSrgbImage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** Emulator-only functional proof, not a Gate 1 performance measurement or Pixel entry.
 * Real engine and Window callback are delegated unchanged, exactly once. No extra GPU pass/readback.
 * Observer failures invalidate this probe without changing the original delegate's return/exception. */
@RunWith(AndroidJUnit4::class)
class GpuGate1GestureObserverProbeTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val target get() = ins.targetContext
    private val observerFailure = AtomicReference<String?>()
    private inline fun observe(block: () -> Unit) {
        try { block() } catch (failure: Throwable) { observerFailure.compareAndSet(null, failure.javaClass.simpleName) }
    }

    private data class Row(val sequence: Int, val strength: Double, val warmth: Double, val ev: Double,
        val beginNs: Long, var endNs: Long = 0, var frameTimeNs: Long? = null,
        var cpuSubmitNs: Long? = null, var gpuNs: Long? = null, var completed: Boolean = false) {
        fun json() = JSONObject().put("sequence", sequence).put("strength", strength).put("warmth", warmth)
            .put("exposureEv", ev).put("beginNs", beginNs).put("endNs", endNs).put("renderCompleted", completed)
            .put("rendererFrameTimeNs", frameTimeNs ?: JSONObject.NULL).put("cpuSubmitNs", cpuSubmitNs ?: JSONObject.NULL)
            .put("gpuNs", gpuNs ?: JSONObject.NULL)
    }
    private data class Touch(val action: Int, val eventMs: Long, val downMs: Long, val x: Float, val y: Float,
        val history: Int, val pointers: Int, val receivedNs: Long, var handled: Boolean = false) {
        fun json() = JSONObject().put("action", action).put("eventMs", eventMs).put("downMs", downMs)
            .put("x", x.toDouble()).put("y", y.toDouble()).put("historySize", history).put("pointerCount", pointers)
            .put("receivedNs", receivedNs).put("handled", handled)
    }
    private data class State(val draw: Long, val pending: Boolean, val strength: Double, val bytes: Long)

    @Test fun observeRealEngineAndDeliveredTouch() {
        val args = InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue(args.getString("gate1Part2") == "gesture-observer-probe-v1")
        check(args.getString("class") == "dev.cinematic.p0.gpu.GpuGate1GestureObserverProbeTest#observeRealEngineAndDeliveredTouch")
        check(Build.HARDWARE in setOf("ranchu", "goldfish") && args.getString("isolated") == "true")
        check(target.packageName == "dev.cinematic.p0.gpudiagnostic")
        val run = checkNotNull(args.getString("gate1ProbeRun")).also { check(it.matches(Regex("[a-zA-Z0-9_-]{1,40}"))) }
        val output = File(checkNotNull(target.getExternalFilesDir("gpu-diagnostics")), "gesture-probe-$run.json")
        check(output.createNewFile()) { "Do not overwrite prior evidence" }
        val report = JSONObject().put("version", "gesture-observer-probe-v1")
            .put("purpose", "synthetic emulator functional observation only; not criterion 1 or Pixel acceptance")
            .put("frameMeaning", "real engine render completed, FIFO paired with renderer callback after swap; not scanout")
            .put("profile", "candidate triangle: 0.50 -> 0.56 -> 0.50 in 800ms; 400ms each linear segment")
            .put("candidateOnly", true).put("probeEndpointTolerance", 0.005)
        val rows = ArrayList<Row>(128)
        val pending = ArrayDeque<Row>()
        val touches = ArrayList<Touch>(128)
        val injected = JSONArray()
        var scenario: ActivityScenario<GpuPreviewDiagnosticActivity>? = null
        var render: GpuPreviewRenderer? = null
        var callback: Window.Callback? = null
        var originalEngine: StageClearGpu? = null
        var originalFrame: ((GpuPreviewFrame) -> Unit)? = null
        var failed: Throwable? = null
        try {
            scenario = ActivityScenario.launch(GpuPreviewDiagnosticActivity::class.java)
            val activity = AtomicReference<GpuPreviewDiagnosticActivity>()
            scenario.onActivity { activity.set(it); render = it.renderer }
            val renderer = checkNotNull(render)
            await { state(renderer).let { it.draw > 0 && !it.pending && it.bytes > 0 } }
            val node = revealStrength()
            val set = Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, .5f) }
            check(node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, set))
            await { state(renderer).let { !it.pending && abs(it.strength - .5) < 1e-6 } }
            ins.waitForIdleSync()
            val slider = revealStrength()
            val bounds = Rect().also(slider::getBoundsInScreen)
            var slop = 0
            var density = 0f
            scenario.onActivity {
                slop = ViewConfiguration.get(it).scaledTouchSlop
                density = it.resources.displayMetrics.density
            }
            val track = bounds.width().toFloat() - 2f * density // Official Material3 active thumb model; probe verifies actual result.
            val center = bounds.exactCenterX()
            val y = bounds.exactCenterY()
            check(track > 0 && center + slop + track * .06f < bounds.right - density)
            report.put("geometry", JSONObject().put("left", bounds.left).put("right", bounds.right)
                .put("top", bounds.top).put("bottom", bounds.bottom).put("density", density.toDouble())
                .put("touchSlopPx", slop).put("activeThumbModelPx", (2f * density).toDouble())
                .put("valuePerPx", 1.0 / track).put("modelRequiresApkDependencyVerification", true))
            val startReference = AtomicReference<State>()
            onGl(renderer) {
                val start = stateOnGl(renderer)
                check(!start.pending)
                startReference.set(start)
                originalEngine = field(renderer, "engine") as StageClearGpu
                @Suppress("UNCHECKED_CAST")
                val existingFrame = field(renderer, "onFrame") as (GpuPreviewFrame) -> Unit
                originalFrame = existingFrame
                val actual = checkNotNull(originalEngine)
                val wrapper = object : StageClearGpu by actual {
                    override fun renderPreview(photo: StageClearGpuPhoto, params: GpuRenderParams,
                        target: GpuPreviewTarget, display: GpuDisplayOptions): GpuFrameStats {
                        var row: Row? = null
                        observe {
                            check(rows.size < 1024)
                            row = Row(rows.size, params.strength, params.adjustments.warmth, params.adjustments.exposureEv, System.nanoTime())
                            rows.add(checkNotNull(row))
                        }
                        val result = actual.renderPreview(photo, params, target, display)
                        observe { checkNotNull(row).also { it.endNs = System.nanoTime(); it.completed = true; pending.addLast(it) } }
                        return result
                    }
                }
                setField(renderer, "engine", wrapper)
                val observedFrame: (GpuPreviewFrame) -> Unit = { frame ->
                    observe {
                        val row = pending.removeFirst()
                        row.frameTimeNs = frame.frameTimeNanos
                        row.cpuSubmitNs = frame.stats.cpuSubmitNanos
                        row.gpuNs = frame.stats.gpuNanos
                    }
                    existingFrame(frame)
                }
                setField(renderer, "onFrame", observedFrame)
            }
            val start = checkNotNull(startReference.get())
            report.put("startActualValue", start.strength).put("startDrawCount", start.draw)
            scenario.onActivity {
                val prior = it.window.callback
                callback = prior
                it.window.callback = object : Window.Callback by prior {
                    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                        var row: Touch? = null
                        observe {
                            check(touches.size < 4096)
                            row = Touch(event.actionMasked, event.eventTime, event.downTime, event.rawX, event.rawY,
                                event.historySize, event.pointerCount, System.nanoTime())
                            touches.add(checkNotNull(row))
                        }
                        val handled = prior.dispatchTouchEvent(event)
                        observe { row?.handled = handled }
                        return handled
                    }
                }
            }
            val down = SystemClock.uptimeMillis() + 100
            fun send(action: Int, time: Long, x: Float) {
                val wait = time - SystemClock.uptimeMillis()
                if (wait > 0) SystemClock.sleep(wait)
                val actualTime = SystemClock.uptimeMillis()
                val event = MotionEvent.obtain(down, time, action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                val accepted = try { ins.uiAutomation.injectInputEvent(event, false) } finally { event.recycle() }
                injected.put(JSONObject().put("action", action).put("plannedMs", time).put("actualInjectMs", actualTime)
                    .put("x", x.toDouble()).put("accepted", accepted))
                check(accepted)
            }
            send(MotionEvent.ACTION_DOWN, down, center)
            for (index in 1..50) {
                val fraction = if (index <= 25) index / 25f else (50 - index) / 25f
                send(MotionEvent.ACTION_MOVE, down + index * 16, center + slop + track * .06f * fraction)
            }
            send(MotionEvent.ACTION_UP, down + 800, center + slop)
            await { state(renderer).let { !it.pending && abs(it.strength - .5) <= .005 } }
            ins.waitForIdleSync()
            val end = state(renderer)
            onGl(renderer) {
                check(pending.isEmpty())
                check(rows.isNotEmpty() && rows.all { it.completed && it.frameTimeNs != null })
                check(end.draw - start.draw == rows.size.toLong()) { "Every renderer draw must have exactly one observed parameter row" }
                report.put("endActualValue", end.strength).put("endDrawCount", end.draw)
                    .put("renderedFrameCount", rows.size).put("maxRenderedStrength", rows.maxOf { it.strength })
                check(abs(rows.maxOf { it.strength } - .56) <= .005)
            }
            scenario.onActivity {
                check(touches.count { it.action == MotionEvent.ACTION_DOWN } == 1)
                check(touches.count { it.action == MotionEvent.ACTION_UP } == 1)
                check(touches.none { it.action == MotionEvent.ACTION_CANCEL || it.pointers != 1 })
                report.put("receivedMotionEventCount", touches.size)
                    .put("receivedSampleCountIncludingHistory", touches.sumOf { 1 + it.history })
            }
            check(observerFailure.get() == null)
            report.put("outcome", "completed_functional_probe")
        } catch (error: Throwable) {
            failed = error
            report.put("outcome", "functional_probe_failed").put("failureType", error.javaClass.simpleName)
        } finally {
            val cleanupErrors = JSONArray()
            fun attempt(phase: String, action: () -> Unit) {
                try { action() } catch (cleanup: Throwable) {
                    cleanupErrors.put(JSONObject().put("phase", phase).put("failureType", cleanup.javaClass.simpleName))
                    if (failed == null) failed = cleanup
                }
            }
            val renderer = render
            var terminal: CountDownLatch? = null
            attempt("capture_close_boundary") { terminal = renderer?.let { field(it, "terminated") as CountDownLatch } }
            attempt("restore_engine") {
                if (renderer != null) onGl(renderer) { originalEngine?.let { setField(renderer, "engine", it) } }
            }
            attempt("restore_frame_callback") {
                if (renderer != null) onGl(renderer) {
                    originalFrame?.let { setField(renderer, "onFrame", it) }
                    report.put("unpairedRows", pending.size)
                }
            }
            attempt("restore_window_callback") { scenario?.onActivity { callback?.let { prior -> it.window.callback = prior } } }
            attempt("close_activity") { scenario?.close() }
            attempt("await_renderer_close") { terminal?.let { check(it.await(5, TimeUnit.SECONDS)) } }
            attempt("verify_release") {
                if (renderer != null) {
                    check(checkNotNull(terminal).count == 0L)
                    val ledger = field(renderer, "ledger") as GpuMemoryLedger
                    report.put("closedBytes", ledger.allocatedBytes).put("closedTextures", ledger.textureCount)
                        .put("closedWindows", renderer.activeWindowCount)
                    check(ledger.allocatedBytes == 0L && ledger.textureCount == 0 && renderer.activeWindowCount == 0)
                    check((field(renderer, "shutdownFailure") as AtomicReference<*>).get() == null)
                    check((field(renderer, "surfaceDestroyFailure") as AtomicReference<*>).get() == null)
                }
            }
            report.put("cleanupErrors", cleanupErrors)
                .put("outcome", if (failed == null && observerFailure.get() == null) "completed_functional_probe" else "functional_probe_failed")
            report.put("injectedEvents", injected).put("receivedEvents", JSONArray(touches.map { it.json() }))
                .put("frames", JSONArray(rows.map { it.json() }))
                .put("observerFailure", observerFailure.get() ?: JSONObject.NULL)
            output.writeText(report.toString(2))
        }
        if (failed != null || observerFailure.get() != null) throw AssertionError("GPU observer functional probe failed; see private report")
    }

    private fun state(renderer: GpuPreviewRenderer): State {
        val result = AtomicReference<State>()
        onGl(renderer) { result.set(stateOnGl(renderer)) }
        return checkNotNull(result.get())
    }
    private fun stateOnGl(renderer: GpuPreviewRenderer): State {
        check(field(renderer, "failed") == false) { "Renderer reported failure" }
        val latest = checkNotNull(field(renderer, "latest"))
        val mailbox = (field(latest, "state") as AtomicReference<*>).get()!!
        val value = field(mailbox, "value")!!
        val timer = field(renderer, "timer")
        val timerPending = timer?.let { (field(it, "queries") as ArrayDeque<*>).isNotEmpty() } ?: false
        val pending = field(mailbox, "dirtySince") != null || field(mailbox, "displayed") != value || timerPending
        return State((field(renderer, "drawCount") as Long), pending,
            (field(value, "params") as GpuRenderParams).strength, (field(renderer, "ledger") as GpuMemoryLedger).allocatedBytes)
    }
    private fun onGl(renderer: GpuPreviewRenderer, action: () -> Unit) {
        val ready = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        renderer.snapshot {
            try { action() } catch (error: Throwable) { failure.set(error) } finally { ready.countDown() }
        }
        check(ready.await(10, TimeUnit.SECONDS)) { "GL observation timed out" }
        failure.get()?.let { throw it }
    }
    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
    private fun setField(owner: Any, name: String, value: Any) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(owner, value)
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 30_000
        do { if (condition()) return; SystemClock.sleep(25) } while (SystemClock.elapsedRealtime() < deadline)
        error("Functional observer condition timed out")
    }
    private fun nodes(): List<AccessibilityNodeInfo> {
        ins.uiAutomation.clearCache()
        val found = ArrayList<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) { found.add(node); repeat(node.childCount) { node.getChild(it)?.let(::visit) } }
        ins.uiAutomation.rootInActiveWindow?.let(::visit)
        return found
    }
    private fun revealStrength(): AccessibilityNodeInfo {
        repeat(20) {
            val all = nodes()
            val node = all.firstOrNull { it.rangeInfo?.let { r -> r.min == 0f && r.max == 1f } == true }
            if (node != null) {
                node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                ins.waitForIdleSync()
                return nodes().first { it.rangeInfo?.let { r -> r.min == 0f && r.max == 1f } == true }
            }
            all.firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(100)
        }
        error("Strength control unavailable")
    }
}
