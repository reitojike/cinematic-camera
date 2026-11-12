package dev.cinematic.p0.gpu

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.gpu.diag.GpuStageTap
import dev.cinematic.p0.gpu.diag.GpuStageTap.Point
import dev.cinematic.p0.gpu.reference.GpuReferenceImages
import dev.cinematic.p0.gpu.reference.ImageDiff
import dev.cinematic.p0.gpu.reference.KotlinReference
import dev.cinematic.p0.raw.LinearSrgbImage
import dev.cinematic.p0.raw.PhotoAdjustments
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import android.util.Log

/** The accepted R7 passes through #284's photo/analysis/tap boundary, not the full Look gate. */
@RunWith(AndroidJUnit4::class)
class StageClearGpuR7IntegrationAndroidTest {
    @Before fun isolatedEmulatorOnly() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish")) { "Synthetic emulator only" }
    }

    @Test fun photoAnalysisFeedsR7TapsWithUserEvAddedExactlyOnce() = gpu { egl, ledger ->
        for (kind in listOf(GpuReferenceImages.Kind.MIXED, GpuReferenceImages.Kind.HDR_LIGHTS)) {
            val input = GpuReferenceImages.create(kind, GpuReferenceImages.Size(256, 192))
            val source = KotlinReference.Source(input)
            DefaultStageClearGpu(egl, ledger).use { engine ->
                engine.openPhoto(input) { false }.use { photo ->
                    for (adjustments in listOf(PhotoAdjustments.NONE, PhotoAdjustments(60.0, 1.0))) {
                        val expected = KotlinReference.prepare(source, adjustments)
                        val stages = mapOf(Point.WARMTH to expected.warmth, Point.R7_PROFILE to expected.r7Stages[0],
                            Point.R7_TONE to expected.r7Stages[1], Point.R7_FOUNDATION to expected.r7Stages[2],
                            Point.R7_FINAL to expected.r7Final)
                        val params = GpuRenderParams(.5, adjustments)
                        for ((point, reference) in stages) {
                            val actual = engine.renderForTest(photo, params, point)
                            reportLinear("$kind $adjustments $point", reference, actual)
                            if (point == Point.R7_FINAL) Log.i("GpuReference", "CPU_REFERENCE_ONLY ${ImageDiff.display(reference, actual, "R7_FINAL")}")
                        }
                        assertEquals(source.analysis.exposure.externalBaseEv.toRawBits(), photo.analysis.meteredBaseEv.toRawBits())
                    }
                }
            }
            assertEquals(0L, ledger.allocatedBytes)
        }
    }

    @Test fun strengthReusesR7AndAdjustmentInvalidatesItInsidePhotoPipeline() = gpu { egl, ledger ->
        var pipeline: ConnectedStageClearGpuPipeline? = null
        val input = GpuReferenceImages.create(GpuReferenceImages.Kind.MIXED, GpuReferenceImages.Size(256, 192))
        DefaultStageClearGpu(egl, ledger) { texture, analysis ->
            ConnectedStageClearGpuPipeline(texture, analysis).also { pipeline = it }
        }.use { engine ->
            engine.openPhoto(input) { false }.use { photo ->
                val params = GpuRenderParams(.25, PhotoAdjustments.NONE)
                val first = engine.renderForTest(photo, params, Point.R7_FINAL)
                val passes = checkNotNull(pipeline)
                val initialCount = passes.r7FragmentPassCount
                passes.render(params.copy(strength = .75)) { output, _ -> output.requireOpen() }
                assertEquals(initialCount, passes.r7FragmentPassCount)
                val second = engine.renderForTest(photo, params.copy(strength = .75), Point.R7_FINAL)
                repeat(first.pixelCount * 3) { assertEquals(first[it].toRawBits(), second[it].toRawBits()) }
                val afterTap = passes.r7FragmentPassCount
                passes.render(params.copy(adjustments = PhotoAdjustments(0.0, 1.0))) { output, _ -> output.requireOpen() }
                assertTrue(passes.r7FragmentPassCount > afterTap)
            }
        }
    }

    private fun reportLinear(label: String, expected: LinearSrgbImage, actual: LinearSrgbImage) {
        Log.i("GpuReference", "CPU_REFERENCE_ONLY ${ImageDiff.linear(expected, actual, label)}")
    }

    private fun gpu(block: (EglContexts, GpuMemoryLedger) -> Unit) = GlThread().use { thread ->
        thread.call {
            EglContexts(thread, InstrumentationRegistry.getInstrumentation().targetContext.assets).use { egl ->
                GlCapabilities.inspect(egl).requireSupported()
                val ledger = GpuMemoryLedger()
                block(egl, ledger)
                assertEquals(0L, ledger.allocatedBytes)
            }
        }
    }
}
