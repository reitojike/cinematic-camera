package dev.cinematic.p0.rawcapture

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DngHandoffTest {
    @get:Rule val temporary = TemporaryFolder()
    private val id = "capture-1"
    private val original = "abc".toByteArray() // Synthetic bytes, not a decodable DNG.
    private val jpeg = "jpg".toByteArray()
    private val fingerprint = OriginalFingerprint(3, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    private var fixtureIndex = 0

    @Test fun successPublishesOneVerifiedOriginalAndReleasesOnlyTemporaryDng() {
        val f = Fixture()
        val result = f.coordinator.advance()
        assertEquals(DngPublicationPhase.SHARED_ONLY, result.phase)
        assertFalse(f.originalFile.exists())
        assertArrayEquals(jpeg, f.jpegFile.readBytes())
        assertArrayEquals(original, f.rows.bytes)
        assertEquals(false, f.rows.row!!.pending)
        assertEquals(1, f.rows.inserts)
        assertEquals(1, f.rows.publishes)
        assertEquals(1, f.deleted)
        assertEquals(fingerprint, f.store.readReceipt()!!.originalFingerprint)
        assertFalse(f.coordinator.hasUnreleasedResources)
        val calls = f.rows.calls
        f.rows.row = null // Later user deletion is not an invitation to recreate the row.
        assertEquals(result, f.coordinator.advance())
        assertEquals(calls, f.rows.calls)
    }

    @Test fun eachDurableBoundaryResumesSameUriAfterBeforeAfterAndReadbackFailures() {
        val phases = listOf(
            DngPublicationPhase.COPYING, DngPublicationPhase.BYTES_COMPLETE, DngPublicationPhase.PUBLISHING,
            DngPublicationPhase.PUBLISHED, DngPublicationPhase.SHARED_READY,
            DngPublicationPhase.LOCAL_RELEASE_PENDING, DngPublicationPhase.SHARED_ONLY,
        )
        for (phase in phases) for (mode in listOf("before", "after", "readback")) {
            val f = Fixture()
            f.publicationStorage.failPhase = phase
            f.publicationStorage.fault = mode
            assertThrows(IOException::class.java) { f.coordinator.advance() }
            f.assertAtLeastOneOriginal()
            assertArrayEquals(jpeg, f.jpegFile.readBytes())
            f.publicationStorage.fault = "none"
            f.publicationStorage.readFails = false
            f.recreate()
            assertEquals(DngPublicationPhase.SHARED_ONLY, f.coordinator.advance().phase)
            assertEquals(1, f.rows.inserts)
            assertEquals(1, f.rows.publishes)
            assertEquals(1, f.deleted)
            assertArrayEquals(jpeg, f.jpegFile.readBytes())
        }
    }

    @Test fun partialWriteAndReadbackCorruptionNeverPublishAndCanRecopySamePendingRow() {
        for (fault in listOf("write", "corrupt")) {
            val f = Fixture()
            f.rows.writeFault = fault
            assertThrows(IOException::class.java) { f.coordinator.advance() }
            assertEquals(DngPublicationPhase.COPYING, f.journal.read()!!.phase)
            assertEquals(0, f.rows.publishes)
            assertArrayEquals(original, f.originalFile.readBytes())
            f.rows.writeFault = "none"
            assertEquals(DngPublicationPhase.SHARED_ONLY, f.coordinator.advance().phase)
            assertEquals(1, f.rows.inserts)
            assertEquals(2, f.rows.outputs)
        }
    }

    @Test fun publishAcknowledgementLossUsesPublishedRowWithoutUpdatingOrCopyingAgain() {
        val f = Fixture()
        f.rows.publishFault = "after"
        assertThrows(IOException::class.java) { f.coordinator.advance() }
        assertEquals(DngPublicationPhase.PUBLISHING, f.journal.read()!!.phase)
        assertEquals(false, f.rows.row!!.pending)
        assertTrue(f.originalFile.exists())
        f.rows.publishFault = "none"
        f.recreate()
        assertEquals(DngPublicationPhase.SHARED_ONLY, f.coordinator.advance().phase)
        assertEquals(1, f.rows.publishes)
        assertEquals(1, f.rows.outputs)
        assertEquals(1, f.rows.inserts)
    }

    @Test fun failedAndSilentPendingUpdatesRemainUnconfirmedAndKeepLocalOriginal() {
        for (fault in listOf("before", "silent")) {
            val f = Fixture()
            f.rows.publishFault = fault
            assertThrows(IOException::class.java) { f.coordinator.advance() }
            assertEquals(DngPublicationPhase.PUBLISHING, f.journal.read()!!.phase)
            assertTrue(f.originalFile.exists())
            assertEquals(true, f.rows.row!!.pending)
            f.rows.publishFault = "none"
            assertEquals(DngPublicationPhase.SHARED_ONLY, f.coordinator.advance().phase)
            assertEquals(1, f.rows.inserts)
        }
    }

    @Test fun sourceChangeIsRejectedBeforeCreatingSharedRow() {
        val f = Fixture()
        f.originalFile.writeBytes("abd".toByteArray())
        assertThrows(DngContentChanged::class.java) { f.coordinator.advance() }
        assertEquals(0, f.rows.inserts)
        assertArrayEquals("abd".toByteArray(), f.originalFile.readBytes())
        assertArrayEquals(jpeg, f.jpegFile.readBytes())
    }

    @Test fun changedCompletePendingRowIsNotPublishedOrOverwritten() {
        val f = Fixture()
        f.publicationStorage.onCommit = { record ->
            if (record.phase == DngPublicationPhase.BYTES_COMPLETE) f.rows.bytes = "abd".toByteArray()
        }
        assertEquals(DngPublicationPhase.UNKNOWN, f.coordinator.advance().phase)
        assertEquals(0, f.rows.publishes)
        assertEquals(1, f.rows.outputs)
        assertArrayEquals(original, f.originalFile.readBytes())
        assertArrayEquals("abd".toByteArray(), f.rows.bytes)
    }

    @Test fun foreignMetadataBeforePublishStopsWithoutTouchingRowOrOriginal() {
        val f = Fixture()
        f.publicationStorage.onCommit = { record ->
            if (record.phase == DngPublicationPhase.BYTES_COMPLETE) f.rows.row = f.rows.row!!.copy(ownedByApp = false)
        }
        assertEquals(DngPublicationPhase.UNKNOWN, f.coordinator.advance().phase)
        assertEquals(0, f.rows.publishes)
        assertArrayEquals(original, f.originalFile.readBytes())
        assertFalse(f.rows.row!!.ownedByApp)
    }

    @Test fun externalOriginalReaderDefersReleaseUntilActualClose() {
        val f = Fixture()
        val reader = f.source.openOriginal()
        assertThrows(IOException::class.java) { f.coordinator.advance() }
        assertEquals(DngPublicationPhase.SHARED_READY, f.journal.read()!!.phase)
        assertTrue(f.originalFile.exists())
        assertEquals(false, f.rows.row!!.pending)
        reader.close()
        assertEquals(DngPublicationPhase.SHARED_ONLY, f.coordinator.advance().phase)
        assertEquals(1, f.rows.inserts)
        assertEquals(1, f.rows.publishes)
    }

    @Test fun existingVerifyOriginalUsesSameLeaseAndDoesNotHoldStoreUpdateLock() {
        val f = Fixture()
        val heldReader = f.source.openOriginal()
        assertThrows(IOException::class.java) { f.coordinator.advance() }
        heldReader.close()
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val checks = AtomicInteger()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val verification = executor.submit(Callable {
                f.store.verifyOriginal(f.store.readReceipt()!!.revision) {
                    if (checks.incrementAndGet() == 2) { entered.countDown(); check(resume.await(5, TimeUnit.SECONDS)) }
                    false
                }
            })
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertThrows(IOException::class.java) { f.coordinator.advance() }
            assertTrue(f.originalFile.exists())
            resume.countDown()
            verification.get(5, TimeUnit.SECONDS)
            assertEquals(DngPublicationPhase.SHARED_ONLY, f.coordinator.advance().phase)
        } finally { resume.countDown(); executor.shutdownNow() }
    }

    @Test fun lateJpegReceiptAfterHandoffDoesNotRequireMissingTemporaryDng() {
        val f = Fixture(receiveJpeg = false)
        assertEquals(DngPublicationPhase.SHARED_ONLY, f.coordinator.advance().phase)
        val receipt = f.store.readReceipt()!!
        val late = f.store.received(receipt.revision, f.previewHandle)
        assertEquals(CaptureReceiptPhase.FILES_RECEIVED, late.phase)
        assertEquals(fingerprint, late.originalFingerprint)
        assertFalse(f.originalFile.exists())
        assertArrayEquals(jpeg, f.jpegFile.readBytes())
    }

    @Test fun outputOrReadCloseFailureHoldsResourcesAndBlocksNewIoUntilReleaseConfirmed() {
        for (fault in listOf("output", "read")) {
            val f = Fixture()
            f.rows.closeFault = fault
            assertThrows(IOException::class.java) { f.coordinator.advance() }
            assertTrue(f.coordinator.hasUnreleasedResources)
            val calls = f.rows.calls
            assertThrows(IOException::class.java) { f.coordinator.advance() }
            assertThrows(IOException::class.java) { f.coordinator.releaseResources() }
            assertEquals(calls, f.rows.calls)
            assertEquals(0, f.rows.publishes)
            assertTrue(f.originalFile.exists())
            f.rows.closeFault = "none"
            f.coordinator.releaseResources()
            assertFalse(f.coordinator.hasUnreleasedResources)
            assertEquals(DngPublicationPhase.SHARED_ONLY, f.coordinator.advance().phase)
            assertEquals(1, f.rows.inserts)
        }
    }

    @Test fun cancellationPreservesFilesAndCompletedButUncommittedCopyIsReused() {
        val f = Fixture()
        assertThrows(DngVerificationStopped::class.java) { f.coordinator.advance { true } }
        assertEquals(0, f.rows.calls)
        assertThrows(DngVerificationStopped::class.java) { f.coordinator.advance { f.rows.bytes.contentEquals(original) } }
        assertEquals(0, f.rows.publishes)
        assertTrue(f.originalFile.exists())
        assertFalse(f.coordinator.hasUnreleasedResources)
        assertEquals(DngPublicationPhase.SHARED_ONLY, f.coordinator.advance().phase)
        assertEquals(1, f.rows.outputs)
    }

    @Test fun unreadableCopyAfterRestartIsNotAssumedEmptyAndIsNeverTruncated() {
        val f = Fixture()
        f.publicationStorage.failPhase = DngPublicationPhase.BYTES_COMPLETE
        f.publicationStorage.fault = "before"
        assertThrows(IOException::class.java) { f.coordinator.advance() }
        f.publicationStorage.fault = "none"
        f.rows.readFails = true
        f.recreate()
        assertThrows(IOException::class.java) { f.coordinator.advance() }
        assertEquals(1, f.rows.outputs)
        assertEquals(0, f.rows.publishes)
        assertArrayEquals(original, f.rows.bytes)
        assertTrue(f.originalFile.exists())
    }

    @Test fun sizeMismatchWithSuppressedCloseFailureCannotRecopyUntilResourceRelease() {
        for (bytes in listOf("a", "abcd")) {
            val f = Fixture()
            f.publicationStorage.failPhase = DngPublicationPhase.BYTES_COMPLETE
            f.publicationStorage.fault = "before"
            assertThrows(IOException::class.java) { f.coordinator.advance() }
            f.publicationStorage.fault = "none"
            f.rows.bytes = bytes.toByteArray()
            f.rows.closeFault = "read"
            assertThrows(IOException::class.java) { f.coordinator.advance() }
            assertTrue(f.coordinator.hasUnreleasedResources)
            assertEquals(1, f.rows.outputs)
            assertEquals(0, f.rows.publishes)
            assertTrue(f.originalFile.exists())
            f.rows.closeFault = "none"
            f.coordinator.releaseResources()
            assertEquals(DngPublicationPhase.SHARED_ONLY, f.coordinator.advance().phase)
            assertEquals(2, f.rows.outputs)
            assertEquals(1, f.rows.inserts)
        }
    }

    @Test fun deleteFailuresAndDeleteCommitWindowResumeWithoutPublishingAgain() {
        for (mode in listOf("before", "after")) {
            val f = Fixture()
            f.deleteFault = mode
            assertThrows(IOException::class.java) { f.coordinator.advance() }
            assertEquals(DngPublicationPhase.LOCAL_RELEASE_PENDING, f.journal.read()!!.phase)
            f.assertAtLeastOneOriginal()
            f.deleteFault = "none"
            f.recreate()
            assertThrows(IOException::class.java) { f.source.openOriginal() }
            assertEquals(DngPublicationPhase.SHARED_ONLY, f.coordinator.advance().phase)
            assertEquals(1, f.deleted)
            assertEquals(1, f.rows.publishes)
            assertEquals(1, f.rows.inserts)
            assertArrayEquals(jpeg, f.jpegFile.readBytes())
        }
    }

    @Test fun missingOrChangedSharedOriginalBeforeDeletionKeepsRemainingLocalCopy() {
        for (change in listOf("missing", "changed")) {
            val f = Fixture()
            f.publicationStorage.onCommit = { record ->
                if (record.phase == DngPublicationPhase.LOCAL_RELEASE_PENDING) {
                    if (change == "missing") f.rows.row = null else f.rows.bytes = "abd".toByteArray()
                }
            }
            assertThrows(IOException::class.java) { f.coordinator.advance() }
            assertTrue(f.originalFile.exists())
            assertEquals(0, f.deleted)
            assertArrayEquals(original, f.originalFile.readBytes())
            assertArrayEquals(jpeg, f.jpegFile.readBytes())
        }
    }

    @Test fun reservationRejectsForeignCaptureAndBlocksGeneralReadersUntilClosed() {
        val f = Fixture()
        f.publicationStorage.failPhase = DngPublicationPhase.LOCAL_RELEASE_PENDING
        f.publicationStorage.fault = "before"
        assertThrows(IOException::class.java) { f.coordinator.advance() }
        f.publicationStorage.fault = "none"
        val record = f.journal.read()!!
        val gate = f.source.reserveRelease(record)
        assertThrows(IOException::class.java) { f.source.openOriginal() }
        assertThrows(IllegalArgumentException::class.java) {
            gate.deleteOriginal(record.copy(target = DngPublicationTarget("other", record.target.token)))
        }
        gate.close()
        f.source.openOriginal().close()
        assertTrue(f.originalFile.exists())
    }

    @Test fun simultaneousAdvanceOrReleaseCannotCloseAnActiveTransfer() {
        val f = Fixture()
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        f.rows.beforeWrite = { entered.countDown(); check(resume.await(5, TimeUnit.SECONDS)) }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val job = executor.submit(Callable { f.coordinator.advance() })
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertThrows(DngHandoffBusy::class.java) { f.coordinator.advance() }
            assertThrows(DngHandoffBusy::class.java) { f.coordinator.releaseResources() }
            resume.countDown()
            assertEquals(DngPublicationPhase.SHARED_ONLY, job.get(5, TimeUnit.SECONDS).phase)
        } finally { resume.countDown(); executor.shutdownNow() }
    }

    @Test fun versionOneRowRecordMigratesWithoutChangingTargetOrUri() {
        val f = Fixture()
        f.journal.prepare(f.source.receipt())
        DngInsertCoordinator(f.journal, f.rows).advance()
        val row = f.journal.read()!!
        val payload = f.publicationStorage.bytes!!.dropLast(32).toByteArray().also { ByteBuffer.wrap(it).putInt(4, 1) }
        f.publicationStorage.bytes = payload + MessageDigest.getInstance("SHA-256").digest(payload)
        assertEquals(row, DngPublicationJournal(id, f.publicationStorage).read())
        val futureCodeInV1 = payload.copyOf().also { it[it.size - requireNotNull(row.uri).length - 4] = 4 }
        f.publicationStorage.bytes = futureCodeInV1 + MessageDigest.getInstance("SHA-256").digest(futureCodeInV1)
        assertThrows(IOException::class.java) { f.journal.read() }
        f.publicationStorage.bytes = payload + MessageDigest.getInstance("SHA-256").digest(payload)
        f.recreate()
        val done = f.coordinator.advance()
        assertEquals(row.target, done.target)
        assertEquals(row.uri, done.uri)
        assertEquals(1, f.rows.inserts)
        assertEquals(2, ByteBuffer.wrap(f.publicationStorage.bytes!!).getInt(4))
    }

    private inner class Fixture(receiveJpeg: Boolean = true) {
        val root = temporary.newFolder("root-${fixtureIndex++}")
        val originalFile get() = File(File(root, id), RawOutput.DNG.fileName)
        val jpegFile get() = File(File(root, id), RawOutput.JPEG.fileName)
        val receiptStorage = MemoryStorage()
        val publicationStorage = PublicationStorage()
        var deleteFault = "none"
        var deleted = 0
        lateinit var store: RawCaptureStore
        lateinit var source: DngHandoffSource
        lateinit var journal: DngPublicationJournal
        lateinit var coordinator: DngHandoffCoordinator
        val previewHandle: RawCaptureOutput
        val rows = FakeRows { journal }
        init {
            makeStore()
            store.create(setOf(RawOutput.DNG, RawOutput.JPEG))
            val raw = store.openOutput(0, RawOutput.DNG)
            val preview = store.openOutput(0, RawOutput.JPEG)
            previewHandle = preview
            raw.write(original); raw.close()
            preview.write(jpeg); preview.close()
            store.received(0, raw)
            if (receiveJpeg) store.received(1, preview)
            store.verifyOriginal(if (receiveJpeg) 2 else 1)
            bind()
        }
        private fun makeStore() {
            store = RawCaptureStore(root, id, receiptStorage, deleteOriginalFile = { file ->
                assertEquals(originalFile.canonicalFile, file.canonicalFile)
                assertEquals(DngPublicationPhase.LOCAL_RELEASE_PENDING, journal.read()!!.phase)
                if (deleteFault == "before") throw IOException("Injected delete failure")
                val removed = Files.deleteIfExists(file.toPath())
                if (removed) deleted++
                if (deleteFault == "after") throw IOException("Injected delete acknowledgement loss")
                removed
            })
        }
        private fun bind() {
            journal = DngPublicationJournal(id, publicationStorage) { "a".repeat(32) }
            source = store.handoffSource(journal)
            coordinator = DngHandoffCoordinator(journal, rows, source)
        }
        fun recreate() { check(!coordinator.hasUnreleasedResources); makeStore(); bind() }
        fun assertAtLeastOneOriginal() {
            val localMatches = originalFile.exists() && originalFile.readBytes().contentEquals(original)
            val sharedMatches = rows.row != null && rows.bytes.contentEquals(original)
            assertTrue(localMatches || sharedMatches)
        }
    }

    private class MemoryStorage : AtomicJournalStorage {
        var bytes: ByteArray? = null
        override fun read(maxBytes: Int) = bytes?.copyOf()
        override fun replace(contents: ByteArray) { bytes = contents.copyOf() }
    }
    private class PublicationStorage : AtomicJournalStorage {
        var bytes: ByteArray? = null
        var failPhase: DngPublicationPhase? = null
        var fault = "none"
        var readFails = false
        var onCommit: (DngPublicationRecord) -> Unit = {}
        override fun read(maxBytes: Int): ByteArray? {
            if (readFails) throw IOException("Injected readback failure")
            return bytes?.copyOf()
        }
        override fun replace(contents: ByteArray) {
            val record = DngPublicationCodec.decode(contents)
            val fail = record.phase == failPhase
            if (fail && fault == "before") throw IOException("Injected commit failure")
            bytes = contents.copyOf()
            onCommit(record)
            if (fail && fault == "after") throw IOException("Injected acknowledgement loss")
            if (fail && fault == "readback") readFails = true
        }
    }

    private inner class FakeRows(val journal: () -> DngPublicationJournal) : DngHandoffRows {
        var row: DngPendingRow? = null
        var bytes = byteArrayOf()
        var inserts = 0
        var outputs = 0
        var publishes = 0
        var queries = 0
        val calls get() = inserts + outputs + publishes + queries
        var writeFault = "none"
        var publishFault = "none"
        var closeFault = "none"
        var readFails = false
        var beforeWrite: () -> Unit = {}
        override fun insert(target: DngPublicationTarget): String {
            inserts++
            val uri = "content://media/external_primary/images/media/7"
            row = DngPendingRow(uri, target.displayName, target.volume, target.relativePath, target.mimeType, true, true)
            return uri
        }
        override fun find(target: DngPublicationTarget): List<DngPendingRow> { queries++; return listOfNotNull(row) }
        override fun inspect(uri: String): DngPendingRow? { queries++; return row?.takeIf { it.uri == uri } }
        override fun openRead(target: DngPublicationTarget, uri: String, pending: Boolean): InputStream {
            check(target.accepts(requireNotNull(row), pending))
            if (readFails) throw IOException("Injected read failure")
            return object : ByteArrayInputStream(bytes) {
                override fun close() {
                    if (closeFault == "read") throw IOException("Injected read close failure")
                    super.close()
                }
            }
        }
        override fun openOutput(target: DngPublicationTarget, uri: String): OutputStream {
            assertEquals(DngPublicationPhase.COPYING, journal().read()!!.phase)
            check(target.accepts(requireNotNull(row)))
            outputs++
            val data = ByteArrayOutputStream()
            bytes = byteArrayOf()
            return object : OutputStream() {
                override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
                override fun write(buffer: ByteArray, offset: Int, length: Int) {
                    beforeWrite()
                    data.write(buffer, offset, if (writeFault == "write") minOf(length, 1) else length)
                    bytes = data.toByteArray()
                    if (writeFault == "corrupt" && bytes.isNotEmpty()) bytes[0] = 99
                    if (writeFault == "write") throw IOException("Injected partial write")
                }
                override fun close() { if (closeFault == "output") throw IOException("Injected output close failure") }
            }
        }
        override fun publish(target: DngPublicationTarget, uri: String) {
            assertEquals(DngPublicationPhase.PUBLISHING, journal().read()!!.phase)
            check(target.accepts(requireNotNull(row)))
            publishes++
            if (publishFault == "before") throw IOException("Injected publish failure")
            if (publishFault != "silent") row = row!!.copy(pending = false)
            if (publishFault == "after") throw IOException("Injected publish acknowledgement loss")
        }
    }
}
