package dev.cinematic.p0.raw.original

import dev.cinematic.p0.raw.*
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class OriginalZeroKeepTest {
    @Test fun candidateMatchesReferenceForBoundaryWeightsAndRgb() {
        val values = floatArrayOf(0f, -0f, Float.MIN_VALUE, -Float.MIN_VALUE, java.lang.Float.MIN_NORMAL, -java.lang.Float.MIN_NORMAL, -4f, 1f, 16f, Float.MAX_VALUE, -Float.MAX_VALUE)
        for (r in values) for (g in values) for (b in values) for (keep in floatArrayOf(0f, -0f, Float.MIN_VALUE, .5f, 1f)) for (afterValues in listOf(
            floatArrayOf(.1f,.2f,.3f), floatArrayOf(0f,-0f,0f), floatArrayOf(-0f,0f,-0f),
            floatArrayOf(-4f,16f,-1f), floatArrayOf(Float.MIN_VALUE,-Float.MIN_VALUE,0f),
            floatArrayOf(Float.MAX_VALUE,-Float.MAX_VALUE,Float.MAX_VALUE))) {
            val before = LinearSrgbImage(1, 1, floatArrayOf(r, g, b))
            val after = LinearSrgbImage(1, 1, afterValues)
            val a = FloatArray(3); val z = FloatArray(3)
            StageClearColor.toLab(before, 0, a); StageClearColor.toLab(after, 0, z)
            val scale = (1f - keep) + keep * min(1f, hypot(a[1], a[2]) / max(hypot(z[1], z[2]), StageClearOrtonV1.CHROMA_FLOOR.toFloat()))
            z[1] *= scale; z[2] *= scale
            val expected = FloatArray(3); StageClearColor.toRgb(z, expected, 0)
            val actual = FloatArray(3)
            OrtonZeroKeepCandidate.pixel(before, after, 0, keep, FloatArray(3), FloatArray(3), actual)
            assertArrayEquals(expected.map { it.toRawBits() }.toIntArray(), actual.map { it.toRawBits() }.toIntArray())
        }
    }

    @Test fun nonFiniteInputIsRejectedBeforeCandidate() {
        for (value in floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { LinearSrgbImage(1, 1, floatArrayOf(value, 0f, 0f)) }
        }
    }

    private class Input(override val width: Int, override val height: Int) : OriginalRaster {
        override fun rows(stop: () -> Boolean, consume: (Int, FloatArray) -> Unit) {
            repeat(height) { y -> originalCheckpoint(stop); consume(y, FloatArray(width * 3) { i -> ((i * 37 + y * 19) % 4096) / 2048f - .05f }) }
        }
    }
    private fun bits(r: OriginalRaster): IntArray = IntArray(r.width * r.height * 3).also { out ->
        r.rows({ false }) { y, row -> row.forEachIndexed { i, v -> out[y * row.size + i] = v.toRawBits() } }
    }
    @Test fun fullPipelinePreservesAllStagesAndFinalBits() {
        for (shape in listOf(48 to 32, 1 to 17, 17 to 1)) for (strength in listOf(0.0, .5, 1.0)) for (workers in listOf(1, 2, 4)) {
            var expected: Map<String, IntArray>? = null
            for (enabled in listOf(false, true)) {
                val root = Files.createTempDirectory("zero-keep-pipeline").toFile()
                val work = OriginalRasterWorkspace(root.resolve("attempt"))
                try {
                    val stages = linkedMapOf<String, IntArray>()
                    val result = OriginalStageClear24(work, parallelOrtonWorkers = workers, skipZeroKeepOrton = enabled)
                        .render(Input(shape.first, shape.second), .23, strength, stage = { n, r -> stages[n] = bits(r) })
                    stages["final"] = bits(result.raster)
                    val baseline = expected
                    if (baseline == null) expected = stages else {
                        assertEquals(baseline.keys, stages.keys)
                        stages.forEach { (n, actual) -> assertArrayEquals("$shape/$strength/$workers/$n", baseline.getValue(n), actual) }
                    }
                    assertFalse(work.hasPendingOwners)
                } finally { work.closePending(); work.disposeConfirmed(); check(root.delete()) }
            }
        }
    }
    @Test fun cancellationAndInjectedFailureRetainOwnerCleanup() {
        for (failure in listOf(false, true)) for (workers in listOf(1, 4)) {
            val root = Files.createTempDirectory("zero-keep-stop").toFile()
            val work = OriginalRasterWorkspace(root.resolve("attempt"))
            val cancelled = java.util.concurrent.atomic.AtomicBoolean()
            try {
                val expected = if (failure) java.io.IOException::class.java else java.util.concurrent.CancellationException::class.java
                assertThrows(expected) {
                    OriginalStageClear24(work, parallelOrtonWorkers = workers, skipZeroKeepOrton = true,
                        ortonRowProbe = { if (it == 1) { if (failure) throw java.io.IOException("synthetic") else cancelled.set(true) } })
                        .render(Input(48, 32), .23, .5, stop = { cancelled.get() })
                }
            } finally { work.closePending(); assertFalse(work.hasPendingOwners); work.disposeConfirmed(); check(root.delete()) }
        }
    }
}
