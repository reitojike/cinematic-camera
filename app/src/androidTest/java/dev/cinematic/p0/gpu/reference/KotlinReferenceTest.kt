package dev.cinematic.p0.gpu.reference

import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.gpu.GpuRenderParams
import dev.cinematic.p0.gpu.diag.GpuStageTap.Point
import dev.cinematic.p0.raw.LinearSrgbImage
import dev.cinematic.p0.raw.PhotoAdjustments
import dev.cinematic.p0.raw.StageClearLook
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class KotlinReferenceTest {
    @Test fun completeSmallMatrixMatchesLookBitForBit() {
        val start = SystemClock.elapsedRealtime()
        val workers = minOf(2, Runtime.getRuntime().availableProcessors())
        val pool = Executors.newFixedThreadPool(workers)
        try {
            for (size in GpuReferenceImages.FAST_SIZES) {
                val tasks = GpuReferenceImages.Kind.entries.map { kind ->
                    pool.submit { compareFixture(kind, size, ADJUSTMENTS, STRENGTHS) }
                }
                for (task in tasks) {
                    try { task.get() } catch (failure: ExecutionException) { throw requireNotNull(failure.cause) }
                }
            }
        } finally {
            pool.shutdownNow()
            check(pool.awaitTermination(30, TimeUnit.SECONDS)) { "Reference workers did not stop" }
        }
        val elapsed = SystemClock.elapsedRealtime() - start
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("stream", "\nGpuReference small matrix: 320 cases, workers=$workers, elapsedMs=$elapsed (budget=120000)\n")
        })
        assertTrue("Small reference matrix took ${elapsed}ms (budget=120000ms)", elapsed <= 120_000)
    }

    @Test fun oddPreviewMatrixMatchesLookBitForBit() {
        compareMatrix(GpuReferenceImages.PREVIEW_SIZE, listOf(PhotoAdjustments.NONE), listOf(.5))
    }

    @Test fun preparedAnalysisDoesNotApplyWarmthOrAdjustmentEvToMetering() {
        val input = GpuReferenceImages.create(GpuReferenceImages.Kind.MIXED, GpuReferenceImages.FAST_SIZES.first())
        val source = KotlinReference.Source(input)
        val neutral = KotlinReference.prepare(source, PhotoAdjustments.NONE)
        val adjusted = KotlinReference.prepare(source, PhotoAdjustments(60.0, 1.0))
        assertSame(neutral.analysis, adjusted.analysis)
        assertSame(neutral.faces, adjusted.faces)
        assertTrue(adjusted.faces.copyWeights().all { it.toRawBits() == 0f.toRawBits() })
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

    @Test fun retainingOnlyShoulderMatchesAllStageCapture() {
        val size = GpuReferenceImages.FAST_SIZES.first()
        for (kind in GpuReferenceImages.Kind.entries) {
            val prepared = KotlinReference.prepare(GpuReferenceImages.create(kind, size), PhotoAdjustments.NONE)
            val full = KotlinReference.render(prepared, .5)
            val compact = KotlinReference.render(prepared, .5, retainIntermediates = false)
            val independent = KotlinReference.render(prepared.input, GpuRenderParams(.5, PhotoAdjustments.NONE))
            assertBitsEqual(full[Point.SHOULDER], independent[Point.SHOULDER], kind.name)
            assertEquals(setOf(Point.SHOULDER), compact.images.keys)
            assertBitsEqual(full[Point.SHOULDER], compact[Point.SHOULDER], kind.name)
            assertSame(full.skinWeight, compact.skinWeight)
            assertEquals(full.mistAutoStrength, compact.mistAutoStrength, 0.0)
            assertArrayEquals(full.display8, compact.display8)
        }
    }

    private fun compareMatrix(size: GpuReferenceImages.Size, adjustments: List<PhotoAdjustments>, strengths: List<Double>) {
        for (kind in GpuReferenceImages.Kind.entries) compareFixture(kind, size, adjustments, strengths)
    }

    private fun compareFixture(kind: GpuReferenceImages.Kind, size: GpuReferenceImages.Size,
        adjustments: List<PhotoAdjustments>, strengths: List<Double>) {
        val source = KotlinReference.Source(GpuReferenceImages.create(kind, size))
        for (adjustment in adjustments) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Reference matrix stopped")
            val prepared = KotlinReference.prepare(source, adjustment)
            for (strength in strengths) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException("Reference matrix stopped")
                val actual = KotlinReference.render(prepared, strength, retainIntermediates = false)
                val expected = StageClearLook.renderFromBase(prepared.r7Final, prepared.faces, strength)
                val label = "$kind $size strength=$strength adjustments=$adjustment SHOULDER"
                assertBitsEqual(expected.image, actual[Point.SHOULDER], label)
                assertEquals(label, 1.0, expected.faceCutFactor, 0.0)
                assertEquals(label, expected.decisions!!.mistAutoStrength, actual.mistAutoStrength, 0.0)
                assertEquals(label, actual.mistAutoStrength * strength, actual.lightAddingStrength, 0.0)
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
