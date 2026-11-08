package dev.cinematic.p0.raw

import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class StageClearDecisionsTest {
    @Test fun absentAndNullOverridePreservePreviousCompositionRawBits() {
        for ((width, height) in listOf(1 to 1, 1 to 9, 17 to 1, 24 to 16)) {
            val base = patterned(width, height)
            val before = base.copyPixels()
            for (withFaces in listOf(false, true)) {
                val faces = mask(width, height, withFaces)
                for (strength in listOf(-0.0, 0.0, .125, .5, 1.0)) {
                    val expected = previousComposition(base, faces, strength)
                    val actual = StageClearLook.renderFromBase(base, faces, strength)
                    assertResultBits(expected, actual)
                    assertResultBits(actual, StageClearLook.renderFromBase(base, faces, strength, decisions = null))
                    assertNotNull(actual.decisions)
                }
            }
            assertArrayEquals(before.map { it.toRawBits() }.toIntArray(), base.copyPixels().map { it.toRawBits() }.toIntArray())
        }
    }

    @Test fun sameSizeCapturedDecisionsPreserveExactAndPreparedRawBits() {
        for (withFaces in listOf(false, true)) {
            val base = patterned(24, 16)
            val faces = mask(24, 16, withFaces)
            val prepared = PreparedStageClearGlow.prepare(base) {}
            for (strength in listOf(-0.0, 0.0, .25, .5, 1.0)) {
                val exact = StageClearLook.renderFromBase(base, faces, strength)
                val exactReuse = StageClearLook.renderFromBase(base, faces, strength, requireNotNull(exact.decisions))
                assertResultBits(exact, exactReuse)
                assertEquals(exact.decisions, exactReuse.decisions)
                val cached = prepared.render(faces, strength) {}
                assertResultBits(cached, prepared.render(faces, strength, decisions = null) {})
                val cachedReuse = prepared.render(faces, strength, requireNotNull(cached.decisions)) {}
                assertResultBits(cached, cachedReuse)
                assertEquals(cached.decisions, cachedReuse.decisions)
            }
        }
    }

    @Test fun capturesSeparateFaceCutMistAndReusesItBelowTheFacePixelThreshold() {
        val base = LinearSrgbImage(1024, 64, FloatArray(1024 * 64 * 3) { .95f })
        val faces = FaceMask(1024, 64, FloatArray(1024 * 64) { p ->
            if (p % 1024 < 96 && p / 1024 < 8) 1f else 0f
        })
        val exact = StageClearLook.renderFromBase(base, faces, .5)
        val decisions = requireNotNull(exact.decisions)
        assertTrue(decisions.faceCutFactor < 1.0)
        assertNotEquals(decisions.mistAutoStrength, requireNotNull(decisions.faceCutMistAutoStrength), 0.0)
        val small = resize(base, 256, 16)
        val smallFaces = FaceMask(256, 16, FloatArray(256 * 16) { p ->
            if (p % 256 < 24 && p / 256 < 2) 1f else 0f
        })
        assertEquals(1.0, StageClearLook.renderFromBase(small, smallFaces, .5).faceCutFactor, 0.0)
        val reused = StageClearLook.renderFromBase(small, smallFaces, .5, decisions)
        assertEquals(decisions, reused.decisions)
        assertResultBits(withDecisions(small, smallFaces, .5, decisions), reused)
        val prepared = PreparedStageClearGlow.prepare(small) {}
        assertEquals(decisions, prepared.render(smallFaces, .5, decisions) {}.decisions)
        // Using the first pass's Mist decision for the rerender must be observably wrong.
        val wrongPass = decisions.copy(faceCutMistAutoStrength = decisions.mistAutoStrength)
        assertFalse(reused.image.copyPixels().contentEquals(
            StageClearLook.renderFromBase(small, smallFaces, .5, wrongPass).image.copyPixels()))
    }

    @Test fun crossSizeReuseKeepsTheMeasuredAutoStrengthAndAppliesCurrentSliderStrength() {
        val base = LinearSrgbImage(1024, 16, FloatArray(1024 * 16 * 3) { i ->
            if ((i / 3) % 41 == 0) 1.5f else .02f
        })
        val exact = StageClearLook.renderFromBase(base, mask(1024, 16), 0.0)
        val decisions = requireNotNull(exact.decisions)
        val small = resize(base, 256, 4)
        val faces = mask(256, 4)
        val independentlyMeasured = requireNotNull(StageClearLook.renderFromBase(small, faces, 0.0).decisions)
        assertNotEquals(decisions.mistAutoStrength, independentlyMeasured.mistAutoStrength, 0.0)
        assertNull(decisions.faceCutMistAutoStrength)
        for (strength in listOf(0.0, .25, 1.0)) {
            val reused = StageClearLook.renderFromBase(small, faces, strength, decisions)
            assertEquals(decisions, reused.decisions)
            assertResultBits(withDecisions(small, faces, strength, decisions), reused)
        }
    }

    @Test fun overriddenMistFeedsCurrentScaledAmountToOrton() {
        val base = patterned(24, 16)
        val skin = StageClearGlow.skinWeight(base)
        for (strength in listOf(-0.0, 0.0, .25, 1.0)) {
            val result = StageClearMist.render(base, skin, strength, .625)
            assertEquals((.625 * strength).toRawBits(), result.lightAddingStrength.toRawBits())
            assertResultBits(StageClearProtections.Result(StageClearOrton.render(result, skin), 1.0),
                StageClearProtections.Result(StageClearOrton.render(
                    StageClearMist.Result(result.image, .625 * strength), skin), 1.0))
        }
    }

    @Test fun overridesDoNotSkipCancellationBoundariesOrPublishPartialDecisions() {
        val base = LinearSrgbImage(10, 10, FloatArray(300) { .95f })
        val faces = mask(10, 10, true)
        val decisions = requireNotNull(StageClearLook.renderFromBase(base, faces, .5).decisions)
        assertTrue(decisions.faceCutFactor < 1.0)
        var oldChecks = 0
        var overrideChecks = 0
        StageClearLook.renderFromBase(base, faces, .5) { oldChecks++; false }
        StageClearLook.renderFromBase(base, faces, .5, decisions) { overrideChecks++; false }
        assertEquals(oldChecks, overrideChecks)
        for (stopAt in 1..oldChecks) {
            var checks = 0
            assertThrows(CancellationException::class.java) {
                StageClearLook.renderFromBase(base, faces, .5, decisions) { ++checks == stopAt }
            }
            assertEquals(stopAt, checks)
        }
        val prepared = PreparedStageClearGlow.prepare(base) {}
        var preparedChecks = 0
        prepared.render(faces, .5) { preparedChecks++ }
        var reusedChecks = 0
        prepared.render(faces, .5, decisions) { reusedChecks++ }
        assertEquals(preparedChecks, reusedChecks)
        for (stopAt in listOf(1, 4, preparedChecks)) {
            var checks = 0
            assertThrows(CancellationException::class.java) {
                prepared.render(faces, .5, decisions) {
                    if (++checks == stopAt) throw CancellationException("Stopped")
                }
            }
            assertEquals(stopAt, checks)
        }
    }

    @Test fun rejectsIncompleteOrNonfiniteDecisionsAtTheEntry() {
        assertThrows(IllegalArgumentException::class.java) { StageClearDecisions(.5, .8) }
        assertThrows(IllegalArgumentException::class.java) { StageClearDecisions(.5, 1.0, .75) }
        for (bad in listOf(Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, -.01, 1.01)) {
            assertThrows(IllegalArgumentException::class.java) { StageClearDecisions(bad, 1.0) }
            assertThrows(IllegalArgumentException::class.java) { StageClearDecisions(.5, .8, bad) }
            assertThrows(IllegalArgumentException::class.java) { StageClearDecisions(.5, bad) }
        }
        assertThrows(IllegalArgumentException::class.java) { StageClearDecisions(.5, 0.0) }
    }

    /** Explicit pre-change stage composition; no decision-override entry is involved. */
    private fun previousComposition(base: LinearSrgbImage, faces: FaceMask, strength: Double): StageClearProtections.Result {
        val skin = StageClearGlow.skinWeight(base)
        val glowed = StageClearGlow.render(base, strength, skin)
        fun layer(input: LinearSrgbImage): LinearSrgbImage = StageClearProtections.skinChromaCap(
            StageClearCharacter.render(StageClearOrton.render(StageClearMist.render(input, skin, strength), skin), strength), faces)
        val first = layer(glowed)
        val factor = StageClearProtections.faceCut(first, faces)
        return StageClearProtections.Result(StageClearProtections.shoulder(
            if (factor < 1.0) layer(glowed.scaled(factor)) else first), factor)
    }

    private fun withDecisions(base: LinearSrgbImage, faces: FaceMask, strength: Double,
        decisions: StageClearDecisions): StageClearProtections.Result {
        val skin = StageClearGlow.skinWeight(base)
        val glowed = StageClearGlow.render(base, strength, skin)
        fun layer(input: LinearSrgbImage, autoStrength: Double) = StageClearProtections.skinChromaCap(
            StageClearCharacter.render(StageClearOrton.render(
                StageClearMist.render(input, skin, strength, autoStrength), skin), strength), faces)
        val first = layer(glowed, decisions.mistAutoStrength)
        val output = if (decisions.faceCutFactor < 1.0) {
            layer(glowed.scaled(decisions.faceCutFactor), requireNotNull(decisions.faceCutMistAutoStrength))
        } else first
        return StageClearProtections.Result(StageClearProtections.shoulder(output), decisions.faceCutFactor)
    }

    private fun patterned(width: Int, height: Int) = LinearSrgbImage(width, height,
        FloatArray(width * height * 3) { i ->
            when ((i / 3) % 7) { 0 -> 1.4f; 1 -> -.02f; else -> ((i * 37) % 100) / 100f }
        })

    private fun mask(width: Int, height: Int, filled: Boolean = false) =
        FaceMask(width, height, FloatArray(width * height) { if (filled) 1f else 0f })

    private fun resize(image: LinearSrgbImage, width: Int, height: Int): LinearSrgbImage {
        val pixels = FloatArray(width * height * 3)
        repeat(3) { channel ->
            val plane = StageClearBlur.resize(FloatPlane(image.width, image.height,
                FloatArray(image.pixelCount) { image[it * 3 + channel] }), width, height, StageClearBlur.Filter.BOX)
            repeat(width * height) { pixels[it * 3 + channel] = plane[it] }
        }
        return LinearSrgbImage(width, height, pixels)
    }

    private fun assertResultBits(expected: StageClearProtections.Result, actual: StageClearProtections.Result) {
        assertEquals(expected.faceCutFactor.toRawBits(), actual.faceCutFactor.toRawBits())
        assertEquals(expected.image.width, actual.image.width)
        assertEquals(expected.image.height, actual.image.height)
        assertArrayEquals(expected.image.copyPixels().map { it.toRawBits() }.toIntArray(),
            actual.image.copyPixels().map { it.toRawBits() }.toIntArray())
    }
}
