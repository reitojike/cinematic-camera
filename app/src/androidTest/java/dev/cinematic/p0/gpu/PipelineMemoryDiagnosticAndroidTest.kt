package dev.cinematic.p0.gpu

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.gpu.diag.GpuStageTap
import dev.cinematic.p0.gpu.diag.GpuStageTap.Point
import dev.cinematic.p0.gpu.reference.GpuReferenceImages
import dev.cinematic.p0.gpu.reference.ImageDiff
import dev.cinematic.p0.gpu.reference.KotlinReference
import dev.cinematic.p0.raw.LinearSrgbImage
import dev.cinematic.p0.raw.PhotoAdjustments
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/** 1024x769 接続経路のメモリ観測と前半一致。Orton以後の画素合否は判定しない。 */
@RunWith(AndroidJUnit4::class)
class PipelineMemoryDiagnosticAndroidTest {
    @Before fun isolatedEmulatorOnly() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish")) { "Synthetic emulator only" }
    }
    @Test fun measureFullPipelinePeakAndVerifyR7GlowMistAtPreviewSize() = GlThread().use { thread ->
        thread.call {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val rows = JSONArray()
            val file = File(context.filesDir, "pipeline-memory-diagnostic.json")
            EglContexts(thread, context.assets).use { egl ->
                GlCapabilities.inspect(egl).requireSupported()
                val input = GpuReferenceImages.create(GpuReferenceImages.Kind.MIXED, GpuReferenceImages.PREVIEW_SIZE)
                for (adjustments in listOf(PhotoAdjustments.NONE, PhotoAdjustments(60.0, 1.0))) {
                    val prepared = KotlinReference.prepare(input, adjustments)
                    for (strength in listOf(.5, 1.0)) {
                        val reference = KotlinReference.render(prepared, strength)
                        val stages = JSONArray()
                        val ledger = GpuMemoryLedger()
                        var decisions: GpuDecisions? = null
                        DefaultStageClearGpu(egl, ledger) { texture, analysis ->
                            val connected = ConnectedStageClearGpuPipeline(texture, analysis)
                            object : StageClearGpuPipeline {
                                override fun render(params: GpuRenderParams, tap: ((Point, GpuTexture) -> Unit)?,
                                    consume: (GpuTexture, GpuDecisions) -> Unit) = connected.render(params, { point, image ->
                                    val stage = JSONObject().put("point", point.name).put("liveBytes", ledger.allocatedBytes)
                                        .put("peakBytesSoFar", ledger.peakAllocatedBytes).put("textureCount", ledger.textureCount)
                                    if (point in setOf(Point.R7_FINAL, Point.GLOW, Point.MIST)) {
                                        val actual = GpuStageTap.readRgb(egl, image)
                                        assertLinear(point.name, reference[point], actual)
                                        stage.put("maxAbsolute", ImageDiff.linear(reference[point], actual, point.name).maxAbsolute)
                                        stage.put("linearPass", true)
                                    }
                                    stages.put(stage); tap?.invoke(point, image)
                                }) { output, value -> decisions = value; consume(output, value) }
                                override fun close() = connected.close()
                            }
                        }.use { engine -> engine.openPhoto(input) { false }.use { photo ->
                            engine.renderForTest(photo, GpuRenderParams(strength, adjustments), Point.MIST)
                        } }
                        assertEquals(0L, ledger.allocatedBytes); assertEquals(0, ledger.textureCount)
                        assertTrue(ledger.peakAllocatedBytes <= 96L * 1024 * 1024)
                        ImageDiff.assertScalarWithin(reference.mistAutoStrength, checkNotNull(decisions).mistAutoStrength, .005, "Mist decision")
                        rows.put(JSONObject().put("width", input.width).put("height", input.height).put("warmth", adjustments.warmth)
                            .put("exposureEv", adjustments.exposureEv).put("strength", strength).put("stages", stages)
                            .put("peakBytes", ledger.peakAllocatedBytes).put("headroomBytes", ledger.budgetBytes - ledger.peakAllocatedBytes)
                            .put("closedBytes", ledger.allocatedBytes).put("closedTextures", ledger.textureCount)
                            .put("ortonAndLaterParity", "HOLD; not evaluated"))
                        file.writeText(JSONObject().put("scope", "Four maximum-size memory diagnostics, not full matrix acceptance")
                            .put("conditions", rows).toString(2))
                        Log.i("PipelineMemory", "PRE-FINISH PASS strength=$strength adjustments=$adjustments peak=${ledger.peakAllocatedBytes} " +
                            "headroom=${ledger.budgetBytes-ledger.peakAllocatedBytes} closed=0; Orton parity HOLD")
                    }
                }
            }
        }
    }
    @Test fun observeSquarePreviewBudgetWithoutResizingOrIncreasingLimit() = GlThread().use { thread ->
        thread.call {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val report = JSONObject().put("scope", "1024x1024 allowed shape; allocation diagnosis only, no acceptance")
            EglContexts(thread, context.assets).use { egl ->
                GlCapabilities.inspect(egl).requireSupported()
                val ledger = GpuMemoryLedger()
                val input = GpuReferenceImages.create(GpuReferenceImages.Kind.MIXED, GpuReferenceImages.Size(1024, 1024))
                try {
                    DefaultStageClearGpu(egl, ledger).use { engine ->
                        engine.openPhoto(input) { false }.use { photo ->
                            try {
                                engine.renderForTest(photo, GpuRenderParams(.5, PhotoAdjustments.NONE), Point.MIST)
                                report.put("outcome", "completed within unchanged budget")
                            } catch (failure: GpuMemoryBudgetException) {
                                report.put("outcome", "budget refused").put("errorType", failure.javaClass.simpleName)
                                    .put("error", failure.message).put("liveBytesAtFailure", ledger.allocatedBytes)
                            }
                        }
                    }
                } finally {
                    report.put("budgetBytes", ledger.budgetBytes).put("peakSuccessfulBytes", ledger.peakAllocatedBytes)
                        .put("closedBytes", ledger.allocatedBytes).put("closedTextures", ledger.textureCount)
                    File(context.filesDir, "pipeline-memory-square-diagnostic.json").writeText(report.toString(2))
                }
                assertEquals(96L * 1024 * 1024, ledger.budgetBytes)
                assertEquals(0L, ledger.allocatedBytes); assertEquals(0, ledger.textureCount)
                Log.i("PipelineMemory", "SQUARE DIAGNOSTIC ${report.getString("outcome")} peak=${ledger.peakAllocatedBytes} closed=0")
                Unit
            }
        }
    }

    private fun assertLinear(label: String, expected: LinearSrgbImage, actual: LinearSrgbImage) {
        assertEquals(expected.width, actual.width); assertEquals(expected.height, actual.height)
        repeat(expected.pixelCount * 3) { i ->
            val tolerance = if (expected[i] <= 1f) 1e-4 else expected[i] * 1e-4
            assertTrue("$label channel=$i expected=${expected[i]} actual=${actual[i]}",
                actual[i].isFinite() && abs(expected[i].toDouble() - actual[i]) <= tolerance)
        }
    }
}
