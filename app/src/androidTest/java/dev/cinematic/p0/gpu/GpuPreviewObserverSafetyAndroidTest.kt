package dev.cinematic.p0.gpu

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.gpu.diag.GpuStageTap
import dev.cinematic.p0.gpu.reference.GpuReferenceImages
import dev.cinematic.p0.photoroll.PreviewRequestGate
import dev.cinematic.p0.photoroll.PreviewTiming
import dev.cinematic.p0.raw.PhotoAdjustments
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Numeric noninterference of request admission and timing; no storage or device operations. */
@RunWith(AndroidJUnit4::class)
class GpuPreviewObserverSafetyAndroidTest {
    @Test fun enabledGatePreservesOutputBitsAndSource() = verify(timingEnabled = false)

    @Test fun timingPreservesOutputBitsAndSource() = verify(timingEnabled = true)

    private fun verify(timingEnabled: Boolean) {
        val args = InstrumentationRegistry.getArguments()
        check(args.getString("isolated") == "true" && args.getString("gpuPreviewObservers") == "true") {
            "Explicit isolated=true and gpuPreviewObservers=true required"
        }
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Emulator only" }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = GpuReferenceImages.create(GpuReferenceImages.Kind.MIXED, GpuReferenceImages.Size(128, 96))
        val source = input.copyPixels().map { it.toRawBits() }.toIntArray()
        GlThread().use { thread -> thread.call {
            EglContexts(thread, context.assets).use { egl ->
                GlCapabilities.inspect(egl).requireSupported()
                val ledger = GpuMemoryLedger(GpuMemoryLedger.PREVIEW_BUDGET)
                val points = mutableListOf<GpuStageTap.Point>()
                var timing: PreviewTiming? = null
                var report = ""
                var now = 0L
                DefaultStageClearGpu(egl, ledger, GpuFrameMode.FULL_RECOMPUTE) { texture, analysis ->
                    val pipeline = ConnectedStageClearGpuPipeline(texture, analysis, GpuFrameMode.FULL_RECOMPUTE)
                    object : StageClearGpuPipeline {
                        override fun render(params: GpuRenderParams, tap: ((GpuStageTap.Point, GpuTexture) -> Unit)?,
                            consume: (GpuTexture, GpuDecisions) -> Unit) = pipeline.render(params, { point, image ->
                            points += point
                            timing?.enter(point.name)
                            tap?.invoke(point, image)
                        }, consume)
                        override fun close() = pipeline.close()
                    }
                }.use { engine -> engine.openPhoto(input) { false }.use { photo ->
                    fun render(): IntArray {
                        points.clear()
                        val output = engine.renderForTest(photo, GpuRenderParams(.5, PhotoAdjustments.NONE), GpuStageTap.Point.DISPLAY8)
                        assertEquals(128, output.width)
                        assertEquals(96, output.height)
                        assertEquals(GpuStageTap.Point.entries.filter { it != GpuStageTap.Point.INPUT }, points)
                        val pixels = output.copyPixels()
                        assertTrue(pixels.all { it.isFinite() })
                        return pixels.map { it.toRawBits() }.toIntArray()
                    }
                    val reference = render()
                    if (timingEnabled) timing = PreviewTiming(0, clock = { now++ }, sink = { report = it })
                    val actual = if (timingEnabled) render() else runBlocking { PreviewRequestGate().run(true) { render() } }
                    timing?.finish("STATE_ADOPTED")
                    assertArrayEquals(reference, actual)
                    assertArrayEquals(source, input.copyPixels().map { it.toRawBits() }.toIntArray())
                    assertEquals(1, engine.photoAnalysisCount)
                    if (timingEnabled) {
                        assertTrue(report.contains("outcome=STATE_ADOPTED"))
                        GpuStageTap.Point.entries.filter { it != GpuStageTap.Point.INPUT }.forEach {
                            assertTrue("Timing contains $it", report.contains("${it.name}Ns="))
                        }
                    }
                } }
                assertEquals(0L, ledger.allocatedBytes)
                assertEquals(0, ledger.textureCount)
            }
        } }
    }
}
