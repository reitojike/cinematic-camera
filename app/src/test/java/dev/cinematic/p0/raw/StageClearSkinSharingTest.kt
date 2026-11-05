package dev.cinematic.p0.raw

import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class StageClearSkinSharingTest {
    private val strengths = listOf(-0.0, 0.0, .125, .5, .75, 1.0)
    private val shapes = listOf(1 to 1, 1 to 9, 17 to 1, 13 to 11, 9 to 15, 24 to 16)

    @Test fun completeLookMatchesTheOldCompositionBitForBitAcrossStrengthsAndFaceRegions() {
        var sawFaceCut = false
        for ((width, height) in shapes) {
            val base = input(width, height)
            val before = base.copyPixels()
            for (region in 0..2) {
                val weights = FloatArray(width * height) { p -> when (region) {
                    0 -> 0f
                    1 -> 1f
                    else -> when (p % 4) { 0 -> 0f; 1 -> .25f; 2 -> .75f; else -> 1f }
                } }
                val faces = FaceMask(width, height, weights)
                for (strength in strengths) {
                    val expected = legacyLook(base, faces, strength)
                    val actual = StageClearLook.renderFromBase(base, faces, strength)
                    assertBits("$width x $height / ROI=$region / strength=$strength", expected.image, actual.image)
                    assertEquals(java.lang.Double.doubleToRawLongBits(expected.faceCutFactor),
                        java.lang.Double.doubleToRawLongBits(actual.faceCutFactor))
                    if (actual.faceCutFactor < 1.0) sawFaceCut = true
                    assertArrayEquals(before, base.copyPixels(), 0f)
                    assertArrayEquals(weights, faces.copyWeights(), 0f)
                }
            }
        }
        assertTrue("Exercise the scaled Glow-input rerun as well as the ordinary protection path", sawFaceCut)
    }

    @Test fun suppliedSkinGlowMatchesTheDirectApiWithoutChangingEitherInput() {
        for ((width, height) in shapes) {
            val base = input(width, height)
            val original = base.copyPixels()
            val skin = StageClearGlow.skinWeight(base)
            val originalSkin = skin.copyValues()
            for (strength in strengths) {
                assertBits("Glow $width x $height / strength=$strength", StageClearGlow.render(base, strength),
                    StageClearGlow.render(base, strength, skin))
                assertArrayEquals(original, base.copyPixels(), 0f)
                assertArrayEquals(originalSkin, skin.copyValues(), 0f)
            }
        }
    }

    @Test fun differentInputsAndGeometryCannotReuseThePrecedingInvocationsSkin() {
        val first = input(13, 11)
        val faces = FaceMask(13, 11, FloatArray(13 * 11) { 1f })
        val expected = legacyLook(first, faces, .75)
        val actual = StageClearLook.renderFromBase(first, faces, .75)
        val before = actual.image.copyPixels()
        val other = LinearSrgbImage(11, 13, FloatArray(13 * 11 * 3) { channel ->
            if (channel % 3 == 2) 1.9f else .02f
        })
        assertBits("Interleaved different base", legacyLook(other, FaceMask(11, 13, FloatArray(143)), .75).image,
            StageClearLook.renderFromBase(other, FaceMask(11, 13, FloatArray(143)), .75).image)
        assertBits("Original result remains unchanged", expected.image, actual.image)
        assertArrayEquals(before, actual.image.copyPixels(), 0f)
        assertBits("Reopening the original input", expected.image, StageClearLook.renderFromBase(first, faces, .75).image)
    }

    @Test fun cancellationBeforeAndAfterSkinAndLaterStagesHasTheSameBoundariesAsBefore() {
        val pixels = FloatArray(300) { 1.4f }
        val base = LinearSrgbImage(10, 10, pixels)
        val faces = FaceMask(10, 10, FloatArray(100) { 1f })
        // 1 and 2 surround the original skin calculation. Later checks include the face-cut rerun.
        for (stopAt in listOf(1, 2, 3, 5, 8, 12)) {
            var legacyChecks = 0
            var sharedChecks = 0
            assertThrows(CancellationException::class.java) {
                legacyLook(base, faces, 0.0) { ++legacyChecks == stopAt }
            }
            assertThrows(CancellationException::class.java) {
                StageClearLook.renderFromBase(base, faces, 0.0) { ++sharedChecks == stopAt }
            }
            assertEquals(stopAt, legacyChecks); assertEquals(legacyChecks, sharedChecks)
            assertArrayEquals(pixels, base.copyPixels(), 0f)
        }
        var legacyChecks = 0
        var sharedChecks = 0
        assertBits("All checkpoints without cancellation", legacyLook(base, faces, .75) { legacyChecks++; false }.image,
            StageClearLook.renderFromBase(base, faces, .75) { sharedChecks++; false }.image)
        assertEquals(legacyChecks, sharedChecks)
    }

    @Test fun legacyZeroAndDefaultRemainCompatibleAndSuppliedSkinRequiresAlignedUnitWeights() {
        val base = input(3, 2)
        val skin = StageClearGlow.skinWeight(base)
        assertSame(base, StageClearGlow.render(base, 0.0))
        assertSame(base, StageClearGlow.render(base, 0.0, skin))
        assertBits("Legacy default strength", StageClearGlow.render(base, .5), StageClearGlow.render(base))
        for (badStrength in listOf(Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, -.01, 1.01)) {
            assertThrows(IllegalArgumentException::class.java) { StageClearGlow.render(base, badStrength, skin) }
        }
        for (strength in listOf(0.0, .5)) {
            for (badSkin in listOf(FloatPlane(2, 3, FloatArray(6)), FloatPlane(3, 2, FloatArray(6) { -.1f }),
                FloatPlane(3, 2, FloatArray(6) { 1.1f }))) {
                assertThrows(IllegalArgumentException::class.java) { StageClearGlow.render(base, strength, badSkin) }
            }
        }
    }

    /** Frozen pre-sharing composition. Direct Glow recomputes skin while Mist/Orton use the first plane. */
    private fun legacyLook(base: LinearSrgbImage, faces: FaceMask, strength: Double,
        shouldStop: () -> Boolean = { false }): StageClearProtections.Result {
        require(strength.isFinite() && strength in 0.0..1.0)
        faces.requireAligned(base)
        fun checkpoint() { if (shouldStop()) throw CancellationException("Legacy comparison stopped") }
        checkpoint()
        val skin = StageClearGlow.skinWeight(base)
        checkpoint()
        val glowed = StageClearGlow.render(base, strength)
        checkpoint()
        return StageClearProtections.finish(glowed, faces) { input ->
            checkpoint()
            val mist = StageClearMist.render(input, skin, strength)
            checkpoint()
            val orton = StageClearOrton.render(mist, skin)
            checkpoint()
            StageClearCharacter.render(orton, strength).also { checkpoint() }
        }.also { checkpoint() }
    }

    private fun input(width: Int, height: Int): LinearSrgbImage {
        val colours = arrayOf(floatArrayOf(1.9f, 1.1f, .6f), floatArrayOf(1.8f, .2f, .1f),
            floatArrayOf(.9f, .55f, .4f), floatArrayOf(.04f, .8f, .15f), floatArrayOf(.07f, .2f, 1.6f),
            floatArrayOf(-.15f, .25f, .65f), floatArrayOf(0f, 0f, 0f), floatArrayOf(2f, 2f, 2f))
        return LinearSrgbImage(width, height, FloatArray(width * height * 3) { i ->
            colours[(i / 3 + i / 3 / width) % colours.size][i % 3]
        })
    }

    private fun assertBits(label: String, expected: LinearSrgbImage, actual: LinearSrgbImage) {
        assertEquals(expected.width, actual.width); assertEquals(expected.height, actual.height)
        val expectedPixels = expected.copyPixels()
        val actualPixels = actual.copyPixels()
        assertArrayEquals(label, IntArray(expectedPixels.size) { java.lang.Float.floatToRawIntBits(expectedPixels[it]) },
            IntArray(actualPixels.size) { java.lang.Float.floatToRawIntBits(actualPixels[it]) })
    }
}
