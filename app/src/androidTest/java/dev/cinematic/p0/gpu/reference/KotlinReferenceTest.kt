package dev.cinematic.p0.gpu.reference

import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.gpu.GpuRenderParams
import dev.cinematic.p0.gpu.diag.GpuStageTap.Point
import dev.cinematic.p0.raw.FaceMask
import dev.cinematic.p0.raw.LinearSrgbImage
import dev.cinematic.p0.raw.PhotoAdjustments
import dev.cinematic.p0.raw.StageClearLook
import org.junit.Assert.*
import org.junit.Test

class KotlinReferenceTest {
    @Test fun completeSmallMatrixMatchesLookBitForBit() {
        val start = SystemClock.elapsedRealtime()
        for (size in GpuReferenceImages.FAST_SIZES) compareMatrix(size, ADJUSTMENTS, STRENGTHS)
        val elapsed = SystemClock.elapsedRealtime() - start
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("stream", "\nGpuReference small matrix: 320 cases, elapsedMs=$elapsed (budget=120000)\n")
        })
        assertTrue("Small reference matrix took ${elapsed}ms (budget=120000ms)", elapsed <= 120_000)
    }

    @Test fun oddPreviewMatrixMatchesLookBitForBit() {
        compareMatrix(GpuReferenceImages.PREVIEW_SIZE, listOf(PhotoAdjustments.NONE), listOf(.5))
    }

    @Test fun preparedAnalysisDoesNotApplyWarmthOrAdjustmentEvToMetering() {
        val input = GpuReferenceImages.create(GpuReferenceImages.Kind.MIXED, GpuReferenceImages.FAST_SIZES.first())
        val neutral = KotlinReference.prepare(input, PhotoAdjustments.NONE)
        val adjusted = KotlinReference.prepare(input, PhotoAdjustments(60.0, 1.0))
        assertEquals(neutral.analysis.exposure, adjusted.analysis.exposure)
        assertEquals(neutral.externalBaseEv + 1, adjusted.externalBaseEv, 0.0)
        assertEquals(0, adjusted.analysis.explicitEllipseCount)
        assertTrue(adjusted.analysis.foundationSkinRoi.copyValues().all { it.toRawBits() == 0f.toRawBits() })
        val result = KotlinReference.render(input, GpuRenderParams(0.0, PhotoAdjustments.NONE))
        assertEquals(Point.entries.toSet(), result.images.keys + setOf(Point.SKIN_WEIGHT, Point.DISPLAY8))
        assertSame(result[Point.R7_FINAL], result.prepared.r7Final)
        assertEquals(0.0, result.lightAddingStrength, 0.0)
        assertArrayEquals(ImageDiff.encodeDisplay(result[Point.SHOULDER]), result.display8)
    }

    private fun compareMatrix(size: GpuReferenceImages.Size, adjustments: List<PhotoAdjustments>, strengths: List<Double>) {
        for (kind in GpuReferenceImages.Kind.entries) {
            val input = GpuReferenceImages.create(kind, size)
            for (adjustment in adjustments) {
                val prepared = KotlinReference.prepare(input, adjustment)
                val faces = FaceMask(size.width, size.height, FloatArray(input.pixelCount))
                for (strength in strengths) {
                    val actual = KotlinReference.render(prepared, strength)
                    val expected = StageClearLook.renderFromBase(prepared.r7Final, faces, strength)
                    val label = "$kind $size strength=$strength adjustments=$adjustment SHOULDER"
                    assertBitsEqual(expected.image, actual[Point.SHOULDER], label)
                    assertEquals(label, 1.0, expected.faceCutFactor, 0.0)
                    assertEquals(label, expected.decisions!!.mistAutoStrength, actual.mistAutoStrength, 0.0)
                    assertEquals(label, actual.mistAutoStrength * strength, actual.lightAddingStrength, 0.0)
                }
            }
        }
    }

    private fun assertBitsEqual(expected: LinearSrgbImage, actual: LinearSrgbImage, label: String) {
        assertEquals(label, expected.width, actual.width)
        assertEquals(label, expected.height, actual.height)
        repeat(expected.pixelCount * 3) { i ->
            if (expected[i].toRawBits() != actual[i].toRawBits()) throw AssertionError(
                "$label: x=${i / 3 % expected.width}, y=${i / 3 / expected.width}, channel=${i % 3}, " +
                    "ref=${expected[i]}, actual=${actual[i]}",
            )
        }
    }

    companion object {
        private val STRENGTHS = listOf(0.0, .25, .5, 1.0)
        private val ADJUSTMENTS = listOf(PhotoAdjustments.NONE, PhotoAdjustments(60.0, 0.0),
            PhotoAdjustments(0.0, 1.0), PhotoAdjustments(-60.0, -1.0))
    }
}
