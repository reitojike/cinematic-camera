package dev.cinematic.p0.gpu

import android.app.KeyguardManager
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.media.ImageReader
import android.opengl.GLES32
import android.os.BatteryManager
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.gpu.reference.GpuReferenceImages
import dev.cinematic.p0.raw.PhotoAdjustments
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.locks.LockSupport
import kotlin.math.ceil

/** Part 2 synthetic criterion ①. Test-only adaptation of the preregistered Part 1 continuous harness. */
@RunWith(AndroidJUnit4::class)
class GpuGate1Part2TimingAndroidTest {
    @Test fun measureContinuousFullRecomputeWithResourceReuse() {
        val method = "dev.cinematic.p0.gpu.GpuGate1Part2TimingAndroidTest#measureContinuousFullRecomputeWithResourceReuse"
        gate1Part2OptIn(method, "continuous-full-v2")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val mode = "continuous-full-reuse"
        val warmups = 60
        val measured = 120
        val output = File(checkNotNull(context.getExternalFilesDir("gpu-diagnostics")), "gate1-$mode-v2.json")
        // Capture in memory. No per-frame logcat, JSON serialization, file writes or image readbacks.
        val frames = ArrayList<Frame>((warmups + measured) * 3)
        val observations = JSONArray()
        val report = JSONObject().put("version", "continuous-full-v2").put("mode", mode)
            .put("input", "MIXED 1024x768 seed=20261110").put("warmupsPerAxis", warmups)
            .put("measuredPerAxis", measured).put("targetPeriodNanos", PERIOD).put("targetCadenceApplied", true)
            .put("gpuClock", "not observed; sustained rendering does not prove a higher clock")
            .put("retained", "AS_SHOT, initial photo analysis, pooled texture/FBO storage, dimension/filter coefficient tables, EGL and programs; no prior frame images/fields/decisions")
            .put("cpuSubmitScope", "section CPU wall before glEndQuery/glFinish")
            .put("cpuCompletedScope", "section CPU wall including glEndQuery/glFinish; excludes query retrieval and swap")
            .put("gpuScope", "TIME_ELAPSED_EXT for section; start disjoint only clears prior history; end disjoint invalidates section")
            .put("display", "1024x768 ImageReader EGL window, not physical presentation")
            .put("frameMode", "FULL_RECOMPUTE")
            .put("synchronization", "glFinish before and after each whole frame; same completion boundary as Part 1; no inter-frame artifact I/O")
            .put("normalAnalysisReadback", "R7 histogram/quantile and Mist scalar GPU-to-CPU reads remain in rendering")
            .put("environmentObservations", observations)
        val initial = environment()
        report.put("start", initial.json())
        var outcome = "failed_or_stopped"
        var primary: Throwable? = null
        val consumer = HandlerThread("Gate1ContinuousConsumer").apply { start() }
        try {
            require(initial.thermal in 0..PowerManager.THERMAL_STATUS_LIGHT) { "Thermal start condition not met" }
            require(initial.interactive && !initial.locked) { "Screen must remain awake and unlocked" }
            val input = GpuReferenceImages.create(GpuReferenceImages.Kind.MIXED, GpuReferenceImages.Size(1024, 768))
            ImageReader.newInstance(1024, 768, PixelFormat.RGBA_8888, 2).use { reader ->
                reader.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, Handler(consumer.looper))
                val surface = reader.surface
                try {
                    GlThread().use { thread -> thread.call {
                        EglContexts(thread, context.assets).use { egl ->
                            GlCapabilities.inspect(egl).requireSupported()
                            val timers = Timers(egl)
                            report.put("timer", timers.description()).put("renderer", GLES32.glGetString(GLES32.GL_RENDERER))
                                .put("glVersion", GLES32.glGetString(GLES32.GL_VERSION))
                            val ledger = GpuMemoryLedger()
                            var pipeline: ConnectedStageClearGpuPipeline? = null
                            var pipelineCreations = 0
                            DefaultStageClearGpu(egl, ledger, GpuFrameMode.FULL_RECOMPUTE) { texture, analysis ->
                                pipelineCreations++
                                ConnectedStageClearGpuPipeline(texture, analysis, GpuFrameMode.FULL_RECOMPUTE)
                                    .also { pipeline = it }
                            }.use { engine -> engine.openPhoto(input) { false }.use { photo ->
                                val analysis = photo.analysis
                                egl.createWindowTarget(surface).use { window ->
                                    egl.makeCurrent(window)
                                    val target = StageClearGpuPreviewTarget(window, 1024, 768)
                                    var previousStart: Long? = null
                                    for ((axis, values) in axes()) {
                                        val before = environment()
                                        requireStable(initial, before)
                                        observations.put(before.json().put("axis", axis).put("boundary", "before"))
                                        repeat(warmups + measured) { ordinal ->
                                            val value = values[if (ordinal < warmups) ordinal % values.size else (ordinal - warmups) % values.size]
                                            val params = when (axis) {
                                                "strength" -> GpuRenderParams(value, PhotoAdjustments.NONE)
                                                "warmth" -> GpuRenderParams(.5, PhotoAdjustments(value, 0.0))
                                                else -> GpuRenderParams(.5, PhotoAdjustments(0.0, value))
                                            }
                                            // Never queue unbounded catch-up work. Late frames immediately follow the previous one.
                                            previousStart?.let { previous ->
                                                while (true) {
                                                    val remaining = previous + PERIOD - System.nanoTime()
                                                    if (remaining <= 0) break
                                                    LockSupport.parkNanos(remaining)
                                                }
                                            }
                                            val frameStart = System.nanoTime()
                                            val interval = previousStart?.let { frameStart - it }
                                            previousStart = frameStart
                                            GLES32.glFinish() // Prior swap/commands are outside this frame's interval.
                                            val beforeR7 = pipeline?.r7FragmentPassCount ?: 0
                                            val beforeGlow = pipeline?.glowPreparationCount ?: 0
                                            val frame: Frame
                                            timers.begin("FULL_FRAME").use { timer ->
                                                engine.renderPreview(photo, params, target, GpuDisplayOptions(false, 0))
                                                frame = Frame(axis, ordinal, ordinal < warmups, params, frameStart, interval,
                                                    listOf(timer.finish()))
                                            }
                                            val current = checkNotNull(pipeline)
                                            frame.r7Passes = current.r7FragmentPassCount - beforeR7
                                            frame.glowPrepares = current.glowPreparationCount - beforeGlow
                                            frame.retainedBytes = ledger.allocatedBytes
                                            frame.peakBytes = ledger.peakAllocatedBytes
                                            frame.resourceAllocations = current.resourceAllocationCount
                                            frame.resourceReuses = current.resourceReuseCount
                                            frame.coefficientTables = current.coefficientTableCount
                                            assertTrue("Every frame executes R7", frame.r7Passes > 0)
                                            assertEquals("Every frame prepares Glow", 1, frame.glowPrepares)
                                            assertEquals("Only one photo analysis", 1, engine.photoAnalysisCount)
                                            assertSame(analysis, photo.analysis)
                                            assertEquals("One photo-lived pipeline", 1, pipelineCreations)
                                            assertTrue(ledger.allocatedBytes <= ledger.peakAllocatedBytes)
                                            assertTrue(ledger.peakAllocatedBytes <= ledger.budgetBytes)
                                            frames += frame
                                            egl.swapBuffers(window)
                                            // Bounded, read-only safety checks; their overhead remains visible in start intervals.
                                            if ((ordinal + 1) % 12 == 0) {
                                                val observed = environment()
                                                observations.put(observed.json().put("axis", axis).put("afterOrdinal", ordinal))
                                                requireStable(initial, observed)
                                            }
                                        }
                                    }
                                }
                            } }
                            assertEquals(1, pipelineCreations)
                            assertEquals(0L, ledger.allocatedBytes); assertEquals(0, ledger.textureCount)
                            report.put("closedBytes", ledger.allocatedBytes).put("closedTextures", ledger.textureCount)
                                .put("peakBytes", ledger.peakAllocatedBytes).put("headroomBytes", ledger.budgetBytes - ledger.peakAllocatedBytes)
                        }
                    } }
                } finally { surface.release() }
            }
            outcome = "completed"
        } catch (error: Throwable) {
            primary = error
            report.put("failureClass", error.javaClass.simpleName)
            throw error
        } finally {
            consumer.quitSafely(); consumer.join()
            val end = environment()
            report.put("end", end.json())
            val changed = end.thermal > initial.thermal || end.plugged != initial.plugged ||
                end.batteryStatus != initial.batteryStatus || !end.interactive || end.locked
            if (changed && primary == null) outcome = "environment_changed"
            report.put("outcome", outcome).put("frames", JSONArray(frames.map { it.json() }))
                .put("summaries", summarize(frames, measured)).put("capturedFrames", frames.size)
            output.writeText(report.toString(2))
            Log.i(TAG, "mode=$mode outcome=$outcome frames=${frames.size} summaries=${report.getJSONArray("summaries")}")
            if (changed && primary == null) error("Environment changed; samples are not a stable-condition acceptance")
        }
    }

    private data class Environment(val thermal: Int, val temperature: Int, val plugged: Int,
        val batteryStatus: Int, val interactive: Boolean, val locked: Boolean) {
        fun json() = JSONObject().put("thermalStatus", thermal).put("batteryDeciC", temperature).put("plugged", plugged)
            .put("batteryStatus", batteryStatus).put("interactive", interactive).put("keyguardLocked", locked)
    }
    private fun environment(): Environment {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return Environment(context.getSystemService(PowerManager::class.java).currentThermalStatus,
            battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1,
            battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1,
            battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1,
            context.getSystemService(PowerManager::class.java).isInteractive,
            context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
    }
    private fun requireStable(start: Environment, current: Environment) {
        check(current.thermal <= start.thermal) { "Thermal status rose; do not extend measurements" }
        check(current.plugged == start.plugged && current.batteryStatus == start.batteryStatus) { "Charging state changed" }
        check(current.interactive && !current.locked) { "Screen condition changed" }
    }
    private fun axes() = linkedMapOf(
        "strength" to listOf(.25, .5, .75, 1.0),
        "warmth" to listOf(-60.0, -30.0, 0.0, 30.0, 60.0, 30.0, 0.0, -30.0, -60.0, -30.0, 0.0, 30.0),
        "exposure" to listOf(-1.0, -.5, 0.0, .5, 1.0, .5, 0.0, -.5, -1.0, -.5, 0.0, .5),
    )
    private data class Frame(val axis: String, val ordinal: Int, val warmup: Boolean, val params: GpuRenderParams,
        val startNanos: Long, val intervalNanos: Long?, val sections: List<Sample>, var r7Passes: Int = 0, var glowPrepares: Int = 0,
        var retainedBytes: Long = 0, var peakBytes: Long = 0, var resourceAllocations: Int = 0,
        var resourceReuses: Int = 0, var coefficientTables: Int = 0) {
        fun json() = JSONObject().put("axis", axis).put("ordinal", ordinal).put("phase", if (warmup) "warmup" else "measured")
            .put("strength", params.strength).put("warmth", params.adjustments.warmth).put("exposureEv", params.adjustments.exposureEv)
            .put("startNanos", startNanos).put("startIntervalNanos", intervalNanos ?: JSONObject.NULL)
            .put("lateBeyondTargetNanos", intervalNanos?.let { (it - PERIOD).coerceAtLeast(0) } ?: JSONObject.NULL)
            .put("r7Passes", r7Passes).put("glowPreparations", glowPrepares)
            .put("retainedBytes", retainedBytes).put("peakBytes", peakBytes)
            .put("resourceAllocations", resourceAllocations).put("resourceReuses", resourceReuses)
            .put("coefficientTables", coefficientTables).put("sections", JSONArray(sections.map { it.json() }))
    }
    private data class Sample(val section: String, val cpuSubmit: Long, val cpuCompleted: Long, val gpu: Long?,
        val before: Int?, val after: Int?, val available: Boolean?, val missing: String?) {
        fun json() = JSONObject().put("section", section).put("cpuSubmitNanos", cpuSubmit).put("cpuCompletedNanos", cpuCompleted)
            .put("gpuNanos", gpu ?: JSONObject.NULL).put("disjointBefore", before ?: JSONObject.NULL)
            .put("disjointAfter", after ?: JSONObject.NULL).put("queryAvailable", available ?: JSONObject.NULL)
            .put("gpuMissingReason", missing ?: JSONObject.NULL)
    }

    /** Follows EXT example: the first disjoint read clears old history; only the final read invalidates this interval.
     * https://registry.khronos.org/OpenGL/extensions/EXT/EXT_disjoint_timer_query.txt (example 1)
     */
    private class Timers(val egl: EglContexts) {
        private val extension = (0 until getInt(GLES32.GL_NUM_EXTENSIONS))
            .any { GLES32.glGetStringi(GLES32.GL_EXTENSIONS, it) == "GL_EXT_disjoint_timer_query" }
        private val bits = if (extension) IntArray(1).also { GLES32.glGetQueryiv(TIME_ELAPSED_EXT, QUERY_COUNTER_BITS_EXT, it, 0) }[0] else 0
        private val usable = extension && bits >= 30
        private val rangeNanos = if (usable) 1L shl minOf(bits, 32) else 0L
        init { egl.checkGl("inspect elapsed timer counter bits") }
        fun description() = JSONObject().put("extension", extension).put("elapsedCounterBits", bits)
            .put("usable", usable).put("resultReadBits", 32).put("usableRangeNanos", if (usable) rangeNanos else JSONObject.NULL)
            .put("overflowPolicy", "counter/readout maximum or completed wall reaching counter/readout range is missing")
        fun begin(section: String) = Timer(section)
        inner class Timer(private val section: String) : AutoCloseable {
            private val query = if (usable) IntArray(1).also { GLES32.glGenQueries(1, it, 0) }[0] else 0
            private val before = if (usable) getInt(GPU_DISJOINT_EXT) else null
            private var active = false
            private var closed = false
            private val cpuStart: Long
            init {
                check(!usable || query != 0) { "Timer query allocation failed" }
                if (query != 0) { GLES32.glBeginQuery(TIME_ELAPSED_EXT, query); active = true }
                cpuStart = System.nanoTime()
            }
            fun finish(): Sample {
                check(!closed)
                val submit = System.nanoTime() - cpuStart
                if (active) { GLES32.glEndQuery(TIME_ELAPSED_EXT); active = false }
                GLES32.glFinish()
                val completed = System.nanoTime() - cpuStart
                val available = if (query != 0) queryValue(query, GLES32.GL_QUERY_RESULT_AVAILABLE) != 0L else null
                val after = if (query != 0) getInt(GPU_DISJOINT_EXT) else null
                var value: Long? = null
                val missing = when {
                    !extension -> "unsupported_extension"
                    !usable -> if (bits == 0) "zero_counter_bits" else "invalid_counter_bits_below_30"
                    after != 0 -> "disjoint_during_interval"
                    available != true -> "unavailable_after_finish"
                    completed >= rangeNanos -> "possible_counter_or_readout_overflow"
                    else -> {
                        val result = queryValue(query, GLES32.GL_QUERY_RESULT)
                        if (result >= rangeNanos - 1L) "counter_or_readout_saturated" else { value = result; null }
                    }
                }
                egl.checkGl("measure $section")
                close()
                return Sample(section, submit, completed, value, before, after, available, missing)
            }
            override fun close() {
                if (closed) return
                if (active) GLES32.glEndQuery(TIME_ELAPSED_EXT)
                active = false
                if (query != 0) GLES32.glDeleteQueries(1, intArrayOf(query), 0)
                closed = true
            }
        }
    }

    private fun summarize(frames: List<Frame>, plannedMeasured: Int): JSONArray = JSONArray().also { result ->
        for (axis in axes().keys) {
            val selected = frames.filter { it.axis == axis && !it.warmup }
            val intervals = selected.mapNotNull { it.intervalNanos }
            result.put(statistics(axis, "FRAME_START_INTERVAL", "intervalNanos", intervals, plannedMeasured))
            val sections = selected.flatMap { it.sections }.map { it.section }.distinct()
            for (section in sections) for (metric in listOf("cpuSubmit", "cpuCompleted", "gpu")) {
                val values = selected.flatMap { it.sections }.filter { it.section == section }.mapNotNull {
                    when (metric) { "cpuSubmit" -> it.cpuSubmit; "cpuCompleted" -> it.cpuCompleted; else -> it.gpu }
                }
                result.put(statistics(axis, section, metric, values, plannedMeasured))
            }
        }
    }
    private fun statistics(axis: String, section: String, metric: String, samples: List<Long>, expected: Int): JSONObject {
        val sorted = samples.sorted()
        val result = JSONObject().put("axis", axis).put("section", section).put("metric", metric)
            .put("n", sorted.size).put("missing", expected - sorted.size).put("p95Definition", "nearest-rank")
        if (sorted.isNotEmpty()) {
            val mid = sorted.size / 2
            val median = if (sorted.size % 2 == 0) (sorted[mid - 1].toDouble() + sorted[mid]) / 2 else sorted[mid].toDouble()
            result.put("medianMs", median / 1e6).put("p95Ms", sorted[ceil(sorted.size * .95).toInt() - 1] / 1e6)
                .put("maxMs", sorted.last() / 1e6)
            if (metric == "intervalNanos") result.put("meanStartRateFps", 1e9 * samples.size / samples.sumOf { it.toDouble() })
        }
        return result
    }
    companion object {
        private const val TAG = "Gate1Additional"
        private const val PERIOD = 16_666_667L
        private const val TIME_ELAPSED_EXT = 0x88BF
        private const val GPU_DISJOINT_EXT = 0x8FBB
        private const val QUERY_COUNTER_BITS_EXT = 0x8864
        private fun getInt(name: Int) = IntArray(1).also { GLES32.glGetIntegerv(name, it, 0) }[0]
        private fun queryValue(query: Int, name: Int) = IntArray(1)
            .also { GLES32.glGetQueryObjectuiv(query, name, it, 0) }[0].toLong() and 0xffffffffL
    }
}

/** A marker is not permission: the operator must first obtain approval for exact APK/device/scope. */
internal fun gate1Part2OptIn(method: String, mode: String) {
    val args = InstrumentationRegistry.getArguments()
    org.junit.Assume.assumeTrue(args.getString("gate1Part2") == mode)
    check(args.getString("class") == method) { "Select exactly one preregistered method" }
    check(InstrumentationRegistry.getInstrumentation().targetContext.packageName == "dev.cinematic.p0.gpudiagnostic")
    val emulator = android.os.Build.HARDWARE in setOf("ranchu", "goldfish")
    if (emulator) check(args.getString("isolated") == "true")
    else {
        check(args.getString("gate1PixelProbe") == "true")
        check(android.os.Build.MODEL == "Pixel 9a")
    }
}
