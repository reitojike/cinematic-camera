package dev.cinematic.p0.rawcapture

import dev.cinematic.p0.raw.PhotoAdjustments
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class RenderedJpegPublicationTest {
    private val original = "synthetic original".toByteArray()
    private val jpeg = "synthetic completed JPEG".toByteArray() // Protocol fixture; native decoding is tested separately.
    private fun fingerprint(bytes: ByteArray) = OriginalFingerprint(bytes.size.toLong(),
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })

    @Test fun publishesOneCompletedResultAndNeverDeletesOrRecreatesSharedRows() {
        val f = Fixture()
        val done = f.publisher().advance()
        assertEquals(JpegPublicationPhase.PUBLISHED, done.phase)
        assertEquals(1, f.rows.inserts)
        assertEquals(1, f.rows.publishes)
        assertArrayEquals(jpeg, f.rows.bytes)
        assertArrayEquals(original, f.originalBytes)
        assertArrayEquals(jpeg, f.jpegBytes)
        f.rows.row = null // A user deletion is never repaired by reinserting.
        assertThrows(IOException::class.java) { f.publisher().advance() }
        assertEquals(1, f.rows.inserts)
        assertEquals(JpegPublicationPhase.PUBLISHED, f.journal().read()!!.phase)
    }

    @Test fun trialUnknownFaceAndDiagnosticExposureCannotCreateRowsOrJournals() {
        val f = Fixture()
        val invalid = listOf(
            f.completion.copy(kind = RenderedResultKind.TRIAL_PREVIEW),
            f.completion.copy(face = RenderedFaceState.NOT_RUN),
            f.completion.copy(face = RenderedFaceState.FAILED),
            f.completion.copy(exposure = RenderedExposureState.DIAGNOSTIC),
            f.completion.copy(exposure = RenderedExposureState.UNKNOWN),
            f.completion.copy(rendererContract = "unsupported"),
            f.completion.copy(request = f.request.copy(recipe = f.request.recipe.copy(schema = "unsupported"))),
        )
        invalid.forEach { assertThrows(IOException::class.java) { f.publisher(it).advance() } }
        assertNull(f.journal().read())
        assertEquals(0, f.rows.inserts)
        assertThrows(IOException::class.java) { f.publisher(contracts = emptySet()).advance() }
    }

    @Test fun corruptOutputAndWrongDimensionsOrientationColorOrSizeNeverCreateRows() {
        val f = Fixture()
        val expected = f.request.output
        for (observed in listOf(expected.copy(width = 3), expected.copy(orientation = 6),
            expected.copy(color = "Display P3"), expected.copy(bytes = expected.bytes - 1))) {
            f.inspectedOutput = observed
            assertThrows(IOException::class.java) { f.publisher().advance() }
        }
        f.inspectorFails = true
        assertThrows(IOException::class.java) { f.publisher().advance() }
        assertEquals(0, f.rows.inserts)
        assertNull(f.journal().read())
    }

    @Test fun everyLedgerBoundarySurvivesBeforeAfterAndReadbackFailuresWithoutDuplicateInsert() {
        for (phase in JpegPublicationPhase.entries.filter { it != JpegPublicationPhase.UNKNOWN }) {
            for (mode in listOf("before", "after", "readback")) {
                val f = Fixture()
                f.storage.faultPhase = phase
                f.storage.fault = mode
                assertThrows(IOException::class.java) { f.publisher().advance() }
                assertArrayEquals(original, f.originalBytes)
                assertArrayEquals(jpeg, f.jpegBytes)
                f.storage.fault = "none"
                f.storage.failRead = false
                val result = f.publisher().advance()
                // PREPARED->INSERTING commit can be durable before insert: absence stays UNKNOWN.
                if (phase == JpegPublicationPhase.INSERTING && mode != "before") {
                    assertEquals(JpegPublicationPhase.UNKNOWN, result.phase)
                    assertEquals(0, f.rows.inserts)
                } else {
                    assertEquals(JpegPublicationPhase.PUBLISHED, result.phase)
                    assertEquals(1, f.rows.inserts)
                    assertEquals(1, f.rows.publishes)
                }
            }
        }
    }

    @Test fun lostInsertAcknowledgementAndUriCommitReconcileSameOwnedPendingRow() {
        val f = Fixture()
        f.rows.afterInsert = { throw SimulatedDeath() }
        assertThrows(SimulatedDeath::class.java) { f.publisher().advance() }
        assertEquals(JpegPublicationPhase.INSERTING, f.journal().read()!!.phase)
        f.rows.afterInsert = {}
        assertEquals(JpegPublicationPhase.PUBLISHED, f.publisher().advance().phase)
        assertEquals(1, f.rows.inserts)
    }

    @Test fun ambiguousMissingOrForeignInsertRecoveryStopsInsteadOfCreatingAnotherRow() {
        for (mode in listOf("missing", "ambiguous", "foreign")) {
            val f = Fixture()
            f.rows.afterInsert = { throw SimulatedDeath() }
            assertThrows(SimulatedDeath::class.java) { f.publisher().advance() }
            f.rows.afterInsert = {}
            when (mode) {
                "missing" -> f.rows.row = null
                "ambiguous" -> f.rows.duplicate = true
                "foreign" -> f.rows.row = f.rows.row!!.copy(ownedByApp = false)
            }
            assertEquals(JpegPublicationPhase.UNKNOWN, f.publisher().advance().phase)
            assertEquals(JpegPublicationPhase.UNKNOWN, f.publisher().advance().phase)
            assertEquals(1, f.rows.inserts)
            assertEquals(0, f.rows.outputs)
            assertEquals(0, f.rows.publishes)
        }
    }

    @Test fun partialWriteAndCorruptReadbackCanRetryOnlySamePendingRow() {
        for (fault in listOf("partial", "corrupt")) {
            val f = Fixture()
            f.rows.writeFault = fault
            assertThrows(IOException::class.java) { f.publisher().advance() }
            assertEquals(0, f.rows.publishes)
            f.rows.writeFault = "none"
            assertEquals(JpegPublicationPhase.PUBLISHED, f.publisher().advance().phase)
            assertEquals(1, f.rows.inserts)
            assertEquals(2, f.rows.outputs)
        }
    }

    @Test fun lostPublishAcknowledgementAdoptsSamePublicRowWithoutAnotherUpdate() {
        val f = Fixture()
        f.rows.publishFault = "after"
        assertThrows(IOException::class.java) { f.publisher().advance() }
        assertFalse(f.rows.row!!.pending!!)
        assertEquals(JpegPublicationPhase.PUBLISHING, f.journal().read()!!.phase)
        f.rows.publishFault = "none"
        f.originalUnavailable = true // Already-public reconciliation never rewrites or requires source access.
        assertEquals(JpegPublicationPhase.PUBLISHED, f.publisher().advance().phase)
        assertEquals(1, f.rows.publishes)
        assertEquals(1, f.rows.outputs)
        assertEquals(1, f.rows.inserts)
    }

    @Test fun changedOrUnavailableOriginalStopsBothInitiallyAndImmediatelyBeforePublish() {
        for (whenChanged in listOf("initial", "beforePublish")) for (missing in listOf(false, true)) {
            val f = Fixture()
            val change = { if (missing) f.originalUnavailable = true else f.originalBytes = "changed original!!".toByteArray() }
            if (whenChanged == "initial") change() else f.storage.afterCommit = {
                if (it.phase == JpegPublicationPhase.PUBLISHING) change()
            }
            assertThrows(IOException::class.java) { f.publisher().advance() }
            assertEquals(0, f.rows.publishes)
            assertEquals(if (whenChanged == "initial") 0 else 1, f.rows.inserts)
        }
    }

    @Test fun sameOperationRejectsRecipeSourceOutputAndGenerationChanges() {
        val f = Fixture()
        f.rows.publishFault = "before"
        assertThrows(IOException::class.java) { f.publisher().advance() }
        val otherRequests = listOf(
            f.request.copy(recipe = f.request.recipe.copy(payload = "different")),
            f.request.copy(source = f.request.source.copy(uri = "content://test/original/2")),
            f.request.copy(output = f.request.output.copy(width = 3)),
            f.request.copy(generation = 2),
        )
        otherRequests.forEach { changed ->
            assertThrows(IOException::class.java) { f.publisher(f.completion.copy(request = changed)).advance() }
        }
        assertEquals(1, f.rows.inserts)
        assertEquals(1, f.rows.outputs)
    }

    @Test fun staleGenerationAndStopBlockBeforeInsertAndBeforePublication() {
        for (phase in listOf(JpegPublicationPhase.PREPARED, JpegPublicationPhase.PUBLISHING)) {
            val f = Fixture()
            var current = true
            f.storage.afterCommit = { if (it.phase == phase) current = false }
            assertThrows(IOException::class.java) { f.publisher(isCurrent = { current }).advance() }
            assertEquals(0, f.rows.publishes)
            assertEquals(if (phase == JpegPublicationPhase.PREPARED) 0 else 1, f.rows.inserts)
        }
        val f = Fixture()
        assertThrows(IOException::class.java) { f.publisher().advance { true } }
        assertNull(f.journal().read())
    }

    @Test fun failedCloseRetainsResourcesAndPreventsMoreIoUntilActualCloseSucceeds() {
        val f = Fixture()
        f.rows.closeFails = true
        val p = f.publisher()
        assertThrows(IOException::class.java) { p.advance() }
        assertTrue(p.hasUnreleasedResources)
        val outputs = f.rows.outputs
        assertThrows(IOException::class.java) { p.advance() }
        assertEquals(outputs, f.rows.outputs)
        assertEquals(0, f.rows.publishes)
        f.rows.closeFails = false
        assertEquals(JpegPublicationPhase.PUBLISHED, p.advance().phase)
        assertFalse(p.hasUnreleasedResources)
    }

    @Test fun publishedAndCompletePendingContentChangesAreNotOverwritten() {
        for (phase in listOf(JpegPublicationPhase.BYTES_COMPLETE, JpegPublicationPhase.PUBLISHED)) {
            val f = Fixture()
            f.storage.afterCommit = { if (it.phase == phase) f.rows.bytes = "changed".toByteArray() }
            if (phase == JpegPublicationPhase.PUBLISHED) {
                assertThrows(IOException::class.java) { f.publisher().advance() }
                assertEquals(phase, f.journal().read()!!.phase)
            } else assertEquals(JpegPublicationPhase.UNKNOWN, f.publisher().advance().phase)
            assertEquals(1, f.rows.outputs)
            assertEquals(if (phase == JpegPublicationPhase.PUBLISHED) 1 else 0, f.rows.publishes)
        }
    }

    @Test fun checksumUnknownSchemaForeignOwnerAndCasConflictsPreserveLedger() {
        val f = Fixture()
        f.publisher().advance()
        val encoded = f.storage.bytes!!.clone()
        f.storage.bytes!![9] = (f.storage.bytes!![9].toInt() xor 1).toByte()
        assertThrows(IOException::class.java) { f.journal().read() }
        assertThrows(IOException::class.java) { f.publisher().advance() }
        f.storage.bytes = encoded
        val unknown = encoded.copyOfRange(0, encoded.size - 32)
        java.nio.ByteBuffer.wrap(unknown).putInt(4, 4) // A checksummed but unsupported future version.
        f.storage.bytes = unknown + MessageDigest.getInstance("SHA-256").digest(unknown)
        assertThrows(IOException::class.java) { f.journal().read() }
        assertThrows(IOException::class.java) { f.publisher().advance() }
        f.storage.bytes = encoded
        assertThrows(IOException::class.java) { JpegPublicationJournal("other", 1, f.storage).read() }
        assertThrows(CaptureJournalConflict::class.java) {
            f.journal().advance(0, JpegPublicationPhase.PREPARED, JpegPublicationPhase.INSERTING)
        }
        assertEquals(1, f.rows.inserts)
    }

    @Test fun journalRoundTripsFullRecipeSourceMetadataAndReceiptWithoutReplacingToken() {
        val f = Fixture()
        val render = f.completion.copy(request = f.request.copy(recipe = RenderRecipeSnapshot("test-recipe-v1", "{\"label\":\"合成\"}")))
        val record = f.journal().prepare(render, fingerprint(jpeg))
        assertEquals(record, JpegPublicationCodec.decode(JpegPublicationCodec.encode(record)))
        assertEquals(record, f.journal().prepare(render, fingerprint(jpeg)))
        assertEquals("capture-1:RENDERED:1", record.target.operationId)
        assertThrows(CaptureJournalConflict::class.java) { f.journal().prepare(render, fingerprint("different".toByteArray())) }
    }

    @Test fun strictEvidenceAndSameRowSurviveEveryCommitBoundaryAndAcknowledgementLoss() {
        for (phase in JpegPublicationPhase.entries.filter { it != JpegPublicationPhase.UNKNOWN })
        for (mode in listOf("before", "after", "readback")) {
            val f = Fixture(strict = true)
            f.storage.faultPhase = phase; f.storage.fault = mode
            assertThrows(IOException::class.java) { f.publisher().advance() }
            val knownUri = f.rows.row?.uri
            f.storage.fault = "none"; f.storage.failRead = false
            val restored = f.publisher().advance()
            val unknownInsert = phase == JpegPublicationPhase.INSERTING && mode != "before"
            assertEquals(if (unknownInsert) JpegPublicationPhase.UNKNOWN else JpegPublicationPhase.PUBLISHED, restored.phase)
            assertEquals(f.completion.original, restored.render.original)
            assertEquals(fingerprint(jpeg), restored.fingerprint)
            if (knownUri != null) assertEquals(knownUri, restored.uri)
            assertEquals(if (unknownInsert) 0 else 1, f.rows.inserts)
            assertArrayEquals(original, f.originalBytes)
            assertEquals(restored, f.publisher().advance())
        }
    }

    @Test fun strictProofMissingUnsupportedOrDifferentFromActualBytesCannotCreateRows() {
        val f = Fixture(strict = true)
        val proof = f.completion.original!!
        val invalid = listOf(
            f.completion.copy(original = null),
            f.completion.copy(original = proof.copy(observation = proof.observation.copy(profileState = AutoInputProfile.UNKNOWN))),
            f.completion.copy(original = proof.copy(observation = proof.observation.copy(appliedEv = 5.0))),
            f.completion.copy(original = proof.copy(outputFingerprint = proof.outputFingerprint.copy(sha256 = "0".repeat(64)))),
        )
        invalid.forEach { assertThrows(IOException::class.java) { f.publisher(it).advance() } }
        assertNull(f.journal().read())
        assertEquals(0, f.rows.inserts)
        assertArrayEquals(original, f.originalBytes)
    }

    @Test fun manualTrialRequiresDedicatedEnrollmentAndCannotImpersonateCompletedRender() {
        val f = Fixture(trial = true)
        val trial = f.manualTrial
        assertEquals(RenderedResultKind.MANUAL_ORIGINAL_TRIAL, trial.kind)
        assertEquals(RenderedFaceState.NOT_RUN, trial.face)
        assertEquals(RenderedExposureState.DIAGNOSTIC, trial.exposure)
        assertEquals(AutoInputProfile.UNKNOWN, trial.profile)
        assertNull(trial.original)
        for (contracts in listOf(emptySet(), setOf(RendererPublicationContract(trial.rendererContract, "other-schema")))) {
            assertThrows(IOException::class.java) { f.publisher(contracts = contracts).advance() }
        }
        val fake = CompletedRender(trial.request, trial.rendererContract, trial.kind,
            RenderedFaceState.VERIFIED_NONE, RenderedExposureState.SUPPORTED)
        assertThrows(IOException::class.java) { f.publisher(fake).advance() }
        assertNull(f.journal().read())
        assertEquals(0, f.rows.inserts)
        val done = f.publisher().advance()
        assertEquals(JpegPublicationPhase.PUBLISHED, done.phase)
        assertTrue(done.target.displayName.startsWith("CINEMATIC_TRIAL24_"))
        assertEquals(trial, done.render)
    }

    @Test fun manualTrialFreezesCanonicalRecipeOriginalGeometryStrengthAndOutputHash() {
        val f = Fixture(trial = true)
        val t = f.manualTrial
        val invalid = listOf<() -> Unit>(
            { t.copy(request = t.request.copy(captureId = "ordinary")) },
            { t.copy(request = t.request.copy(recipe = t.request.recipe.copy(payload = "strength=0.5"))) },
            { t.copy(appliedStrength = .75) },
            { t.copy(appliedStrength = Double.NaN) },
            { t.copy(externalEv = Double.POSITIVE_INFINITY) },
            { t.copy(geometry = t.geometry.copy(fullCoverage = false)) },
            { t.copy(geometry = t.geometry.copy(sampledWidth = 1)) },
            { t.copy(geometry = t.geometry.copy(sourceOrientation = 6, sourceWidth = 3)) },
            { t.copy(outputFingerprint = t.outputFingerprint.copy(bytes = t.outputFingerprint.bytes + 1)) },
            { t.copy(request = t.request.copy(output = t.request.output.copy(width = 6000, height = 4001)),
                geometry = OriginalRasterGeometry(6000, 4001, 1, 6000, 4001, true)) },
        )
        invalid.forEach { assertThrows(IllegalArgumentException::class.java) { it() } }
        val wrongHash = t.copy(outputFingerprint = t.outputFingerprint.copy(sha256 = "0".repeat(64)))
        assertThrows(IOException::class.java) { f.publisher(wrongHash).advance() }
        assertNull(f.journal().read())
        assertEquals(0, f.rows.inserts)
    }

    @Test fun manualTrialV3PreservesEvidenceAcrossEveryCommitBoundary() {
        for (phase in JpegPublicationPhase.entries.filter { it != JpegPublicationPhase.UNKNOWN })
        for (mode in listOf("before", "after", "readback")) {
            val f = Fixture(trial = true)
            f.storage.faultPhase = phase; f.storage.fault = mode
            assertThrows(IOException::class.java) { f.publisher().advance() }
            f.storage.fault = "none"; f.storage.failRead = false
            val record = f.publisher().advance()
            val unknownInsert = phase == JpegPublicationPhase.INSERTING && mode != "before"
            assertEquals(if (unknownInsert) JpegPublicationPhase.UNKNOWN else JpegPublicationPhase.PUBLISHED, record.phase)
            assertEquals(f.manualTrial, record.render)
            assertEquals(fingerprint(jpeg), record.fingerprint)
            assertEquals(3, java.nio.ByteBuffer.wrap(f.storage.bytes!!).getInt(4))
            assertEquals(record, JpegPublicationCodec.decode(JpegPublicationCodec.encode(record)))
            assertEquals(record, f.publisher().advance())
            assertEquals(if (unknownInsert) 0 else 1, f.rows.inserts)
        }
    }

    @Test fun manualTrialRecipeAndV3ProofBindWarmthAndExposureWithoutChangingDiagnosticStates() {
        val edits = PhotoAdjustments(-42.5, 1.25)
        val f = Fixture(trial = true, adjustments = edits)
        val trial = f.manualTrial
        assertEquals(edits, ManualOriginalTrialRender.adjustmentsFromRecipe(trial.request.recipe, trial.appliedStrength))
        assertThrows(IllegalArgumentException::class.java) { trial.copy(adjustments = edits.copy(warmth = -42.0)) }
        assertThrows(IllegalArgumentException::class.java) { trial.copy(adjustments = edits.copy(exposureEv = 1.0)) }
        val recipe = trial.request.recipe
        for (payload in listOf(recipe.payload + ";warmth=-0x1.54p5", recipe.payload.replace("exposureEv=0x1.4p0", "exposureEv=1.25"),
            recipe.payload.replace(PhotoAdjustments.VERSION, "unknown-policy"), recipe.payload.substringBefore(";adjust="))) {
            assertThrows(IOException::class.java) { ManualOriginalTrialRender.adjustmentsFromRecipe(recipe.copy(payload = payload), .5) }
        }
        val done = f.publisher().advance()
        val recovered = JpegPublicationCodec.decode(JpegPublicationCodec.encode(done)).render as ManualOriginalTrialRender
        assertEquals(trial, recovered)
        assertEquals(edits, recovered.adjustments)
        assertEquals(RenderedFaceState.NOT_RUN, recovered.face)
        assertEquals(RenderedExposureState.DIAGNOSTIC, recovered.exposure)
        assertEquals(AutoInputProfile.UNKNOWN, recovered.profile)
        assertEquals(1, f.rows.inserts)
        assertEquals(done, f.publisher().advance())
    }

    @Test fun manualTrialLostPublishReplyAndChangedOriginalUseExistingRecoveryRules() {
        val f = Fixture(trial = true)
        f.rows.publishFault = "after"
        assertThrows(IOException::class.java) { f.publisher().advance() }
        f.rows.publishFault = "none"; f.originalUnavailable = true
        assertEquals(JpegPublicationPhase.PUBLISHED, f.publisher().advance().phase)
        assertEquals(1, f.rows.inserts)
        assertEquals(1, f.rows.publishes)
        for (phase in listOf(JpegPublicationPhase.PREPARED, JpegPublicationPhase.PUBLISHING)) {
            val changed = Fixture(trial = true)
            changed.storage.afterCommit = { if (it.phase == phase) changed.originalBytes = "changed".toByteArray() }
            assertThrows(IOException::class.java) { changed.publisher().advance() }
            assertEquals(0, changed.rows.publishes)
        }
    }

    /** Manual-save recovery/reopen scenarios from d4be7fd, using the independent original trial proof. */
    @Test fun manualTrialReopensOnlyTheSameVerifiedPublicBytesWithoutDuplicatingRows() {
        val f = Fixture(trial = true)
        val owner = f.publisher()
        f.rows.publishFault = "after"
        assertThrows(IOException::class.java) { owner.advance() }
        val insertedUri = f.rows.row!!.uri
        f.rows.publishFault = "none"
        val saved = owner.advance()
        assertEquals(JpegPublicationPhase.PUBLISHED, saved.phase)
        assertEquals(insertedUri, saved.uri)
        assertTrue(saved.target.displayName.startsWith("CINEMATIC_TRIAL24_"))
        assertEquals(f.manualTrial, saved.render)
        assertArrayEquals(jpeg, f.rows.bytes)
        assertArrayEquals(original, f.originalBytes)
        assertEquals(saved.uri, owner.advance().uri)
        // A recreated adapter must also reconcile the persisted receipt, never create a second row.
        assertEquals(saved.uri, f.publisher().advance().uri)
        f.rows.bytes = jpeg.clone().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertThrows(IOException::class.java) { owner.advance() }
        assertThrows(IOException::class.java) { f.publisher().advance() }
        assertEquals(JpegPublicationPhase.PUBLISHED, f.journal().read()!!.phase)
        assertEquals(1, f.rows.inserts)
        assertEquals(1, f.rows.outputs)
        assertEquals(1, f.rows.publishes)
    }

    @Test fun manualTrialRejectsSameLengthChangedProducerBytesBeforeAllocatingAnyRow() {
        val f = Fixture(trial = true)
        f.jpegBytes = jpeg.clone().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertEquals(f.manualTrial.outputFingerprint.bytes, f.jpegBytes.size.toLong())
        assertThrows(IOException::class.java) { f.publisher().advance() }
        assertNull(f.journal().read())
        assertEquals(0, f.rows.inserts)
        assertEquals(0, f.rows.outputs)
        assertEquals(0, f.rows.publishes)
    }

    @Test fun manualTrialPersistedReceiptCannotBeReusedForDifferentStrengthSourceOrGeneration() {
        val f = Fixture(trial = true)
        val saved = f.publisher().advance()
        val trial = f.manualTrial
        val changed = listOf(
            trial.copy(request = trial.request.copy(recipe = ManualOriginalTrialRender.recipe(.75)), appliedStrength = .75),
            trial.copy(request = trial.request.copy(source = trial.request.source.copy(uri = "content://test/original/2"))),
            trial.copy(request = trial.request.copy(generation = trial.request.generation + 1)),
            trial.copy(outputFingerprint = fingerprint(jpeg.clone().also { it[0] = (it[0].toInt() xor 1).toByte() })),
        )
        changed.forEach { assertThrows(CaptureJournalConflict::class.java) { f.publisher(it).advance() } }
        assertEquals(saved, f.journal().read())
        assertEquals(1, f.rows.inserts)
        assertEquals(1, f.rows.outputs)
        assertEquals(1, f.rows.publishes)
    }

    @Test fun v1AndV2EncodingRemainByteCompatible() {
        for (strict in listOf(false, true)) {
            val f = Fixture(strict = strict)
            val record = f.journal().prepare(f.completion, fingerprint(jpeg))
            assertArrayEquals(legacyEncoding(record, if (strict) 2 else 1), JpegPublicationCodec.encode(record))
        }
    }

    /** Frozen v1/v2 field order; adding the manual variant must never migrate these existing bytes. */
    private fun legacyEncoding(record: JpegPublicationRecord, version: Int): ByteArray {
        val buffer = ByteArrayOutputStream()
        java.io.DataOutputStream(buffer).use { out ->
            fun fp(value: OriginalFingerprint) { out.writeLong(value.bytes); out.writeUTF(value.sha256) }
            val r = record.render
            val q = r.request
            out.writeInt(0x43434a50); out.writeInt(version)
            out.writeUTF(record.target.captureId); out.writeLong(record.target.generation); out.writeUTF(record.target.token)
            out.writeLong(record.revision); out.writeInt(record.phase.ordinal)
            out.writeBoolean(record.uri != null); record.uri?.let(out::writeUTF)
            out.writeUTF(q.source.uri); out.writeInt(q.source.grant.ordinal); fp(q.source.fingerprint)
            out.writeUTF(q.recipe.schema); out.writeUTF(q.recipe.payload)
            out.writeInt(q.output.width); out.writeInt(q.output.height); out.writeInt(q.output.orientation)
            out.writeUTF(q.output.color); out.writeLong(q.output.bytes)
            out.writeUTF(r.rendererContract); out.writeInt(r.kind.ordinal); out.writeInt(r.face.ordinal); out.writeInt(r.exposure.ordinal)
            fp(record.fingerprint)
            if (version == 2) OriginalEvidenceCodec.writeCompletion(out, r.original)
        }
        val payload = buffer.toByteArray()
        return payload + MessageDigest.getInstance("SHA-256").digest(payload)
    }

    private inner class Fixture(strict: Boolean = false, private val trial: Boolean = false,
        private val adjustments: PhotoAdjustments = PhotoAdjustments.NONE) {
        var originalBytes = original.clone()
        var jpegBytes = jpeg.clone()
        var originalUnavailable = false
        var inspectorFails = false
        val request = RenderedJpegRequest(if (trial) "TRIAL24_capture-1" else "capture-1", 1,
            SharedOriginalReference("content://test/original/1", fingerprint(original), OriginalGrant.APP_OWNED),
            if (trial) ManualOriginalTrialRender.recipe(.5, adjustments) else RenderRecipeSnapshot("test-recipe-v1", "{\"strength\":0.5}"),
            JpegOutputDescription(2, 2, 1, "sRGB", jpeg.size.toLong()))
        val completion = if (strict) {
            val auto = AutoDevelopRequest(request.captureId, request.generation, request.source, request.recipe,
                "test-renderer-v1", 2, 2, AutoRenderQuality.FINAL_ORIGINAL, RenderedFaceState.VERIFIED_NONE,
                RenderedExposureState.SUPPORTED, AutoInputProfile.SUPPORTED).forStrictOriginal()
            AutoDevelopRecord(auto, 2, AutoJobPhase.RENDERED, "a".repeat(32), 1, fingerprint(jpeg),
                observed = auto.syntheticReceipt("a".repeat(32), 2, 2, jpeg.size.toLong())).completed()
        } else CompletedRender(request, "test-renderer-v1", RenderedResultKind.COMPLETE_STAGE_CLEAR,
            RenderedFaceState.VERIFIED_NONE, RenderedExposureState.SUPPORTED)
        val manualTrial get() = ManualOriginalTrialRender(request, OriginalRasterGeometry(2, 2, 1, 2, 2, true),
            fingerprint(jpeg), .5, -.25, adjustments)
        var inspectedOutput = request.output
        val storage = MemoryStorage()
        val rows = FakeRows()
        fun journal() = JpegPublicationJournal(request.captureId, 1, storage) { "a".repeat(32) }
        fun publisher(c: JpegPublicationRender = if (trial) manualTrial else completion,
            contracts: Set<RendererPublicationContract> = if (trial) setOf(ManualOriginalTrialRender.CONTRACT) else
                setOf(RendererPublicationContract("test-renderer-v1", "test-recipe-v1", completion.original?.declaration)),
            isCurrent: () -> Boolean = { true }) = RecoverablePhotoPublisher(journal(), rows, c, contracts,
                object : RenderedJpegInput {
                    override fun openJpeg() = ByteArrayInputStream(jpegBytes)
                    override fun openOriginal(reference: SharedOriginalReference): InputStream {
                        if (originalUnavailable) throw IOException("Source unavailable")
                        return ByteArrayInputStream(originalBytes)
                    }
                }, JpegInspector { open, _ ->
                    if (inspectorFails) throw IOException("Invalid JPEG")
                    JpegInspection(inspectedOutput, fingerprint(open().use { it.readBytes() }))
                }, isCurrent)
    }

    private class SimulatedDeath : Error()
    private class MemoryStorage : AtomicJournalStorage {
        var bytes: ByteArray? = null
        var faultPhase: JpegPublicationPhase? = null
        var fault = "none"
        var failRead = false
        var afterCommit: (JpegPublicationRecord) -> Unit = {}
        override fun read(maxBytes: Int): ByteArray? {
            if (failRead) throw IOException("readback fault")
            return bytes?.clone()
        }
        override fun replace(contents: ByteArray) {
            val record = JpegPublicationCodec.decode(contents)
            if (record.phase == faultPhase && fault == "before") throw IOException("before commit")
            bytes = contents.clone()
            afterCommit(record)
            if (record.phase == faultPhase) {
                if (fault == "readback") failRead = true
                if (fault == "after") throw IOException("after commit")
            }
        }
    }

    private class FakeRows : RenderedJpegRows {
        var row: DngPendingRow? = null // Shared MediaStore row metadata; no DNG write capability.
        var bytes = byteArrayOf()
        var inserts = 0
        var outputs = 0
        var publishes = 0
        var duplicate = false
        var afterInsert: () -> Unit = {}
        var writeFault = "none"
        var publishFault = "none"
        var closeFails = false
        override fun insert(target: JpegPublicationTarget): String {
            inserts++
            row = DngPendingRow("content://media/external_primary/images/media/10", target.displayName,
                target.volume, target.relativePath, target.mimeType, true, true)
            afterInsert()
            return row!!.uri
        }
        override fun find(target: JpegPublicationTarget) = listOfNotNull(row).let { if (duplicate) it + it else it }
        override fun inspect(uri: String) = row?.takeIf { it.uri == uri }
        override fun openRead(target: JpegPublicationTarget, uri: String, pending: Boolean): InputStream {
            check(target.accepts(requireNotNull(row), pending))
            return ByteArrayInputStream(bytes)
        }
        override fun openOutput(target: JpegPublicationTarget, uri: String): OutputStream {
            check(target.accepts(requireNotNull(row)))
            outputs++
            return object : ByteArrayOutputStream() {
                override fun write(b: ByteArray, off: Int, len: Int) {
                    if (writeFault == "partial") { super.write(b, off, 1); throw IOException("write fault") }
                    super.write(b, off, len)
                }
                override fun close() {
                    bytes = if (writeFault == "corrupt") "bad".toByteArray() else toByteArray()
                    if (closeFails) throw IOException("close fault")
                }
            }
        }
        override fun publish(target: JpegPublicationTarget, uri: String) {
            if (publishFault == "before") throw IOException("before publish")
            publishes++
            row = row!!.copy(pending = false)
            if (publishFault == "after") throw IOException("ack lost")
        }
    }
}
