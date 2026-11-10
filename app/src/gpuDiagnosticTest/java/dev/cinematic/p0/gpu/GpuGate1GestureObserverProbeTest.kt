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
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.gpu.preview.GpuPreviewFrame
import dev.cinematic.p0.gpu.preview.GpuPreviewRenderer
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
        var cpuSubmitNs: Long? = null, var gpuNs: Long? = null, var completed: Boolean = false,
        var callbackNs: Long? = null) {
        fun json() = JSONObject().put("sequence", sequence).put("strength", strength).put("warmth", warmth)
            .put("exposureEv", ev).put("beginNs", beginNs).put("endNs", endNs).put("renderCompleted", completed)
            .put("rendererFrameTimeNs", frameTimeNs ?: JSONObject.NULL).put("cpuSubmitNs", cpuSubmitNs ?: JSONObject.NULL)
            .put("gpuNs", gpuNs ?: JSONObject.NULL).put("callbackNs", callbackNs ?: JSONObject.NULL)
    }
    private data class Touch(val action: Int, val eventMs: Long, val downMs: Long, val x: Float, val y: Float,
        val history: Int, val pointers: Int, val receivedNs: Long, val receivedUptimeMs: Long,
        var handled: Boolean = false, var returnedNs: Long? = null) {
        fun json() = JSONObject().put("action", action).put("eventMs", eventMs).put("downMs", downMs)
            .put("x", x.toDouble()).put("y", y.toDouble()).put("historySize", history).put("pointerCount", pointers)
            .put("receivedNs", receivedNs).put("receivedUptimeMs", receivedUptimeMs).put("handled", handled)
            .put("delegateReturnedNs", returnedNs ?: JSONObject.NULL)
    }
    private data class State(val draw: Long, val pending: Boolean, val params: GpuRenderParams, val bytes: Long) {
        fun json() = JSONObject().put("drawCount", draw).put("pending", pending)
            .put("strength", params.strength).put("warmth", params.adjustments.warmth)
            .put("exposureEv", params.adjustments.exposureEv)
    }
    private data class Axis(val name: String, val min: Float, val max: Float, val baseline: Double,
        val peak: Double, val cpuTapStep: Double, val quantizationHalfWidth: Double) {
        val span get() = (max - min).toDouble()
        fun value(params: GpuRenderParams): Double = when (name) {
            "strength" -> params.strength
            "warmth" -> params.adjustments.warmth
            else -> params.adjustments.exposureEv
        }
        fun value(row: Row): Double = when (name) {
            "strength" -> row.strength
            "warmth" -> row.warmth
            else -> row.ev
        }
    }
    private val axes = listOf(
        Axis("strength", 0f, 1f, .50, .56, .01, .005),
        Axis("warmth", -100f, 100f, 0.0, 6.0, 1.0, 0.0),
        Axis("exposureEv", -2f, 2f, 0.0, .6, .1, 0.0),
    )
    private data class Geometry(val track: Double, val cpuOnePx: Double, val gpuOnePx: Double,
        val floatAllowance: Double, val tolerance: Double)

    @Test fun observeRealEngineAndDeliveredTouch() {
        val args = InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue(args.getString("gate1Part2") == "gesture-observer-probe-v3")
        check(args.getString("class") == "dev.cinematic.p0.gpu.GpuGate1GestureObserverProbeTest#observeRealEngineAndDeliveredTouch")
        check(Build.HARDWARE in setOf("ranchu", "goldfish") && args.getString("isolated") == "true")
        check(target.packageName == "dev.cinematic.p0.gpudiagnostic")
        val run = checkNotNull(args.getString("gate1ProbeRun")).also { check(it.matches(Regex("[a-zA-Z0-9_-]{1,40}"))) }
        val output = File(checkNotNull(target.getExternalFilesDir("gpu-diagnostics")), "gesture-probe-v3-$run.json")
        check(output.createNewFile()) { "Do not overwrite prior evidence" }
        val report = JSONObject().put("version", "gesture-observer-probe-v3")
            .put("purpose", "synthetic emulator endpoint and observer proof only; timing validity not assessed; not criterion 1 or Pixel acceptance")
            .put("frameMeaning", "real engine render completed, FIFO paired with renderer callback after swap; not scanout")
            .put("profile", "three rise-hold-return value profiles, one trial per axis; 200ms linear rise, 400ms peak hold, 200ms linear return; 800ms DOWN to UP")
            .put("candidateOnly", true)
            .put("profileIdentity", "v3 symmetric rise-hold-return candidate; not the v2 triangle or an identical prior comparison")
            .put("peakObservation", "Holding the requested peak does not guarantee a rendered peak; frozen endpoint rules still apply")
            .put("trajectoryTimingStatus", "not_assessed")
            .put("timingDiagnostics", "late means actualInjectMs>plannedMs; burst interval means adjacent injections in the same uptime millisecond; diagnostic counts only, no timing acceptance tolerance")
            .put("finalMoveIdentity", "Window MOVE with matching downTime, eventTime=down+800, and x within 1px of injected return coordinate; otherwise boundary_unproven")
            .put("modelRequiresApkDependencyVerification", true)
            .put("startEvidence", "GL barrier after drawCount>0, mailbox displayed==value, no dirty state or timer queries; setup is outside trial row boundaries")
            .put("toleranceRule", "max(CPU quantization half-width, CPU tapStep/(24*density), GPU span/track, 8 Float ULP(target peak)); frozen before gesture input")
            .put("conversion", "GPU x=center+touchSlop+track*(value-baseline)/span; positive then negative; CPU has a different physical gesture")
            .put("intermediateRounding", "CPU/GPU intermediate values may differ; compare endpoints with the preregistered functional tolerance")
            .put("historyMeaning", "sum(1+historySize) over Window MotionEvents, not unique input samples")
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
        var invalidTrials = 0
        var coverageVerified = false
        val trials = JSONArray()
        report.put("trials", trials)
        try {
            scenario = ActivityScenario.launch(GpuPreviewDiagnosticActivity::class.java)
            val activeScenario = checkNotNull(scenario)
            activeScenario.onActivity { render = it.renderer }
            val renderer = checkNotNull(render)
            await { state(renderer).let { it.draw > 0 && !it.pending && it.bytes > 0 } }
            // All endpoint rules are computed and persisted before the first gesture, never tuned to results.
            var slop = 0
            var density = 0f
            activeScenario.onActivity {
                slop = ViewConfiguration.get(it).scaledTouchSlop
                density = it.resources.displayMetrics.density
            }
            check(density > 0)
            val geometry = axes.associateWith { axis ->
                val node = reveal(axis)
                setBaseline(node, axis)
                val bounds = Rect().also(node::getBoundsInScreen)
                val track = bounds.width().toDouble() - 2.0 * density
                check(track > 0)
                val cpuOnePx = axis.cpuTapStep / (24.0 * density)
                val gpuOnePx = axis.span / track
                val ulp = 8.0 * Math.ulp(axis.peak.toFloat())
                Geometry(track, cpuOnePx, gpuOnePx, ulp,
                    maxOf(axis.quantizationHalfWidth, cpuOnePx, gpuOnePx, ulp))
            }
            await { state(renderer).let { !it.pending && axes.all { axis -> abs(axis.value(it.params) - axis.baseline) < 1e-6 } } }
            ins.waitForIdleSync()
            report.put("preInputContract", JSONArray(axes.map { axis ->
                val g = geometry.getValue(axis)
                JSONObject().put("axis", axis.name).put("start", axis.baseline).put("peak", axis.peak)
                    .put("return", axis.baseline).put("durationMs", 800)
                    .put("riseMs", 200).put("peakHoldStartMs", 200).put("peakHoldEndMs", 600)
                    .put("peakHoldMs", 400).put("returnMs", 200)
                    .put("outwardValuePerSecond", (axis.peak - axis.baseline) / .2)
                    .put("holdValuePerSecond", 0.0)
                    .put("returnValuePerSecond", -(axis.peak - axis.baseline) / .2)
                    .put("density", density.toDouble()).put("touchSlopPx", slop)
                    .put("activeThumbModelPx", 2.0 * density).put("trackPx", g.track)
                    .put("rangeMin", axis.min.toDouble()).put("rangeMax", axis.max.toDouble())
                    .put("cpuTapStep", axis.cpuTapStep).put("cpuQuantizationHalfWidth", axis.quantizationHalfWidth)
                    .put("cpuOnePx", g.cpuOnePx).put("gpuValuePerPx", g.gpuOnePx)
                    .put("eightFloatUlp", g.floatAllowance).put("endpointTolerance", g.tolerance)
                    .put("plannedInjectedEventCount", 42).put("plannedMoveCount", 40).put("moveIntervalMs", 20)
            })).put("preInputContractRecordedUptimeMs", SystemClock.uptimeMillis())
            output.writeText(report.toString(2))
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
                        row.callbackNs = System.nanoTime()
                        row.frameTimeNs = frame.frameTimeNanos
                        row.cpuSubmitNs = frame.stats.cpuSubmitNanos
                        row.gpuNs = frame.stats.gpuNanos
                    }
                    existingFrame(frame)
                }
                setField(renderer, "onFrame", observedFrame)
            }
            val start = checkNotNull(startReference.get())
            report.put("observationStartState", start.json())
            activeScenario.onActivity {
                val prior = it.window.callback
                callback = prior
                it.window.callback = object : Window.Callback by prior {
                    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                        var row: Touch? = null
                        observe {
                            check(touches.size < 4096)
                            row = Touch(event.actionMasked, event.eventTime, event.downTime, event.rawX, event.rawY,
                                event.historySize, event.pointerCount, System.nanoTime(), SystemClock.uptimeMillis())
                            touches.add(checkNotNull(row))
                        }
                        val handled = prior.dispatchTouchEvent(event)
                        observe { row?.also { it.handled = handled; it.returnedNs = System.nanoTime() } }
                        return handled
                    }
                }
            }
            for (axis in axes) {
                // Reset/reveal actions are setup, outside this trial's row/draw/touch boundaries.
                axes.forEach { setBaseline(reveal(it), it) }
                val slider = reveal(axis)
                ins.waitForIdleSync()
                await { state(renderer).let { !it.pending && axes.all { axis -> abs(axis.value(it.params) - axis.baseline) < 1e-6 } } }
                val bounds = Rect().also(slider::getBoundsInScreen)
                val g = geometry.getValue(axis)
                check(bounds.width().toDouble() - 2.0 * density == g.track) { "Geometry changed after preregistration" }
                val center = bounds.exactCenterX()
                val y = bounds.exactCenterY()
                val travel = (g.track * (axis.peak - axis.baseline) / axis.span).toFloat()
                check(center + slop + travel < bounds.right - density) { "Candidate trajectory does not fit; do not shorten it" }
                var rowFrom = 0
                var touchFrom = 0
                val trialStart = AtomicReference<State>()
                onGl(renderer) {
                    trialStart.set(stateOnGl(renderer).also { check(!it.pending) })
                    check(pending.isEmpty())
                    rowFrom = rows.size
                }
                activeScenario.onActivity { touchFrom = touches.size }
                val trial = JSONObject().put("axis", axis.name).put("startState", trialStart.get().json())
                    .put("rowFromInclusive", rowFrom).put("touchFromInclusive", touchFrom)
                    .put("left", bounds.left).put("right", bounds.right).put("top", bounds.top).put("bottom", bounds.bottom)
                    .put("travelPx", travel.toDouble()).put("endpointTolerance", g.tolerance)
                    .put("boundaryStatus", "boundary_unproven").put("endpointValidity", "not_assessed")
                    .put("trajectoryTimingStatus", "not_assessed")
                trials.put(trial)
                val injectionFrom = injected.length()
                val down = SystemClock.uptimeMillis() + 100
                fun send(action: Int, time: Long, x: Float) {
                    val wait = time - SystemClock.uptimeMillis()
                    if (wait > 0) SystemClock.sleep(wait)
                    val actualTime = SystemClock.uptimeMillis()
                    val event = MotionEvent.obtain(down, time, action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                    val accepted = try { ins.uiAutomation.injectInputEvent(event, false) } finally { event.recycle() }
                    injected.put(JSONObject().put("axis", axis.name).put("action", action).put("plannedMs", time)
                        .put("actualInjectMs", actualTime).put("injectReturnedMs", SystemClock.uptimeMillis())
                        .put("x", x.toDouble()).put("y", y.toDouble()).put("accepted", accepted))
                    check(accepted)
                }
                send(MotionEvent.ACTION_DOWN, down, center)
                for (index in 1..40) {
                    val fraction = when {
                        index <= 10 -> index / 10f
                        index <= 30 -> 1f
                        else -> (40 - index) / 10f
                    }
                    send(MotionEvent.ACTION_MOVE, down + index * 20, center + slop + travel * fraction)
                }
                send(MotionEvent.ACTION_UP, down + 800, center + slop)
                val injectionRows = (injectionFrom until injected.length()).map { injected.getJSONObject(it) }
                trial.put("injectedEventCount", injectionRows.size)
                    .put("plannedDownToUpMs", 800)
                    .put("actualInjectionDownToUpMs", injectionRows.last().getLong("actualInjectMs") - injectionRows.first().getLong("actualInjectMs"))
                    .put("lateInjectionCount", injectionRows.count { it.getLong("actualInjectMs") > it.getLong("plannedMs") })
                    .put("maxInjectionLatenessMs", injectionRows.maxOf { it.getLong("actualInjectMs") - it.getLong("plannedMs") })
                    .put("sameUptimeMillisecondInjectionIntervalCount", injectionRows.zipWithNext().count { (a, b) ->
                        a.getLong("actualInjectMs") == b.getLong("actualInjectMs") })
                // UP receipt alone plus an old idle mailbox cannot establish Compose's final submission.
                var returnedUp: Touch? = null
                await {
                    activeScenario.onActivity {
                        returnedUp = touches.drop(touchFrom).lastOrNull {
                            it.action == MotionEvent.ACTION_UP && it.downMs == down && it.returnedNs != null
                        }
                    }
                    returnedUp != null
                }
                trial.put("finalUpDelivery", checkNotNull(returnedUp).json())
                var finalMove: Touch? = null
                activeScenario.onActivity {
                    val index = (touchFrom until touches.size).lastOrNull { index ->
                        val touch = touches[index]
                        touch.action == MotionEvent.ACTION_MOVE && touch.downMs == down && touch.eventMs == down + 800 &&
                            abs(touch.x - (center + slop)) <= 1f
                    }
                    if (index != null) {
                        finalMove = touches[index]
                        trial.put("finalMoveWindowSequence", index).put("finalMoveDelivery", touches[index].json())
                    }
                }
                val deliveredReturnMove = checkNotNull(finalMove) { "boundary_unproven: final MOVE was not identified" }
                trial.put("composeSubmitBarrier", awaitComposeSubmitBarrier(checkNotNull(returnedUp?.returnedNs)))
                // Read semantics only AFTER the pinned Compose/Main barrier has drained the final effect.
                // Endpoint tolerance is deliberately not part of this boundary proof.
                val uiFinalValue = checkNotNull(nodes().firstOrNull {
                    it.rangeInfo?.let { r -> r.min == axis.min && r.max == axis.max } == true
                }?.rangeInfo?.current?.toDouble())
                trial.put("uiLastObservedValue", uiFinalValue)
                var returnRowSequence = -1
                // A final MOVE render may complete before UP; no forced submit/render is used.
                await {
                    var proven = false
                    onGl(renderer) {
                        val end = stateOnGl(renderer)
                        val last = rows.lastOrNull()?.takeIf { it.sequence >= rowFrom }
                        if (last != null && last.beginNs >= deliveredReturnMove.receivedNs &&
                            last.completed && last.callbackNs != null && !end.pending && pending.isEmpty() &&
                            axis.value(last) == uiFinalValue && axis.value(end.params) == uiFinalValue) {
                            returnRowSequence = last.sequence
                            proven = true
                        }
                    }
                    proven
                }
                trial.put("uiReturnValue", uiFinalValue).put("returnEvidenceRowSequence", returnRowSequence)
                    .put("uiReturnWithinTolerance", abs(uiFinalValue - axis.baseline) <= g.tolerance)
                val trialRows = ArrayList<Row>()
                val endRef = AtomicReference<State>()
                onGl(renderer) {
                    val end = stateOnGl(renderer)
                    check(!end.pending && pending.isEmpty())
                    check(rows.lastOrNull()?.sequence == returnRowSequence && axis.value(end.params) == uiFinalValue) {
                        "boundary_unproven: final state changed after return evidence"
                    }
                    endRef.set(end)
                    trialRows.addAll(rows.subList(rowFrom, rows.size))
                    check(trialRows.all { it.completed && it.frameTimeNs != null })
                    check(end.draw - trialStart.get().draw == trialRows.size.toLong()) { "Every trial draw must have exactly one observed parameter row" }
                    trial.put("rowToExclusive", rows.size).put("endState", end.json())
                        .put("rendererDrawCountDelta", end.draw - trialStart.get().draw)
                        .put("rendererCompletedFrameCount", trialRows.count { it.completed })
                        .put("rendererCallbackCount", trialRows.count { it.frameTimeNs != null })
                        .put("pendingRows", pending.size).put("drainedAtNs", System.nanoTime())
                }
                trial.put("boundaryStatus", "final_dispatch_compose_submit_and_render_proven")
                val trialTouches = ArrayList<Touch>()
                activeScenario.onActivity {
                    trialTouches.addAll(touches.subList(touchFrom, touches.size))
                    trial.put("touchToExclusive", touches.size)
                }
                check(trialTouches.count { it.action == MotionEvent.ACTION_DOWN } == 1)
                check(trialTouches.count { it.action == MotionEvent.ACTION_UP } == 1)
                check(trialTouches.all { it.downMs == down && it.action != MotionEvent.ACTION_CANCEL && it.pointers == 1 })
                val actualPeak = trialRows.maxOfOrNull { axis.value(it) }
                val lastRow = trialRows.lastOrNull()
                val actualReturn = lastRow?.let { axis.value(it) } ?: axis.value(trialStart.get().params)
                val startValid = abs(axis.value(trialStart.get().params) - axis.baseline) <= g.tolerance
                val peakValid = actualPeak != null && abs(actualPeak - axis.peak) <= g.tolerance
                val returnValid = abs(actualReturn - axis.baseline) <= g.tolerance
                val othersFixed = axes.filter { it != axis }.all { other ->
                    abs(other.value(trialStart.get().params) - other.baseline) < 1e-6 &&
                        trialRows.all { abs(other.value(it) - other.baseline) < 1e-6 }
                }
                check(axes.all { abs(it.value(endRef.get().params) -
                    (lastRow?.let(it::value) ?: it.value(trialStart.get().params))) < 1e-6 })
                val valid = startValid && peakValid && returnValid && othersFixed
                if (!valid) invalidTrials++
                trial.put("startActualValue", axis.value(trialStart.get().params)).put("maxRenderedValue", actualPeak ?: JSONObject.NULL)
                    .put("returnRenderedValue", actualReturn).put("startWithinTolerance", startValid)
                    .put("peakWithinTolerance", peakValid).put("returnWithinTolerance", returnValid)
                    .put("otherAxesFixed", othersFixed).put("endpointValid", valid)
                    .put("endpointValidity", if (valid) "valid_endpoints" else "invalid_endpoints")
                    .put("observerCoverage", "one row per renderer draw and one FIFO callback per row")
                    .put("injectedEventCount", injected.length() - injectionFrom)
                    .put("windowMotionEventCount", trialTouches.size)
                    .put("summedSampleCountIncludingHistoryNotUnique", trialTouches.sumOf { 1 + it.history })
                    .put("plannedDownToUpMs", 800)
                    .put("actualInjectionDownToUpMs", injected.getJSONObject(injected.length() - 1).getLong("actualInjectMs") -
                        injected.getJSONObject(injectionFrom).getLong("actualInjectMs"))
                    .put("windowDownToUpEventMs", trialTouches.last().eventMs - trialTouches.first().eventMs)
                    .put("windowDownToUpReceivedNs", trialTouches.last().receivedNs - trialTouches.first().receivedNs)
                    .put("windowDownToUpReceivedUptimeMs", trialTouches.last().receivedUptimeMs - trialTouches.first().receivedUptimeMs)
                    .put("windowReceiptAfterEventTimeCount", trialTouches.count { it.receivedUptimeMs > it.eventMs })
                    .put("sameUptimeMillisecondWindowIntervalCount", trialTouches.zipWithNext().count { (a, b) -> a.receivedUptimeMs == b.receivedUptimeMs })
                    .put("returnFrameCompletedNs", lastRow?.endNs ?: JSONObject.NULL)
                    .put("returnRendererCallbackNs", lastRow?.callbackNs ?: JSONObject.NULL)
                output.writeText(report.toString(2))
            }
            onGl(renderer) {
                val end = stateOnGl(renderer)
                check(!end.pending && pending.isEmpty())
                check(end.draw - start.draw == rows.size.toLong()) { "Global coverage must include setup draws too" }
                check(rows.all { it.completed && it.frameTimeNs != null })
                report.put("observationEndState", end.json()).put("allObservedRowsIncludingSetup", rows.size)
                    .put("globalDrawCountDelta", end.draw - start.draw)
                    .put("allRendererCallbackCountIncludingSetup", rows.count { it.frameTimeNs != null })
                    .put("invalidTrialCount", invalidTrials)
            }
            check(observerFailure.get() == null)
            coverageVerified = true
            report.put("outcome", "completed_functional_probe")
        } catch (error: Throwable) {
            failed = error
            report.put("outcome", "functional_probe_failed").put("failureType", error.javaClass.simpleName)
                .put("failureMessage", error.message ?: JSONObject.NULL)
            if (trials.length() > 0 && trials.getJSONObject(trials.length() - 1).optString("boundaryStatus") == "boundary_unproven") {
                report.put("failurePhase", "boundary_unproven")
            }
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
                .put("cleanupStatus", if (cleanupErrors.length() == 0) "verified" else "failed")
                .put("observerOutcome", when {
                    observerFailure.get() != null -> "observer_failed"
                    coverageVerified -> "coverage_verified"
                    else -> "not_fully_verified"
                })
                .put("allEndpointValid", trials.length() == axes.size && (0 until trials.length()).all {
                    trials.getJSONObject(it).optBoolean("endpointValid", false)
                })
                .put("outcome", when {
                    failed != null || observerFailure.get() != null -> "functional_probe_failed"
                    else -> "completed_functional_observer_probe"
                })
            report.put("injectedEvents", injected).put("receivedEvents", JSONArray(touches.map { it.json() }))
                .put("frames", JSONArray(rows.map { it.json() }))
                .put("observerFailure", observerFailure.get() ?: JSONObject.NULL)
            output.writeText(report.toString(2))
        }
        if (failed != null || observerFailure.get() != null) throw AssertionError("GPU observer functional probe failed; see private report")
        if (invalidTrials > 0) throw AssertionError("GPU endpoints invalid; timing not assessed; observer coverage and cleanup are reported separately")
    }

    /** Fixed runtime/ui 1.12.1 and default Activity.setContent only; not a generic effect-idle API.
     * Recomposer.hasPendingWork excludes LaunchedEffect jobs. Effects.kt onRemembered launches
     * before apply returns; default WindowRecomposer uses AndroidUiDispatcher.CurrentThread.
     * AndroidUiDispatcher queues jobs FIFO, drains its current trampoline or posts its Handler
     * callback immediately. Therefore a SECOND Main message after an idle observation is after
     * those launches. This app's parameter LaunchedEffect has only synchronous renderer.submit.
     * A suspending effect, custom dispatcher or concurrent recomposer invalidates this proof.
     * Read-only: no sendApplyNotifications, requested frame, state mutation or renderer submit.
     */
    private fun awaitComposeSubmitBarrier(dispatchReturnedNs: Long): JSONObject {
        val result = JSONObject()
        await {
            var first: Pair<androidx.compose.runtime.RecomposerInfo, Long>? = null
            ins.runOnMainSync {
                val running = Recomposer.runningRecomposers.value
                check(running.size == 1) { "boundary_unproven: expected one default window recomposer" }
                val info = running.single()
                if (!Snapshot.current.hasPendingChanges() && !info.hasPendingWork) {
                    first = info to info.changeCount
                    result.put("firstIdleMainNs", System.nanoTime()).put("changeCount", info.changeCount)
                }
            }
            var verified = false
            first?.let { (info, changes) ->
                // A distinct posted Main message, not an inline continuation of the first check.
                ins.runOnMainSync {
                    verified = Recomposer.runningRecomposers.value == setOf(info) &&
                        !Snapshot.current.hasPendingChanges() && !info.hasPendingWork && info.changeCount == changes
                    result.put("secondMainBarrierNs", System.nanoTime())
                }
            }
            verified
        }
        check(result.getLong("firstIdleMainNs") > dispatchReturnedNs)
        return result.put("finalWindowDispatchReturnedNs", dispatchReturnedNs)
            .put("contract", "runtime/ui 1.12.1 default Main dispatcher; synchronous parameter effect; two Main messages with applied snapshots and stable idle recomposer")
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
            (field(value, "params") as GpuRenderParams), (field(renderer, "ledger") as GpuMemoryLedger).allocatedBytes)
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
    private fun setBaseline(node: AccessibilityNodeInfo, axis: Axis) {
        if (checkNotNull(node.rangeInfo).current != axis.baseline.toFloat()) {
            val set = Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, axis.baseline.toFloat()) }
            check(node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, set))
            ins.waitForIdleSync()
        }
    }
    private fun reveal(axis: Axis): AccessibilityNodeInfo {
        repeat(20) {
            val all = nodes()
            val node = all.firstOrNull { it.rangeInfo?.let { r -> r.min == axis.min && r.max == axis.max } == true }
            if (node != null) {
                node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                ins.waitForIdleSync()
                return nodes().first { it.rangeInfo?.let { r -> r.min == axis.min && r.max == axis.max } == true }
            }
            all.firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(100)
        }
        error("${axis.name} control unavailable")
    }
}
