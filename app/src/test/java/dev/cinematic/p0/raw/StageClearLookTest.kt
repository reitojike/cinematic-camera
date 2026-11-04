package dev.cinematic.p0.raw

import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

class StageClearLookTest {
    private var largestToleranceRatio = 0f
    private var maximumFullError = 0f
    private val diagnostics = mutableMapOf<String, Pair<Float, String>>()

    @Test fun stagesFourFiveAndCompleteLookMatchEveryPcPixel() {
        val lines = java.util.zip.GZIPInputStream(requireNotNull(
            javaClass.getResourceAsStream("/stage_clear_character_v1.csv.gz")))
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
            val factor = header[5].toDouble()
            val count = width * height
            val basePixels = FloatArray(count * 3)
            val facePixels = FloatArray(count)
            val stage3Pixels = FloatArray(count * 3)
            val vignette = FloatArray(count * 3)
            val character = FloatArray(count * 3)
            val full = FloatArray(count * 3)
            repeat(count) { p ->
                val row = lines[next++].split(',')
                assertEquals("PIX", row[0])
                assertEquals(17, row.size)
                repeat(3) { c ->
                    basePixels[p * 3 + c] = row[1 + c].toFloat()
                    stage3Pixels[p * 3 + c] = row[5 + c].toFloat()
                    vignette[p * 3 + c] = row[8 + c].toFloat()
                    character[p * 3 + c] = row[11 + c].toFloat()
                    full[p * 3 + c] = row[14 + c].toFloat()
                }
                facePixels[p] = row[4].toFloat()
            }
            val stage3 = LinearSrgbImage(width, height, stage3Pixels)
            maximumError = maxOf(maximumError, compare("$name vignette", vignette,
                StageClearCharacter.vignette(stage3, .35 * strength).copyPixels(), 2e-6f))
            val vignetted = LinearSrgbImage(width, height, vignette)
            maximumError = maxOf(maximumError, compare("$name vibrance", character,
                StageClearCharacter.vibrance(vignetted, .35 * strength).copyPixels(), 2e-6f))
            maximumError = maxOf(maximumError, compare("$name stages4-5", character,
                StageClearCharacter.render(stage3, strength).copyPixels(), 2e-6f))
            val base = LinearSrgbImage(width, height, basePixels)
            val originalSkin = StageClearGlow.skinWeight(base)
            val composedStage3 = StageClearOrton.render(StageClearMist.render(StageClearGlow.render(base, strength),
                originalSkin, strength), originalSkin)
            observe("stages1-3", "$name/$strength", stage3Pixels, composedStage3.copyPixels())
            maximumError = maxOf(maximumError, compare("$name/$strength stages1-5", character,
                StageClearCharacter.render(composedStage3, strength).copyPixels(), 2e-5f))
            val faces = FaceMask(width, height, facePixels)
            val result = StageClearLook.renderFromBase(base, faces, strength)
            maximumError = maxOf(maximumError, compare("$name stages1-8", full, result.image.copyPixels()))
            assertEquals("$name face factor", factor, result.faceCutFactor, 2e-6)
            assertArrayEquals(basePixels, base.copyPixels(), 0f)
            assertArrayEquals(stage3Pixels, stage3.copyPixels(), 0f)
            assertArrayEquals(facePixels, faces.copyWeights(), 0f)
            cases++
            pixels += count
        }
        assertEquals(45, cases)
        assertEquals(13740, pixels)
        println("PC stages4-5/full cases=$cases; pixels=$pixels; maximum absolute error=$maximumError; full maximum error=$maximumFullError; tolerance ratio=$largestToleranceRatio")
        println("Cumulative diagnostics: $diagnostics")
        assertTrue("Full comparison ratio $largestToleranceRatio", largestToleranceRatio <= 1f)
    }

    @Test fun zeroRetainsProtectionsAndDefaultIsHalf() {
        val base = LinearSrgbImage(10, 10, FloatArray(300) { 1.4f })
        val faces = FaceMask(10, 10, FloatArray(100) { 1f })
        val zero = StageClearLook.renderFromBase(base, faces, 0.0)
        assertTrue(zero.faceCutFactor < 1.0)
        assertTrue(zero.image[0] < base[0])
        assertArrayEquals(StageClearLook.renderFromBase(base, faces, .5).image.copyPixels(),
            StageClearLook.renderFromBase(base, faces).image.copyPixels(), 0f)
        assertEquals(.5, StageClearV1.DEFAULT_STRENGTH, 0.0)
    }

    @Test fun vignetteUsesPixelCentresAndNoClipping() {
        val image = LinearSrgbImage(5, 3, FloatArray(45) { if (it % 3 == 0) -1f else 2f })
        val vignette = StageClearCharacter.vignette(image, .35)
        assertEquals(-1f, vignette[(1 * 5 + 2) * 3], 0f)
        assertTrue(vignette[0] < 0f)
        assertTrue(vignette[1] > 1f)
        assertEquals(vignette[0], vignette[4 * 3], 1e-7f)
        assertArrayEquals(image.copyPixels(), StageClearCharacter.vignette(image, 0.0).copyPixels(), 0f)
    }

    @Test fun invalidStrengthAndMisalignedFacesFailBeforeProcessing() {
        val image = LinearSrgbImage(1, 1, floatArrayOf(.4f, .2f, .08f))
        val faces = FaceMask(1, 1, floatArrayOf(1f))
        for (bad in listOf(-.01, 1.01, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { StageClearLook.renderFromBase(image, faces, bad) }
            assertThrows(IllegalArgumentException::class.java) { StageClearCharacter.render(image, bad) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            StageClearLook.renderFromBase(image, FaceMask(2, 1, FloatArray(2)), 0.0)
        }
    }

    private fun observe(stage: String, label: String, expected: FloatArray, actual: FloatArray) {
        assertEquals(label, expected.size, actual.size)
        for (i in expected.indices) {
            assertTrue("$label channel $i must be finite", actual[i].isFinite())
            val error = abs(expected[i] - actual[i])
            if (error > (diagnostics[stage]?.first ?: -1f)) {
                diagnostics[stage] = error to "$label channel=$i expected=${expected[i]} actual=${actual[i]}"
            }
        }
    }

    private fun compare(label: String, expected: FloatArray, actual: FloatArray, relativeTolerance: Float = 0f): Float {
        assertEquals(label, expected.size, actual.size)
        if (label.endsWith("stages1-5")) observe("stages1-5", label, expected, actual)
        var maximum = 0f
        for (i in expected.indices) {
            val error = abs(expected[i] - actual[i])
            assertTrue("$label channel $i must be finite", actual[i].isFinite())
            val ratio = error / (2e-5f + relativeTolerance * abs(expected[i]))
            if (relativeTolerance == 0f) maximumFullError = maxOf(maximumFullError, error)
            if (ratio > largestToleranceRatio) {
                largestToleranceRatio = ratio
            }
            maximum = maxOf(maximum, error)
        }
        return maximum
    }
}
