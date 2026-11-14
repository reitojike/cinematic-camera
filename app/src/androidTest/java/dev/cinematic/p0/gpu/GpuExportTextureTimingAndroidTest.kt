package dev.cinematic.p0.gpu

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.gpu.export.GpuExportRenderer
import dev.cinematic.p0.raw.original.OriginalRaster
import dev.cinematic.p0.raw.original.originalCheckpoint
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Synthetic A/B only: same shaders/input/device, no Pixel performance claim. */
class GpuExportTextureTimingAndroidTest {
    @Test fun compareFreshAndPooledStorageAfterWarmup() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("isolated") == "true" && args.getString("gpuExportReuseTiming") == "true")
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        GlThread().use { thread -> thread.call {
            EglContexts(thread, InstrumentationRegistry.getInstrumentation().targetContext.assets).use { egl ->
                GlCapabilities.inspect(egl).requireSupported()
                val input = object : OriginalRaster {
                    override val width = 1600
                    override val height = 1200
                    override fun rows(stop: () -> Boolean, consume: (Int, FloatArray) -> Unit) {
                        val row = FloatArray(width * 3)
                        repeat(height) { y ->
                            originalCheckpoint(stop)
                            for (x in 0 until width) for (c in 0..2) {
                                val sample = ((y * width + x) * 15485863L + c * 32452843L) and 65535L
                                row[x * 3 + c] = sample.toInt() / 65535f
                            }
                            consume(y, row)
                        }
                    }
                }
                var reference: IntArray? = null
                var freshAllocations = 0L
                for (iteration in 0..3) {
                    val order = if (iteration % 2 == 0) listOf(false, true) else listOf(true, false)
                    for (reuse in order) {
                        val ledger = GpuMemoryLedger(GpuMemoryLedger.EXPORT_BUDGET)
                        val pixels = IntArray(input.width * input.height)
                        val start = System.nanoTime()
                        val result = GpuExportRenderer(egl, ledger, 256, reuse).render(
                            input, .25, doubleArrayOf(1.02, 1.0, .98), .75
                        ) { y, row -> row.copyInto(pixels, y * input.width) }
                        val total = System.nanoTime() - start
                        reference?.let { assertArrayEquals(it, pixels) } ?: run { reference = pixels }
                        assertEquals(0L, ledger.allocatedBytes)
                        assertEquals(0, ledger.textureCount)
                        assertTrue(result.peakTextureBytes <= GpuMemoryLedger.EXPORT_BUDGET)
                        if (!reuse) freshAllocations = ledger.allocationCount
                        else assertTrue("Pool must reduce actual storage allocations", ledger.allocationCount < freshAllocations)
                        val record = JSONObject().put("iteration", iteration).put("warmup", iteration == 0)
                            .put("pooled", reuse).put("width", input.width).put("height", input.height)
                            .put("bandHeight", 256).put("allocations", ledger.allocationCount)
                            .put("peakTextureBytes", result.peakTextureBytes).put("totalNanos", total)
                            .put("boundaryNanos", JSONObject(result.boundaryNanos))
                        Log.i("GpuExportReuseTiming", record.toString())
                    }
                }
                egl.checkGl("export texture timing cleanup")
            }
        } }
    }
}
