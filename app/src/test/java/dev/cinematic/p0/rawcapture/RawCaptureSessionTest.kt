package dev.cinematic.p0.rawcapture

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.FilterInputStream
import java.nio.file.Files

class RawCaptureSessionTest {
    @get:Rule val temporary = TemporaryFolder()
    private val original = "synthetic original".toByteArray()
    private val jpeg = "synthetic preview".toByteArray()

    @Test fun reverseCallbacksCommitReceiptsBeforeAnyHashOrSharedIo() {
        val f = Fixture()
        val streams = f.session.prepare(setOf(RawOutput.DNG, RawOutput.JPEG))
        streams.getValue(RawOutput.DNG).write(original)
        streams.getValue(RawOutput.JPEG).write(jpeg)
        val partial = f.session.received(RawOutput.JPEG)
        assertEquals(setOf(RawOutput.JPEG), partial.receivedBytes.keys)
        val ready = f.session.received(RawOutput.DNG)
        assertEquals(CaptureReceiptPhase.FILES_RECEIVED, ready.phase)
        assertNull(ready.originalFingerprint)
        assertEquals(0, f.rows.inserts)
        assertEquals(DngPublicationPhase.SHARED_ONLY, f.session.advanceHandoff()!!.phase)
        assertArrayEquals(jpeg, f.jpegFile.readBytes())
        assertFalse(f.rawFile.exists())
        assertEquals(CaptureReceiptPhase.FILES_RECEIVED, f.session.received(RawOutput.DNG).phase)
        assertEquals(1, f.rows.inserts)
    }

    @Test fun errorThenLateSuccessPreservesEachFormatWithoutPromotingTheRequest() {
        val f = Fixture()
        val streams = f.session.prepare(setOf(RawOutput.DNG, RawOutput.JPEG))
        streams.getValue(RawOutput.DNG).write(original)
        streams.getValue(RawOutput.JPEG).write(jpeg)
        f.session.received(RawOutput.DNG)
        f.session.interruptAndCloseWriters()
        val late = f.session.received(RawOutput.JPEG)
        assertEquals(CaptureReceiptPhase.INTERRUPTED, late.phase)
        assertEquals(setOf(RawOutput.DNG, RawOutput.JPEG), late.receivedBytes.keys)
        assertEquals(DngPublicationPhase.SHARED_ONLY, f.session.advanceHandoff()!!.phase)
        assertArrayEquals(jpeg, f.jpegFile.readBytes())
        assertArrayEquals(original, f.rows.bytes)
    }

    @Test fun unconfirmedBytesSurviveInterruptionAndAreNeverPromotedDuringRecovery() {
        val f = Fixture()
        val streams = f.session.prepare(setOf(RawOutput.DNG, RawOutput.JPEG))
        streams.getValue(RawOutput.DNG).write(original)
        streams.getValue(RawOutput.JPEG).write(jpeg)
        f.session.interruptAndCloseWriters()
        val recovered = f.recreate().recoverAfterProcessDeath()!!
        assertEquals(CaptureReceiptPhase.INTERRUPTED, recovered.phase)
        assertTrue(recovered.receivedBytes.isEmpty())
        assertNull(f.session.advanceHandoff())
        assertEquals(0, f.rows.inserts)
        assertArrayEquals(original, f.rawFile.readBytes())
        assertArrayEquals(jpeg, f.jpegFile.readBytes())
    }

    @Test fun receivedDngSurvivesProcessRecreationWithMissingJpegCallback() {
        val f = Fixture()
        val streams = f.session.prepare(setOf(RawOutput.DNG, RawOutput.JPEG))
        streams.getValue(RawOutput.DNG).write(original)
        f.session.received(RawOutput.DNG)
        streams.getValue(RawOutput.JPEG).close() // Simulated old-process FD release, no success callback.
        val recovered = f.recreate().recoverAfterProcessDeath()!!
        assertEquals(CaptureReceiptPhase.INTERRUPTED, recovered.phase)
        assertEquals(setOf(RawOutput.DNG), recovered.receivedBytes.keys)
        assertEquals(DngPublicationPhase.SHARED_ONLY, f.session.advanceHandoff()!!.phase)
        assertTrue(f.jpegFile.exists())
        assertEquals(0, f.jpegFile.length())
        assertArrayEquals(original, f.rows.bytes)
    }

