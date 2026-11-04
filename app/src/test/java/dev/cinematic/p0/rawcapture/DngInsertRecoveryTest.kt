package dev.cinematic.p0.rawcapture

import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class DngInsertRecoveryTest {
    private val id = "capture-1"
    private val token = "a".repeat(32)
    private val fingerprint = OriginalFingerprint(3, "b".repeat(64))
    private val uri = "content://media/external_primary/images/media/7"

    @Test fun prepareBindsOneOriginalOperationAndDoesNotRegenerateItsToken() {
        val storage = MemoryStorage()
        var tokens = 0
        val journal = DngPublicationJournal(id, storage) { tokens++; token }
        val first = journal.prepare(receipt())
        val again = journal.prepare(receipt())
        assertEquals(first, again)
        assertEquals(1, tokens)
        assertEquals("$id:ORIGINAL", first.target.operationId)
        assertEquals("Pictures/Cinematic Camera/", first.target.relativePath)
        assertEquals("image/x-adobe-dng", first.target.mimeType)
        assertTrue(first.target.displayName.contains(token))
        assertEquals(first, DngPublicationJournal(id, storage).read())
        assertThrows(IllegalArgumentException::class.java) { journal.prepare(receipt("other")) }
        assertThrows(IllegalArgumentException::class.java) { journal.prepare(receipt(verified = false)) }
        assertThrows(CaptureJournalConflict::class.java) { journal.prepare(receipt(bytes = 4)) }
        assertEquals(1, storage.writes)
    }

    @Test fun normalInsertCommitsIntentBeforeApiAndRecordsOnlyOnePendingUri() {
        val fixture = fixture()
        fixture.rows.beforeInsert = { assertEquals(DngPublicationPhase.INSERTING, fixture.journal.read()!!.phase) }
        val result = fixture.coordinator.advance()
        assertEquals(DngPublicationPhase.ROW_RECORDED, result.phase)
        assertEquals(uri, result.uri)
        assertEquals(1, fixture.rows.inserts)
        assertEquals(result, fixture.coordinator.advance())
        assertEquals(1, fixture.rows.inserts)
        assertEquals(fingerprint, result.fingerprint)
    }

    @Test fun deathAfterInsertBeforeUriCommitFindsTheSameRowWithoutReinserting() {
        val fixture = fixture()
        fixture.rows.crash = "after"
        assertThrows(SimulatedDeath::class.java) { fixture.coordinator.advance() }
        assertEquals(DngPublicationPhase.INSERTING, fixture.journal.read()!!.phase)
        fixture.rows.crash = "none"
        val restarted = DngInsertCoordinator(DngPublicationJournal(id, fixture.storage), fixture.rows)
        assertEquals(uri, restarted.advance().uri)
        assertEquals(1, fixture.rows.inserts)
        assertEquals(1, fixture.rows.finds)
    }

    @Test fun deathBeforeInsertWithNoCandidateBecomesUnknownAndNeverInsertsAgain() {
        val fixture = fixture()
        fixture.rows.crash = "before"
        assertThrows(SimulatedDeath::class.java) { fixture.coordinator.advance() }
        fixture.rows.crash = "none"
        val restarted = DngInsertCoordinator(DngPublicationJournal(id, fixture.storage), fixture.rows)
        assertEquals(DngPublicationPhase.UNKNOWN, restarted.advance().phase)
        val calls = fixture.rows.totalCalls
        assertEquals(DngPublicationPhase.UNKNOWN, restarted.advance().phase)
        assertEquals(calls, fixture.rows.totalCalls)
        assertEquals(1, fixture.rows.inserts) // One attempted call, no created row.
        assertTrue(fixture.rows.rows.isEmpty())
    }

    @Test fun insertingCommitFailureNeverCallsInsertAndLostAckCannotCauseRetryInsert() {
        for (fault in listOf("before", "after", "readback")) {
            val fixture = fixture()
            fixture.storage.failAt = 2
            fixture.storage.fault = fault
            assertThrows(IOException::class.java) { fixture.coordinator.advance() }
            assertEquals(0, fixture.rows.inserts)
            fixture.storage.fault = "none"
            fixture.storage.readFails = false
            val result = fixture.coordinator.advance()
            if (fault == "before") {
                assertEquals(DngPublicationPhase.ROW_RECORDED, result.phase)
                assertEquals(1, fixture.rows.inserts)
            } else {
                assertEquals(DngPublicationPhase.UNKNOWN, result.phase)
                assertEquals(0, fixture.rows.inserts)
            }
        }
    }

    @Test fun uriCommitFailureResolvesFromActualJournalAndSameRow() {
        for (fault in listOf("before", "after", "readback")) {
            val fixture = fixture()
            fixture.storage.failAt = 3
            fixture.storage.fault = fault
            assertThrows(IOException::class.java) { fixture.coordinator.advance() }
            fixture.storage.fault = "none"
            fixture.storage.readFails = false
            val restarted = DngInsertCoordinator(DngPublicationJournal(id, fixture.storage), fixture.rows)
            assertEquals(uri, restarted.advance().uri)
            assertEquals(1, fixture.rows.inserts)
            assertEquals(1, fixture.rows.rows.size)
        }
    }

    @Test fun insertExceptionsAndNullResponseStopEvenIfAnUnacknowledgedRowExists() {
        for (fault in listOf("throw-before", "throw-after", "null")) {
            val fixture = fixture()
            fixture.rows.crash = fault
            val result = fixture.coordinator.advance()
            assertEquals(DngPublicationPhase.UNKNOWN, result.phase)
            fixture.rows.crash = "none"
            val calls = fixture.rows.totalCalls
            assertEquals(result, fixture.coordinator.advance())
            assertEquals(calls, fixture.rows.totalCalls)
            assertEquals(1, fixture.rows.inserts)
        }
    }

    @Test fun missingMultipleForeignPublishedAndMismatchedCandidatesAreNeverAdopted() {
        val valid = row()
        val cases = listOf(
            emptyList(), listOf(valid, valid.copy(uri = uri.dropLast(1) + "8")),
            listOf(valid.copy(ownedByApp = false)), listOf(valid.copy(pending = false)),
            listOf(valid.copy(pending = null)), listOf(valid.copy(trashed = true)),
            listOf(valid.copy(trashed = null)), listOf(valid.copy(volume = "external")),
            listOf(valid.copy(volume = null)), listOf(valid.copy(mimeType = null)),
            listOf(valid.copy(relativePath = "Pictures/Other/")), listOf(valid.copy(mimeType = "image/jpeg")),
            listOf(valid.copy(displayName = "other.dng")), listOf(valid.copy(uri = "content://other/7")),
        )
        for (candidates in cases) {
            val fixture = fixture()
            fixture.journal.markInserting(0)
            fixture.rows.rows += candidates
            assertEquals(DngPublicationPhase.UNKNOWN, fixture.coordinator.advance().phase)
            assertEquals(0, fixture.rows.inserts)
            assertEquals(candidates, fixture.rows.rows)
        }
    }

    @Test fun queryErrorAndMissingRecordedRowDoNotCreateReplacementRows() {
        for (point in listOf("inserting", "recorded")) {
            val fixture = fixture()
            if (point == "inserting") fixture.journal.markInserting(0)
            else fixture.coordinator.advance()
            fixture.rows.queryFails = true
            val result = fixture.coordinator.advance()
            assertEquals(DngPublicationPhase.UNKNOWN, result.phase)
            assertEquals(if (point == "recorded") uri else null, result.uri)
            val inserts = fixture.rows.inserts
            fixture.rows.queryFails = false
            fixture.rows.rows.clear()
            fixture.coordinator.advance()
            assertEquals(inserts, fixture.rows.inserts)
        }
        val fixture = fixture()
        fixture.coordinator.advance()
        fixture.rows.rows.clear()
        assertEquals(DngPublicationPhase.UNKNOWN, fixture.coordinator.advance().phase)
        assertEquals(1, fixture.rows.inserts)
    }

    @Test fun recordedUriIsRecheckedAndPublishedOrForeignRowsAreNotTouched() {
        for (changed in listOf(row().copy(pending = false), row().copy(ownedByApp = false))) {
            val fixture = fixture()
            fixture.coordinator.advance()
            fixture.rows.rows[0] = changed
            val result = fixture.coordinator.advance()
            assertEquals(DngPublicationPhase.UNKNOWN, result.phase)
            assertEquals(uri, result.uri)
            assertEquals(listOf(changed), fixture.rows.rows)
            assertEquals(1, fixture.rows.inserts)
        }
    }

    @Test fun invalidReturnedUriAndDifferentInspectionUriAreStopped() {
        for (response in listOf("content://other/7", "content://media/external_primary/images/media/0", uri)) {
            val fixture = fixture()
            fixture.rows.returnUri = response
            fixture.rows.inspectOverride = row().copy(uri = uri.dropLast(1) + "8")
            val result = fixture.coordinator.advance()
            assertEquals(DngPublicationPhase.UNKNOWN, result.phase)
            assertEquals(if (response == uri) uri else null, result.uri)
            assertEquals(1, fixture.rows.inserts)
        }
    }

    @Test fun unknownOrCorruptJournalCannotTriggerProviderCallsOrBeReset() {
        val fixture = fixture()
        val good = fixture.storage.contents!!.copyOf()
        val unknown = withDigest(good.dropLast(32).toByteArray().also { it[7] = 3 })
        val trailing = withDigest(good.dropLast(32).toByteArray() + byteArrayOf(0))
        val badTarget = good.dropLast(32).toByteArray().also {
            val offset = it.toString(Charsets.ISO_8859_1).indexOf("Pictures/")
            check(offset >= 0)
            it[offset] = 'X'.code.toByte()
        }
        val badPhase = good.dropLast(32).toByteArray().also { it[it.size - 2] = 99 }
        for (bad in listOf(byteArrayOf(0), ByteArray(4097), unknown, trailing, withDigest(badTarget), withDigest(badPhase), good.copyOf().also { it[0] = 0 })) {
            fixture.storage.contents = bad
            val writes = fixture.storage.writes
            assertThrows(IOException::class.java) { fixture.coordinator.advance() }
            assertThrows(IOException::class.java) { fixture.journal.prepare(receipt()) }
            assertEquals(0, fixture.rows.totalCalls)
            assertEquals(writes, fixture.storage.writes)
            assertArrayEquals(bad, fixture.storage.contents)
        }
        fixture.storage.contents = good
        assertThrows(IOException::class.java) { DngPublicationJournal("other", fixture.storage).read() }
    }

    @Test fun missingOrUnreadableJournalCannotTriggerProviderCalls() {
        val fixture = fixture()
        val prior = fixture.storage.contents!!.copyOf()
        fixture.storage.readFails = true
        assertThrows(IOException::class.java) { fixture.coordinator.advance() }
        assertThrows(IOException::class.java) { fixture.journal.prepare(receipt()) }
        fixture.storage.readFails = false
        assertArrayEquals(prior, fixture.storage.contents)
        fixture.storage.contents = null
        assertThrows(IOException::class.java) { fixture.coordinator.advance() }
        assertEquals(0, fixture.rows.totalCalls)
    }

    @Test fun staleTransitionsAndInvalidUrisCannotOverwritePublicationRecord() {
        val fixture = fixture()
        val inserting = fixture.journal.markInserting(0)
        val prior = fixture.storage.contents!!.copyOf()
        assertThrows(CaptureJournalConflict::class.java) { fixture.journal.markInserting(0) }
        assertThrows(IllegalArgumentException::class.java) { fixture.journal.recordRow(inserting.revision, "file:///outside") }
        assertArrayEquals(prior, fixture.storage.contents)
        val recorded = fixture.journal.recordRow(1, uri)
        assertEquals(recorded, fixture.journal.recordRow(2, uri))
        assertThrows(CaptureJournalConflict::class.java) { fixture.journal.recordRow(2, uri.dropLast(1) + "8") }
        assertThrows(CaptureJournalConflict::class.java) { fixture.journal.markUnknown(2, uri.dropLast(1) + "8") }
        val unknown = fixture.journal.markUnknown(2, null)
        assertEquals(uri, unknown.uri)
        assertEquals(unknown, fixture.journal.markUnknown(3, null))
    }

    @Test fun sameCoordinatorSerializesConcurrentAttemptsWithoutDuplicateInsert() {
        val fixture = fixture()
        val go = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results = (1..2).map { executor.submit(Callable { go.await(); fixture.coordinator.advance() }) }
            go.countDown()
            assertEquals(setOf(uri), results.map { it.get(5, TimeUnit.SECONDS).uri }.toSet())
            assertEquals(1, fixture.rows.inserts)
        } finally { executor.shutdownNow() }
    }

    private fun receipt(captureId: String = id, verified: Boolean = true, bytes: Long = 3) = CaptureReceipt(
        captureId, 2, setOf(RawOutput.DNG), mapOf(RawOutput.DNG to bytes), CaptureReceiptPhase.FILES_RECEIVED,
        if (verified) OriginalFingerprint(bytes, fingerprint.sha256) else null,
    )

    private fun row() = DngPendingRow(
        uri, DngPublicationTarget(id, token).displayName, "external_primary", "Pictures/Cinematic Camera/",
        "image/x-adobe-dng", true, true, false,
    )

    private fun fixture(): Fixture {
        val storage = MemoryStorage()
        val journal = DngPublicationJournal(id, storage) { token }
        journal.prepare(receipt())
        val rows = FakeRows()
        return Fixture(storage, journal, rows, DngInsertCoordinator(journal, rows))
    }

    private class SimulatedDeath : Error("Injected process death")
    private inner class FakeRows : DngPendingRows {
        val rows = mutableListOf<DngPendingRow>()
        var inserts = 0
        var finds = 0
        var inspections = 0
        val totalCalls get() = inserts + finds + inspections
        var crash = "none"
        var queryFails = false
        var returnUri = uri
        var inspectOverride: DngPendingRow? = null
        var beforeInsert: () -> Unit = {}
        override fun insert(target: DngPublicationTarget): String? {
            inserts++
            beforeInsert()
            if (crash == "before") throw SimulatedDeath()
            if (crash == "throw-before") throw IOException("Injected insert failure")
            rows += row()
            if (crash == "after") throw SimulatedDeath()
            if (crash == "throw-after") throw IOException("Injected lost response")
            return if (crash == "null") null else returnUri
        }
        override fun find(target: DngPublicationTarget): List<DngPendingRow> {
            finds++
            if (queryFails) throw IOException("Injected query failure")
            return rows.toList()
        }
        override fun inspect(uri: String): DngPendingRow? {
            inspections++
            if (queryFails) throw IOException("Injected query failure")
            return inspectOverride ?: rows.firstOrNull { it.uri == uri }
        }
    }

    private class Fixture(
        val storage: MemoryStorage, val journal: DngPublicationJournal,
        val rows: FakeRows, val coordinator: DngInsertCoordinator,
    )

    private class MemoryStorage : AtomicJournalStorage {
        var contents: ByteArray? = null
        var writes = 0
        var failAt = -1
        var fault = "none"
        var readFails = false
        override fun read(maxBytes: Int): ByteArray? {
            if (readFails) throw IOException("Injected read failure")
            return contents?.copyOf()
        }
        override fun replace(contents: ByteArray) {
            writes++
            if (writes == failAt && fault == "before") throw IOException("Injected pre-commit failure")
            this.contents = contents.copyOf()
            if (writes == failAt && fault == "readback") readFails = true
            if (writes == failAt && fault == "after") throw IOException("Injected lost acknowledgement")
        }
    }

    private fun withDigest(payload: ByteArray) = payload + MessageDigest.getInstance("SHA-256").digest(payload)
}
