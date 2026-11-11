package dev.cinematic.p0.gpu

import android.content.Intent
import android.graphics.Rect
import dev.cinematic.p0.raw.DiagnosticHubActivity
import dev.cinematic.p0.raw.LinearSrgbImage
import androidx.lifecycle.Lifecycle
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.lang.ref.WeakReference
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
import kotlin.math.roundToInt

/** Functional observations, not criterion 1 or acceptance. MIXED probes remain emulator-only.
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
        var callbackNs: Long? = null, val showBefore: Boolean = false, val rotationDegrees: Int = 0) {
        fun json() = JSONObject().put("sequence", sequence).put("strength", strength).put("warmth", warmth)
            .put("exposureEv", ev).put("beginNs", beginNs).put("endNs", endNs).put("renderCompleted", completed)
            .put("rendererFrameTimeNs", frameTimeNs ?: JSONObject.NULL).put("cpuSubmitNs", cpuSubmitNs ?: JSONObject.NULL)
            .put("gpuNs", gpuNs ?: JSONObject.NULL).put("callbackNs", callbackNs ?: JSONObject.NULL)
            .put("showBefore", showBefore).put("rotationDegrees", rotationDegrees)
    }
    private data class HistoricalTouch(val eventMs: Long, val x: Float, val y: Float) {
        fun json() = JSONObject().put("eventMs", eventMs).put("windowX", x.toDouble()).put("windowY", y.toDouble())
    }
    private data class ActiveTouch(val axis: String, val downMs: Long, var x: Float, val y: Float,
        var lastAction: Int = MotionEvent.ACTION_DOWN, var downInjectionAttempted: Boolean = false,
        var downInjectionReturned: Boolean = false, var downAccepted: Boolean? = null,
        var upInjectionReturned: Boolean = false, var upAccepted: Boolean? = null)
    private data class Touch(val action: Int, val eventMs: Long, val downMs: Long, val x: Float, val y: Float,
        val history: Int, val pointers: Int, val receivedNs: Long, val receivedUptimeMs: Long,
        var handled: Boolean = false, var returnedNs: Long? = null) {
        var historySamples: List<HistoricalTouch>? = null
        var windowX: Float = 0f
        var windowY: Float = 0f
        fun json() = JSONObject().put("action", action).put("eventMs", eventMs).put("downMs", downMs)
            .put("x", x.toDouble()).put("y", y.toDouble()).put("historySize", history).put("pointerCount", pointers)
            .put("receivedNs", receivedNs).put("receivedUptimeMs", receivedUptimeMs).put("handled", handled)
            .put("delegateReturnedNs", returnedNs ?: JSONObject.NULL).also { result ->
                historySamples?.let { samples ->
                    result.put("windowX", windowX.toDouble()).put("windowY", windowY.toDouble())
                        .put("historicalSamples", JSONArray(samples.map { it.json() }))
                }
            }
    }
    private data class State(val draw: Long, val pending: Boolean, val params: GpuRenderParams, val bytes: Long, val display: GpuDisplayOptions) {
        fun json() = JSONObject().put("drawCount", draw).put("pending", pending)
            .put("strength", params.strength).put("warmth", params.adjustments.warmth)
            .put("exposureEv", params.adjustments.exposureEv)
            .put("showBefore", display.showBefore).put("rotationDegrees", display.rotationDegrees)
    }
    private data class Axis(val name: String, val min: Float, val max: Float, val baseline: Double,
        val peak: Double, val cpuTapStep: Double, val quantizationHalfWidth: Double) {
        val span get() = (max - min).toDouble()
        fun value(params: GpuRenderParams): Double = when (name) {
            "strength" -> params.strength
            "warmth" -> params.adjustments.warmth
            else -> params.adjustments.exposureEv
        }
        fun withValue(params: GpuRenderParams, value: Double): GpuRenderParams = when (name) {
            "strength" -> params.copy(strength = value)
            "warmth" -> params.copy(adjustments = params.adjustments.copy(warmth = value))
            else -> params.copy(adjustments = params.adjustments.copy(exposureEv = value))
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

    @Test fun observeRealEngineAndDeliveredTouch() = observeProfile(programmatic = false)
    @Test fun observeProgrammaticRenderValues() = observeProfile(programmatic = true)

    @Test fun observeSelectedDngProgrammaticValues() = observeProfile(programmatic = true, dng = true)
    @Test fun observeSelectedDngPssReference() = observeProfile(programmatic = true, dng = true, pssMode = true)
    @Test fun observeSelectedDngAuxiliaryTouch() = observeProfile(programmatic = false, dng = true)

    private fun observeProfile(programmatic: Boolean, dng: Boolean = false, pssMode: Boolean = false) {
        val args = InstrumentationRegistry.getArguments()
        val auxiliaryTouch = dng && !programmatic
        val version = if (auxiliaryTouch) "dng-auxiliary-touch-v1" else if (dng) { if (pssMode) "dng-programmatic-pss-v1" else "dng-programmatic-values-v1" } else if (programmatic) "programmatic-observer-probe-v1" else "gesture-observer-probe-v4"
        val method = if (auxiliaryTouch) "observeSelectedDngAuxiliaryTouch" else if (dng) { if (pssMode) "observeSelectedDngPssReference" else "observeSelectedDngProgrammaticValues" } else if (programmatic) "observeProgrammaticRenderValues" else "observeRealEngineAndDeliveredTouch"
        org.junit.Assume.assumeTrue(args.getString("gate1Part2") == version)
        check(args.getString("class") == "dev.cinematic.p0.gpu.GpuGate1GestureObserverProbeTest#$method")
        if (dng) gate1Part2OptIn("dev.cinematic.p0.gpu.GpuGate1GestureObserverProbeTest#$method", version)
        else check(Build.HARDWARE in setOf("ranchu", "goldfish") && args.getString("isolated") == "true")
        check(target.packageName == "dev.cinematic.p0.gpudiagnostic")
        val failAfterDown = auxiliaryTouch && args.getString("gate1AuxTouchFailAfterDown") == "true"
        if (auxiliaryTouch) {
            check(!pssMode && args.getString("isolated") == "true")
            check(args.getString("gate1AuxTouchFailAfterDown") in listOf(null, "false", "true"))
            check(!failAfterDown || Build.HARDWARE in setOf("ranchu", "goldfish")) {
                "Intentional auxiliary failure is emulator-only"
            }
        }
        val run = checkNotNull(args.getString("gate1ProbeRun")).also { check(it.matches(Regex("[a-zA-Z0-9_-]{1,40}"))) }
        val output = File(checkNotNull(target.getExternalFilesDir("gpu-diagnostics")), "$version-$run.json")
        check(output.createNewFile()) { "Do not overwrite prior evidence" }
        val report = JSONObject().put("version", version)
            .put("purpose", "MIXED synthetic emulator endpoint/input/observer proof; not shared-DNG comparison, criterion 1 or Pixel acceptance")
            .put("frameMeaning", "real engine render completed, FIFO paired with renderer callback after swap; not scanout")
            .put("profile", "three rise-hold-return value profiles, one trial per axis; 200ms linear rise, 400ms peak hold, 200ms linear return; 800ms planned input span")
            .put("candidateOnly", true)
            .put("inputMode", if (programmatic) "direct_existing_renderer_submit" else "auxiliary_touch")
            .put("uiSliderSynchronized", !programmatic)
            .put("uiEvidenceScope", if (programmatic) "setup semantics only; main trial endpoints are actual render values"
                else "touch setup and final semantics values only; peak is actual render maximum, not a separate peak UI read")
            .put("sharedPath", "touch onValueChange -> Compose state -> LaunchedEffect -> renderer.submit; main probe enters that same submit -> latest-wins mailbox -> existing FULL_RECOMPUTE engine -> swap/callback")
            .put("latestWins", "not all scheduled values need be rendered; actual render parameters and callbacks are recorded")
            .put("programmaticSchedule", "41 fixed values at relative 0,20,...,800ms; actual first Main submit uptime is origin; never drop or recompute samples based on lateness")
            .put("inputTimingRule", "programmatic only: every relative lateness in [0,20]ms, final span [800,820]ms, no adjacent submits in same uptime millisecond; not criterion 1")
            .put("clockResolution", "input timing validity uses integer SystemClock.uptimeMillis; nanoTime recorded separately")
            .put("geometryRule", "accessibility width - 2*roundToPx(10dp semantics padding) - roundToPx(2dp active thumb); Material3 1.4.0")
            .put("profileIdentity", "symmetric rise-hold-return candidate; new input/geometry contract, not an identical prior comparison")
            .put("peakObservation", "Holding the requested peak does not guarantee a rendered peak; frozen endpoint rules still apply")
            .put("trajectoryTimingStatus", if (programmatic) "pending_input_schedule_check" else "not_assessed")
            .put("timingDiagnostics", "late means actualInjectMs>plannedMs; burst interval means adjacent injections in the same uptime millisecond; diagnostic counts only, no timing acceptance tolerance")
            .put("finalMoveIdentity", "Window MOVE with matching downTime, eventTime=down+800, and x within 1px of injected return coordinate; otherwise boundary_unproven")
            .put("modelRequiresApkDependencyVerification", true)
            .put("startEvidence", "GL barrier after drawCount>0, mailbox displayed==value, no dirty state or timer queries; setup is outside trial row boundaries")
            .put("toleranceRule", "max(CPU quantization half-width, CPU tapStep/(24*density), GPU span/track, 8 Float ULP(target peak)); frozen before gesture input")
            .put("conversion", "GPU x=center+touchSlop+track*(value-baseline)/span; positive then negative; CPU has a different physical gesture")
            .put("intermediateRounding", "CPU/GPU intermediate values may differ; compare endpoints with the preregistered functional tolerance")
            .put("historyMeaning", "sum(1+historySize) over Window MotionEvents, not unique input samples")
        val operations = if (dng && !auxiliaryTouch) axes.flatMap { axis -> (0..4).map { axis to it } } else axes.map { it to 0 }
        val support = if (dng) Gate1DngObservationSupport(output.parentFile!!, "$version-$run") else null
        if (dng) report.put("purpose", "shared DNG programmatic values or separate PSS reference; not criterion 1 or acceptance by itself")
            .put("profile", "strength, warmth, EV; each warmup1 then recorded4; all15 retain endpoint/input/frame results")
            .put("targetVerification", checkNotNull(support).verifyTarget())
            .put("pssMode", pssMode).put("pssSamples", support.pss)
            .put("pssScope", "18 planned reference samples only in PSS mode; fixed1s settle; shell dump and file IO perturb process memory; not speed or pass/fail")
            .put("postClosePssScope", "observer-attached same-Hub reference includes retained test renderer/callback references; does not prove Activity GC or unobserved full release")
            .put("dumpTimeoutScope", "dumpsys -t10 service output poll only; not end-to-end shell/Binder/file IO hard deadline")
            .put("nativeOwnerCleanup", "not observed; loading=false and GL ledger0 do not prove native pending/owner release")
            .put("toleranceInterpretation", "GPU1px is shared touch/CPU operation tolerance, not a programmatic minimum step;8FloatULP is separate rounding allowance")
        if (auxiliaryTouch) {
            report.put("purpose", "shared DNG auxiliary touch operation proof only; separate from main input, PSS and criterion 1")
                .put("intentionalFailureAfterDown", failAfterDown)
                .put("profile", "strength, warmth, EV; each once; 3 auxiliary operations, no warmup repetitions")
                .put("touchSchedule", "DOWN + 40 MOVE at 20,...,800ms + UP at 800ms; 42 events per operation; no compensation or resampling changes")
                .put("sharedPath", "injected touch -> Window -> Slider onValueChange -> Compose state -> LaunchedEffect -> existing renderer.submit -> FULL_RECOMPUTE -> swap/callback")
                .put("historyTraceScope", "Window pointer0 local coordinates/time copied before delegate; raw current coordinates separate; max 64 history/event and 4096 total; overflow invalidates observer; no JSON/file IO in callback")
                .put("historyInterpretation", "injected and delivered/history traces may differ; no unique-sample count, resampled flag, prediction attribution or timing validity inferred")
                .put("failureTouchCleanupRule", "failed run only: one best-effort CANCEL if accepted/delivered or attempted-but-unreturned DOWN has no completed Window UP/CANCEL; injection return is not delivery; never added on successful operations")
            report.remove("programmaticSchedule")
        }
        var hub: ActivityScenario<DiagnosticHubActivity>? = null
        var hubIdentity = 0
        var originalHub: WeakReference<DiagnosticHubActivity>? = null
        fun requireHub(): String {
            val active = checkNotNull(hub)
            check(active.state == Lifecycle.State.RESUMED)
            var token = ""
            active.onActivity {
                check(it === originalHub?.get() && System.identityHashCode(it) == hubIdentity && it.hasWindowFocus())
                token = "hub/$hubIdentity/${it.taskId}/${System.identityHashCode(it.window.decorView)}"
            }
            return token
        }
        val rows = ArrayList<Row>(128)
        val pending = ArrayDeque<Row>()
        val touches = ArrayList<Touch>(128)
        var historySampleCount = 0
        var activeTouch: ActiveTouch? = null
        val failureTouchCleanup = JSONObject().put("status", "not_required")
        val injected = JSONArray()
        val submissions = JSONArray()
        var scenario: ActivityScenario<GpuPreviewDiagnosticActivity>? = null
        var dngActivity: WeakReference<GpuPreviewDiagnosticActivity>? = null
        fun withActivity(action: (GpuPreviewDiagnosticActivity) -> Unit) {
            if (!dng) checkNotNull(scenario).onActivity { action(it) }
            else ins.runOnMainSync {
                val activity = checkNotNull(dngActivity?.get())
                check(ActivityLifecycleMonitorRegistry.getInstance().getLifecycleStageOf(activity) != Stage.DESTROYED)
                action(activity)
            }
        }
        var render: GpuPreviewRenderer? = null
        var callback: Window.Callback? = null
        var originalEngine: StageClearGpu? = null
        var originalFrame: ((GpuPreviewFrame) -> Unit)? = null
        var failed: Throwable? = null
        var invalidTrials = 0
        var invalidTimingTrials = 0
        var coverageVerified = false
        val trials = JSONArray()
        report.put("trials", trials)
        try {
            if (dng) {
                hub = ActivityScenario.launch(DiagnosticHubActivity::class.java)
                checkNotNull(hub).onActivity { hubIdentity = System.identityHashCode(it); originalHub = WeakReference(it) }
                await { try { requireHub(); true } catch (_: IllegalStateException) { false } }
                report.put("hubIdentity", hubIdentity)
                if (pssMode) checkNotNull(support).capturePss("hub_before_input", ::requireHub)
                // Do not use ActivityScenario for this launch: its invoker adds CLEAR_TASK.
                checkNotNull(hub).onActivity { it.startActivity(Intent(it, GpuPreviewDiagnosticActivity::class.java)) }
                await {
                    var found = false
                    ins.runOnMainSync {
                        val resumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                            .filterIsInstance<GpuPreviewDiagnosticActivity>()
                        check(resumed.size <= 1)
                        resumed.singleOrNull()?.let { dngActivity = WeakReference(it); found = true }
                    }
                    found
                }
            } else scenario = ActivityScenario.launch(GpuPreviewDiagnosticActivity::class.java)
            withActivity { render = it.renderer }
            val renderer = checkNotNull(render)
            await { state(renderer).let { it.draw > 0 && !it.pending && it.bytes > 0 } }
            if (dng) {
                // Existing Files callback closes the synthetic photo itself. Do not add another close/release.
                report.put("dngSource", checkNotNull(support).loadThroughFiles(::withActivity))
                await { state(renderer).let { !it.pending && it.bytes > 0 } }
                onGl(renderer) {
                    report.put("dngInput", checkNotNull(support).inputIdentity(field(renderer, "retainedInput") as LinearSrgbImage))
                }
            }
            fun requireDngBaseline(): String {
                var activityId = 0
                withActivity {
                    check(it.renderer === renderer)
                    check(it.diagnosticState().let { state -> state.resumed && !state.gpuError && !state.loading && state.inputResult == "loaded" })
                    activityId = System.identityHashCode(it)
                }
                var token = ""
                onGl(renderer) {
                    val state = stateOnGl(renderer)
                    check(!state.pending && state.bytes > 0 && axes.all { axis -> abs(axis.value(state.params) - axis.baseline) < 1e-6 })
                    token = boundaryToken(renderer, activityId, state)
                }
                return token
            }
            // All endpoint rules are computed and persisted before the first gesture, never tuned to results.
            var slop = 0
            var density = 0f
            withActivity {
                slop = ViewConfiguration.get(it).scaledTouchSlop
                density = it.resources.displayMetrics.density
            }
            check(density > 0)
            val geometry = axes.associateWith { axis ->
                val node = reveal(axis)
                setBaseline(node, axis)
                val bounds = Rect().also(node::getBoundsInScreen)
                val track = activeTrackPx(bounds, density)
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
                    .put("semanticsPaddingEachSidePx", (10f * density).roundToInt())
                    .put("activeThumbModelPx", (2f * density).roundToInt()).put("trackPx", g.track)
                    .put("rangeMin", axis.min.toDouble()).put("rangeMax", axis.max.toDouble())
                    .put("cpuTapStep", axis.cpuTapStep).put("cpuQuantizationHalfWidth", axis.quantizationHalfWidth)
                    .put("cpuOnePx", g.cpuOnePx).put("gpuValuePerPx", g.gpuOnePx)
                    .put("eightFloatUlp", g.floatAllowance).put("endpointTolerance", g.tolerance)
                    .put("plannedInjectedEventCount", if (programmatic) 0 else 42)
                    .put("plannedMoveCount", if (programmatic) 0 else 40)
                    .put("plannedSubmitCount", if (programmatic) 41 else 0).put("sampleIntervalMs", 20)
            })).put("preInputContractRecordedUptimeMs", SystemClock.uptimeMillis())
            output.writeText(report.toString(2))
            if (pssMode) checkNotNull(support).capturePss("initial_dng_display", ::requireDngBaseline)
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
                            row = Row(rows.size, params.strength, params.adjustments.warmth, params.adjustments.exposureEv, System.nanoTime(),
                                showBefore = display.showBefore, rotationDegrees = display.rotationDegrees)
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
            withActivity {
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
                            if (auxiliaryTouch) {
                                check(event.historySize <= 64 && historySampleCount + event.historySize <= 4096)
                                checkNotNull(row).also { touch ->
                                    touch.windowX = event.x; touch.windowY = event.y
                                    touch.historySamples = List(event.historySize) { index ->
                                        HistoricalTouch(event.getHistoricalEventTime(index), event.getHistoricalX(index), event.getHistoricalY(index))
                                    }
                                }
                                historySampleCount += event.historySize
                            }
                        }
                        val handled = prior.dispatchTouchEvent(event)
                        observe { row?.also { it.handled = handled; it.returnedNs = System.nanoTime() } }
                        return handled
                    }
                }
            }
            for ((operationIndex, operation) in operations.withIndex()) {
                val (axis, repetition) = operation
                // Reset/reveal actions are setup, outside this trial's row/draw/touch boundaries.
                axes.forEach { setBaseline(reveal(it), it) }
                val slider = reveal(axis)
                ins.waitForIdleSync()
                await { state(renderer).let { !it.pending && axes.all { axis -> abs(axis.value(it.params) - axis.baseline) < 1e-6 } } }
                val bounds = Rect().also(slider::getBoundsInScreen)
                val g = geometry.getValue(axis)
                check(activeTrackPx(bounds, density) == g.track) { "Geometry changed after preregistration" }
                val center = bounds.exactCenterX()
                val y = bounds.exactCenterY()
                val travel = (g.track * (axis.peak - axis.baseline) / axis.span).toFloat()
                check(center + slop + travel < bounds.right - density) { "Candidate trajectory does not fit; do not shorten it" }
                val setupBarrier = awaitComposeSubmitBarrier(0L)
                await { state(renderer).let { !it.pending && axes.all { axis -> abs(axis.value(it.params) - axis.baseline) < 1e-6 } } }
                var rowFrom = 0
                var touchFrom = 0
                val trialStart = AtomicReference<State>()
                onGl(renderer) {
                    trialStart.set(stateOnGl(renderer).also { check(!it.pending && !it.display.showBefore && it.display.rotationDegrees == 0) })
                    check(pending.isEmpty())
                    rowFrom = rows.size
                }
                withActivity { touchFrom = touches.size }
                val trial = JSONObject().put("axis", axis.name)
                    .put("operationIndex", operationIndex).put("axisRepetition", repetition)
                    .put("role", if (auxiliaryTouch) "auxiliary" else if (dng && repetition == 0) "warmup" else "recorded").put("startState", trialStart.get().json())
                    .put("setupComposeBarrier", setupBarrier)
                    .put("rowFromInclusive", rowFrom).put("touchFromInclusive", touchFrom)
                    .put("left", bounds.left).put("right", bounds.right).put("top", bounds.top).put("bottom", bounds.bottom)
                    .put("travelPx", travel.toDouble()).put("endpointTolerance", g.tolerance)
                    .put("boundaryStatus", "boundary_unproven").put("endpointValidity", "not_assessed")
                    .put("trajectoryTimingStatus", "not_assessed")
                trials.put(trial)
                val injectionFrom = injected.length()
                val down = SystemClock.uptimeMillis() + 100
                var settledValue = Double.NaN
                var returnRowSequence = -1
                if (programmatic) {
                    // Setup is drained before row boundaries; unchanged UI keys do not launch parameter effects.
                    val submissionFrom = submissions.length()
                    var originMs = 0L
                    var finalSubmitNs = 0L
                    for (index in 0..40) {
                        val fraction = when {
                            index <= 10 -> index / 10.0
                            index <= 30 -> 1.0
                            else -> (40 - index) / 10.0
                        }
                        val params = axis.withValue(trialStart.get().params, axis.baseline + (axis.peak - axis.baseline) * fraction)
                        if (index > 0) {
                            val wait = originMs + index * 20 - SystemClock.uptimeMillis()
                            if (wait > 0) SystemClock.sleep(wait)
                        }
                        val submission = JSONObject().put("axis", axis.name).put("sampleIndex", index)
                            .put("plannedRelativeMs", index * 20).put("strength", params.strength)
                            .put("warmth", params.adjustments.warmth).put("exposureEv", params.adjustments.exposureEv)
                            .put("showBefore", trialStart.get().display.showBefore)
                            .put("rotationDegrees", trialStart.get().display.rotationDegrees)
                        ins.runOnMainSync {
                            val actualMs = SystemClock.uptimeMillis()
                            val beginNs = System.nanoTime()
                            if (index == 0) originMs = actualMs
                            // Exactly the existing Activity LaunchedEffect's submit entry; no force-render.
                            renderer.submit(params, trialStart.get().display)
                            submission.put("actualSubmitUptimeMs", actualMs).put("submitBeginNs", beginNs)
                                .put("submitReturnedNs", System.nanoTime()).put("plannedUptimeMs", originMs + index * 20)
                                .put("actualRelativeMs", actualMs - originMs)
                                .put("relativeLatenessMs", actualMs - originMs - index * 20)
                            if (index == 40) finalSubmitNs = beginNs
                        }
                        submissions.put(submission)
                    }
                    val submitted = (submissionFrom until submissions.length()).map { submissions.getJSONObject(it) }
                    val lateness = submitted.map { it.getLong("relativeLatenessMs") }
                    val span = submitted.last().getLong("actualRelativeMs")
                    val bursts = submitted.zipWithNext().count { (a, b) -> a.getLong("actualSubmitUptimeMs") == b.getLong("actualSubmitUptimeMs") }
                    val timingValid = lateness.all { it in 0L..20L } && span in 800L..820L && bursts == 0
                    if (!timingValid) invalidTimingTrials++
                    trial.put("submissionFromInclusive", submissionFrom).put("submissionToExclusive", submissions.length())
                        .put("programmaticSubmitCount", submitted.size).put("plannedSubmitSpanMs", 800)
                        .put("actualSubmitSpanMs", span).put("minRelativeLatenessMs", lateness.min())
                        .put("maxRelativeLatenessMs", lateness.max()).put("lateSubmitCount", lateness.count { it > 0 })
                        .put("earlySubmitCount", lateness.count { it < 0 })
                        .put("sameUptimeMillisecondSubmitIntervalCount", bursts)
                        .put("inputTimingValid", timingValid)
                        .put("trajectoryTimingStatus", if (timingValid) "valid_input_schedule" else "invalid_input_schedule")
                    settledValue = axis.baseline
                    await {
                        var proven = false
                        onGl(renderer) {
                            val end = stateOnGl(renderer)
                            val last = rows.lastOrNull()?.takeIf { it.sequence >= rowFrom }
                            if (last != null && last.beginNs >= finalSubmitNs && last.completed && last.callbackNs != null &&
                                !end.pending && pending.isEmpty() && axis.value(last) == settledValue && axis.value(end.params) == settledValue) {
                                returnRowSequence = last.sequence
                                proven = true
                            }
                        }
                        proven
                    }
                    trial.put("finalProgrammaticSubmitNs", finalSubmitNs).put("returnEvidenceRowSequence", returnRowSequence)
                } else {
                    if (auxiliaryTouch) activeTouch = ActiveTouch(axis.name, down, center, y)
                    fun send(action: Int, time: Long, x: Float) {
                        val wait = time - SystemClock.uptimeMillis()
                        if (wait > 0) SystemClock.sleep(wait)
                        val actualTime = SystemClock.uptimeMillis()
                        val event = MotionEvent.obtain(down, time, action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                        if (auxiliaryTouch) checkNotNull(activeTouch).also { it.x = x; it.lastAction = action }
                        val accepted = try {
                            if (auxiliaryTouch && action == MotionEvent.ACTION_DOWN) checkNotNull(activeTouch).downInjectionAttempted = true
                            ins.uiAutomation.injectInputEvent(event, false).also { accepted ->
                                if (auxiliaryTouch) checkNotNull(activeTouch).also { stream ->
                                    if (action == MotionEvent.ACTION_DOWN) { stream.downInjectionReturned = true; stream.downAccepted = accepted }
                                    if (action == MotionEvent.ACTION_UP) { stream.upInjectionReturned = true; stream.upAccepted = accepted }
                                }
                            }
                        } finally { event.recycle() }
                        injected.put(JSONObject().put("axis", axis.name).put("action", action).put("plannedMs", time)
                            .put("actualInjectMs", actualTime).put("injectReturnedMs", SystemClock.uptimeMillis())
                            .put("x", x.toDouble()).put("y", y.toDouble()).put("accepted", accepted))
                        check(accepted)
                    }
                    send(MotionEvent.ACTION_DOWN, down, center)
                    if (failAfterDown) error("intentional_auxiliary_touch_failure_after_accepted_down")
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
                    if (auxiliaryTouch) check(injectionRows.size == 42)
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
                        withActivity {
                            returnedUp = touches.drop(touchFrom).lastOrNull {
                                it.action == MotionEvent.ACTION_UP && it.downMs == down && it.returnedNs != null
                            }
                        }
                        returnedUp != null
                    }
                    trial.put("finalUpDelivery", checkNotNull(returnedUp).json())
                    var finalMove: Touch? = null
                    withActivity {
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
                    settledValue = checkNotNull(nodes().firstOrNull {
                        it.rangeInfo?.let { r -> r.min == axis.min && r.max == axis.max } == true
                    }?.rangeInfo?.current?.toDouble())
                    trial.put("uiLastObservedValue", settledValue)
                    // A final MOVE render may complete before UP; no forced submit/render is used.
                    await {
                        var proven = false
                        onGl(renderer) {
                            val end = stateOnGl(renderer)
                            val last = rows.lastOrNull()?.takeIf { it.sequence >= rowFrom }
                            if (last != null && last.beginNs >= deliveredReturnMove.receivedNs &&
                                last.completed && last.callbackNs != null && !end.pending && pending.isEmpty() &&
                                axis.value(last) == settledValue && axis.value(end.params) == settledValue) {
                                returnRowSequence = last.sequence
                                proven = true
                            }
                        }
                        proven
                    }
                    trial.put("uiReturnValue", settledValue).put("returnEvidenceRowSequence", returnRowSequence)
                        .put("uiReturnWithinTolerance", abs(settledValue - axis.baseline) <= g.tolerance)
                }
                val trialRows = ArrayList<Row>()
                val endRef = AtomicReference<State>()
                onGl(renderer) {
                    val end = stateOnGl(renderer)
                    check(!end.pending && pending.isEmpty())
                    check(rows.lastOrNull()?.sequence == returnRowSequence && axis.value(end.params) == settledValue) {
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
                trial.put("boundaryStatus", if (programmatic) "final_programmatic_submit_and_render_proven" else "final_dispatch_compose_submit_and_render_proven")
                val trialTouches = ArrayList<Touch>()
                withActivity {
                    trialTouches.addAll(touches.subList(touchFrom, touches.size))
                    trial.put("touchToExclusive", touches.size)
                }
                if (programmatic) {
                    check(trialTouches.isEmpty()) { "Unexpected touch during programmatic trial" }
                } else {
                    check(trialTouches.count { it.action == MotionEvent.ACTION_DOWN } == 1)
                    check(trialTouches.count { it.action == MotionEvent.ACTION_UP } == 1)
                    check(trialTouches.all { it.downMs == down && it.action != MotionEvent.ACTION_CANCEL && it.pointers == 1 })
                }
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
                val displayFixed = trialRows.all {
                    it.showBefore == trialStart.get().display.showBefore && it.rotationDegrees == trialStart.get().display.rotationDegrees
                }
                val valid = startValid && peakValid && returnValid && othersFixed && displayFixed
                if (!valid) invalidTrials++
                trial.put("startActualValue", axis.value(trialStart.get().params)).put("maxRenderedValue", actualPeak ?: JSONObject.NULL)
                    .put("returnRenderedValue", actualReturn).put("startWithinTolerance", startValid)
                    .put("peakWithinTolerance", peakValid).put("returnWithinTolerance", returnValid)
                    .put("otherAxesFixed", othersFixed).put("displayFixed", displayFixed).put("endpointValid", valid)
                    .put("endpointValidity", if (valid) "valid_endpoints" else "invalid_endpoints")
                    .put("observerCoverage", "one row per renderer draw and one FIFO callback per row")
                    .put("injectedEventCount", injected.length() - injectionFrom)
                    .put("windowMotionEventCount", trialTouches.size)
                    .put("summedSampleCountIncludingHistoryNotUnique", trialTouches.sumOf { 1 + it.history })
                    .put("returnFrameCompletedNs", lastRow?.endNs ?: JSONObject.NULL)
                    .put("returnRendererCallbackNs", lastRow?.callbackNs ?: JSONObject.NULL)
                if (!programmatic) trial
                    .put("plannedDownToUpMs", 800)
                    .put("actualInjectionDownToUpMs", injected.getJSONObject(injected.length() - 1).getLong("actualInjectMs") -
                        injected.getJSONObject(injectionFrom).getLong("actualInjectMs"))
                    .put("windowDownToUpEventMs", trialTouches.last().eventMs - trialTouches.first().eventMs)
                    .put("windowDownToUpReceivedNs", trialTouches.last().receivedNs - trialTouches.first().receivedNs)
                    .put("windowDownToUpReceivedUptimeMs", trialTouches.last().receivedUptimeMs - trialTouches.first().receivedUptimeMs)
                    .put("windowReceiptAfterEventTimeCount", trialTouches.count { it.receivedUptimeMs > it.eventMs })
                    .put("sameUptimeMillisecondWindowIntervalCount", trialTouches.zipWithNext().count { (a, b) -> a.receivedUptimeMs == b.receivedUptimeMs })
                if (pssMode) checkNotNull(support).capturePss("operation_${operationIndex}_${axis.name}_$repetition", ::requireDngBaseline)
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
                    .put("invalidTrialCount", invalidTrials).put("invalidTimingTrialCount", invalidTimingTrials)
            }
            if (dng) {
                checkNotNull(support).verifyOriginalUnchanged()
                report.put("sourceOriginalUnchanged", true)
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
            // Still attached to the original Window delegate. Never put cleanup input in the normal 42-event stream.
            if (auxiliaryTouch && failed != null) attempt("cancel_failed_touch") {
                activeTouch?.let { stream ->
                    failureTouchCleanup.put("status", "checking_failed_stream").put("axis", stream.axis).put("downMs", stream.downMs)
                        .put("downInjectionAttempted", stream.downInjectionAttempted).put("downInjectionReturned", stream.downInjectionReturned)
                        .put("downInjectionAccepted", stream.downAccepted ?: JSONObject.NULL).put("lastAttemptedAction", stream.lastAction)
                        .put("upInjectionReturned", stream.upInjectionReturned).put("upInjectionAccepted", stream.upAccepted ?: JSONObject.NULL)
                    var downDelivered = false
                    var touchCount = 0
                    fun terminalReceipt(): Touch? {
                        var terminalTouch: Touch? = null
                        withActivity {
                            touchCount = touches.size
                            downDelivered = touches.any { it.downMs == stream.downMs && it.action == MotionEvent.ACTION_DOWN }
                            terminalTouch = touches.lastOrNull { it.downMs == stream.downMs &&
                                it.action in setOf(MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL) }
                        }
                        return terminalTouch
                    }
                    val before = terminalReceipt()
                    failureTouchCleanup.put("downWindowDelivered", downDelivered)
                        .put("terminalBefore", before?.json() ?: JSONObject.NULL)
                    val uncertainDown = stream.downInjectionAttempted && !stream.downInjectionReturned
                    failureTouchCleanup.put("downInjectionOutcomeUncertain", uncertainDown)
                    // A thrown injection may already be queued even when the first Window snapshot is empty.
                    if ((stream.downAccepted != true && !downDelivered && !uncertainDown) || before?.returnedNs != null) {
                        failureTouchCleanup.put("status", "not_required_no_active_stream")
                    } else {
                        failureTouchCleanup.put("status", "cancel_attempted").put("touchFromInclusive", touchCount)
                        val time = SystemClock.uptimeMillis()
                        failureTouchCleanup.put("cancelEventMs", time).put("cancelX", stream.x.toDouble()).put("cancelY", stream.y.toDouble())
                        val cancel = MotionEvent.obtain(stream.downMs, time, MotionEvent.ACTION_CANCEL, stream.x, stream.y, 0)
                            .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                        var accepted: Boolean? = null
                        failureTouchCleanup.put("cancelInjectionReturned", false)
                        try {
                            accepted = ins.uiAutomation.injectInputEvent(cancel, false)
                            failureTouchCleanup.put("cancelInjectionReturned", true).put("cancelInjectionReturnedMs", SystemClock.uptimeMillis())
                        } catch (cancelFailure: Throwable) {
                            failureTouchCleanup.put("cancelInjectionFailureType", cancelFailure.javaClass.simpleName)
                        } finally { cancel.recycle() }
                        failureTouchCleanup.put("cancelInjectionAccepted", accepted ?: JSONObject.NULL)
                        failureTouchCleanup.put("terminalPollScope", "1s polling budget between Main calls; not a hard end-to-end deadline")
                        val deadline = SystemClock.elapsedRealtime() + 1000
                        var after = terminalReceipt()
                        while (after?.returnedNs == null && SystemClock.elapsedRealtime() < deadline) {
                            SystemClock.sleep(25); after = terminalReceipt()
                        }
                        failureTouchCleanup.put("terminalAfter", after?.json() ?: JSONObject.NULL).put("touchToExclusive", touchCount)
                            .put("downWindowDeliveredAfter", downDelivered).put("upWindowDelivered", after?.action == MotionEvent.ACTION_UP)
                            .put("cancelWindowDelivered", after?.action == MotionEvent.ACTION_CANCEL)
                            .put("status", if (accepted == true && after?.returnedNs != null) "terminal_observed_after_cancel_attempt" else "cancel_unproven")
                        check(accepted == true && after?.returnedNs != null) { "Failure touch cleanup unproven" }
                    }
                }
            }
            val renderer = render
            var terminal: CountDownLatch? = null
            var glWorker: Thread? = null
            var glOwner: Any? = null
            attempt("capture_close_boundary") {
                terminal = renderer?.let { field(it, "terminated") as CountDownLatch }
                if (dng && renderer != null) {
                    glOwner = checkNotNull(field(renderer, "thread"))
                    glWorker = field(checkNotNull(glOwner), "thread") as Thread
                }
            }
            attempt("restore_engine") {
                if (renderer != null) onGl(renderer) { originalEngine?.let { setField(renderer, "engine", it) } }
            }
            attempt("restore_frame_callback") {
                if (renderer != null) onGl(renderer) {
                    originalFrame?.let { setField(renderer, "onFrame", it) }
                    report.put("unpairedRows", pending.size)
                }
            }
            attempt("restore_window_callback") { if (scenario != null || dngActivity?.get() != null) withActivity { callback?.let { prior -> it.window.callback = prior } } }
            attempt("close_activity") {
                if (!dng) scenario?.close() else dngActivity?.get()?.let { activity ->
                    ins.runOnMainSync { activity.finish() }
                    await {
                        var destroyed = false
                        ins.runOnMainSync { destroyed = ActivityLifecycleMonitorRegistry.getInstance().getLifecycleStageOf(activity) == Stage.DESTROYED }
                        destroyed
                    }
                    dngActivity?.clear()
                }
            }
            attempt("await_renderer_close") { terminal?.let { check(it.await(5, TimeUnit.SECONDS)) } }
            if (dng) attempt("join_gl_thread") {
                val worker = checkNotNull(glWorker)
                check(Thread.currentThread() !== worker)
                val deadline = SystemClock.elapsedRealtime() + 5000
                while (worker.isAlive) {
                    val left = deadline - SystemClock.elapsedRealtime()
                    check(left > 0) { "GL thread join deadline exceeded" }
                    worker.join(left)
                }
                report.put("glThreadTerminated", !worker.isAlive)
                check(field(checkNotNull(glOwner), "asynchronousFailure") == null)
            }
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
            if (dng && cleanupErrors.length() == 0) attempt("return_same_hub") {
                await { try { requireHub(); true } catch (_: IllegalStateException) { false } }
                report.put("returnedSameHub", true)
                if (pssMode) {
                    checkNotNull(support).capturePss("closed_same_hub") {
                        val hubToken = requireHub()
                        val closed = checkNotNull(renderer)
                        check(checkNotNull(glWorker).isAlive.not() && checkNotNull(terminal).count == 0L)
                        val ledger = field(closed, "ledger") as GpuMemoryLedger
                        check(ledger.allocatedBytes == 0L && ledger.textureCount == 0 && closed.activeWindowCount == 0)
                        "$hubToken/closed/" + boundaryToken(closed, 0, null)
                    }
                    check(support.pss.length() == 18)
                }
            }
            attempt("close_hub_after_observation") { hub?.close() }
            report.put("cleanupErrors", cleanupErrors)
                .put("cleanupStatus", if (cleanupErrors.length() == 0) "verified" else "failed")
                .put("observerOutcome", when {
                    observerFailure.get() != null -> "observer_failed"
                    coverageVerified -> "coverage_verified"
                    else -> "not_fully_verified"
                })
                .put("allEndpointValid", trials.length() == operations.size && (0 until trials.length()).all {
                    trials.getJSONObject(it).optBoolean("endpointValid", false)
                })
                .put("outcome", when {
                    failed != null || observerFailure.get() != null -> "functional_probe_failed"
                    else -> "completed_functional_observer_probe"
                })
            val timingComplete = trials.length() == operations.size && (0 until trials.length()).all {
                trials.getJSONObject(it).has("inputTimingValid")
            }
            val allTimingValid = timingComplete && (0 until trials.length()).all {
                trials.getJSONObject(it).getBoolean("inputTimingValid")
            }
            report.put("programmaticSubmissions", submissions)
                .put("allInputTimingValid", if (programmatic) allTimingValid else JSONObject.NULL)
                .put("trajectoryTimingStatus", when {
                    !programmatic -> "not_assessed"
                    !timingComplete -> "input_schedule_incomplete"
                    allTimingValid -> "valid_input_schedule"
                    else -> "invalid_input_schedule"
                })
            if (auxiliaryTouch) report.put("failureTouchCleanup", failureTouchCleanup).put("capturedHistoricalSampleCount", historySampleCount)
            report.put("injectedEvents", injected).put("receivedEvents", JSONArray(touches.map { it.json() }))
                .put("frames", JSONArray(rows.map { it.json() }))
                .put("observerFailure", observerFailure.get() ?: JSONObject.NULL)
            output.writeText(report.toString(2))
        }
        if (auxiliaryTouch && failed != null) throw AssertionError("GPU auxiliary touch failed; see private report").also { it.initCause(failed) }
        if (failed != null || observerFailure.get() != null) throw AssertionError("GPU observer functional probe failed; see private report")
        if (invalidTrials > 0 || invalidTimingTrials > 0) throw AssertionError("GPU endpoints or input schedule invalid; observer coverage and cleanup are reported separately")
    }

    /** Scalar witnesses only: do not keep photo/input/window references alive across dumps. */
    private fun boundaryToken(renderer: GpuPreviewRenderer, activityId: Int, state: State?): String {
        val ledger = field(renderer, "ledger") as GpuMemoryLedger
        return JSONObject().put("activityId", activityId).put("rendererId", System.identityHashCode(renderer))
            .put("state", state?.json() ?: JSONObject.NULL).put("drawCount", field(renderer, "drawCount"))
            .put("bytes", ledger.allocatedBytes).put("textures", ledger.textureCount).put("windows", renderer.activeWindowCount)
            .also { token -> listOf("photo", "enginePhoto", "retainedInput", "window", "windowLease").forEach {
                token.put(it + "Id", field(renderer, it)?.let(System::identityHashCode) ?: 0)
            } }.toString()
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

    private fun activeTrackPx(bounds: Rect, density: Float): Double =
        (bounds.width() - 2 * (10f * density).roundToInt() - (2f * density).roundToInt()).toDouble()

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
            (field(value, "params") as GpuRenderParams), (field(renderer, "ledger") as GpuMemoryLedger).allocatedBytes,
            field(value, "display") as GpuDisplayOptions)
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
