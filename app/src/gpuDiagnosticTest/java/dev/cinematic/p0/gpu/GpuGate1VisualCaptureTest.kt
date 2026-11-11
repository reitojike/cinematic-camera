package dev.cinematic.p0.gpu

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.gpu.preview.GpuPreviewRenderer
import dev.cinematic.p0.raw.LinearSrgbImage
import dev.cinematic.p0.raw.PhotoAdjustments
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ceil
import kotlin.math.floor

/** Existing real GPU Surface only. No offscreen render, engine replacement, resize or performance run. */
@RunWith(AndroidJUnit4::class)
class GpuGate1VisualCaptureTest {
    @Test fun captureSelectedGpuDisplay() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("gate1VisualCapture") == "gpu")
        var support: Gate1VisualCaptureSupport? = null
        var renderer: GpuPreviewRenderer? = null
        var failed = false
        try {
            val s = Gate1VisualCaptureSupport("gpu",
                "dev.cinematic.p0.gpu.GpuGate1VisualCaptureTest#captureSelectedGpuDisplay",
                Gate1VisualCaptureSupport.GPU_SHA).also { support = it }
            s.report.put("captureMeaning", "latest queued real SurfaceView buffer, not offscreen rerender or scanout")
                .put("frameMode", "FULL_RECOMPUTE").put("uiSliderSynchronized", false).put("displayGeometryVerified", false)
                .put("inputMode", "one baseline submit through existing renderer after external Files selection")
                .put("observerScope", "read-only existing GPU snapshot and pinned fields; no CPU hooks")
            s.startHub()
            var activity: GpuPreviewDiagnosticActivity? = null
            var surface: SurfaceView? = null
            Gate1VisualCaptureSupport.await(180000) {
                s.observeExternalSetup()
                activity = s.bindExpectedActivity(Gate1VisualCaptureSupport.GPU_ACTIVITY) as? GpuPreviewDiagnosticActivity
                val host = activity
                if (host == null) false else {
                    var ready = false
                    s.ins.runOnMainSync {
                        renderer = host.renderer
                        val state = host.diagnosticState()
                        check(!state.gpuError && state.inputResult !in setOf("load_error", "readiness_timeout"))
                        surface = allSurfaces(host.window.decorView).singleOrNull()
                        ready = state.resumed && !state.loading && !state.pending && state.inputResult == "loaded" &&
                            state.completedEpoch == state.epoch && surface?.let(::fullyVisible) == true
                    }
                    ready
                }
            }
            val host = checkNotNull(activity)
            val render = checkNotNull(renderer)
            val view = checkNotNull(surface)
            s.requireActive(host, Gate1VisualCaptureSupport.GPU_ACTIVITY)
            s.phase("verify_gpu_source_and_input")
            s.report.put("sourceBefore", s.verifySource())
            val input = onGl(render) {
                val image = field(render, "retainedInput") as LinearSrgbImage
                check(image.width == 1024 && image.height == 768)
                val digest = MessageDigest.getInstance("SHA-256"); val bytes = ByteArray(4)
                repeat(image.pixelCount * 3) { channel ->
                    val bits = image[channel].toRawBits()
                    repeat(4) { bytes[it] = (bits ushr (it * 8)).toByte() }; digest.update(bytes)
                }
                val hash = Gate1VisualCaptureSupport.hex(digest.digest())
                check(hash == "db20dc7a341bd1c9c60e1087896380c20c5b130248ef1d6eb51cce1221882ae3")
                JSONObject().put("width", image.width).put("height", image.height).put("sha256", hash)
                    .put("encoding", "RGB Float raw bits little-endian row-major")
            }
            s.report.put("gpuInput", input)
            s.phase("settle_gpu_baseline")
            s.ins.runOnMainSync { render.submit(BASELINE, DISPLAY) }
            var before: JSONObject? = null
            Gate1VisualCaptureSupport.await(30000) {
                before = snapshot(render)
                before?.getBoolean("settled") == true && before?.getBoolean("baseline") == true
            }
            val boundary = checkNotNull(before)
            check(boundary.getLong("drawCount") > 0)
            val geometry = geometry(s, view)
            val displayBefore = displayGeometry(s, geometry, boundary, input)
            s.report.put("gpuBefore", boundary).put("geometryBefore", geometry)
                .put("displayGeometryBefore", displayBefore); s.save()
            var first: Bitmap? = null
            var second: Bitmap? = null
            try {
                s.phase("copy_gpu_surface_a")
                first = s.surfaceCopy(view, "gpu-surface-a")
                s.report.put("originalA", s.saveOriginal(first, "gpu-surface-a")); s.save()
                s.requireActive(host, Gate1VisualCaptureSupport.GPU_ACTIVITY)
                check(snapshot(render).toString() == boundary.toString())
                check(geometry(s, view).toString() == geometry.toString())
                SystemClock.sleep(250)
                s.phase("copy_gpu_surface_b")
                second = s.surfaceCopy(view, "gpu-surface-b")
                s.report.put("originalB", s.saveOriginal(second, "gpu-surface-b"))
                val after = snapshot(render); val geometryAfter = geometry(s, view)
                s.report.put("gpuAfter", after).put("geometryAfter", geometryAfter)
                    .put("twoCopiesSamePixels", first.sameAs(second))
                    .put("stabilityMeaning", "two native-size Surface copies 250ms apart; not user visual acceptance")
                check(after.toString() == boundary.toString() && geometryAfter.toString() == geometry.toString())
                check(first.sameAs(second)) { "GPU Surface changed; no recapture" }
                val displayAfter = displayGeometry(s, geometryAfter, after, input)
                s.report.put("displayGeometryAfter", displayAfter); s.save()
                check(displayAfter.toString() == displayBefore.toString())
                s.report.put("displayGeometryVerified", true)
            } finally { first?.recycle(); second?.recycle() }
            s.requireActive(host, Gate1VisualCaptureSupport.GPU_ACTIVITY)
            s.report.put("sourceAfter", s.verifySource()).put("acquisitionStatus", "ORIGINALS_CAPTURED")
                .put("formalVisualPairReady", false)
        } catch (error: Throwable) {
            failed = true
            support?.recordFailure(error)
        } finally {
            support?.let { s ->
                try {
                    // Bind the existing renderer even when setup failed with its picker on screen.
                    if (renderer == null) (s.capturedActivity as? GpuPreviewDiagnosticActivity)?.let { renderer = it.renderer }
                    s.returnToHub()
                    if (renderer == null) (s.capturedActivity as? GpuPreviewDiagnosticActivity)?.let { renderer = it.renderer }
                    renderer?.let { render ->
                        val terminal = field(render, "terminated") as CountDownLatch
                        check(terminal.await(5, TimeUnit.SECONDS))
                        val owner = checkNotNull(field(render, "thread"))
                        val worker = field(owner, "thread") as Thread
                        worker.join(5000); check(!worker.isAlive)
                        val ledger = field(render, "ledger") as GpuMemoryLedger
                        val cleanup = s.report.getJSONObject("cleanup")
                        cleanup.put("glThreadTerminated", true).put("closedBytes", ledger.allocatedBytes)
                            .put("closedTextures", ledger.textureCount).put("closedWindows", render.activeWindowCount)
                        check(ledger.allocatedBytes == 0L && ledger.textureCount == 0 && render.activeWindowCount == 0)
                        check(field(owner, "asynchronousFailure") == null)
                        check((field(render, "shutdownFailure") as AtomicReference<*>).get() == null)
                        check((field(render, "surfaceDestroyFailure") as AtomicReference<*>).get() == null)
                    }
                } catch (error: Throwable) {
                    failed = true
                    s.report.getJSONObject("cleanup").put("status", "UNPROVEN").put("failureType", error.javaClass.simpleName)
                }
                s.report.put("outcome", if (failed) "FAILED_OR_STOPPED" else "CAPTURE_CANDIDATE_ONLY"); s.save()
            }
        }
        if (failed) throw AssertionError("GPU visual acquisition stopped; inspect the private report")
    }

    private fun geometry(s: Gate1VisualCaptureSupport, view: SurfaceView): JSONObject {
        var result: JSONObject? = null
        s.ins.runOnMainSync {
            check(fullyVisible(view))
            val frame = Rect(view.holder.surfaceFrame)
            val location = IntArray(2).also(view::getLocationOnScreen)
            val bounds = Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
            val visible = Rect(); check(view.getGlobalVisibleRect(visible))
            // setFixedSize is deliberately not used. These observed dimensions must agree.
            check(frame.width() == view.width && frame.height() == view.height)
            val scale = minOf(frame.width() / 1024.0, frame.height() / 768.0)
            val w = 1024 * scale; val h = 768 * scale
            result = JSONObject().put("surfaceFrame", s.rect(frame)).put("viewBoundsScreen", s.rect(bounds))
                .put("viewVisibleRectScreen", s.rect(visible)).put("fullyVisibleByViewGeometry", true)
                .put("fitContentRectSurfaceContinuous", JSONArray(listOf((frame.width() - w) / 2,
                    (frame.height() - h) / 2, (frame.width() + w) / 2, (frame.height() + h) / 2)))
                .put("fitMeaning", "fixed present.frag continuous FIT geometry; no automatic crop or rescale")
                .put("unoccluded", "UNPROVEN_VIEW_GEOMETRY_ONLY")
        }
        return checkNotNull(result)
    }

    /** Fixed present.frag FIT, from observed native Surface/input and rotation, not pixel colors. */
    private fun displayGeometry(s: Gate1VisualCaptureSupport, geometry: JSONObject,
        state: JSONObject, input: JSONObject): JSONObject {
        val frame = geometry.getJSONArray("surfaceFrame")
        check(frame.getInt(0) == 0 && frame.getInt(1) == 0 && state.getInt("rotationDegrees") == 0)
        val width = frame.getInt(2); val height = frame.getInt(3)
        check(state.getInt("surfaceWidth") == width && state.getInt("surfaceHeight") == height)
        val inputWidth = input.getInt("width"); val inputHeight = input.getInt("height")
        val scale = minOf(width.toFloat() / inputWidth, height.toFloat() / inputHeight)
        val fittedWidth = inputWidth * scale; val fittedHeight = inputHeight * scale
        val left = (width - fittedWidth) * .5f; val top = (height - fittedHeight) * .5f
        val content = listOf(left, top, left + fittedWidth, top + fittedHeight)
        check(content.all { it.isFinite() } && left >= 0 && top >= 0 && content[2] <= width && content[3] <= height)
        val crop = Rect(ceil(left.toDouble()).toInt(), ceil(top.toDouble()).toInt(),
            floor(content[2].toDouble()).toInt(), floor(content[3].toDouble()).toInt())
        check(!crop.isEmpty && Rect(0, 0, width, height).contains(crop))
        return JSONObject().put("schema", "layout-crop-v1").put("observationKind", "FIXED_GPU_SURFACE_INPUT_FIT")
            .put("coordinateSpace", "raw_original_pixels").put("surfaceFrame", frame)
            .put("rawSize", JSONArray(listOf(width, height))).put("sourceSizeBasis", JSONArray(listOf(inputWidth, inputHeight)))
            .put("sourceAspectBasis", "OBSERVED_GPU_INPUT_AND_ROTATION").put("rotationDegrees", 0)
            .put("surfaceLeaseIdentityToken", state.getInt("surfaceLeaseIdentityToken"))
            .put("inputIdentityToken", state.getInt("inputIdentityToken"))
            .put("contentScale", "Fit").put("alignment", "Center").put("fitArithmetic", "float32_fixed_present_shader")
            .put("contentRectRawContinuous", JSONArray(content)).put("cropRectRaw", s.rect(crop))
            .put("cropRounding", "inward_ceil_start_floor_end").put("unoccluded", "UNPROVEN_VIEW_GEOMETRY_ONLY")
            .put("meaning", "layout-derived crop metadata only; raw PNG unchanged; not scanout or user acceptance")
    }

    private fun fullyVisible(view: SurfaceView): Boolean {
        val visible = Rect()
        return view.isShown && view.holder.surface.isValid && view.width > 0 && view.height > 0 &&
            view.getGlobalVisibleRect(visible) && visible.width() == view.width && visible.height() == view.height
    }

    private fun allSurfaces(view: View): List<SurfaceView> = buildList {
        if (view is SurfaceView) add(view)
        if (view is ViewGroup) repeat(view.childCount) { addAll(allSurfaces(view.getChildAt(it))) }
    }

    private fun snapshot(renderer: GpuPreviewRenderer): JSONObject = onGl(renderer) {
        check(field(renderer, "failed") == false)
        val mailbox = (field(checkNotNull(field(renderer, "latest")), "state") as AtomicReference<*>).get()!!
        val value = checkNotNull(field(mailbox, "value"))
        val params = field(value, "params") as GpuRenderParams
        val display = field(value, "display") as GpuDisplayOptions
        val timer = field(renderer, "timer")
        val pendingTimer = timer?.let { (field(it, "queries") as ArrayDeque<*>).isNotEmpty() } ?: false
        val lease = (field(renderer, "lease") as AtomicReference<*>).get()
        JSONObject().put("drawCount", field(renderer, "drawCount"))
            .put("settled", field(mailbox, "dirtySince") == null && field(mailbox, "displayed") == value && !pendingTimer)
            .put("baseline", params == BASELINE && display == DISPLAY)
            .put("strength", params.strength).put("warmth", params.adjustments.warmth)
            .put("exposureEv", params.adjustments.exposureEv).put("showBefore", display.showBefore)
            .put("rotationDegrees", display.rotationDegrees)
            .put("inputIdentityToken", System.identityHashCode(field(renderer, "retainedInput")))
            .put("surfaceLeaseIdentityToken", System.identityHashCode(lease))
            .put("surfaceWidth", lease?.let { field(it, "width") } ?: JSONObject.NULL)
            .put("surfaceHeight", lease?.let { field(it, "height") } ?: JSONObject.NULL)
    }

    private fun <T> onGl(renderer: GpuPreviewRenderer, action: () -> T): T {
        val latch = CountDownLatch(1); val result = AtomicReference<T>(); val failure = AtomicReference<Throwable>()
        renderer.snapshot {
            try { result.set(action()) } catch (error: Throwable) { failure.set(error) } finally { latch.countDown() }
        }
        check(latch.await(10, TimeUnit.SECONDS))
        failure.get()?.let { throw it }
        return checkNotNull(result.get())
    }

    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)

    companion object {
        private val BASELINE = GpuRenderParams(.5, PhotoAdjustments.NONE)
        private val DISPLAY = GpuDisplayOptions(false, 0)
    }
}