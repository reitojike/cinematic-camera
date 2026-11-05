package dev.cinematic.p0.jpegrender

import dev.cinematic.p0.raw.PhotoAdjustments
import org.junit.Assert.*
import org.junit.Test

class PhotoAdjustmentRecipeTest {
    @Test fun allContinuousCoordinatesRoundTripAndNeutralKeepsLegacyBytes() {
        val baseline = JpegGenerationRecipe.trial(.5)
        assertEquals(baseline.snapshot, JpegGenerationRecipe.trial(.5, PhotoAdjustments()).snapshot)
        for (edit in listOf(PhotoAdjustments(-100.0, -2.0), PhotoAdjustments(100.0, 2.0),
            PhotoAdjustments(.123456789, .23456789), PhotoAdjustments(0.0, 1.0), PhotoAdjustments(1.0, 0.0))) {
            val recipe = JpegGenerationRecipe.trial(.123, edit)
            val restored = requireNotNull(JpegGenerationRecipe.fromTrialSnapshot(recipe.snapshot))
            assertEquals(edit, restored.adjustments)
            assertEquals(recipe.snapshot, restored.snapshot)
            assertNotEquals(baseline.snapshot, recipe.snapshot)
        }
    }

    @Test fun unknownAlgorithmsDuplicatesAndOutOfRangeValuesAreNotRestored() {
        val recipe = JpegGenerationRecipe.trial(.5, PhotoAdjustments(100.0, 2.0)).snapshot
        for (payload in listOf(recipe.payload.replace(PhotoAdjustments.VERSION, "future-v2"),
            recipe.payload + ";ev=0x1.0p1", recipe.payload.replace("ev=0x1.0p1", "ev=0x1.8p1"),
            recipe.payload.replace("warmth=0x1.9p6", "warmth=NaN"),
            recipe.payload.replace("ev=0x1.0p1", "ev=2.0"))) {
            assertNull(payload, JpegGenerationRecipe.fromTrialSnapshot(recipe.copy(payload = payload)))
        }
    }
}
