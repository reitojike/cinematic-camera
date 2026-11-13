package dev.cinematic.p0.rawcapture

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import org.junit.Assert.*
import org.junit.Test

class ReconciledJpegPublicationTest {
    @Test fun matchingPublicAndPendingUseSameUriWithoutPrivateJpegInsertOrCopy() {
        for (pending in listOf(false, true)) {
            val f = Fixture(pending)
            val old = f.originalStorage.bytes!!.clone()
            assertEquals(JpegPublicationPhase.PUBLISHING, f.reconcile()!!.phase)
            assertEquals(JpegPublicationPhase.PUBLISHED, f.publisher().advance().phase)
            assertEquals(if (pending) 1 else 0, f.publishes)
            assertEquals(0, f.writes)
            assertArrayEquals(old, f.originalStorage.bytes)
            assertEquals(f.row.uri, f.recovery().read()!!.uri)
            assertEquals(JpegPublicationPhase.PUBLISHED, f.reader().read()!!.phase)
        }
    }

    @Test fun emptyPartialChangedPendingStayHeldWithoutTruncation() {
        for (bytes in listOf(byteArrayOf(), byteArrayOf(1), byteArrayOf(9, 8, 7, 6))) {
            val f = Fixture(true)
            f.bytes = bytes
            assertEquals(JpegPublicationPhase.UNKNOWN, f.reconcile()!!.phase)
            assertNull(f.recovery().read())
            assertNull(f.receiptStorage.bytes)
            assertEquals(0, f.writes)
            assertEquals(0, f.publishes)
        }
    }

    @Test fun receiptAndRecoveryCommitDeathAndIoFaultsKeepOriginalAndResumeSameRow() {
        for (boundary in listOf("receipt", "recovery")) for (fault in listOf("before", "after", "readback", "death")) {
            val f = Fixture(true)
            val old = f.originalStorage.bytes!!.clone()
            val storage = if (boundary == "receipt") f.receiptStorage else f.recoveryStorage
            storage.fault = fault
            assertThrows(if (fault == "death") Death::class.java else IOException::class.java) { f.reconcile() }
            assertEquals(0, f.publishes)
            storage.fault = "none"
            f.reconcile()
            assertEquals(JpegPublicationPhase.PUBLISHED, f.publisher().advance().phase)
            assertArrayEquals(old, f.originalStorage.bytes)
            assertEquals(1, f.publishes)
            assertEquals(0, f.writes)
        }
    }

    @Test fun publishAckLossRestartsStandardPublisherWithoutSecondPublish() {
        val f = Fixture(true)
        f.reconcile()
        f.losePublishAck = true
        assertThrows(IOException::class.java) { f.publisher().advance() }
        assertFalse(f.row.pending!!)
        f.losePublishAck = false
        assertEquals(JpegPublicationPhase.PUBLISHED, f.publisher().advance().phase)
        assertEquals(1, f.publishes)
        assertEquals(JpegPublicationPhase.UNKNOWN, f.original().read()!!.phase)
    }

    @Test fun pendingCompleteStillRequiresActualOriginalHashBeforePublication() {
        val f = Fixture(true)
        f.reconcile()
        f.originalBytes = byteArrayOf(8, 8, 8, 8)
        assertThrows(DngContentChanged::class.java) { f.publisher().advance() }
        assertEquals(0, f.publishes)
        assertTrue(f.row.pending!!)
        assertEquals(0, f.writes)
    }

    @Test fun orphanReceiptChangedOriginalAndForeignRecoveryAreHeld() {
        val f = Fixture(false)
        f.reconcile()
        val old = f.originalStorage.bytes!!.clone()
        f.originalStorage.bytes = null
        assertThrows(CaptureJournalConflict::class.java) { f.reader().read() }
        f.originalStorage.bytes = old
        f.originalStorage.bytes = JpegPublicationCodec.encode(f.original().read()!!.copy(revision = 99))
        assertThrows(CaptureJournalConflict::class.java) { f.reader().read() }
        f.originalStorage.bytes = old
        f.recoveryStorage.bytes = JpegPublicationCodec.encode(f.recovery().read()!!.copy(uri = f.row.uri + "1"))
        assertThrows(CaptureJournalConflict::class.java) { f.reader().read() }
    }

    @Test fun metadataDeathBeforeRecoverySeedCanReobservePublicAfterPendingReceipt() {
        val f = Fixture(true)
        f.recoveryStorage.fault = "before"
        assertThrows(IOException::class.java) { f.reconcile() }
        assertTrue(f.reader().hasEvidence())
        assertFalse(f.reader().hasPublishedEvidence()) // Pending observation does not prove a public result.
        f.row = f.row.copy(pending = false)
        f.recoveryStorage.fault = "none"
        assertEquals(JpegPublicationPhase.PUBLISHING, f.reconcile()!!.phase)
        assertEquals(JpegPublicationPhase.PUBLISHED, f.publisher().advance().phase)
        assertEquals(0, f.publishes)
    }

