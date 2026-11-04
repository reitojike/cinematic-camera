package dev.cinematic.p0.raw

import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

class StageClearOrtonTest {
    @Test fun standaloneAndComposedStageThreeMatchEveryPcPixel() {
        val lines = java.util.zip.GZIPInputStream(requireNotNull(
            javaClass.getResourceAsStream("/stage_clear_orton_v1.csv.gz")))
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
            val amount = header[5].toDouble()
            val count = width * height
            val basePixels = FloatArray(count * 3)
            val skinPixels = FloatArray(count)
            val mistedPixels = FloatArray(count * 3)
            val orton = FloatArray(count * 3)
            val output = FloatArray(count * 3)
            repeat(count) { p ->
                val row = lines[next++].split(',')
                assertEquals("PIX", row[0])
                assertEquals(14, row.size)
                repeat(3) { c ->
                    basePixels[p * 3 + c] = row[1 + c].toFloat()
                    mistedPixels[p * 3 + c] = row[5 + c].toFloat()
                    orton[p * 3 + c] = row[8 + c].toFloat()
                    output[p * 3 + c] = row[11 + c].toFloat()
                }
                skinPixels[p] = row[4].toFloat()
            }
            val misted = LinearSrgbImage(width, height, mistedPixels)
            val skin = FloatPlane(width, height, skinPixels)
            maximumError = maxOf(maximumError, compare("$name orton", orton,
                StageClearOrton.orton(misted, skin).copyPixels()))
            val result = StageClearOrton.render(StageClearMist.Result(misted, amount), skin)
            maximumError = maxOf(maximumError, compare("$name isolated", output, result.copyPixels()))
            val base = LinearSrgbImage(width, height, basePixels)
            val baseSkin = StageClearGlow.skinWeight(base)
            val composed = StageClearOrton.render(StageClearMist.render(StageClearGlow.render(base, strength),
                baseSkin, strength), baseSkin)
            maximumError = maxOf(maximumError, compare("$name stages1-3", output, composed.copyPixels()))
            assertArrayEquals(basePixels, base.copyPixels(), 0f)
            assertArrayEquals(mistedPixels, misted.copyPixels(), 0f)
            assertArrayEquals(skinPixels, skin.copyValues(), 0f)
            cases++
            pixels += count
        }
        assertEquals(33, cases)
        assertEquals(12540, pixels)
        println("PC stage-3 cases=$cases; pixels=$pixels; maximum absolute error=$maximumError")
    }

    @Test fun displayTransferClipsAndHighlightFadeUsesEncodedLuminance() {
        assertEquals(0f, StageClearColor.linearToUnit(-.2f), 0f)
        assertEquals(1f, StageClearColor.linearToUnit(1.4f), 2e-7f)
        assertEquals(0f, StageClearColor.unitToLinear(-.2f), 0f)
        assertEquals(1f, StageClearColor.unitToLinear(1.4f), 2e-7f)
        assertEquals(.21404114f, StageClearColor.unitToLinear(.5f), 1e-7f)
        for (v in listOf(0f, .0031308f, .003131f, .1f, .5f, 1f)) {
            assertEquals(v, StageClearColor.unitToLinear(StageClearColor.linearToUnit(v)), 3e-7f)
        }
        assertEquals(1f, StageClearOrton.highlightFade(.75f), 0f)
        assertEquals(.5f, StageClearOrton.highlightFade(.85f), 2e-7f)
        assertEquals(0f, StageClearOrton.highlightFade(.95f), 2e-7f)
        // Linear .9 is already above the encoded fade ceiling, unlike the stage-2 .9 test.
        assertEquals(0f, StageClearOrton.highlightFade(StageClearColor.linearToUnit(.9f)), 0f)
    }

    @Test fun pairedAmountIsReusedZeroSkipsAndInputsStayUnchanged() {
        val pixels = floatArrayOf(.4f, .2f, .08f)
        val image = LinearSrgbImage(1, 1, pixels)
        val weights = floatArrayOf(.5f)
        val skin = FloatPlane(1, 1, weights)
        val full = StageClearOrton.orton(image, skin)
        val result = StageClearOrton.render(StageClearMist.Result(image, .25), skin)
        repeat(3) { assertEquals(image[it] + .25f * (full[it] - image[it]), result[it], 0f) }
        assertSame(image, StageClearOrton.render(StageClearMist.Result(image, 0.0), skin))
        assertArrayEquals(pixels, image.copyPixels(), 0f)
        assertArrayEquals(weights, skin.copyValues(), 0f)
    }

    @Test fun skinPreventsChromaGainAndHdrRestorationPrecedesSkinProtection() {
        val image = LinearSrgbImage(1, 1, floatArrayOf(.62f, .3f, .22f))
        val protected = StageClearOrton.orton(image, FloatPlane(1, 1, floatArrayOf(1f)))
        val before = FloatArray(3)
        val after = FloatArray(3)
        StageClearColor.toLab(image, 0, before)
        StageClearColor.toLab(protected, 0, after)
        assertTrue(kotlin.math.hypot(after[1], after[2]) <= kotlin.math.hypot(before[1], before[2]) + 2e-6f)
        val hdr = LinearSrgbImage(1, 1, floatArrayOf(1.4f, 1.4f, 1.4f))
        val result = StageClearOrton.orton(hdr, FloatPlane(1, 1, floatArrayOf(0f)))
        assertArrayEquals(hdr.copyPixels(), result.copyPixels(), 2e-6f)
        // PC h.orton([1.4, .02, .03], skin=1): skin protection can reduce the restored red.
        // Restoring HDR after skin protection would incorrectly force red back to 1.4.
        val colouredHdr = LinearSrgbImage(1, 1, floatArrayOf(1.4f, .02f, .03f))
        assertArrayEquals(floatArrayOf(1.3847569f, .018357314f, .02430199f),
            StageClearOrton.orton(colouredHdr, FloatPlane(1, 1, floatArrayOf(1f))).copyPixels(), 2e-6f)
        val negative = LinearSrgbImage(1, 1, floatArrayOf(-.2f, -.2f, -.2f))
        assertArrayEquals(FloatArray(3), StageClearOrton.orton(negative,
            FloatPlane(1, 1, floatArrayOf(0f))).copyPixels(), 0f)
    }

    @Test fun invalidPairedAmountAndOriginalSkinAreRejectedEvenAtZero() {
        val image = LinearSrgbImage(1, 1, floatArrayOf(.4f, .2f, .08f))
        val skin = FloatPlane(1, 1, floatArrayOf(.5f))
        for (bad in listOf(-.01, 1.01, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) {
                StageClearOrton.render(StageClearMist.Result(image, bad), skin)
            }
        }
        for (bad in listOf(-.01f, 1.01f)) {
            assertThrows(IllegalArgumentException::class.java) {
                StageClearOrton.render(StageClearMist.Result(image, 0.0), FloatPlane(1, 1, floatArrayOf(bad)))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            StageClearOrton.render(StageClearMist.Result(image, 0.0), FloatPlane(2, 1, FloatArray(2)))
        }
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
