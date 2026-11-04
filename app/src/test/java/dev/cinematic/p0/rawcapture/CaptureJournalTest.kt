package dev.cinematic.p0.rawcapture

import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class CaptureJournalTest {
    private val id = "test-capture-1"
    private val pair = setOf(RawOutput.DNG, RawOutput.JPEG)

    @Test fun roundTripAndRestartPreserveOnlyExplicitlyReceivedFormats() {
        val file = MemoryJournalFile()
        val journal = CaptureJournal(id, file)
        assertNull(journal.read())
        val initial = journal.create(pair)
        assertEquals(0L, initial.revision)
        val partial = journal.received(0, RawOutput.DNG, 123)
        assertEquals(mapOf(RawOutput.DNG to 123L), partial.receivedBytes)
        val restarted = CaptureJournal(id, file)
        val recovered = requireNotNull(restarted.recoverAfterProcessDeath())
        assertEquals(CaptureReceiptPhase.INTERRUPTED, recovered.phase)
        assertEquals(2L, recovered.revision)
        assertEquals(partial.receivedBytes, recovered.receivedBytes)
        assertEquals(recovered.revision, restarted.recoverAfterProcessDeath()!!.revision)
        val late = restarted.received(2, RawOutput.JPEG, 45)
        assertEquals(CaptureReceiptPhase.INTERRUPTED, late.phase)
        assertEquals(mapOf(RawOutput.DNG to 123L, RawOutput.JPEG to 45L), late.receivedBytes)
    }

    @Test fun completedReceiptsStayCompleteAndDoNotClaimRawDecodeValidity() {
        val file = MemoryJournalFile()
        val journal = CaptureJournal(id, file)
        journal.create(pair)
        journal.received(0, RawOutput.JPEG, 21)
        val complete = journal.received(1, RawOutput.DNG, 42)
        assertEquals(CaptureReceiptPhase.FILES_RECEIVED, complete.phase)
        assertEquals(complete.revision, journal.interrupt(2).revision)
        assertEquals(complete.revision, CaptureJournal(id, file).recoverAfterProcessDeath()!!.revision)
        assertEquals("$id/original.dng", complete.relativePath(RawOutput.DNG))
    }

    @Test fun snapshotsAreDefensiveAndRawOnlyDoesNotInventAJpegReceipt() {
        val file = MemoryJournalFile()
        val expected = mutableSetOf(RawOutput.DNG)
        val journal = CaptureJournal(id, file)
        val created = journal.create(expected)
        expected.add(RawOutput.JPEG)
        assertEquals(setOf(RawOutput.DNG), created.expectedFormats)
        assertThrows(UnsupportedOperationException::class.java) {
            (created.expectedFormats as MutableSet).add(RawOutput.JPEG)
        }
        val complete = journal.received(0, RawOutput.DNG, 5)
        assertThrows(UnsupportedOperationException::class.java) {
            (complete.receivedBytes as MutableMap)[RawOutput.JPEG] = 1L
        }
        assertEquals(CaptureReceiptPhase.FILES_RECEIVED, complete.phase)
        assertFalse(journal.read()!!.receivedBytes.containsKey(RawOutput.JPEG))
    }

    @Test fun staleOrConflictingUpdatesNeverOverwriteReceipts() {
        val file = MemoryJournalFile()
        val journal = CaptureJournal(id, file)
        journal.create(pair)
        journal.received(0, RawOutput.DNG, 10)
        val committed = file.contents!!.copyOf()
        assertThrows(CaptureJournalConflict::class.java) { journal.received(0, RawOutput.JPEG, 8) }
        assertThrows(CaptureJournalConflict::class.java) { journal.received(1, RawOutput.DNG, 11) }
        assertThrows(CaptureJournalConflict::class.java) { journal.create(pair) }
        val writes = file.writes
        assertEquals(1L, journal.received(1, RawOutput.DNG, 10).revision)
        assertEquals(writes, file.writes)
        assertArrayEquals(committed, file.contents)
    }

    @Test fun invalidRequestsDoNotWrite() {
        val file = MemoryJournalFile()
        val journal = CaptureJournal(id, file)
        assertThrows(IllegalArgumentException::class.java) { journal.create(emptySet()) }
        assertThrows(IllegalArgumentException::class.java) { journal.create(setOf(RawOutput.JPEG)) }
        assertEquals(0, file.writes)
        journal.create(setOf(RawOutput.DNG))
        val prior = file.contents!!.copyOf()
        for (bytes in listOf(0L, -1L)) {
            assertThrows(IllegalArgumentException::class.java) { journal.received(0, RawOutput.DNG, bytes) }
        }
        assertThrows(IllegalArgumentException::class.java) { journal.received(0, RawOutput.JPEG, 4) }
        assertArrayEquals(prior, file.contents)
    }

    @Test fun failureBeforeAtomicCommitKeepsOldReceiptAndCanBeRetried() {
        val file = MemoryJournalFile()
        val journal = CaptureJournal(id, file)
        journal.create(pair)
        file.fault = Fault.BEFORE_COMMIT
        assertThrows(IOException::class.java) { journal.received(0, RawOutput.DNG, 20) }
        assertEquals(0L, journal.read()!!.revision)
        file.fault = Fault.NONE
        assertEquals(1L, journal.received(0, RawOutput.DNG, 20).revision)
    }

    @Test fun lostCommitAcknowledgementIsResolvedByReadNotRollback() {
        val file = MemoryJournalFile()
        val journal = CaptureJournal(id, file)
        journal.create(pair)
        file.fault = Fault.AFTER_COMMIT
        assertThrows(IOException::class.java) { journal.received(0, RawOutput.DNG, 20) }
        val observed = CaptureJournal(id, file).read()!!
        assertEquals(1L, observed.revision)
        assertEquals(20L, observed.receivedBytes[RawOutput.DNG])
        file.fault = Fault.NONE
        assertThrows(CaptureJournalConflict::class.java) { journal.received(0, RawOutput.JPEG, 30) }
        journal.received(observed.revision, RawOutput.JPEG, 30)
        assertEquals(CaptureReceiptPhase.FILES_RECEIVED, journal.read()!!.phase)
    }

    @Test fun silentCommitFailureIsNotReportedAsSuccess() {
        val file = MemoryJournalFile()
        val journal = CaptureJournal(id, file)
        journal.create(pair)
        file.fault = Fault.DROP_WRITE
        assertThrows(IOException::class.java) { journal.received(0, RawOutput.DNG, 20) }
        assertEquals(0L, journal.read()!!.revision)
    }

    @Test fun tornWriteOrCorruptionBlocksAllFurtherWritesWithoutDeletingData() {
        val file = MemoryJournalFile()
        val journal = CaptureJournal(id, file)
        journal.create(pair)
        file.fault = Fault.TORN_WRITE
        assertThrows(IOException::class.java) { journal.received(0, RawOutput.DNG, 20) }
        val damaged = file.contents!!.copyOf()
        val writes = file.writes
        file.fault = Fault.NONE
        assertThrows(IOException::class.java) { journal.create(pair) }
        assertThrows(IOException::class.java) { journal.recoverAfterProcessDeath() }
        assertEquals(writes, file.writes)
        assertArrayEquals(damaged, file.contents)
    }

    @Test fun readFailureIsNotMistakenForMissingJournal() {
        val file = MemoryJournalFile()
        file.readFails = true
        assertThrows(IOException::class.java) { CaptureJournal(id, file).create(pair) }
        assertEquals(0, file.writes)
    }

    @Test fun checksumUnknownVersionTrailingBytesAndWrongOwnerAreBlocked() {
        val file = MemoryJournalFile()
        CaptureJournal(id, file).create(pair)
        val good = file.contents!!.copyOf()
        val badChecksum = good.copyOf().also { it[10] = (it[10].toInt() xor 1).toByte() }
        val badVersion = withDigest(good.dropLast(32).toByteArray().also { it[7] = 3 })
        val trailing = withDigest(good.dropLast(32).toByteArray() + byteArrayOf(0))
        for (bad in listOf(badChecksum, badVersion, trailing, byteArrayOf(0), ByteArray(4097))) {
            file.contents = bad
            val writes = file.writes
            assertThrows(IOException::class.java) { CaptureJournal(id, file).create(pair) }
            assertEquals(writes, file.writes)
            assertArrayEquals(bad, file.contents)
        }
        file.contents = good
        assertThrows(IOException::class.java) { CaptureJournal("different-capture", file).read() }
    }

    @Test fun invalidCaptureIdsAreRejectedBeforeStorageAccess() {
        for (invalid in listOf("", ".", "..", "../outside", "nested/one", "nested\\one", "C:escape", "x".repeat(65))) {
            val file = MemoryJournalFile()
            assertThrows(IllegalArgumentException::class.java) { CaptureJournal(invalid, file) }
            assertEquals(0, file.reads)
        }
    }

    @Test fun invalidFieldsAndExhaustedRevisionDoNotOverwriteJournal() {
        val file = MemoryJournalFile()
        CaptureJournal(id, file).create(pair)
        val payload = file.contents!!.dropLast(32).toByteArray()
        val maskOffset = 17 + id.length
        val phaseOffset = maskOffset + 1
        val malformed = listOf(
            payload.copyOf().also { it[maskOffset] = 0 },
            payload.copyOf().also { it[phaseOffset] = 99 },
            payload.copyOf().also { it[9] = '!'.code.toByte() },
            payload.copyOf().also { ByteBuffer.wrap(it).putLong(9 + id.length, -1) },
        )
        for (bad in malformed) {
            file.contents = withDigest(bad)
            assertThrows(IOException::class.java) { CaptureJournal(id, file).read() }
        }
        file.contents = withDigest(payload.copyOf().also { ByteBuffer.wrap(it).putLong(9 + id.length, Long.MAX_VALUE) })
        val prior = file.contents!!.copyOf()
        val writes = file.writes
        assertThrows(IOException::class.java) {
            CaptureJournal(id, file).received(Long.MAX_VALUE, RawOutput.DNG, 7)
        }
        assertEquals(writes, file.writes)
        assertArrayEquals(prior, file.contents)
    }

    @Test fun readbackFailureLeavesCommitUnacknowledgedButObservableAfterRecovery() {
        val file = MemoryJournalFile()
        val journal = CaptureJournal(id, file)
        journal.create(pair)
        file.fault = Fault.READ_AFTER_COMMIT
        assertThrows(IOException::class.java) { journal.received(0, RawOutput.DNG, 20) }
        file.readFails = false
        assertEquals(1L, CaptureJournal(id, file).read()!!.revision)
        assertEquals(20L, journal.read()!!.receivedBytes[RawOutput.DNG])
    }

    @Test fun concurrentUpdatesRequireTheLatestRevision() {
        val file = MemoryJournalFile()
        val journal = CaptureJournal(id, file)
        journal.create(pair)
        val executor = Executors.newFixedThreadPool(2)
        val go = CountDownLatch(1)
        try {
            val results = pair.map { format -> executor.submit(Callable {
                go.await()
                try { journal.received(0, format, 10); true }
                catch (_: CaptureJournalConflict) { false }
            }) }
            go.countDown()
            assertEquals(1, results.count { it.get(5, TimeUnit.SECONDS) })
            assertEquals(1L, journal.read()!!.revision)
            assertEquals(1, journal.read()!!.receivedBytes.size)
        } finally { executor.shutdownNow() }
    }

    @Test fun versionOneReceiptIsReadWithoutWritesAndUpgradedWithOriginalFingerprint() {
        // Frozen v1 layout: completed RAW-only receipt, revision 7, 3-byte DNG, no JPEG.
        val payload = ByteBuffer.allocate(35 + id.length)
            .putInt(0x4343524a).putInt(1).put(id.length.toByte()).put(id.toByteArray(Charsets.US_ASCII))
            .putLong(7).put(1.toByte()).put(2.toByte()).putLong(3).putLong(-1).array()
        val file = MemoryJournalFile().also { it.contents = withDigest(payload) }
        val journal = CaptureJournal(id, file)
        assertNull(journal.read()!!.originalFingerprint)
        assertEquals(0, file.writes)
        val fingerprint = OriginalFingerprint(3, "a".repeat(64))
        val updated = journal.recordOriginalFingerprint(7, fingerprint)
        assertEquals(8L, updated.revision)
        assertEquals(CaptureReceiptPhase.FILES_RECEIVED, updated.phase)
        assertEquals(fingerprint, CaptureJournal(id, file).read()!!.originalFingerprint)
        assertEquals(2, ByteBuffer.wrap(file.contents!!).getInt(4))
    }

    @Test fun originalFingerprintCannotBeReplacedAndSurvivesInterruptAndLateJpeg() {
        val file = MemoryJournalFile()
        val journal = CaptureJournal(id, file)
        val fingerprint = OriginalFingerprint(3, "a".repeat(64))
        journal.create(pair)
        assertThrows(IllegalArgumentException::class.java) { journal.recordOriginalFingerprint(0, fingerprint) }
        journal.received(0, RawOutput.DNG, 3)
        assertThrows(IllegalArgumentException::class.java) { journal.recordOriginalFingerprint(1, OriginalFingerprint(4, "a".repeat(64))) }
        journal.recordOriginalFingerprint(1, fingerprint)
        journal.interrupt(2)
        val late = journal.received(3, RawOutput.JPEG, 5)
        assertEquals(fingerprint, late.originalFingerprint)
        assertEquals(fingerprint, CaptureJournal(id, file).recoverAfterProcessDeath()!!.originalFingerprint)
        val prior = file.contents!!.copyOf()
        assertEquals(4L, journal.recordOriginalFingerprint(4, fingerprint).revision)
        assertThrows(CaptureJournalConflict::class.java) { journal.recordOriginalFingerprint(1, fingerprint) }
        assertThrows(CaptureJournalConflict::class.java) {
            journal.recordOriginalFingerprint(4, OriginalFingerprint(3, "b".repeat(64)))
        }
        assertArrayEquals(prior, file.contents)
    }

    @Test fun invalidFingerprintFlagTruncationAndMissingDngArePreservedWithoutWrites() {
        val file = MemoryJournalFile()
        val journal = CaptureJournal(id, file)
        journal.create(pair)
        journal.received(0, RawOutput.DNG, 3)
        journal.recordOriginalFingerprint(1, OriginalFingerprint(3, "a".repeat(64)))
        val payload = file.contents!!.dropLast(32).toByteArray()
        val invalid = listOf(
            payload.copyOf().also { it[it.size - 33] = 2 },
            payload.copyOf(payload.size - 1),
            payload.copyOf().also { ByteBuffer.wrap(it).putLong(19 + id.length, -1) },
        )
        for (bad in invalid) {
            file.contents = withDigest(bad)
            val writes = file.writes
            assertThrows(IOException::class.java) { journal.recordOriginalFingerprint(2, OriginalFingerprint(3, "a".repeat(64))) }
            assertEquals(writes, file.writes)
            assertArrayEquals(withDigest(bad), file.contents)
        }
    }

    private fun withDigest(payload: ByteArray): ByteArray =
        payload + MessageDigest.getInstance("SHA-256").digest(payload)

    private enum class Fault { NONE, BEFORE_COMMIT, AFTER_COMMIT, DROP_WRITE, TORN_WRITE, READ_AFTER_COMMIT }

    private class MemoryJournalFile : AtomicJournalStorage {
        var contents: ByteArray? = null
        var fault = Fault.NONE
        var reads = 0
        var writes = 0
        var readFails = false
        override fun read(maxBytes: Int): ByteArray? {
            reads++
            if (readFails) throw IOException("Injected read failure")
            return contents?.copyOf() // Codec must also reject oversized/malformed backend results.
        }
        override fun replace(contents: ByteArray) {
            writes++
            if (fault == Fault.BEFORE_COMMIT) throw IOException("Injected pre-commit failure")
            if (fault == Fault.DROP_WRITE) return
            this.contents = if (fault == Fault.TORN_WRITE) contents.copyOf(contents.size / 2) else contents.copyOf()
            if (fault == Fault.READ_AFTER_COMMIT) readFails = true
            if (fault == Fault.AFTER_COMMIT) throw IOException("Injected lost acknowledgement")
        }
    }
}
