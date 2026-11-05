package dev.cinematic.p0.rawcapture

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class CaptureOriginalRequestTest {
    private val pair = setOf(RawOutput.DNG, RawOutput.JPEG)
    private val fingerprint = OriginalFingerprint(3, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    private val intent = CaptureDevelopIntent(RenderRecipeSnapshot("synthetic-final", "fixed"), "synthetic-renderer",
        AutoRenderQuality.FINAL_ORIGINAL_STRICT, syntheticOriginalDeclaration)
    private fun receipt(selected: CaptureDevelopIntent? = intent, phase: CaptureReceiptPhase = CaptureReceiptPhase.FILES_RECEIVED,
        fp: OriginalFingerprint? = fingerprint) = CaptureReceipt("capture", 4, pair,
        mapOf(RawOutput.DNG to 3L, RawOutput.JPEG to 2L), phase, fp, selected)
    private fun shared(phase: DngPublicationPhase = DngPublicationPhase.SHARED_ONLY, id: String = "capture",
        fp: OriginalFingerprint = fingerprint) = DngPublicationRecord(DngPublicationTarget(id, "a".repeat(32)), 10,
        fp, phase, if (phase in setOf(DngPublicationPhase.PREPARED, DngPublicationPhase.INSERTING)) null
            else "content://media/external_primary/images/media/1")
    private class Memory : AtomicJournalStorage {
        var bytes: ByteArray? = null
        var writes = 0
        override fun read(maxBytes: Int) = bytes?.clone()
        override fun replace(contents: ByteArray) { bytes = contents.clone(); writes++ }
    }

    @Test fun strictQualityAndFrozenDeclarationMustTravelTogether() {
        assertThrows(IllegalArgumentException::class.java) { intent.copy(originalDeclaration = null) }
        assertThrows(IllegalArgumentException::class.java) { intent.copy(requestedQuality = AutoRenderQuality.FINAL_ORIGINAL) }
        for (quality in listOf(AutoRenderQuality.PREVIEW, AutoRenderQuality.PRIVATE_TRIAL_1024,
            AutoRenderQuality.MANUAL_BOUNDED_1024_PRELIMINARY)) {
            assertThrows(IllegalArgumentException::class.java) { intent.copy(requestedQuality = quality) }
        }
    }

    @Test fun versionFourKeepsTheCapturedPolicyAcrossPartialReceiptInterruptionLateCallbackAndRecovery() {
        for (interrupted in listOf(false, true)) {
            val storage = Memory()
            val journal = CaptureJournal("capture", storage)
            assertEquals(intent, journal.create(pair, intent).developIntent)
            assertEquals(4, ByteBuffer.wrap(storage.bytes!!).getInt(4))
            assertEquals(intent, journal.received(0, RawOutput.DNG, 3).developIntent)
            assertEquals(intent, journal.recordOriginalFingerprint(1, fingerprint).developIntent)
            var revision = 2L
            if (interrupted) revision = journal.interrupt(revision).revision
            val all = journal.received(revision, RawOutput.JPEG, 2)
            val recovered = CaptureJournal("capture", storage).recoverAfterProcessDeath()!!
            assertEquals(all.revision, recovered.revision)
            assertEquals(intent, recovered.developIntent)
            assertEquals(fingerprint, recovered.originalFingerprint)
            if (interrupted) assertNull(recovered.originalDevelopRequest(shared()))
            else assertEquals(intent.originalDeclaration, recovered.originalDevelopRequest(shared())!!.originalDeclaration)
        }
    }

    @Test fun duplicateSnapshotsRecreateTheSameUnobservedRequestWithoutCurrentSettingsOrEnrollment() {
        val original = receipt()
        val restored = CaptureReceiptCodec.decode(CaptureReceiptCodec.encode(original))
        val request = requireNotNull(restored.originalDevelopRequest(shared()))
        assertEquals(original.originalDevelopRequest(shared()), request)
        assertEquals(request, restored.originalDevelopRequest(shared().copy(revision = 20)))
        assertEquals(1L, request.generation)
        assertEquals(intent.recipe, request.recipe)
        assertEquals(intent.rendererContract, request.rendererContract)
        assertEquals(intent.originalDeclaration, request.originalDeclaration)
        assertEquals(fingerprint, request.source.fingerprint)
        assertEquals(shared().uri, request.source.uri)
        assertEquals(OriginalGrant.APP_OWNED, request.source.grant)
        assertEquals(0, request.width); assertEquals(0, request.height)
        assertEquals(RenderedFaceState.NOT_RUN, request.face)
        assertEquals(RenderedExposureState.UNKNOWN, request.exposure)
        assertEquals(AutoInputProfile.UNKNOWN, request.profile)
        assertNull(AutoRendererCatalog().find(request)) // Metadata eligibility does not enroll a producer.
    }

    @Test fun legacyOrAbsentIntentKeepsVersionThreeAndCannotBePromoted() {
        val legacy = CaptureDevelopIntent(intent.recipe, intent.rendererContract)
        for (selected in listOf(null, legacy)) {
            val encoded = CaptureReceiptCodec.encode(receipt(selected))
            assertEquals(3, ByteBuffer.wrap(encoded).getInt(4))
            val restored = CaptureReceiptCodec.decode(encoded)
            assertEquals(selected, restored.developIntent)
            assertNull(restored.originalDevelopRequest(shared()))
        }
        val storage = Memory()
        val journal = CaptureJournal("capture", storage)
        journal.create(pair, intent)
        assertThrows(CaptureJournalConflict::class.java) {
            journal.create(pair, intent.copy(originalDeclaration = syntheticOriginalDeclaration.copy(maximumEv = 3.0)))
        }
        assertEquals(intent, journal.read()!!.developIntent)
    }

    @Test fun onlyNormallyReceivedAndMatchingSharedOnlySnapshotsAreEligible() {
        val valid = receipt()
        assertNotNull(valid.originalDevelopRequest(shared()))
        assertNull(receipt(phase = CaptureReceiptPhase.INTERRUPTED).originalDevelopRequest(shared()))
        assertNull(receipt(fp = null).originalDevelopRequest(shared()))
        assertNull(CaptureReceipt("capture", 1, pair, mapOf(RawOutput.DNG to 3L), CaptureReceiptPhase.CAPTURING,
            fingerprint, intent).originalDevelopRequest(shared()))
        assertNull(valid.originalDevelopRequest(shared(id = "other")))
        assertNull(valid.originalDevelopRequest(shared(fp = fingerprint.copy(sha256 = "f".repeat(64)))))
        for (phase in DngPublicationPhase.entries.filter { it != DngPublicationPhase.SHARED_ONLY })
            assertNull(valid.originalDevelopRequest(shared(phase)))
        val dngOnly = CaptureReceipt("capture", 3, setOf(RawOutput.DNG), mapOf(RawOutput.DNG to 3L),
            CaptureReceiptPhase.FILES_RECEIVED, fingerprint, intent)
        assertNotNull(dngOnly.originalDevelopRequest(shared())) // Require all expected formats, not a fabricated JPEG.
    }

    @Test fun maximumStrictMetadataFitsTheExistingBoundAndSurvivesFingerprintUpdates() {
        val policy = RenderPolicyVersion("p".repeat(64), Int.MAX_VALUE)
        val maximum = intent.copy(recipe = RenderRecipeSnapshot("s".repeat(64), "\u0000".repeat(1024)),
            rendererContract = "r".repeat(64), originalDeclaration = OriginalRenderDeclaration(policy, policy, policy, policy, -4.0, 4.0))
        val storage = Memory()
        val journal = CaptureJournal("c".repeat(64), storage)
        journal.create(pair, maximum)
        journal.received(0, RawOutput.DNG, 3)
        journal.recordOriginalFingerprint(1, fingerprint)
        journal.received(2, RawOutput.JPEG, 2)
        assertTrue(storage.bytes!!.size <= CaptureReceiptCodec.MAX_BYTES)
        assertEquals(maximum, journal.read()!!.developIntent)
    }

    @Test fun malformedStrictQualityOrMissingDeclarationIsHeldWithoutReadSideWrites() {
        val encoded = CaptureReceiptCodec.encode(receipt())
        val payload = encoded.copyOfRange(0, encoded.size - 32)
        val intentOffset = CaptureReceiptCodec.encode(receipt(null)).size - 33
        val declarationBytes = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { OriginalEvidenceCodec.writeDeclaration(it, intent.originalDeclaration) }
        }.size()
        val invalid = listOf(payload.clone().also { it[7] = 3 }, // v3 cannot contain strict code.
            payload.clone().also { it[intentOffset + 1] = 127 },
            payload.copyOfRange(0, payload.size - declarationBytes) + byteArrayOf(0))
        for (value in invalid) {
            val storage = Memory().apply { bytes = value + MessageDigest.getInstance("SHA-256").digest(value) }
            val before = storage.bytes!!.clone()
            val journal = CaptureJournal("capture", storage)
            assertThrows(IOException::class.java) { journal.read() }
            assertThrows(IOException::class.java) { journal.create(pair, intent) }
            assertEquals(0, storage.writes)
            assertArrayEquals(before, storage.bytes)
        }
    }
}
