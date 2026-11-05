package dev.cinematic.p0.jpegrender

import dev.cinematic.p0.rawcapture.*
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class PrivateTrialStrengthRecipeTest {
    private val legacyPolicy = "ev=PC_CENTRAL_P97_DIAGNOSTIC;wb=AS_SHOT_BEFORE_W1;roi=none-explicit;face=NOT_RUN;mask=protection-disabled"
    private val legacyPayload = "input=libraw-as-shot-bounded-v1;controls=as-shot-p97-no-face-trial-v1;policy=" +
        Base64.getUrlEncoder().withoutPadding().encodeToString(legacyPolicy.toByteArray(Charsets.UTF_8)) +
        ";edge=1024;strength=0x1.0p-1;quality=95;color=sRGB-SDR;pixelProfile=preliminary"
    private val legacy = RenderRecipeSnapshot("stage-clear-private-no-face-trial-v1", legacyPayload)
    private val source = SharedOriginalReference("content://example.test/original/1",
        OriginalFingerprint(3, "a".repeat(64)), OriginalGrant.USER_SELECTED)
    private class Memory : AtomicJournalStorage {
        var bytes: ByteArray? = null
        override fun read(maxBytes: Int) = bytes?.clone()
        override fun replace(contents: ByteArray) { bytes = contents.clone() }
    }

    @Test fun defaultRecipeAndItsRestorationKeepTheExistingHalfStrengthBytes() {
        assertEquals(legacy, JpegGenerationRecipe.trial().snapshot)
        val restored = requireNotNull(JpegGenerationRecipe.fromTrialSnapshot(legacy))
        assertTrue(restored.isTrial)
        assertEquals(.5, restored.strength, 0.0)
        assertEquals(legacy, restored.snapshot)
        assertEquals(95, restored.quality)
        assertEquals(AsShotNoFaceTrialControls.POLICY, restored.controls)
    }

    @Test fun canonicalStrengthsRoundTripExactlyWithoutChangingOtherTrialParameters() {
        for (strength in listOf(0.0, .123456789012345, .5, Math.nextDown(1.0), 1.0)) {
            val recipe = JpegGenerationRecipe.trial(strength)
            val restored = requireNotNull(JpegGenerationRecipe.fromTrialSnapshot(recipe.snapshot))
            assertEquals(java.lang.Double.doubleToRawLongBits(strength), java.lang.Double.doubleToRawLongBits(restored.strength))
            assertEquals(recipe.snapshot, restored.snapshot)
            assertEquals(JpegGenerationRecipe.TRIAL_SCHEMA, restored.snapshot.schema)
            assertEquals(AndroidAsShotJpegFrames.CONTRACT, restored.inputContract)
            assertEquals(AsShotNoFaceTrialControls.POLICY, restored.controls)
            assertEquals(95, restored.quality)
        }
    }

    @Test fun outOfRangeAndNonfiniteFactoryInputsAreRejected() {
        for (strength in listOf(-.001, 1.001, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY))
            assertThrows(IllegalArgumentException::class.java) { JpegGenerationRecipe.trial(strength) }
    }

    @Test fun onlyTheExistingCanonicalPrivateRecipeWithAnAllowedStrengthCanBeRestored() {
        val changedPayloads = listOf(
            legacyPayload.replace("strength=0x1.0p-1", "strength=.5"),
            legacyPayload.replace("strength=0x1.0p-1", "strength=NaN"),
            legacyPayload.replace("strength=0x1.0p-1", "strength=Infinity"),
            legacyPayload.replace("strength=0x1.0p-1", "strength=-0x1.0p-1"),
            legacyPayload.replace("strength=0x1.0p-1", "strength=0x1.0000000000001p0"),
            legacyPayload.replace(";strength=0x1.0p-1", ""),
            legacyPayload.replace("strength=0x1.0p-1", "strength=0x1.0p-1;strength=0x1.0p-1"),
            legacyPayload.replace("input=libraw-as-shot-bounded-v1", "input=other-input"),
            legacyPayload.replace("controls=as-shot-p97-no-face-trial-v1", "controls=other-controls"),
            legacyPayload.replace("edge=1024", "edge=2048"),
            legacyPayload.replace("quality=95", "quality=96"),
            legacyPayload.replace("color=sRGB-SDR", "color=DisplayP3"),
            legacyPayload.replace("pixelProfile=preliminary", "pixelProfile=supported"),
            legacyPayload + ";extra=value",
            legacyPayload.split(';').reversed().joinToString(";"),
        )
        for (payload in changedPayloads) assertNull(payload, JpegGenerationRecipe.fromTrialSnapshot(legacy.copy(payload = payload)))
        assertNull(JpegGenerationRecipe.fromTrialSnapshot(legacy.copy(schema = "unknown-private-v2")))
        assertNull(JpegGenerationRecipe.fromTrialSnapshot(legacy.copy(schema = JpegGenerationRecipe.SCHEMA)))
        val completed = JpegGenerationRecipe(AndroidAsShotJpegFrames.CONTRACT, AsShotNoFaceTrialControls.POLICY)
        assertNull(JpegGenerationRecipe.fromTrialSnapshot(completed.snapshot))
    }

    @Test fun existingJournalCodecRetainsTheSelectedStrengthAndPrivateGatesAcrossRecovery() {
        val selected = Memory()
        var previousGeneration = 0L
        for (strength in listOf(.5, 0.0, 1.0)) {
            val recipe = JpegGenerationRecipe.trial(strength)
            val request = ManualDevelopRequests(source, selected).prepare(recipe.snapshot)
            assertEquals(previousGeneration + 1, request.generation)
            previousGeneration = request.generation
            assertEquals(request, ManualDevelopRequests(source, selected).prepare(recipe.snapshot))
            val restoredRequest = AutoDevelopCodec.decode(AutoDevelopCodec.encode(AutoDevelopRecord(request))).request
            assertEquals(recipe.snapshot, restoredRequest.recipe)
            assertEquals(strength, JpegGenerationRecipe.fromTrialSnapshot(restoredRequest.recipe)!!.strength, 0.0)
            assertTrue(restoredRequest.boundedTrial)
            assertFalse(restoredRequest.publishable)
            val store = Memory()
            val journal = AutoDevelopJournal(request.captureId, request.generation, store)
            journal.prepare(request)
            val started = journal.start(0)
            journal.rendered(started.revision, source.fingerprint, AutoRenderReceipt(request,
                JpegOutputDescription(4, 4, 1, "sRGB", 3), RenderedFaceState.NOT_RUN, RenderedExposureState.DIAGNOSTIC))
            val restored = AutoDevelopJournal(request.captureId, request.generation, store).recoverAfterProcessDeath()!!
            assertEquals(recipe.snapshot, restored.request.recipe)
            assertEquals(PrivateTrialPhase.READY, PrivateTrialStatus.from(restored).phase)
            assertThrows(IllegalStateException::class.java) { restored.completed() }
            assertThrows(CaptureJournalConflict::class.java) { journal.publishing(restored.revision) }
        }
    }
}
