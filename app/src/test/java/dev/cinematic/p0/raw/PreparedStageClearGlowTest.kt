package dev.cinematic.p0.raw

import dev.cinematic.p0.jpegrender.TrialPreparedFoundation
import java.util.concurrent.CancellationException
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

class PreparedStageClearGlowTest {
    @Test fun cachedFieldsPreserveSyntheticLookAcrossStrengths() {
        val width = 96; val height = 72
        val pixels = FloatArray(width * height * 3) { i ->
            val x = (i / 3) % width; val y = (i / 3) / width
            when {
                x < 5 && y < 9 -> if (i % 3 == 0) 3f else 1.2f
                x in 24..48 && y in 36..54 -> when (i % 3) { 0 -> .65f; 1 -> .35f; else -> .22f }
                y > 60 -> x / 96f * 1.1f - .03f
                else -> (i * 37 % 200) / 200f
            }
        }
        val base = LinearSrgbImage(width, height, pixels)
        val foundation = TrialPreparedFoundation(base, width, height, true, 0.0)
        val faces = FaceMask(width, height, FloatArray(width * height) { p ->
            if (p % width in 24..48 && p / width in 36..54) 1f else 0f
        })
        for (strength in listOf(0.0, .25, .5, 1.0, .5)) {
            val expected = StageClearLook.renderFromBase(base, faces, strength)
            val actual = foundation.renderTrial(faces, strength) { false }
            var maximum = 0.0
            repeat(width * height * 3) { maximum = maxOf(maximum, abs(expected.image[it] - actual.image[it]).toDouble()) }
            assertTrue("difference=$maximum strength=$strength", maximum < .00002)
            assertEquals(expected.faceCutFactor, actual.faceCutFactor, .00002)
        }
        assertTrue(foundation.previewFieldBytes in 1..500_000)
        assertArrayEquals(pixels, base.copyPixels(), 0f)
    }

    @Test fun cancelledPreparationDoesNotPublishFields() {
        val base = LinearSrgbImage(64, 48, FloatArray(64 * 48 * 3) { .5f })
        val foundation = TrialPreparedFoundation(base, 64, 48, true, 0.0)
        val faces = FaceMask(64, 48, FloatArray(64 * 48))
        var checks = 0
        assertThrows(CancellationException::class.java) {
            foundation.renderTrial(faces, .5) { ++checks > 4 }
        }
        assertEquals(0, foundation.previewFieldBytes)
        foundation.renderTrial(faces, .5) { false }
        assertTrue(foundation.previewFieldBytes > 0)
    }

    @Test fun portrait1024PreviewMatchesExistingLook() {
        val width = 768; val height = 1024
        val pixels = FloatArray(width * height * 3) { i ->
            val x = (i / 3) % width; val y = (i / 3) / width
            when {
                x in 380..400 && y in 200..240 -> if (i % 3 == 0) 1.8f else .7f
                x < 16 || y > height - 16 -> .015f
                else -> ((x * 13 + y * 7 + i % 3 * 31) % 257) / 256f
            }
        }
        val base = LinearSrgbImage(width, height, pixels)
        val faces = FaceMask(width, height, FloatArray(width * height))
        val foundation = TrialPreparedFoundation(base, width, height, true, 0.0)
        for (strength in listOf(.35, .8)) {
            val expected = StageClearLook.renderFromBase(base, faces, strength)
            val actual = foundation.renderTrial(faces, strength) { false }
            var maximum = 0.0
            repeat(pixels.size) { maximum = maxOf(maximum, abs(expected.image[it] - actual.image[it]).toDouble()) }
            assertTrue("portrait difference=$maximum strength=$strength", maximum < .00002)
        }
        assertTrue(foundation.previewFieldBytes in 1..500_000)
    }
}
