package dev.cinematic.p0.gpu

import android.opengl.GLES32
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.gpu.diag.GpuStageTap
import dev.cinematic.p0.gpu.reference.GpuReferenceImages
import dev.cinematic.p0.raw.PhotoAdjustments
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Short emulator-only execution evidence, independent of image golden comparison and Gate 1. */
@RunWith(AndroidJUnit4::class)
class GpuFullPipelineTimingAndroidTest {
    @Test fun measure1024FullRecompute() {
        val args = InstrumentationRegistry.getArguments()
        check(args.getString("gpuPerformance") == "true") { "Explicit gpuPerformance=true required" }
        check(args.getString("isolated") == "true") { "Synthetic isolated run required" }
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Emulator only" }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = GpuReferenceImages.create(GpuReferenceImages.Kind.MIXED, GpuReferenceImages.Size(1024, 768))
        val samples = JSONArray()
        val report = JSONObject().put("schema", 1).put("frameMode", "FULL_RECOMPUTE")
            .put("width", 1024).put("height", 768).put("fixture", "MIXED").put("seed", GpuReferenceImages.SEED)
            .put("warmups", 1).put("measuredFrames", 3).put("samples", samples)
            .put("scope", "CPU wall including GPU completion and DISPLAY8 readback; excludes openPhoto, EGL setup and close; not pure GPU time or physical presentation")
        GlThread().use { thread -> thread.call {
            EglContexts(thread, context.assets).use { egl ->
                GlCapabilities.inspect(egl).requireSupported()
                report.put("glVendor", GLES32.glGetString(GLES32.GL_VENDOR))
                    .put("glRenderer", GLES32.glGetString(GLES32.GL_RENDERER))
                    .put("glVersion", GLES32.glGetString(GLES32.GL_VERSION)).put("sdk", Build.VERSION.SDK_INT)
                val ledger = GpuMemoryLedger(GpuMemoryLedger.PREVIEW_BUDGET)
                val points = ArrayList<GpuStageTap.Point>()
                var connected: ConnectedStageClearGpuPipeline? = null
                var creations = 0
                DefaultStageClearGpu(egl, ledger, GpuFrameMode.FULL_RECOMPUTE) { texture, analysis ->
                    creations++
                    val pipeline = ConnectedStageClearGpuPipeline(texture, analysis, GpuFrameMode.FULL_RECOMPUTE)
                    connected = pipeline
                    object : StageClearGpuPipeline {
                        override fun render(params: GpuRenderParams, tap: ((GpuStageTap.Point, GpuTexture) -> Unit)?,
                            consume: (GpuTexture, GpuDecisions) -> Unit) = pipeline.render(params, { point, image ->
                            points += point
                            tap?.invoke(point, image)
                        }, consume)
                        override fun close() = pipeline.close()
                    }
                }.use { engine -> engine.openPhoto(input) { false }.use { photo ->
                    // Same settings deliberately repeat: every measured frame must recompute, not reuse a result.
                    repeat(4) { ordinal ->
                        points.clear()
                        val beforeR7 = connected?.r7FragmentPassCount ?: 0
                        val beforeGlow = connected?.glowPreparationCount ?: 0
                        GLES32.glFinish()
                        egl.checkGl("before full-pipeline timing")
                        val start = System.nanoTime()
                        val output = engine.renderForTest(photo, GpuRenderParams(.5, PhotoAdjustments.NONE), GpuStageTap.Point.DISPLAY8)
                        GLES32.glFinish()
                        val elapsed = System.nanoTime() - start
                        egl.checkGl("after full-pipeline timing")
                        assertTrue("Positive completed wall time", elapsed > 0)
                        assertEquals(1024, output.width)
                        assertEquals(768, output.height)
                        // INPUT is the uploaded source, not an emitted processing stage.
                        assertEquals(GpuStageTap.Point.entries.filter { it != GpuStageTap.Point.INPUT }, points)
                        val pipeline = checkNotNull(connected)
                        val r7Passes = pipeline.r7FragmentPassCount - beforeR7
                        val glowPreparations = pipeline.glowPreparationCount - beforeGlow
                        assertTrue("Every frame executes R7", r7Passes > 0)
                        assertEquals("Every frame prepares Glow", 1, glowPreparations)
                        assertEquals(1, engine.photoAnalysisCount)
                        assertEquals(1, creations)
                        samples.put(JSONObject().put("ordinal", ordinal).put("warmup", ordinal == 0)
                            .put("completedWallNanos", elapsed).put("r7Passes", r7Passes)
                            .put("glowPreparations", glowPreparations).put("stages", JSONArray(points.map { it.name })))
                    }
                } }
                assertEquals(4, samples.length())
                assertEquals("All texture bytes released", 0L, ledger.allocatedBytes)
                assertEquals("All textures released", 0, ledger.textureCount)
                report.put("closedBytes", ledger.allocatedBytes).put("closedTextures", ledger.textureCount)
                    .put("peakTextureBytes", ledger.peakAllocatedBytes)
            }
        } }
        report.put("status", "complete")
        // Instrumentation status survives logcat truncation. Host records this together with head/APK hashes.
        val result = android.os.Bundle().apply { putString("gpuPerformanceReport", report.toString()) }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, result)
        Log.i("GpuFullPipelineTiming", report.toString())
    }
}
