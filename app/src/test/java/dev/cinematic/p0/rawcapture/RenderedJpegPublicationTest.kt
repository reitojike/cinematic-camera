package dev.cinematic.p0.rawcapture

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
        java.nio.ByteBuffer.wrap(unknown).putInt(4, 2) // A checksummed but unsupported future version.
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

    private inner class Fixture {
        var originalBytes = original.clone()
        var jpegBytes = jpeg.clone()
        var originalUnavailable = false
        var inspectorFails = false
        val request = RenderedJpegRequest("capture-1", 1,
            SharedOriginalReference("content://test/original/1", fingerprint(original), OriginalGrant.APP_OWNED),
            RenderRecipeSnapshot("test-recipe-v1", "{\"strength\":0.5}"),
            JpegOutputDescription(2, 2, 1, "sRGB", jpeg.size.toLong()))
        val completion = CompletedRender(request, "test-renderer-v1", RenderedResultKind.COMPLETE_STAGE_CLEAR,
            RenderedFaceState.VERIFIED_NONE, RenderedExposureState.SUPPORTED)
        var inspectedOutput = request.output
        val storage = MemoryStorage()
        val rows = FakeRows()
        fun journal() = JpegPublicationJournal("capture-1", 1, storage) { "a".repeat(32) }
        fun publisher(c: CompletedRender = completion,
            contracts: Set<RendererPublicationContract> = setOf(RendererPublicationContract("test-renderer-v1", "test-recipe-v1")),
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
