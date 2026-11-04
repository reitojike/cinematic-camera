package dev.cinematic.p0.raw

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow

class StageClearProtectionsTest {
    @Test fun allSyntheticPixelsMatchPcStages() {
        val lines = java.util.zip.GZIPInputStream(requireNotNull(
            javaClass.getResourceAsStream("/stage_clear_protections_v1.csv.gz")))
            .bufferedReader().use { it.readLines() }.filterNot { it.startsWith("#") }
        var position = 0
        var caseCount = 0
        while (position < lines.size) {
            val header = lines[position++].split(',')
            assertEquals("CASE", header[0])
            val name = header[1]
            val width = header[2].toInt()
            val height = header[3].toInt()
            val factor = header[4].toDouble()
            val pixels = width * height
            val rgb = FloatArray(pixels * 3)
            val mask = FloatArray(pixels)
            val cap = FloatArray(pixels * 3)
            val shoulder = FloatArray(pixels * 3)
            val finished = FloatArray(pixels * 3)
            repeat(pixels) { p ->
                val row = lines[position++].split(',')
                assertEquals("PIX", row[0])
                assertEquals(14, row.size)
                repeat(3) { c ->
                    rgb[p * 3 + c] = row[1 + c].toFloat()
                    cap[p * 3 + c] = row[5 + c].toFloat()
                    shoulder[p * 3 + c] = row[8 + c].toFloat()
                    finished[p * 3 + c] = row[11 + c].toFloat()
                }
                mask[p] = row[4].toFloat()
            }
            val source = LinearSrgbImage(width, height, rgb)
            val faces = FaceMask(width, height, mask)
            compare("$name cap", cap, StageClearProtections.skinChromaCap(source, faces).copyPixels())
            compare("$name shoulder", shoulder, StageClearProtections.shoulder(source).copyPixels())
            var calls = 0
            val result = StageClearProtections.finish(source, faces) { input ->
                calls++
                val out = input.copyPixels()
                for (i in out.indices) out[i] += .06f * out[i] * out[i]
                LinearSrgbImage(width, height, out)
            }
            compare("$name finish", finished, result.image.copyPixels())
            assertEquals("$name face cut", factor, result.faceCutFactor, 2e-6)
            assertEquals("$name rerender count", if (factor < 1) 2 else 1, calls)
            assertArrayEquals("$name source unchanged", rgb, source.copyPixels(), 0f)
            assertArrayEquals("$name mask unchanged", mask, faces.copyWeights(), 0f)
            caseCount++
        }
        assertEquals(9, caseCount)
    }

    @Test fun contractsRejectInvalidDataAndDoNotExposeMutableStorage() {
        fun rejects(block: () -> Unit) { assertThrows(IllegalArgumentException::class.java, block) }
        rejects { LinearSrgbImage(0, 1, floatArrayOf()) }
        rejects { LinearSrgbImage(Int.MAX_VALUE, 2, floatArrayOf()) }
        rejects { LinearSrgbImage(1, 1, floatArrayOf(1f)) }
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            rejects { LinearSrgbImage(1, 1, floatArrayOf(value, 0f, 0f)) }
            rejects { FaceMask(1, 1, floatArrayOf(value)) }
        }
        for (value in listOf(-.01f, 1.01f)) rejects { FaceMask(1, 1, floatArrayOf(value)) }
        rejects { FaceMask(1, 2, floatArrayOf(0f)) }
        val pixels = floatArrayOf(-.2f, 1.4f, .3f)
        val image = LinearSrgbImage(1, 1, pixels)
        val weights = floatArrayOf(.8f)
        val mask = FaceMask(1, 1, weights)
        pixels[0] = 999f
        weights[0] = 0f
        image.copyPixels()[0] = 123f
        mask.copyWeights()[0] = 0f
        assertEquals(-.2f, image.copyPixels()[0])
        assertEquals(.8f, mask.copyWeights()[0])
        rejects { StageClearProtections.skinChromaCap(image, FaceMask(2, 1, floatArrayOf(0f, 0f))) }
        rejects { StageClearProtections.faceCut(image, FaceMask(2, 1, floatArrayOf(0f, 0f))) }
        rejects {
            StageClearProtections.finish(image, mask) { LinearSrgbImage(2, 1, FloatArray(6)) }
        }
        assertEquals("stage-clear-v1", StageClearV1.VERSION)
        assertEquals(.5, StageClearV1.DEFAULT_STRENGTH, 0.0)
    }

    @Test fun faceCutUsesStrictHalfThresholdAndInterpolatedNinetiethPercentile() {
        val pixels = FloatArray(50 * 3) { i -> if (i / 3 < 45) .8f else 1.2f }
        val image = LinearSrgbImage(10, 5, pixels)
        val face = FaceMask(10, 5, FloatArray(50) { 1f })
        // NumPy linear interpolation: rank=44.1, between encoded .8 and encoded 1.2.
        val top = .9 * StageClearProtections.encode(.8) + .1 * StageClearProtections.encode(1.2)
        val expected = StageClearProtections.decode(.9) / StageClearProtections.decode(top)
        assertEquals(expected, StageClearProtections.faceCut(image, face), 2e-6)
        assertEquals(1.0, StageClearProtections.faceCut(image, FaceMask(10, 5, FloatArray(50) { .5f })), 0.0)
    }

    @Test fun v1PoolsAllFacesAndDoesNotPromiseAFinalBrightnessCeiling() {
        val faces = FaceMask(10, 10, FloatArray(100) { 1f })
        // Nine bright pixels (one small face), 91 dim pixels (other faces): pooled p90 misses it.
        val pooled = LinearSrgbImage(10, 10, FloatArray(300) { if (it / 3 < 9) 3f else .5f })
        assertEquals(1.0, StageClearProtections.faceCut(pooled, faces), 0.0)
        val bright = LinearSrgbImage(10, 10, FloatArray(300) { 3f })
        val result = StageClearProtections.finish(bright, faces) { it }
        assertEquals(2.0.pow(-.7), result.faceCutFactor, 2e-6)
        val pixels = result.image.copyPixels()
        val luma = pixels[0] * .2126 + pixels[1] * .7152 + pixels[2] * .0722
        assertTrue(StageClearProtections.encode(luma) > .9)
    }

    private fun compare(label: String, expected: FloatArray, actual: FloatArray) {
        assertEquals(label, expected.size, actual.size)
        for (i in expected.indices) {
            assertTrue("$label channel $i expected ${expected[i]} got ${actual[i]}",
                actual[i].isFinite() && abs(expected[i] - actual[i]) <= 2e-5f)
        }
        println("$label max absolute error: ${expected.indices.maxOf { abs(expected[it] - actual[it]) }}")
    }
}
