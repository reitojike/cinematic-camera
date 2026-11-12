package dev.cinematic.p0.gpu.mesa

import dev.cinematic.p0.gpu.reference.GpuReferenceImages
import dev.cinematic.p0.gpu.reference.GpuTolerances
import dev.cinematic.p0.gpu.reference.ImageDiff
import dev.cinematic.p0.raw.LinearSrgbImage
import dev.cinematic.p0.raw.StageClearColor
import org.junit.Assert.assertEquals
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
                assertEquals(pixels.size, actual.size)
                pixels.indices.forEach { i ->
                    assertEquals("copy $kind ${width}x$height channel=$i", pixels[i].toRawBits(), actual[i].toRawBits())
                }
                val diff = ImageDiff.display(image, LinearSrgbImage.takeOwnership(width, height, actual), "copy $kind ${width}x$height")
                diff.assertDisplayWithin()
                println("GPU_MESA_AGREEMENT PASS $diff")
            }
        } finally { glDeleteProgram(program) }
    }

    @Test fun realColorAssetReportsUnchangedKotlinReference() = MesaContext().use {
        val program = MesaShaders().compileColorProbe()
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
                    println(ImageDiff.referenceFloats(expected, actual, label))
                    var outsideReference = 0
                    var worst = 0
                    var ratio = -1.0
                    for (i in expected.indices) {
                        val limit = if (abs(expected[i]) <= 1) GpuTolerances.LAB_ABSOLUTE
                            else abs(expected[i]) * GpuTolerances.LAB_RELATIVE
                        val delta = abs(expected[i].toDouble() - actual[i])
                        if (delta > limit) outsideReference++
                        if (delta / limit > ratio) { ratio = delta / limit; worst = i }
                    }
                    val report = "$label outsideHistoricalReference=$outsideReference worstPixel=${worst / 3} channel=${worst % 3} " +
                        "input=${input.copyOfRange(worst / 3 * 3, worst / 3 * 3 + 3).contentToString()} " +
                        "kotlin=${expected[worst]} gpu=${actual[worst]} toleranceRatio=$ratio"
                    println("CPU_REFERENCE_ONLY $report")
                    if (operation != 0) {
                        val diff = ImageDiff.display(LinearSrgbImage(width, height, expected),
                            LinearSrgbImage.takeOwnership(width, height, actual), label)
                        println("CPU_REFERENCE_ONLY GPU_MESA_DISPLAY $diff")
                    }
                }
            }
        } finally { glDeleteProgram(program) }
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0,number,0}x{1,number,0}")
        fun sizes(): List<Array<Int>> = (GpuReferenceImages.FAST_SIZES + GpuReferenceImages.EXPORT_SIZE)
            .map { arrayOf(it.width, it.height) }
    }
}