    @Test fun publicReceiptSurvivesSeedFailureAndBlocksExplicitExportBeforeRecoveryJournalExists() {
        val f = Fixture(false)
        f.recoveryStorage.fault = "before"
        assertThrows(IOException::class.java) { f.reconcile() }
        assertTrue(f.reader().hasEvidence())
        assertTrue(f.reader().hasPublishedEvidence()) // B cannot bypass already-public proof.
        assertNull(f.recoveryStorage.bytes)
        assertEquals(JpegPublicationPhase.UNKNOWN, f.original().read()!!.phase)
    }

    private class Fixture(pending: Boolean) : RenderedJpegRows, JpegReconciliationRows {
        var bytes = byteArrayOf(1, 2, 3, 4)
        var originalBytes = bytes.clone()
        private val fingerprint = DngContentVerifier.identify(4, { ByteArrayInputStream(bytes) })
        private val source = SharedOriginalReference("content://test/source/1", fingerprint, OriginalGrant.APP_OWNED)
        private val render = ManualOriginalTrialRender(RenderedJpegRequest("TRIAL24_capture-1", 1, source,
            ManualOriginalTrialRender.recipe(.5), JpegOutputDescription(2, 2, 1, "sRGB", 4)),
            OriginalRasterGeometry(2, 2, 1, 2, 2, true), fingerprint, .5, 0.0)
        private val target = JpegPublicationTarget(render.request.captureId, 1, "a".repeat(32))
        val originalStorage = Storage().also { it.bytes = JpegPublicationCodec.encode(JpegPublicationRecord(target,
            render, fingerprint, 9, JpegPublicationPhase.UNKNOWN, "content://media/external_primary/images/media/10")) }
        val recoveryStorage = Storage()
        val receiptStorage = Storage()
        var row = DngPendingRow("content://media/external_primary/images/media/10", target.displayName,
            target.volume, target.relativePath, target.mimeType, true, pending)
        var publishes = 0
        var writes = 0
        var losePublishAck = false
        fun original() = JpegPublicationJournal(target.captureId, 1, originalStorage)
        fun recovery() = JpegPublicationJournal(target.captureId, 1, recoveryStorage)
        fun reader() = ReconciledJpegPublication(original(), recovery(), JpegReconciliationReceipt(receiptStorage),
            this, DngIoResources()) { true }
        fun reconcile() = reader().reconcile(render, setOf(ManualOriginalTrialRender.CONTRACT)) { false }
        fun publisher() = RecoverablePhotoPublisher(recovery(), this, render, setOf(ManualOriginalTrialRender.CONTRACT),
            object : RenderedJpegInput {
                override fun openJpeg(): InputStream = throw AssertionError("Private JPEG must not be needed")
                override fun openOriginal(reference: SharedOriginalReference) = ByteArrayInputStream(originalBytes)
            }, JpegInspector { _, _ -> throw AssertionError("No private inspection") }) { true }
        override fun insert(target: JpegPublicationTarget): String? { writes++; throw AssertionError("No insert") }
        override fun find(target: JpegPublicationTarget) = listOf(row)
        override fun inspect(uri: String) = row.takeIf { it.uri == uri }
        override fun openRead(target: JpegPublicationTarget, uri: String, pending: Boolean) = ByteArrayInputStream(bytes)
        override fun openOutput(target: JpegPublicationTarget, uri: String): OutputStream { writes++; throw AssertionError("No overwrite") }
        override fun publish(target: JpegPublicationTarget, uri: String) {
            check(target.accepts(row)); publishes++; row = row.copy(pending = false)
            if (losePublishAck) throw IOException("Synthetic publish ack loss")
        }
    }
    private class Death : Error()
    private class Storage : AtomicJournalStorage {
        var bytes: ByteArray? = null
        var fault = "none"
        private var committed = false
        override fun read(maxBytes: Int): ByteArray? {
            if (fault == "readback" && committed) throw IOException("Synthetic readback failure")
            return bytes?.clone()
        }
        override fun replace(contents: ByteArray) {
            if (fault == "before") throw IOException("Synthetic precommit failure")
            bytes = contents.clone(); committed = true
            if (fault == "after") throw IOException("Synthetic ack failure")
            if (fault == "death") throw Death()
        }
    }
}
