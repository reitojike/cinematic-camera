package dev.cinematic.p0.gpu

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.gpu.analysis.GpuAnalysis
import dev.cinematic.p0.gpu.diag.GpuStageTap
import dev.cinematic.p0.gpu.diag.GpuStageTap.Point
import dev.cinematic.p0.gpu.r7.R7Gpu
import dev.cinematic.p0.gpu.reference.GpuReferenceImages
import dev.cinematic.p0.gpu.reference.ImageDiff
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

/** #280 / #290: Kotlinとの数値差は参考値。finite・形状・cache・資源を検証する。 */
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
    private fun compare(label: String, input: LinearSrgbImage, gpuInput: LinearSrgbImage, expected: LinearSrgbImage, actual: LinearSrgbImage) {
        assertEquals(expected.width, actual.width); assertEquals(expected.height, actual.height)
        var worst = 0.0; var index = 0; var outsideReference = 0
        repeat(expected.pixelCount * 3) { i ->
            val error = abs(expected[i].toDouble() - actual[i]); val limit = if (expected[i] <= 1f) 1e-4 else expected[i] * 1e-4
            assertTrue("$label non-finite channel=$i", actual[i].isFinite())
            if (error > limit) outsideReference++
            if (error / limit > worst) { worst = error / limit; index = i }
        }
        fun ordered(value: Float): Long {
            val bits = value.toRawBits().toLong()
            return if (bits < 0) Int.MIN_VALUE.toLong() - bits else bits
        }
        val offset = index / 3 * 3
        val ulp = abs(ordered(expected[index]) - ordered(actual[index]))
        Log.i(TAG, "$label CPU_REFERENCE_ONLY outsideHistoricalReference=$outsideReference worstRatio=$worst pixel=${index / 3} channel=${index % 3} " +
            "input=[${input[offset]},${input[offset + 1]},${input[offset + 2]}] " +
            "gpuInput=[${gpuInput[offset]},${gpuInput[offset + 1]},${gpuInput[offset + 2]}] expected=${expected[index]} actual=${actual[index]} ulp=$ulp")
    }
    @Test fun foundationEndpointsAreFiniteAndMonotoneAcrossHdrPole() = gpu { egl, ledger ->
        val samples = floatArrayOf(-1f, 0f, .001f, .25f, .5f, .999f, 1f, 1.25f,
            Math.nextDown(4f / 3f), 4f / 3f, Math.nextUp(4f / 3f), 2f, 4f)
        val rgb = FloatArray(samples.size * 3) { samples[it / 3] }
        upload(egl, ledger, samples.size, 1, rgb, true).use { input ->
            upload(egl, ledger, samples.size, 1, FloatArray(samples.size)).use { roi ->
                GpuTexture.create(egl, ledger, samples.size, 1, GpuTexture.Format.RGBA32F).use { output ->
                    for (gain in floatArrayOf(.25f, 1f, 4f)) {
                        egl.passes().run(FragmentPass(egl.shaders().fragment("r7/foundation.frag"),
                            listOf(TextureInput("s_input", input, 0), TextureInput("s_roi", roi, 1)), output,
                            uniforms = { program -> android.opengl.GLES32.glUniform1f(
                                android.opengl.GLES32.glGetUniformLocation(program, "u_gain"), gain) }))
                        val actual = GpuStageTap.readRgb(egl, output)
                        var previous = -1f
                        for (p in samples.indices) {
                            val value = actual[p * 3]
                            assertTrue("finite gain=$gain pixel=$p", value.isFinite())
                            assertTrue("monotonic gain=$gain pixel=$p", value >= previous - 1e-6f)
                            if (samples[p] <= 0f) assertEquals(0f, value, 0f)
                            if (samples[p] >= 1f) assertEquals(1f, value, 1e-6f)
                            previous = value
                        }
                    }
                }
            }
        }
    }

    @Test fun autoNegativeEvAndBoundedInputKeepBrightPointThroughFullR7() = gpu { egl, ledger ->
        val width = 256; val height = 192
        for ((bright, manualEv) in listOf(4f to 0.0, 1f to 0.0, 1f to -2.0)) {
            val pixels = FloatArray(width * height * 3) { i ->
                val x = (i / 3) % width; val y = (i / 3) / width
                if ((x - 128) * (x - 128) + (y - 96) * (y - 96) <= 4) bright
                else if (x in 96 until 160 && y in 64 until 128) .001f else bright
            }
            val input = LinearSrgbImage(width, height, pixels)
            val prepared = KotlinReference.prepare(KotlinReference.Source(input), PhotoAdjustments(0.0, manualEv))
            val ev = prepared.externalBaseEv
            assertTrue("auto base EV must be negative", ev - manualEv < 0)
            val gain = 2.0.pow(ev + dev.cinematic.p0.raw.R7BaseV1.BRIGHTNESS_EV +
                dev.cinematic.p0.raw.R7BaseV1.FOUNDATION_EV)
            val seen = mutableSetOf<Point>()
            upload(egl, ledger, width, height, pixels, true).use { texture ->
                R7Gpu(egl, ledger).use { r7 ->
                    r7.render(texture, 0.0, ev, tap = { point, image ->
                        seen += point
                        val actual = GpuStageTap.readRgb(egl, image)
                        assertTrue(actual.copyPixels().all { it.isFinite() })
                        val p = (96 * width + 128) * 3
                        if (point == Point.R7_TONE && gain < 1) {
                            val y = actual[p] * .2126f + actual[p + 1] * .7152f + actual[p + 2] * .0722f
                            assertTrue("fixture crosses old pole", 1 + (gain - 1) * y < 0)
                        }
                        if (point == Point.R7_FOUNDATION) repeat(3) { c ->
                            assertEquals("bright=$bright manualEV=$manualEv", 1f, actual[p + c], 1e-5f)
                        }
                    })
                }
            }
            assertTrue(seen.containsAll(listOf(Point.R7_TONE, Point.R7_FOUNDATION, Point.R7_FINAL)))
            assertArrayEquals(pixels, input.copyPixels(), 0f)
        }
    }

    @Test fun everyR7TapAndQuantileReportsKotlinReference() = gpu { egl, ledger ->
        var conditions = 0
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
                            val stageInputs = mapOf(Point.WARMTH to image, Point.R7_PROFILE to reference.warmth,
                                Point.R7_TONE to reference.r7Stages[0], Point.R7_FOUNDATION to reference.r7Stages[1], Point.R7_FINAL to reference.r7Stages[2])
                            var previousActual = image
                            val label = "$kind ${size.width}x${size.height} $adjustment"; val seen = mutableSetOf<Point>()
                            r7.render(input, adjustment.warmth, reference.externalBaseEv, tap = { point, texture ->
                                assertTrue("Duplicate tap $point", seen.add(point))
                                val actual = GpuStageTap.readRgb(egl, texture)
                                compare("$label $point", stageInputs.getValue(point), previousActual, expected.getValue(point), actual)
                                previousActual = actual
                                if (point == Point.R7_FINAL) {
                                    val a = TrialDisplayPixels.encode(actual, max(size.width, size.height)) { false }
                                    val b = TrialDisplayPixels.encode(reference.r7Final, max(size.width, size.height)) { false }
                                    var maximum = 0
                                    for (p in a.indices) for (shift in intArrayOf(0, 8, 16)) maximum = max(maximum, abs((a[p] ushr shift and 255) - (b[p] ushr shift and 255)))
                                    Log.i(TAG, "$label CPU_REFERENCE_ONLY display8 max=$maximum")
                                }
                            }, smoothLogTap = { texture, actual ->
                                assertEquals(2, actual.size)
                                val gpuValues = GpuStageTap.readPlane(egl, texture).copyValues()
                                val kotlinValues = R7Base.smoothLogLuma(reference.r7Stages[0]).copyValues()
                                for ((i, q) in listOf(.5, .92).withIndex()) {
                                    Log.i(TAG, ImageDiff.referenceScalar(percentile(gpuValues, q), actual[i], "$label GPU-input sorted reference q=$q"))
                                    val difference = abs(percentile(kotlinValues, q) - actual[i])
                                    Log.i(TAG, "$label CPU_REFERENCE_ONLY q=$q Kotlin difference=$difference")
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
    }
    @Test fun radixSelectionReportsSortedReferenceAndRejectsInvalidInputs() = gpu { egl, ledger ->
        val cases = listOf(floatArrayOf(1f), floatArrayOf(-0f, 0f), FloatArray(4097) { -.25f },
            FloatArray(17) { Float.MIN_VALUE }, FloatArray(17) { -Float.MIN_VALUE },
            floatArrayOf(-10f, -5f, 0f, 1f, 10f, 100f),
            floatArrayOf(-Float.MAX_VALUE, -1f, -Float.MIN_VALUE, -0f, 0f, Float.MIN_VALUE, 1f, Float.MAX_VALUE),
            FloatArray(1024 * 769) { p -> ((p * 15485863L % 32452843) / 32452843.0 * 40 - 20).toFloat() })
        for (values in cases) {
            val w = if (values.size == 1024 * 769) 1024 else values.size; val h = values.size / w
            upload(egl, ledger, w, h, values).use { texture ->
                val start = System.nanoTime(); val actual = GpuAnalysis.localToneQuantiles(texture, w, h)
                Log.i(TAG, "Quantiles ${w}x$h nanos=${System.nanoTime() - start} (emulator reference only)")
                assertEquals(2, actual.size)
                for ((i, q) in listOf(.5, .92).withIndex()) Log.i(TAG, ImageDiff.referenceScalar(percentile(values, q), actual[i], "sorted reference q=$q"))
                assertThrows(IllegalArgumentException::class.java) { GpuAnalysis.localToneQuantiles(texture, w + 1, h) }
            }
        }
        for (invalid in floatArrayOf(Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY)) {
            upload(egl, ledger, 2, 1, floatArrayOf(0f, invalid)).use { texture ->
                assertThrows(IllegalArgumentException::class.java) { GpuAnalysis.localToneQuantiles(texture, 2, 1) }
            }
        }
    }
    /** pow差も診断値のみ。CPU/GPU数値差で停止しない。 */
    @Test fun recordPowPrecisionWithoutNewTolerance() = gpu { egl, ledger ->
        val bases = doubleArrayOf(0.0, 1e-8, 1e-5, .0031308, .04, .1, .18 / .82, .10 / .90, .5, .75, 1.0, 1.01, 4.0)
        val powers = doubleArrayOf(1.23, 1.12, 1.23 - 1, 1.12 - 1, 1.0 / 2.4)
        val cases = bases.flatMap { base -> powers.map { power -> base to power } }
        val pixels = FloatArray(cases.size * 3) { i -> when (i % 3) { 0 -> cases[i / 3].first.toFloat(); 1 -> cases[i / 3].second.toFloat(); else -> 0f } }
        val shader = """
            #version 320 es
            precision highp float;
            precision highp int;
            precision highp sampler2D;
            uniform sampler2D s_input;
            layout(location = 0) out vec4 o_color;
            void main() {
                vec2 value = texelFetch(s_input, ivec2(gl_FragCoord.xy), 0).rg;
                o_color = vec4(vec3(pow(value.x, value.y)), 1.0);
            }
        """.trimIndent()
        upload(egl, ledger, cases.size, 1, pixels, true).use { input ->
            GpuTexture.create(egl, ledger, cases.size, 1, GpuTexture.Format.RGBA32F).use { output ->
                egl.shaders().fragmentSource("r7-pow-diagnostic", shader).use { program ->
                    egl.passes().run(FragmentPass(program, listOf(TextureInput("s_input", input, 0)), output))
                    val actual = GpuStageTap.readRgb(egl, output)
                    fun display(value: Float): Int {
                        val x = value.toDouble().coerceIn(0.0, 1.0)
                        return ((if (x <= .0031308) 12.92 * x else 1.055 * x.pow(1.0 / 2.4) - .055) * 255).roundToInt()
                    }
                    for ((i, pair) in cases.withIndex()) {
                        val (base, power) = pair
                        val cpu = base.pow(power).toFloat()
                        val floatArgs = base.toFloat().toDouble().pow(power.toFloat().toDouble()).toFloat()
                        val value = actual[i * 3]
                        // Inputs/outputs are nonnegative, so raw-bit distance is ULP distance.
                        val ulp = abs(cpu.toRawBits().toLong() - value.toRawBits())
                        Log.i(TAG, "POW input=$base exponent=$power cpu=$cpu floatArgsCpu=$floatArgs gpu=$value ulp=$ulp display8=${abs(display(cpu) - display(value))}")
                    }
                }
            }
        }
    }
    companion object { private const val TAG = "GpuR7Test" }
}
