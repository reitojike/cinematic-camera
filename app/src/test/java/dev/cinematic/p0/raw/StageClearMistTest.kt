package dev.cinematic.p0.raw

import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

class StageClearMistTest {
    @Test fun standaloneAndComposedStageTwoMatchEveryPcPixel() {
        val lines = requireNotNull(javaClass.getResourceAsStream("/stage_clear_mist_v1.csv"))
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
            val strength = header[4].toDouble()
            val fraction = header[5].toDouble()
            val auto = header[6].toDouble()
            val amount = header[7].toDouble()
            val count = width * height
            val basePixels = FloatArray(count * 3)
            val skinPixels = FloatArray(count)
            val glowedPixels = FloatArray(count * 3)
            val mist = FloatArray(count * 3)
            val output = FloatArray(count * 3)
            repeat(count) { p ->
                val row = lines[next++].split(',')
                assertEquals("PIX", row[0])
                assertEquals(14, row.size)
                repeat(3) { c ->
                    basePixels[p * 3 + c] = row[1 + c].toFloat()
                    glowedPixels[p * 3 + c] = row[5 + c].toFloat()
                    mist[p * 3 + c] = row[8 + c].toFloat()
                    output[p * 3 + c] = row[11 + c].toFloat()
                }
                skinPixels[p] = row[4].toFloat()
            }
            val glowed = LinearSrgbImage(width, height, glowedPixels)
            val skin = FloatPlane(width, height, skinPixels)
            assertEquals("$name fraction", fraction, StageClearMist.nearWhiteFraction(glowed), 1e-12)
            assertEquals("$name auto", auto, StageClearMist.autoStrength(glowed), 1e-12)
            maximumError = maxOf(maximumError, compare("$name mist", mist,
                StageClearMist.blackMist(glowed, skin).copyPixels()))
            val result = StageClearMist.render(glowed, skin, strength)
            assertEquals("$name amount", amount, result.lightAddingStrength, 1e-12)
            maximumError = maxOf(maximumError, compare("$name isolated", output, result.image.copyPixels()))
            val base = LinearSrgbImage(width, height, basePixels)
            val composed = StageClearMist.render(StageClearGlow.render(base, strength),
                StageClearGlow.skinWeight(base), strength)
            maximumError = maxOf(maximumError, compare("$name stages1-2", output, composed.image.copyPixels()))
            assertEquals("$name composed amount", amount, composed.lightAddingStrength, 1e-12)
            assertArrayEquals(basePixels, base.copyPixels(), 0f)
            assertArrayEquals(glowedPixels, glowed.copyPixels(), 0f)
            assertArrayEquals(skinPixels, skin.copyValues(), 0f)
            cases++
            pixels += count
        }
        assertEquals(33, cases)
        println("PC stage-2 cases=$cases; pixels=$pixels; maximum absolute error=$maximumError")
    }

    @Test fun autoUsesStrictLinearThresholdAndOneToThreePointFivePercent() {
        fun image(bright: Int, value: Float = 1.2f): LinearSrgbImage =
            LinearSrgbImage(20, 10, FloatArray(600) { if (it / 3 < bright) value else .2f })
        for ((count, expected) in listOf(0 to 1.0, 2 to 1.0, 4 to .8, 7 to .5, 8 to .5)) {
            assertEquals(expected, StageClearMist.autoStrength(image(count)), 1e-12)
        }
        assertEquals(1.0, StageClearMist.autoStrength(image(200, .9f)), 0.0)
        assertEquals(.5, StageClearMist.autoStrength(image(200, Math.nextUp(.9f))), 0.0)
        // Encoded sRGB brightness is >.9 here, but v1 compares LINEAR .81 against .9.
        assertEquals(1.0, StageClearMist.autoStrength(image(200, .81f)), 0.0)
    }

    @Test fun skinProtectsHalfTheAddedMistAndZeroPreservesPixels() {
        val bright = LinearSrgbImage(10, 10, FloatArray(300) { 1f })
        val noSkin = FloatPlane(10, 10, FloatArray(100))
        val skin = FloatPlane(10, 10, FloatArray(100) { 1f })
        val plain = StageClearMist.render(bright, noSkin, 1.0).image.copyPixels()
        val protected = StageClearMist.render(bright, skin, 1.0).image.copyPixels()
        repeat(300) { assertEquals((plain[it] - 1f) * .5f, protected[it] - 1f, 2e-7f) }
        assertArrayEquals(bright.copyPixels(), StageClearMist.render(bright, skin, 0.0).image.copyPixels(), 0f)
        val dark = LinearSrgbImage(10, 10, FloatArray(300) { .1f })
        assertArrayEquals(dark.copyPixels(), StageClearMist.render(dark, noSkin, 1.0).image.copyPixels(), 0f)
    }

    @Test fun invalidInputsAreRejectedAndDefaultIsHalf() {
        val image = LinearSrgbImage(1, 1, floatArrayOf(-.2f, .08f, 1.4f))
        val skin = FloatPlane(1, 1, floatArrayOf(.5f))
        for (bad in listOf(-.01, 1.01, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { StageClearMist.render(image, skin, bad) }
        }
        for (bad in listOf(-.01f, 1.01f)) {
            assertThrows(IllegalArgumentException::class.java) { StageClearMist.render(image, FloatPlane(1, 1, floatArrayOf(bad))) }
        }
        assertThrows(IllegalArgumentException::class.java) { StageClearMist.render(image, FloatPlane(2, 1, FloatArray(2))) }
        assertArrayEquals(StageClearMist.render(image, skin, .5).image.copyPixels(),
            StageClearMist.render(image, skin).image.copyPixels(), 0f)
        assertEquals(.5, StageClearV1.DEFAULT_STRENGTH, 0.0)
    }

    private fun compare(label: String, expected: FloatArray, actual: FloatArray): Float {
        assertEquals(label, expected.size, actual.size)
        var maximum = 0f
        for (i in expected.indices) {
            val error = abs(expected[i] - actual[i])
            assertTrue("$label channel $i: expected ${expected[i]}, got ${actual[i]}",
                actual[i].isFinite() && error <= 2e-5f)
            maximum = maxOf(maximum, error)
        }
        return maximum
    }
}
