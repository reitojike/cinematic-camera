package dev.cinematic.p0.gpu

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.gpu.analysis.GpuAnalysis
import dev.cinematic.p0.gpu.diag.GpuStageTap
import dev.cinematic.p0.gpu.diag.GpuStageTap.Point
import dev.cinematic.p0.gpu.r7.R7Gpu
import dev.cinematic.p0.gpu.reference.GpuReferenceImages
import dev.cinematic.p0.gpu.reference.KotlinReference
import dev.cinematic.p0.photoroll.TrialDisplayPixels
import dev.cinematic.p0.raw.LinearSrgbImage
import dev.cinematic.p0.raw.PhotoAdjustments
import dev.cinematic.p0.raw.R7Base
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/** #280: Kotlin 見本との比較を先に定義する。許容差は結果によって変更しない。 */
@RunWith(AndroidJUnit4::class)
class GpuR7AndroidTest {
    @Before fun syntheticEmulatorOnly() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish")) { "Synthetic emulator only" }
    }
    private fun gpu(block: (EglContexts, GpuMemoryLedger) -> Unit) = GlThread().use { thread ->
        thread.call { EglContexts(thread, InstrumentationRegistry.getInstrumentation().targetContext.assets).use { egl ->
            val caps = GlCapabilities.inspect(egl); caps.requireSupported(); Log.i(TAG, "capabilities=$caps")
            val ledger = GpuMemoryLedger()
            block(egl, ledger)
            assertEquals(0L, ledger.allocatedBytes); assertEquals(0, ledger.textureCount)
        } }
    }
    private fun upload(egl: EglContexts, ledger: GpuMemoryLedger, w: Int, h: Int, values: FloatArray, rgb: Boolean = false): GpuTexture {
        val data = if (rgb) FloatArray(w * h * 4) { if (it % 4 == 3) 1f else values[it / 4 * 3 + it % 4] } else values
        val texture = GpuTexture.create(egl, ledger, w, h, if (rgb) GpuTexture.Format.RGBA32F else GpuTexture.Format.R32F)
        try {
            texture.upload(ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(data); flip() })
            return texture
        } catch (failure: Throwable) { texture.close(); throw failure }
    }
    private fun percentile(values: FloatArray, q: Double): Double {
        val sorted = values.sortedArray(); val rank = (sorted.size - 1) * q
        val lo = floor(rank).toInt(); val hi = ceil(rank).toInt()
        return sorted[lo].toDouble() + (sorted[hi].toDouble() - sorted[lo]) * (rank - lo)
    }
    private fun compare(label: String, expected: LinearSrgbImage, actual: LinearSrgbImage): String? {
        assertEquals(expected.width, actual.width); assertEquals(expected.height, actual.height)
        var worst = 0.0; var index = 0; var failures = 0
        repeat(expected.pixelCount * 3) { i ->
            val error = abs(expected[i].toDouble() - actual[i]); val limit = if (abs(expected[i]) <= 1f) 1e-4 else abs(expected[i]) * 1e-4
            if (!actual[i].isFinite() || error > limit) failures++
            if (error / limit > worst) { worst = error / limit; index = i }
        }
        Log.i(TAG, "$label failed=$failures worstRatio=$worst pixel=${index / 3} channel=${index % 3} expected=${expected[index]} actual=${actual[index]}")
        return if (failures == 0) null else "$label failed=$failures worstRatio=$worst"
    }
    @Test fun everyR7TapAndQuantileMatchesKotlin() = gpu { egl, ledger ->
        val failures = mutableListOf<String>(); var conditions = 0
        val adjustments = listOf(PhotoAdjustments(), PhotoAdjustments(60.0), PhotoAdjustments(0.0, 1.0), PhotoAdjustments(-60.0, -1.0))
        for (size in listOf(GpuReferenceImages.Size(256, 192), GpuReferenceImages.PREVIEW_SIZE)) {
            for (kind in GpuReferenceImages.Kind.entries) {
                val image = GpuReferenceImages.create(kind, size); val source = KotlinReference.Source(image)
                upload(egl, ledger, size.width, size.height, image.copyPixels(), true).use { input ->
                    R7Gpu(egl, ledger).use { r7 ->
                        for (adjustment in adjustments) {
                            val reference = KotlinReference.prepare(source, adjustment)
                            val expected = mapOf(Point.WARMTH to reference.warmth, Point.R7_PROFILE to reference.r7Stages[0],
                                Point.R7_TONE to reference.r7Stages[1], Point.R7_FOUNDATION to reference.r7Stages[2], Point.R7_FINAL to reference.r7Final)
                            val label = "$kind ${size.width}x${size.height} $adjustment"; val seen = mutableSetOf<Point>()
                            r7.render(input, adjustment.warmth, reference.externalBaseEv, tap = { point, texture ->
                                assertTrue("Duplicate tap $point", seen.add(point))
                                val actual = GpuStageTap.readRgb(egl, texture)
                                compare("$label $point", expected.getValue(point), actual)?.let(failures::add)
                                if (point == Point.R7_FINAL) {
                                    val a = TrialDisplayPixels.encode(actual, max(size.width, size.height)) { false }
                                    val b = TrialDisplayPixels.encode(reference.r7Final, max(size.width, size.height)) { false }
                                    var maximum = 0
                                    for (p in a.indices) for (shift in intArrayOf(0, 8, 16)) maximum = max(maximum, abs((a[p] ushr shift and 255) - (b[p] ushr shift and 255)))
                                    Log.i(TAG, "$label display8 max=$maximum")
                                    if (maximum > 1) failures += "$label display8 max=$maximum"
                                }
                            }, smoothLogTap = { texture, actual ->
                                val gpuValues = GpuStageTap.readPlane(egl, texture).copyValues()
                                val kotlinValues = R7Base.smoothLogLuma(reference.r7Stages[0]).copyValues()
                                for ((i, q) in listOf(.5, .92).withIndex()) {
                                    assertEquals("$label exact GPU selection q=$q", percentile(gpuValues, q).toRawBits(), actual[i].toRawBits())
                                    val difference = abs(percentile(kotlinValues, q) - actual[i])
                                    Log.i(TAG, "$label q=$q Kotlin difference=$difference")
                                    if (difference > 1e-4) failures += "$label q=$q difference=$difference"
                                }
                            })
                            assertEquals(expected.keys, seen)
                            val count = r7.fragmentPassCount
                            val first = r7.render(input, adjustment.warmth, reference.externalBaseEv)
                            val second = r7.render(input, adjustment.warmth, reference.externalBaseEv)
                            assertSame("Strength-only reuse", first, second); assertEquals(count, r7.fragmentPassCount)
                            conditions++
                        }
                    }
                }
            }
        }
        assertEquals(80, conditions)
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
    @Test fun radixSelectionIsExactAndRejectsInvalidInputs() = gpu { egl, ledger ->
        val cases = listOf(floatArrayOf(1f), floatArrayOf(-0f, 0f), FloatArray(4097) { -.25f },
            floatArrayOf(-Float.MAX_VALUE, -1f, -Float.MIN_VALUE, -0f, 0f, Float.MIN_VALUE, 1f, Float.MAX_VALUE),
            FloatArray(1024 * 769) { p -> ((p * 15485863L % 32452843) / 32452843.0 * 40 - 20).toFloat() })
        for (values in cases) {
            val w = if (values.size == 1024 * 769) 1024 else values.size; val h = values.size / w
            upload(egl, ledger, w, h, values).use { texture ->
                val start = System.nanoTime(); val actual = GpuAnalysis.localToneQuantiles(texture, w, h)
                Log.i(TAG, "Quantiles ${w}x$h nanos=${System.nanoTime() - start} (emulator reference only)")
                for ((i, q) in listOf(.5, .92).withIndex()) assertEquals(percentile(values, q).toRawBits(), actual[i].toRawBits())
                assertThrows(IllegalArgumentException::class.java) { GpuAnalysis.localToneQuantiles(texture, w + 1, h) }
            }
        }
        for (invalid in floatArrayOf(Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY)) {
            upload(egl, ledger, 2, 1, floatArrayOf(0f, invalid)).use { texture ->
                assertThrows(IllegalArgumentException::class.java) { GpuAnalysis.localToneQuantiles(texture, 2, 1) }
            }
        }
    }
    companion object { private const val TAG = "GpuR7Test" }
}
