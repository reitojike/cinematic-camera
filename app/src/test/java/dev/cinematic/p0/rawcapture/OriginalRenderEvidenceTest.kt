package dev.cinematic.p0.rawcapture

import java.io.IOException
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class OriginalRenderEvidenceTest {
    private val fp = OriginalFingerprint(3, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    private val attempt = "a".repeat(32)
    private fun request() = AutoDevelopRequest("capture", 1,
        SharedOriginalReference("content://test/original/1", fp, OriginalGrant.APP_OWNED),
        RenderRecipeSnapshot("synthetic-original-v1", "fixed"), "synthetic-renderer-v1", 0, 0,
        AutoRenderQuality.FINAL_ORIGINAL_STRICT, RenderedFaceState.NOT_RUN, RenderedExposureState.UNKNOWN,
        AutoInputProfile.UNKNOWN, syntheticOriginalDeclaration)
    // Geometry/protocol metadata only; these tests do not decode or render a 24MP image.
    private fun receipt(request: AutoDevelopRequest = request()) = request.syntheticReceipt(attempt, 6000, 4000, fp.bytes)
    private fun record(request: AutoDevelopRequest = request(), observed: AutoRenderReceipt? = receipt(request)) =
        AutoDevelopRecord(request, 2, AutoJobPhase.RENDERED, attempt, 1, fp, observed = observed)

    @Test fun declarationHasNoInventedDimensionsOrSuccessAndCannotInheritThem() {
        val request = request()
        assertTrue(request.admissible)
        assertEquals(0, request.width); assertEquals(0, request.height)
        assertEquals(AutoInputProfile.UNKNOWN, request.profile)
        assertFalse(request.accepts(AutoRenderReceipt(request, receipt().output)))
        assertThrows(IllegalArgumentException::class.java) { request.copy(width = 1024, height = 1024) }
        assertThrows(IllegalArgumentException::class.java) { request.copy(face = RenderedFaceState.VERIFIED_NONE) }
        assertThrows(IllegalArgumentException::class.java) { request.copy(profile = AutoInputProfile.SUPPORTED) }
    }

    @Test fun inspectedCompletionKeepsAllEvidenceThroughBothJournalsAndPublicationPhases() {
        val record = record()
        val autoBytes = AutoDevelopCodec.encode(record)
        assertEquals(3, ByteBuffer.wrap(autoBytes).getInt(4))
        val restored = AutoDevelopCodec.decode(autoBytes)
        assertEquals(record, restored)
        val completion = restored.completed()
        assertEquals(RenderedResultKind.COMPLETE_ORIGINAL, completion.kind)
        assertEquals(fp, completion.original!!.outputFingerprint)
        completion.requirePublishable(setOf(request().contract))
        val target = JpegPublicationTarget("capture", 1, "b".repeat(32))
        for (phase in JpegPublicationPhase.entries) {
            val uri = if (phase in setOf(JpegPublicationPhase.PREPARED, JpegPublicationPhase.INSERTING)) null
                else "content://media/external_primary/images/media/10"
            val published = JpegPublicationRecord(target, completion, fp, 4, phase, uri)
            val bytes = JpegPublicationCodec.encode(published)
            assertEquals(2, ByteBuffer.wrap(bytes).getInt(4))
            assertEquals(published, JpegPublicationCodec.decode(bytes))
        }
    }

    @Test fun everyBindingPolicyAndObservedStateMustMatchRatherThanBeingFilledFromTheRequest() {
        val good = receipt()
        val original = good.original!!
        val binding = original.binding
        val changed = listOf(
            original.copy(binding = binding.copy(captureId = "other")),
            original.copy(binding = binding.copy(generation = 2)),
            original.copy(binding = binding.copy(sourceFingerprint = fp.copy(sha256 = "0".repeat(64)))),
            original.copy(binding = binding.copy(recipeSha256 = "0".repeat(64))),
            original.copy(binding = binding.copy(rendererContract = "other")),
            original.copy(inputPolicy = original.inputPolicy.copy(revision = 2)),
            original.copy(geometryPolicy = original.geometryPolicy.copy(revision = 2)),
            original.copy(faceDetector = original.faceDetector.copy(revision = 2)),
            original.copy(meteringPolicy = original.meteringPolicy.copy(revision = 2)),
            original.copy(profileState = AutoInputProfile.UNKNOWN),
            original.copy(appliedEv = 4.01),
            original.copy(geometry = original.geometry.copy(fullCoverage = false)),
        )
        changed.forEach { assertFalse(request().accepts(good.copy(original = it))) }
        assertFalse(request().accepts(good.copy(face = RenderedFaceState.NOT_RUN)))
        assertFalse(request().accepts(good.copy(exposure = RenderedExposureState.DIAGNOSTIC)))
        assertThrows(IllegalArgumentException::class.java) { original.copy(appliedEv = Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { RenderPolicyVersion("", 1) }
        assertThrows(IllegalArgumentException::class.java) { RenderPolicyVersion("profile", 0) }
    }

    @Test fun originalSizedJpegCannotHideAScaledInputAndPhysicalOrientationMustAgree() {
        val good = receipt()
        val observation = good.original!!
        assertFalse(request().accepts(good.copy(original = observation.copy(
            geometry = observation.geometry.copy(sampledWidth = 1024, sampledHeight = 683)))))
        val rotatedOutput = good.output.copy(width = 4000, height = 6000)
        val rotated = observation.copy(geometry = OriginalRasterGeometry(6000, 4000, 6, 4000, 6000, true))
        assertTrue(request().accepts(good.copy(output = rotatedOutput, original = rotated)))
        assertFalse(request().accepts(good.copy(original = rotated)))
    }

    @Test fun ownerAttemptAndIndependentlyInspectedFingerprintCannotBeSubstituted() {
        val good = receipt()
        val otherAttempt = good.copy(original = good.original!!.copy(binding = good.original.binding.copy(attemptId = "b".repeat(32))))
        assertThrows(IllegalArgumentException::class.java) { record(observed = otherAttempt) }
        val completion = record().completed()
        assertThrows(IOException::class.java) { completion.requireFingerprint(fp.copy(sha256 = "0".repeat(64))) }
        val storage = MemoryStorage()
        val journal = JpegPublicationJournal("capture", 1, storage)
        assertThrows(IOException::class.java) { journal.prepare(completion, fp.copy(sha256 = "0".repeat(64))) }
        assertNull(storage.bytes)
    }

    @Test fun missingStrictEvidenceIsReadableAndPreservedButCannotBecomeCompletedOrPublish() {
        val missing = record(observed = receipt().copy(original = null))
        assertEquals(missing, AutoDevelopCodec.decode(AutoDevelopCodec.encode(missing)))
        assertThrows(IOException::class.java) { missing.completed() }
        val storage = MemoryStorage().apply { bytes = AutoDevelopCodec.encode(missing) }
        val journal = AutoDevelopJournal("capture", 1, storage)
        assertThrows(CaptureJournalConflict::class.java) { journal.publishing(missing.revision) }
        assertEquals(missing, journal.read())
    }

    @Test fun strictPublicationCannotBeDowngradedToTheLegacyContractOrAnAbsentProof() {
        val completion = record().completed()
        val registered = setOf(request().contract)
        assertThrows(IOException::class.java) { completion.copy(original = null).requirePublishable(registered) }
        assertThrows(IOException::class.java) { completion.copy(kind = RenderedResultKind.COMPLETE_STAGE_CLEAR, original = null).requirePublishable(registered) }
        assertThrows(IOException::class.java) {
            completion.requirePublishable(setOf(RendererPublicationContract(completion.rendererContract, completion.request.recipe.schema)))
        }
        val missing = completion.copy(original = null)
        for (phase in listOf(JpegPublicationPhase.PUBLISHING, JpegPublicationPhase.PUBLISHED)) {
            val record = JpegPublicationRecord(JpegPublicationTarget("capture", 1, "b".repeat(32)), missing, fp, 5, phase,
                "content://media/external_primary/images/media/10")
            val restored = JpegPublicationCodec.decode(JpegPublicationCodec.encode(record))
            assertEquals(record, restored)
            assertEquals(record.uri, restored.uri)
            assertThrows(IOException::class.java) { restored.render.requirePublishable(registered) }
        }
    }

    @Test fun recipeBindingIsLengthDelimitedAndIncludesTheRecipeSchema() {
        assertNotEquals(OriginalAttemptBinding.recipeHash(RenderRecipeSnapshot("a", "bc")),
            OriginalAttemptBinding.recipeHash(RenderRecipeSnapshot("ab", "c")))
    }

    @Test fun aggregateLimitRejectsEvidenceWithoutTruncatingOrWritingAnyJournal() {
        val request = longRequest(1024)
        val record = record(request)
        assertThrows(AutoDevelopJournalTooLarge::class.java) { AutoDevelopCodec.encode(record) }
        val completion = record.completed()
        val storage = MemoryStorage()
        val journal = JpegPublicationJournal(request.captureId, 1, storage)
        assertThrows(JpegPublicationTooLarge::class.java) { journal.prepare(completion, fp) }
        assertNull(storage.bytes)
    }

    @Test fun publicationReservesTheFutureRowUriBeforeAnyDurableInsertIntent() {
        val prepared = (1..1024).asSequence().map { length ->
            val request = longRequest(length)
            JpegPublicationRecord(JpegPublicationTarget(request.captureId, 1, "b".repeat(32)), record(request).completed(), fp)
        }.first { record ->
            try { JpegPublicationCodec.encode(record).size in (JpegPublicationCodec.MAX_BYTES - 64)..JpegPublicationCodec.MAX_BYTES }
            catch (_: JpegPublicationTooLarge) { false }
        }
        val storage = MemoryStorage()
        val journal = JpegPublicationJournal(prepared.target.captureId, 1, storage)
        assertThrows(JpegPublicationTooLarge::class.java) { journal.prepare(prepared.render, fp) }
        assertNull(storage.bytes)
    }

    @Test fun unicodeUriCannotConsumeTheSpaceNeededByTheFutureAttemptIntent() {
        val request = request().nearAttemptCapacity()
        val queued = AutoDevelopRecord(request)
        assertTrue(AutoDevelopCodec.encode(queued).size <= AutoDevelopCodec.MAX_BYTES)
        assertThrows(AutoDevelopJournalTooLarge::class.java) {
            AutoDevelopCodec.encode(queued.copy(phase = AutoJobPhase.RENDERING, attemptId = attempt, attemptCount = 1))
        }
        val storage = MemoryStorage()
        assertThrows(AutoDevelopJournalTooLarge::class.java) { AutoDevelopJournal("capture", 1, storage).prepare(request) }
        assertNull(storage.bytes)
        val fitting = request.copy(recipe = request.recipe.copy(payload = request.recipe.payload.dropLast(1)))
        val journal = AutoDevelopJournal("capture", 1, storage)
        journal.prepare(fitting)
        val started = journal.start(0)
        assertEquals(AutoJobPhase.RENDERING, started.phase)
        assertTrue(AutoDevelopCodec.encode(started).size <= AutoDevelopCodec.MAX_BYTES)
        assertEquals(started, journal.read())
    }

    private fun longRequest(length: Int): AutoDevelopRequest {
        val id = RenderPolicyVersion("p".repeat(64), 1)
        return request().copy(captureId = "c".repeat(64), rendererContract = "r".repeat(64),
            source = request().source.copy(uri = "content://test/" + "x".repeat(1024 - "content://test/".length)),
            recipe = RenderRecipeSnapshot("s".repeat(64), "\u0000".repeat(length)),
            originalDeclaration = OriginalRenderDeclaration(id, id, id, id, -4.0, 4.0))
    }
    private class MemoryStorage : AtomicJournalStorage {
        var bytes: ByteArray? = null
        override fun read(maxBytes: Int) = bytes?.clone()
        override fun replace(contents: ByteArray) { bytes = contents.clone() }
    }
}