    @Test fun cooperativeStopDoesNotHashPublishOrLoseReceivedSource() {
        val f = Fixture()
        f.session.prepare(setOf(RawOutput.DNG)).getValue(RawOutput.DNG).write(original)
        f.session.received(RawOutput.DNG)
        assertThrows(DngVerificationStopped::class.java) { f.session.advanceHandoff { true } }
        assertNull(f.session.receipt()!!.originalFingerprint)
        assertEquals(0, f.rows.inserts)
        assertArrayEquals(original, f.rawFile.readBytes())
    }

    @Test fun unexpectedOrForeignCallbackCannotRecordAnUnrequestedFormat() {
        val f = Fixture()
        f.session.prepare(setOf(RawOutput.DNG)).getValue(RawOutput.DNG).write(original)
        assertThrows(IllegalArgumentException::class.java) { f.session.received(RawOutput.JPEG) }
        assertTrue(f.session.receipt()!!.receivedBytes.isEmpty())
        assertArrayEquals(original, f.rawFile.readBytes())
        f.session.interruptAndCloseWriters()
    }

    @Test fun firstHashCloseFailureRetainsTheReaderAndPreventsAnyAdditionalIo() {
        val f = Fixture()
        f.session.prepare(setOf(RawOutput.DNG)).getValue(RawOutput.DNG).write(original)
        f.session.received(RawOutput.DNG)
        f.readCloseFails = true
        assertThrows(IOException::class.java) { f.session.advanceHandoff() }
        assertTrue(f.session.hasUnreleasedHandoffResources)
        assertEquals(1, f.readOpens)
        assertThrows(IOException::class.java) { f.session.advanceHandoff() }
        assertEquals(1, f.readOpens)
        assertEquals(0, f.rows.inserts)
        assertNull(f.session.receipt()!!.originalFingerprint)
        f.readCloseFails = false
        f.session.releaseHandoffResources()
        assertFalse(f.session.hasUnreleasedHandoffResources)
        assertEquals(DngPublicationPhase.SHARED_ONLY, f.session.advanceHandoff()!!.phase)
    }

    private inner class Fixture {
        val root = temporary.newFolder()
        val id = "synthetic_capture"
        val rawFile get() = File(File(root, id), RawOutput.DNG.fileName)
        val jpegFile get() = File(File(root, id), RawOutput.JPEG.fileName)
        val receiptStorage = MemoryStorage()
        val publicationStorage = MemoryStorage()
        val rows = Rows()
        var readCloseFails = false
        var readOpens = 0
        var session = makeSession()
        private fun makeSession() = RawCaptureSession(
            RawCaptureStore(root, id, receiptStorage, openOriginalInput = { file ->
                readOpens++
                object : FilterInputStream(Files.newInputStream(file.toPath())) {
                    override fun close() { if (readCloseFails) throw IOException("Synthetic read close failure"); super.close() }
                }
            }), DngPublicationJournal(id, publicationStorage), rows,
        )
        fun recreate(): RawCaptureSession = makeSession().also { session = it }
    }

    private class MemoryStorage : AtomicJournalStorage {
        private var bytes: ByteArray? = null
        override fun read(maxBytes: Int) = bytes?.copyOf()
        override fun replace(contents: ByteArray) { bytes = contents.copyOf() }
    }

    private class Rows : DngHandoffRows {
        var inserts = 0
        var bytes = byteArrayOf()
        private var row: DngPendingRow? = null
        override fun insert(target: DngPublicationTarget): String {
            inserts++
            val uri = "content://media/external_primary/images/media/17"
            row = DngPendingRow(uri, target.displayName, target.volume, target.relativePath, target.mimeType, true, true, false)
            return uri
        }
        override fun inspect(uri: String) = row?.takeIf { it.uri == uri }
        override fun find(target: DngPublicationTarget) = listOfNotNull(row)
        override fun openRead(target: DngPublicationTarget, uri: String, pending: Boolean) = ByteArrayInputStream(bytes)
        override fun openOutput(target: DngPublicationTarget, uri: String) = object : ByteArrayOutputStream() {
            override fun close() { bytes = toByteArray(); super.close() }
        }
        override fun publish(target: DngPublicationTarget, uri: String) { row = row?.copy(pending = false) ?: throw IOException() }
    }
}
