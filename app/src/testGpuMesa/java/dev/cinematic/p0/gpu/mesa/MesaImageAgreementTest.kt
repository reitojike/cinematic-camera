package dev.cinematic.p0.gpu.mesa

import dev.cinematic.p0.gpu.reference.GpuReferenceImages
import dev.cinematic.p0.gpu.reference.GpuTolerances
import dev.cinematic.p0.gpu.reference.ImageDiff
import dev.cinematic.p0.raw.LinearSrgbImage
import dev.cinematic.p0.raw.StageClearColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.lwjgl.opengles.GLES32.glDeleteProgram
import kotlin.math.abs

@RunWith(Parameterized::class)
class MesaImageAgreementTest(private val width: Int, private val height: Int) {
    @Test fun rgba32fCopyPreservesEverySyntheticPixel() = MesaContext().use {
        val program = MesaShaders().compileContractCopy()
        try {
            for (kind in GpuReferenceImages.Kind.entries) {
                val image = GpuReferenceImages.create(kind, GpuReferenceImages.Size(width, height))
                val pixels = image.copyPixels()
                val actual = renderRgb(program, width, height, pixels)
                pixels.indices.forEach { i ->
                    assertEquals("copy $kind ${width}x$height channel=$i", pixels[i].toRawBits(), actual[i].toRawBits())
                }
                val diff = ImageDiff.display(image, LinearSrgbImage.takeOwnership(width, height, actual), "copy $kind ${width}x$height")
                diff.assertDisplayWithin()
                println("GPU_MESA_AGREEMENT PASS $diff")
            }
        } finally { glDeleteProgram(program) }
    }

    @Test fun realColorAssetMatchesUnchangedKotlin() = MesaContext().use {
        val program = MesaShaders().compileColorProbe()
        val failures = mutableListOf<String>()
        try {
            for (kind in GpuReferenceImages.Kind.entries) {
                val image = GpuReferenceImages.create(kind, GpuReferenceImages.Size(width, height))
                val pixels = image.copyPixels()
                val lab = FloatArray(pixels.size)
                val triple = FloatArray(3)
                repeat(image.pixelCount) { p ->
                    StageClearColor.toLab(image, p * 3, triple)
                    triple.copyInto(lab, p * 3)
                }
                val roundTrip = FloatArray(pixels.size)
                repeat(image.pixelCount) { p ->
                    lab.copyInto(triple, 0, p * 3, p * 3 + 3)
                    StageClearColor.toRgb(triple, roundTrip, p * 3)
                }
                for (operation in 0..2) {
                    val input = if (operation == 1) lab else pixels
                    val expected = if (operation == 0) lab else roundTrip
                    val actual = renderRgb(program, width, height, input, operation)
                    val label = "color op=$operation $kind ${width}x$height"
                    var failed = 0
                    var worst = 0
                    var ratio = -1.0
                    for (i in expected.indices) {
                        val limit = if (abs(expected[i]) <= 1) GpuTolerances.LAB_ABSOLUTE
                            else abs(expected[i]) * GpuTolerances.LAB_RELATIVE
                        val delta = if (actual[i].isFinite()) abs(expected[i].toDouble() - actual[i]) else Double.POSITIVE_INFINITY
                        if (delta > limit) failed++
                        if (delta / limit > ratio) { ratio = delta / limit; worst = i }
                    }
                    val report = "$label failedChannels=$failed worstPixel=${worst / 3} channel=${worst % 3} " +
                        "input=${input.copyOfRange(worst / 3 * 3, worst / 3 * 3 + 3).contentToString()} " +
                        "kotlin=${expected[worst]} gpu=${actual[worst]} toleranceRatio=$ratio"
                    println("GPU_MESA_AGREEMENT ${if (failed == 0) "PASS" else "FAIL"} $report")
                    if (failed != 0) failures += report
                    if (operation != 0 && actual.all { it.isFinite() }) {
                        val diff = ImageDiff.display(LinearSrgbImage(width, height, expected),
                            LinearSrgbImage.takeOwnership(width, height, actual), label)
                        println("GPU_MESA_DISPLAY $diff")
                        try { diff.assertDisplayWithin() } catch (failure: AssertionError) {
                            failures += checkNotNull(failure.message)
                        }
                    }
                }
            }
            assertTrue(failures.joinToString("\n"), failures.isEmpty())
        } finally { glDeleteProgram(program) }
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}x{1}")
        fun sizes(): List<Array<Int>> = (GpuReferenceImages.FAST_SIZES + GpuReferenceImages.EXPORT_SIZE)
            .map { arrayOf(it.width, it.height) }
    }
}
