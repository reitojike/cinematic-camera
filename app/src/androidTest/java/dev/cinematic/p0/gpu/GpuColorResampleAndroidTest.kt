package dev.cinematic.p0.gpu

import android.opengl.GLES32
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.gpu.diag.GpuStageTap
import dev.cinematic.p0.gpu.reference.GpuReferenceImages
import dev.cinematic.p0.gpu.reference.GpuTolerances
import dev.cinematic.p0.gpu.reference.ImageDiff
import dev.cinematic.p0.gpu.resample.GpuResample
import dev.cinematic.p0.raw.FloatPlane
import dev.cinematic.p0.raw.LinearSrgbImage
import dev.cinematic.p0.raw.StageClearBlur
import dev.cinematic.p0.raw.StageClearColor
import dev.cinematic.p0.raw.StageClearProtections
import dev.cinematic.p0.raw.StageClearV1
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.*

/** #279 の合成入力のみ。asset 構文試験の成功後に opt-in で実行する。 */
@RunWith(AndroidJUnit4::class)
class GpuColorResampleAndroidTest {
    private val assets get() = InstrumentationRegistry.getInstrumentation().targetContext.assets
    @Before fun syntheticEmulatorOnly() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish")) { "Synthetic emulator only" }
    }
    private fun gpu(block: (EglContexts, GpuMemoryLedger) -> Unit) = GlThread().use { thread ->
        thread.call { EglContexts(thread, assets).use { egl ->
            val caps = GlCapabilities.inspect(egl)
            Log.i(TAG, "capabilities=$caps")
            caps.requireSupported()
            val ledger = GpuMemoryLedger()
            block(egl, ledger)
            assertEquals("All photo textures released", 0L, ledger.allocatedBytes)
            assertEquals(0, ledger.textureCount)
        } }
    }
    private fun upload(egl: EglContexts, ledger: GpuMemoryLedger, w: Int, h: Int, values: FloatArray, rgb: Boolean): GpuTexture {
        val data = if (rgb) FloatArray(w * h * 4) { i -> if (i % 4 == 3) 1f else values[(i / 4) * 3 + i % 4] } else values
        val texture = GpuTexture.create(egl, ledger, w, h, if (rgb) GpuTexture.Format.RGBA32F else GpuTexture.Format.R32F)
        try {
            texture.upload(ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(data); flip() })
            return texture
        } catch (failure: Throwable) { texture.close(); throw failure }
    }

    @Test fun colorFunctionsMatchKotlinForEverySyntheticPixel() = gpu { egl, ledger ->
        val source = ShaderSource({ path -> if (path == "color-test.frag") COLOR_TEST else assets.open("gpu/$path").bufferedReader().use { it.readText() } })
        egl.shaders().fragmentSource("color-acceptance", source.expand("color-test.frag")).use { program ->
            var conditions = 0
            val inputDigest = MessageDigest.getInstance("SHA-256")
            val referenceDigest = MessageDigest.getInstance("SHA-256")
            val failures = mutableListOf<String>()
            for (size in GpuReferenceImages.FAST_SIZES) for (kind in GpuReferenceImages.Kind.entries) {
                val image = GpuReferenceImages.create(kind, size)
                val pixels = image.copyPixels()
                val lab = FloatArray(pixels.size)
                val triple = FloatArray(3)
                repeat(image.pixelCount) { p -> StageClearColor.toLab(image, p * 3, triple); triple.copyInto(lab, p * 3) }
                for (operation in 0..10) {
                    val input = if (operation == 1 || operation in 7..9) lab else pixels
                    val expected = colorReference(operation, image, input)
                    recordHashes("$kind $size op=$operation", input, expected, inputDigest, referenceDigest)
                    checkColor(egl, ledger, program, size.width, size.height, input, expected, operation, "$kind $size op=$operation")?.let(failures::add)
                    conditions++
                }
            }
            // Signed, zero, HDR and transfer breakpoints beyond the nonnegative reference set.
            val boundary = floatArrayOf(-2f, -.1f, -0f, 0f, 1e-8f, .0031308f, .04045f, .25f, 1f, 1.01f, 2f, 4f)
            val image = LinearSrgbImage(4, 1, boundary)
            for (operation in 0..10) {
                val expected = colorReference(operation, image, boundary)
                recordHashes("boundary op=$operation", boundary, expected, inputDigest, referenceDigest)
                checkColor(egl, ledger, program, 4, 1, boundary, expected, operation, "boundary op=$operation")?.let(failures::add)
                conditions++
            }
            Log.i(TAG, "Color conditions=$conditions; failed=${failures.size}; Lab abs<=4e-6 / relative<=2e-5; circular hue<=1e-4 degrees; other bounds unchanged")
            Log.i(TAG, "Color all raw-float little-endian SHA256; input=${hex(inputDigest.digest())}; reference=${hex(referenceDigest.digest())}")
            assertTrue(failures.joinToString("\n"), failures.isEmpty())
        }
    }

    private fun colorReference(operation: Int, image: LinearSrgbImage, input: FloatArray): FloatArray {
        val result = FloatArray(input.size)
        val lab = FloatArray(3)
        repeat(image.pixelCount) { p ->
            val i = p * 3
            when (operation) {
                0 -> { StageClearColor.toLab(image, i, lab); lab.copyInto(result, i) }
                1 -> { input.copyInto(lab, 0, i, i + 3); StageClearColor.toRgb(lab, result, i) }
                2 -> { StageClearColor.toLab(image, i, lab); StageClearColor.toRgb(lab, result, i) }
                in 3..6 -> repeat(3) { c -> result[i + c] = when (operation) {
                    3 -> StageClearColor.linearToUnit(input[i + c])
                    4 -> StageClearColor.unitToLinear(input[i + c])
                    5 -> StageClearProtections.encode(input[i + c].toDouble()).toFloat()
                    else -> StageClearProtections.decode(input[i + c].toDouble()).toFloat()
                } }
                10 -> repeat(3) { c -> result[i + c] = Math.cbrt(input[i + c].toDouble()).toFloat() }
                else -> {
                    val hue = (Math.toDegrees(atan2(input[i + 2].toDouble(), input[i + 1].toDouble())) + 360) % 360
                    val value = when (operation) {
                        7 -> hue
                        8 -> { val t = (1 - abs((hue - 40 + 540) % 360 - 180) / 45).coerceIn(0.0, 1.0); t * t * (3 - 2 * t) }
                        else -> StageClearColor.skinWeight(input[i].toDouble(), hypot(input[i + 1].toDouble(), input[i + 2].toDouble()), hue)
                    }.toFloat()
                    repeat(3) { result[i + it] = value }
                }
            }
        }
        return result
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun recordHashes(label: String, input: FloatArray, reference: FloatArray,
        inputDigest: MessageDigest, referenceDigest: MessageDigest) {
        fun bytes(values: FloatArray): ByteArray = ByteBuffer.allocate(values.size * 4)
            .order(ByteOrder.LITTLE_ENDIAN).apply { values.forEach { putInt(it.toRawBits()) } }.array()
        val inputBytes = bytes(input); val referenceBytes = bytes(reference)
        inputDigest.update(inputBytes); referenceDigest.update(referenceBytes)
        Log.i(TAG, "Color SHA256 $label; input=${hex(MessageDigest.getInstance("SHA-256").digest(inputBytes))}; reference=${hex(MessageDigest.getInstance("SHA-256").digest(referenceBytes))}")
    }

    @Test fun circularHueAndBandMatchAtSignedAxesAndFiniteExtremes() = gpu { egl, ledger ->
        assertEquals(0.0, GpuTolerances.circularHueDifference(0f, 360f), 0.0)
        assertEquals(2.0, GpuTolerances.circularHueDifference(359f, 1f), 0.0)
        assertEquals(2.0, GpuTolerances.circularHueDifference(1f, 359f), 0.0)
        assertEquals(180.0, GpuTolerances.circularHueDifference(0f, 180f), 0.0)
        val directions = mutableListOf<Pair<Float, Float>>()
        for (a in floatArrayOf(0f, -0f)) for (b in floatArrayOf(0f, -0f)) directions += a to b
        directions += listOf(1f to 0f, 1f to -0f, -1f to 0f, -1f to -0f,
            0f to 1f, -0f to 1f, 0f to -1f, -0f to -1f)
        for (epsilon in floatArrayOf(1e-30f, 1e-20f, 1e-10f, 1e-8f, 1e-6f, 1e-4f)) {
            for (sign in floatArrayOf(-1f, 1f)) { directions += -1f to sign * epsilon; directions += 1f to sign * epsilon }
        }
        for (scale in floatArrayOf(java.lang.Float.MIN_NORMAL, 1e-30f, 1e-20f, 1e-8f)) {
            for (sign in floatArrayOf(-1f, 1f)) directions += scale to sign * scale
        }
        val m = Float.MAX_VALUE
        directions += listOf(m to m, m to -m, -m to m, -m to -m,
            m to 1f, m to -1f, -m to 1f, -m to -1f, 1f to m, -1f to m, 1f to -m, -1f to -m,
            m to 0f, m to -0f, -m to 0f, -m to -0f, 0f to m, -0f to m, 0f to -m, -0f to -m)
        assertEquals(64, directions.size)
        val pixels = FloatArray(directions.size * 3) { i -> when (i % 3) {
            0 -> .5f; 1 -> directions[i / 3].first; else -> directions[i / 3].second
        } }
        val image = LinearSrgbImage(directions.size, 1, pixels)
        val source = ShaderSource({ path -> if (path == "color-test.frag") COLOR_TEST else assets.open("gpu/$path").bufferedReader().use { it.readText() } })
        egl.shaders().fragmentSource("hue-boundaries", source.expand("color-test.frag")).use { program ->
            val failures = mutableListOf<String>()
            for (operation in 7..8) checkColor(egl, ledger, program, directions.size, 1,
                pixels, colorReference(operation, image, pixels), operation, "hue boundary 64 op=$operation")?.let(failures::add)
            assertTrue(failures.joinToString("\n"), failures.isEmpty())
        }
    }
    private fun ulps(a: Float, b: Float): Long {
        fun ordered(value: Float): Long {
            val bits = value.toRawBits()
            val magnitude = bits.toLong() and 0x7fffffffL
            return 0x80000000L + if (bits < 0) -magnitude else magnitude
        }
        return abs(ordered(a) - ordered(b))
    }

    private fun checkColor(egl: EglContexts, ledger: GpuMemoryLedger, program: GpuProgram, w: Int, h: Int,
        input: FloatArray, expected: FloatArray, operation: Int, label: String): String? {
        upload(egl, ledger, w, h, input, true).use { source ->
            GpuTexture.create(egl, ledger, w, h, GpuTexture.Format.RGBA32F).use { output ->
                egl.passes().run(FragmentPass(program, listOf(TextureInput("s_input", source, 0)), output,
                    uniforms = { GLES32.glUniform1i(GLES32.glGetUniformLocation(it, "u_operation"), operation) }))
                val actual = GpuStageTap.readRgb(egl, output)
                var worstRatio = -1.0; var worst = 0; var maximum = 0.0; var maxUlps = 0L; var failed = 0
                expected.indices.forEach { i ->
                    val limit = when {
                        operation == 7 -> GpuTolerances.HUE_CIRCULAR_DEGREES
                        operation == 9 -> 1e-5
                        operation in 0..2 -> if (abs(expected[i]) <= 1) GpuTolerances.LAB_ABSOLUTE else abs(expected[i]) * GpuTolerances.LAB_RELATIVE
                        else -> if (abs(expected[i]) <= 1) 2e-6 else abs(expected[i]) * 1e-5
                    }
                    val delta = if (operation == 7) GpuTolerances.circularHueDifference(expected[i], actual[i])
                        else abs(expected[i].toDouble() - actual[i])
                    if (!actual[i].isFinite() || (operation == 7 && actual[i] !in 0f..360f) || delta > limit) failed++
                    maximum = max(maximum, delta); maxUlps = max(maxUlps, ulps(expected[i], actual[i]))
                    if (delta / limit > worstRatio) { worstRatio = delta / limit; worst = i }
                }
                val pixel = worst / 3
                val display = if (operation == 1 || operation == 2) {
                    val diff = ImageDiff.display(LinearSrgbImage(w, h, expected), actual, label)
                    "display8bitMax=${diff.maxDiff}; displayChangedPixels=${diff.changedPixels}; displayMean=${diff.meanDiff}"
                } else "display8bit=not-applicable-to-this-intermediate"
                val metric = if (operation == 7) "circularDegrees" else "absolute"
                val report = "$label; metric=$metric; failedChannels=$failed; maxDiff=$maximum; maxULP=$maxUlps; " +
                    "worstPixel=$pixel (${pixel % w},${pixel / w}); channel=${worst % 3}; " +
                    "input=${input.copyOfRange(pixel * 3, pixel * 3 + 3).contentToString()}; " +
                    "kotlin=${expected[worst]}; gpu=${actual[worst]}; ULP=${ulps(expected[worst], actual[worst])}; " +
                    "toleranceRatio=$worstRatio; $display"
                Log.i(TAG, (if (failed == 0) "PASS " else "FAIL ") + report)
                if (w * h <= 4) Log.i(TAG, "$label; fullKotlin=${expected.contentToString()}; fullGpu=${actual.copyPixels().contentToString()}")
                return report.takeIf { failed != 0 }
            }
        }
    }

    @Test fun precisionProbesRecordCbrtAndSignedTinyInputs() = gpu { egl, ledger ->
        val source = ShaderSource({ path -> if (path == "color-test.frag") COLOR_TEST else assets.open("gpu/$path").bufferedReader().use { it.readText() } })
        egl.shaders().fragmentSource("color-probes", source.expand("color-test.frag")).use { program ->
            val failures = mutableListOf<String>()
            for ((size, pixel) in listOf(GpuReferenceImages.Size(64, 48) to 241,
                GpuReferenceImages.Size(64, 48) to 242, GpuReferenceImages.Size(256, 192) to 25097,
                GpuReferenceImages.Size(256, 192) to 24833)) {
                val image = GpuReferenceImages.create(GpuReferenceImages.Kind.NOISE, size)
                val rgb = FloatArray(3) { image[pixel * 3 + it] }
                val matrix = StageClearV1.RGB_TO_LMS
                val lms = FloatArray(3) { row -> matrix[row * 3] * rgb[0] + matrix[row * 3 + 1] * rgb[1] + matrix[row * 3 + 2] * rgb[2] }
                val roots = FloatArray(3) { Math.cbrt(lms[it].toDouble()).toFloat() }
                val lab = FloatArray(3).also { StageClearColor.toLab(image, pixel * 3, it) }
                val roundTrip = FloatArray(3).also { StageClearColor.toRgb(lab, it, 0) }
                val label = "NOISE $size pixel=$pixel"
                Log.i(TAG, "Kotlin probe $label; rgb=${rgb.contentToString()}; lms=${lms.contentToString()}; cbrt=${roots.contentToString()}; lab=${lab.contentToString()}; roundTrip=${roundTrip.contentToString()}")
                checkColor(egl, ledger, program, 1, 1, lms, roots, 10, "$label cbrt(LMS)")?.let(failures::add)
                checkColor(egl, ledger, program, 1, 1, rgb, lab, 0, "$label toLab")?.let(failures::add)
                checkColor(egl, ledger, program, 1, 1, rgb, roundTrip, 2, "$label roundTrip")?.let(failures::add)
            }
            // Finite extremes and denormals reveal flushing/overflow; ULP is logged even below the absolute limit.
            val tiny = floatArrayOf(Float.MIN_VALUE, -Float.MIN_VALUE, 0f,
                java.lang.Float.MIN_NORMAL, -java.lang.Float.MIN_NORMAL, -0f,
                Float.MAX_VALUE, -Float.MAX_VALUE, 1e-30f)
            checkColor(egl, ledger, program, 3, 1, tiny,
                FloatArray(tiny.size) { Math.cbrt(tiny[it].toDouble()).toFloat() }, 10, "finite cbrt extremes")?.let(failures::add)
            assertTrue(failures.joinToString("\n"), failures.isEmpty())
        }
    }
    @Test fun planeAndRgbBlurMatchAllRadiiAndSizes() = gpu { egl, ledger ->
        var conditions = 0
        for (size in listOf(GpuReferenceImages.Size(256, 192), GpuReferenceImages.PREVIEW_SIZE)) {
            // Smooth + random + hard edges in all channels, clamped to the AC's [0,1] input.
            val raw = GpuReferenceImages.create(GpuReferenceImages.Kind.MIXED, size).copyPixels()
            val image = LinearSrgbImage(size.width, size.height, FloatArray(raw.size) { raw[it].coerceIn(0f, 1f) })
            val plane = FloatPlane.fromChannel(image, 0)
            GpuResample(egl, ledger).use { resample ->
                upload(egl, ledger, size.width, size.height, image.copyPixels(), true).use { rgb ->
                    upload(egl, ledger, size.width, size.height, plane.copyValues(), false).use { single ->
                        for (radius in RADII) {
                            val label = "$size radius=$radius"
                            resample.blurPlane(single, radius).use { output ->
                                val reference = StageClearBlur.plane(plane, radius)
                                val actual = GpuStageTap.readPlane(egl, output)
                                val diff = ImageDiff.linear(reference, actual, "plane $label")
                                assertTrue(diff.toString(), diff.maxAbsolute <= 1e-5)
                                assertDisplayPlane(reference, actual, "plane $label")
                                Log.i(TAG, "PASS $diff")
                            }
                            resample.blurRgb(rgb, radius).use { output ->
                                val reference = StageClearBlur.rgb(image, radius)
                                val actual = GpuStageTap.readRgb(egl, output)
                                val diff = ImageDiff.linear(reference, actual, "rgb $label")
                                assertTrue(diff.toString(), diff.maxAbsolute <= 1e-5)
                                ImageDiff.display(reference, actual, label).assertDisplayWithin(maxDiff = 1)
                                Log.i(TAG, "PASS $diff")
                            }
                            conditions += 2
                        }
                    }
                }
            }
        }
        Log.i(TAG, "PASS blur conditions=$conditions; all radii/sizes; linear abs<=1e-5, display<=1")
    }

    private fun assertDisplayPlane(reference: FloatPlane, actual: FloatPlane, label: String) {
        fun rgb(plane: FloatPlane) = LinearSrgbImage(plane.width, plane.height, FloatArray(plane.width * plane.height * 3) { plane[it / 3] })
        ImageDiff.display(rgb(reference), rgb(actual), label).assertDisplayWithin(maxDiff = 1)
    }

    @Test fun bicubicLogLumaAndVerticalFirstMatchKotlin() = gpu { egl, ledger ->
        for (size in listOf(GpuReferenceImages.Size(256, 192), GpuReferenceImages.PREVIEW_SIZE)) {
            val image = GpuReferenceImages.create(GpuReferenceImages.Kind.MIXED, size)
            val log = FloatPlane(size.width, size.height, FloatArray(image.pixelCount) { p ->
                log2(max((.2126f * image[p * 3] + .7152f * image[p * 3 + 1] + .0722f * image[p * 3 + 2]).toDouble(), 1e-5)).toFloat()
            })
            val cell = .04 * max(size.width, size.height)
            val w = max(4, round(size.width / cell).toInt()); val h = max(4, round(size.height / cell).toInt())
            val reference = StageClearBlur.resize(StageClearBlur.resize(log, w, h, StageClearBlur.Filter.BOX), size.width, size.height, StageClearBlur.Filter.BICUBIC)
            GpuResample(egl, ledger).use { resample ->
                upload(egl, ledger, size.width, size.height, log.copyValues(), false).use { input ->
                    resample.reduce(input, w, h).use { down ->
                        resample.upsampleFrom(down, size.width, size.height, StageClearBlur.Filter.BICUBIC).use { output ->
                            val diff = ImageDiff.linear(reference, GpuStageTap.readPlane(egl, output), "log2 bicubic $size")
                            assertTrue(diff.toString(), diff.maxAbsolute <= 1e-4)
                            Log.i(TAG, "PASS $diff")
                        }
                    }
                }
            }
        }
        val image = GpuReferenceImages.create(GpuReferenceImages.Kind.NOISE, GpuReferenceImages.Size(64, 1024))
        val plane = FloatPlane.fromChannel(image, 0)
        for (filter in StageClearBlur.Filter.entries) GpuResample(egl, ledger).use { resample ->
            upload(egl, ledger, 64, 1024, plane.copyValues(), false).use { input ->
                resample.resize(input, 60, 200, filter).use { output ->
                    val diff = ImageDiff.linear(StageClearBlur.resize(plane, 60, 200, filter), GpuStageTap.readPlane(egl, output), "verticalFirst $filter")
                    assertTrue(diff.toString(), diff.maxAbsolute <= 1e-5)
                    Log.i(TAG, "PASS $diff")
                }
            }
        }
    }

    @Test fun cacheReusesKeysReleasesOnCloseAndKeepsInputOwnedByCaller() = gpu { egl, ledger ->
        upload(egl, ledger, 64, 48, FloatArray(64 * 48) { (it % 19) / 19f }, false).use { input ->
            val baseline = ledger.allocatedBytes
            val resample = GpuResample(egl, ledger)
            resample.use {
                assertSame(input, resample.resize(input, 64, 48, StageClearBlur.Filter.BOX))
                assertSame(input, resample.blurPlane(input, 0.0))
                resample.reduce(input, 8, 6).close()
                val cached = ledger.allocatedBytes; val textures = ledger.textureCount
                assertTrue(cached > baseline)
                assertEquals(5, textures) // input + two axes, each R32F weights / R32I first.
                repeat(3) { resample.reduce(input, 8, 6).close(); assertEquals(cached, ledger.allocatedBytes); assertEquals(textures, ledger.textureCount) }
                resample.resize(input, 8, 6, StageClearBlur.Filter.BILINEAR).close()
                assertEquals(textures + 4, ledger.textureCount) // filter is part of the key.
                // One-axis passes transfer their output to the caller, never return a closed intermediate.
                resample.resize(input, 64, 6, StageClearBlur.Filter.BOX).use { output -> output.requireOpen() }
                resample.resize(input, 8, 48, StageClearBlur.Filter.BOX).use { output -> output.requireOpen() }
                assertThrows(IllegalArgumentException::class.java) { resample.resize(input, 0, 6, StageClearBlur.Filter.BOX) }
                assertThrows(IllegalArgumentException::class.java) { resample.blurPlane(input, Double.NaN) }
            }
            resample.close()
            assertEquals(baseline, ledger.allocatedBytes); input.requireOpen()
            assertThrows(IllegalStateException::class.java) { resample.reduce(input, 8, 6) }
        }
    }

    @Test fun allocationFailureReleasesIntermediateAndPartiallyBuiltWeights() = gpu { egl, _ ->
        // Horizontal BOX weights/output fit, vertical weights' values fit, their first-index texture does not.
        val ledger = GpuMemoryLedger(64L * 48 * 4 + 288 + 8 * 48 * 4 + 200)
        upload(egl, ledger, 64, 48, FloatArray(64 * 48), false).use { input ->
            val baseline = ledger.allocatedBytes
            GpuResample(egl, ledger).use { resample ->
                assertThrows(GpuMemoryBudgetException::class.java) { resample.reduce(input, 8, 6) }
                assertEquals(baseline + 288, ledger.allocatedBytes) // only successful horizontal weights remain.
                assertEquals(3, ledger.textureCount)
                input.requireOpen()
            }
            assertEquals(baseline, ledger.allocatedBytes)
        }
        assertEquals(0L, ledger.allocatedBytes)
    }

    private companion object {
        const val TAG = "GpuColorResample"
        val RADII = doubleArrayOf(.006, .01, .012, .02, .025, .03, .06, .07)
        val COLOR_TEST = """#version 320 es
precision highp float;
precision highp int;
precision highp sampler2D;
#include "common/color.glsl"
uniform sampler2D s_input;
uniform int u_operation;
layout(location=0) out vec4 o_color;
void main() {
    vec3 v = texelFetch(s_input, ivec2(gl_FragCoord.xy), 0).rgb;
    vec3 value;
    if (u_operation == 0) value = cs_linearToLab(v);
    else if (u_operation == 1) value = cs_labToLinear(v);
    else if (u_operation == 2) value = cs_labToLinear(cs_linearToLab(v));
    else if (u_operation == 3) value = vec3(cs_linearToUnit(v.r), cs_linearToUnit(v.g), cs_linearToUnit(v.b));
    else if (u_operation == 4) value = vec3(cs_unitToLinear(v.r), cs_unitToLinear(v.g), cs_unitToLinear(v.b));
    else if (u_operation == 5) value = vec3(cs_encode(v.r), cs_encode(v.g), cs_encode(v.b));
    else if (u_operation == 6) value = vec3(cs_decode(v.r), cs_decode(v.g), cs_decode(v.b));
    else if (u_operation == 7) value = vec3(cs_hueDeg(v));
    else if (u_operation == 8) value = vec3(cs_band(cs_hueDeg(v), 40.0, 45.0));
    else if (u_operation == 10) value = vec3(cs_signedCbrt(v.r), cs_signedCbrt(v.g), cs_signedCbrt(v.b));
    else value = vec3(cs_skinWeight(v.r, length(v.gb), cs_hueDeg(v)));
    o_color = vec4(value, 1.0);
}
"""
    }
}
