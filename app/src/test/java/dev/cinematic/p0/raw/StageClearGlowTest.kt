package dev.cinematic.p0.raw

import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

class StageClearGlowTest {
    @Test fun skinWeightsAndStageOneMatchEveryPcPixel() {
        val lines = java.util.zip.GZIPInputStream(requireNotNull(
            javaClass.getResourceAsStream("/stage_clear_glow_v1.csv.gz")))
            .bufferedReader().use { it.readLines() }.filterNot { it.startsWith("#") }
        var next = 0
        var cases = 0
        var pixels = 0
        var maximumError = 0f
        while (next < lines.size) {
            val header = lines[next++].split(',')
            assertEquals("CASE", header[0])
            val name = header[1]
            val width = header[2].toInt()
            val height = header[3].toInt()
            val count = width * height
            val input = FloatArray(count * 3)
            val rawSkin = FloatArray(count)
            val skin = FloatArray(count)
            val half = FloatArray(count * 3)
            val one = FloatArray(count * 3)
            repeat(count) { p ->
                val row = lines[next++].split(',')
                assertEquals("PIX", row[0])
                assertEquals(12, row.size)
                repeat(3) { c ->
                    input[p * 3 + c] = row[1 + c].toFloat()
                    half[p * 3 + c] = row[6 + c].toFloat()
                    one[p * 3 + c] = row[9 + c].toFloat()
                }
                rawSkin[p] = row[4].toFloat()
                skin[p] = row[5].toFloat()
            }
            val base = LinearSrgbImage(width, height, input)
            maximumError = maxOf(maximumError, compare("$name raw skin", rawSkin,
                StageClearColor.skinWeight(base).copyValues()))
            maximumError = maxOf(maximumError, compare("$name blurred skin", skin,
                StageClearGlow.skinWeight(base).copyValues()))
            maximumError = maxOf(maximumError, compare("$name half", half,
                StageClearGlow.render(base, .5).copyPixels()))
            maximumError = maxOf(maximumError, compare("$name one", one,
                StageClearGlow.render(base, 1.0).copyPixels()))
            assertArrayEquals(input, StageClearGlow.render(base, 0.0).copyPixels(), 0f)
            assertArrayEquals(StageClearGlow.render(base, .5).copyPixels(),
                StageClearGlow.render(base).copyPixels(), 0f)
            assertArrayEquals("$name unchanged base", input, base.copyPixels(), 0f)
            cases++
            pixels += count
        }
        assertEquals(9, cases)
        println("PC stage-1 cases=$cases; pixels=$pixels; maximum absolute error=$maximumError")
    }

    @Test fun blackStaysBlackAndDarkFlatGetsOnlyThePcDiffusionLift() {
        val black = LinearSrgbImage(9, 7, FloatArray(9 * 7 * 3))
        assertArrayEquals(black.copyPixels(), StageClearGlow.render(black, 1.0).copyPixels(), 0f)
        val dark = LinearSrgbImage(9, 7, FloatArray(9 * 7 * 3) { .01f })
        val expected = .01f + .08f * (.01f - .01f * .01f)
        for (v in StageClearGlow.render(dark, 1.0).copyPixels()) assertEquals(expected, v, 2e-7f)
    }

    @Test fun haloIsWarmAndSpreadsMoreAtHigherStrength() {
        val pixels = FloatArray(191 * 7 * 3)
        repeat(3) { pixels[(3 * 191 + 95) * 3 + it] = 4f }
        val base = LinearSrgbImage(191, 7, pixels)
        val half = StageClearGlow.render(base, .5).copyPixels()
        val full = StageClearGlow.render(base, 1.0).copyPixels()
        val i = (3 * 191 + 97) * 3
        assertTrue(full[i] > full[i + 1] && full[i + 1] > full[i + 2])
        assertTrue(full[i] > half[i] && half[i] > pixels[i])
    }

    @Test fun invalidStrengthIsRejectedAndDefaultRemainsHalf() {
        val base = LinearSrgbImage(1, 1, floatArrayOf(-.2f, 1.4f, .3f))
        for (bad in listOf(-.01, 1.01, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { StageClearGlow.render(base, bad) }
        }
        assertEquals(.5, StageClearV1.DEFAULT_STRENGTH, 0.0)
        assertEquals("stage-clear-v1", StageClearV1.VERSION)
    }

    private fun compare(label: String, expected: FloatArray, actual: FloatArray): Float {
        assertEquals(label, expected.size, actual.size)
        var error = 0f
        for (i in expected.indices) {
            val delta = abs(expected[i] - actual[i])
            assertTrue("$label value $i: expected ${expected[i]}, got ${actual[i]}",
                actual[i].isFinite() && delta <= 2e-5f)
            error = maxOf(error, delta)
        }
        return error
    }
}
