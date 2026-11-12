package dev.cinematic.p0.rawcapture

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class DngImportContractTest {
    private val original = byteArrayOf(1, 2, 3, 4)
    private val uri = "content://media/external_primary/images/media/9"
    private fun fingerprint(bytes: ByteArray) = DngContentVerifier.identify(bytes.size.toLong(), { ByteArrayInputStream(bytes) })
    private fun record(grant: DngImportGrant = DngImportGrant.SESSION_ONLY) = DngImportRecord(
        DngImportTarget("import-1", "a".repeat(32)), "content://test/document/synthetic", fingerprint(original), grant)

    @Test fun completeCopyPublishesOnceAndRetainsOneSharedOriginal() {
        val f = Fixture()
        assertEquals(DngImportResult.READY, f.owner.advance())
        assertEquals(DngImportPhase.READY, f.journal.read()!!.phase)
        assertArrayEquals(original, f.rows.bytes)
        assertArrayEquals(original, f.sourceBytes)
        assertEquals(1, f.rows.inserts); assertEquals(1, f.rows.publishes); assertEquals(0, f.rows.discards)
        assertFalse(f.owner.hasUnreleasedResources)
        f.access = false // SD/revoked source no longer participates after READY.
        assertEquals(DngImportResult.READY, f.restart().advance())
        assertEquals(1, f.rows.inserts)
        assertEquals(DngImportPhase.READY, f.journal.read()!!.phase)
    }

    @Test fun unavailableGrantWaitsButSessionOnlyCanComplete() {
        val f = Fixture(DngImportGrant.UNAVAILABLE)
        assertEquals(DngImportResult.WAIT_SOURCE, f.owner.advance()); assertEquals(0, f.rows.inserts)
        f.journal.grant(f.journal.read()!!, DngImportGrant.SESSION_ONLY)
        assertEquals(DngImportResult.READY, f.owner.advance())
    }

    @Test fun revokedSourceBeforeInsertCannotCreateDestination() {
        val f = Fixture(); f.access = false
        assertThrows(IOException::class.java) { f.owner.advance() }
        assertEquals(0, f.rows.inserts); assertFalse(f.owner.hasUnreleasedResources)
        f.access = true // same selected content, explicit retry
        assertEquals(DngImportResult.READY, f.owner.advance())
    }

    @Test fun insufficientOrUnknownCapacityDoesNotInsert() {
        val f = Fixture(); f.space = false
        assertThrows(IOException::class.java) { f.owner.advance() }; assertEquals(0, f.rows.inserts)
    }

    @Test fun deathAfterInsertRecoversSameRowWithoutAnotherInsert() {
        val f = Fixture(); f.rows.afterInsert = { throw Death() }
        assertThrows(Death::class.java) { f.owner.advance() }
        assertEquals(DngImportPhase.INSERTING, f.journal.read()!!.phase)
        f.rows.afterInsert = {}
        assertEquals(DngImportResult.READY, f.restart().advance()); assertEquals(1, f.rows.inserts)
    }

    @Test fun unknownInsertWithZeroOrMultipleCandidatesNeverReinserts() {
        for (mode in listOf("zero", "multiple", "unavailable", "foreign")) {
            val f = Fixture(); f.journal.advance(f.journal.read()!!, DngImportPhase.INSERTING)
            f.rows.findMode = mode
            if (mode == "unavailable") assertThrows(IOException::class.java) { f.owner.advance() }
            else assertEquals(DngImportResult.HOLD, f.owner.advance())
            assertEquals(0, f.rows.inserts); assertEquals(0, f.rows.publishes)
        }
    }

    @Test fun pendingDisappearanceNeverBecomesReadyOrReinsert() {
        val f = Fixture(); f.partial()
        f.rows.row = null
        assertEquals(DngImportResult.HOLD, f.restart().advance())
        assertEquals(1, f.rows.inserts); assertEquals(0, f.rows.publishes)
    }

    @Test fun shortExtraOrChangedSourceCannotPublish() {
        for (changed in listOf(byteArrayOf(1), original + 5, byteArrayOf(9, 2, 3, 4))) {
            val f = Fixture(); f.rows.afterInsert = { f.sourceBytes = changed }
            assertThrows(DngContentChanged::class.java) { f.owner.advance() }
            assertEquals(0, f.rows.publishes); assertTrue(f.rows.row!!.pending!!)
            f.sourceBytes = original; f.rows.afterInsert = {}
            assertEquals(DngImportResult.READY, f.restart().advance())
            assertEquals(1, f.rows.inserts)
        }
    }

    @Test fun noProgressReadCannotPublish() {
        val f = Fixture(); f.zeroRead = true
        assertThrows(IOException::class.java) { f.owner.advance() }
        assertEquals(0, f.rows.inserts)
    }

    @Test fun midCopySpaceFailureRetainsPendingForExplicitRetry() {
        val f = Fixture(); f.rows.failWrite = true
        assertThrows(IOException::class.java) { f.owner.advance() }
        assertEquals(0, f.rows.publishes); assertFalse(f.owner.hasUnreleasedResources)
        f.rows.failWrite = false
        assertEquals(DngImportResult.READY, f.restart().advance()); assertEquals(1, f.rows.inserts)
    }

    @Test fun sourceCloseFailureRetainsOwnerAndBlocksNewEffects() {
        val f = Fixture(); f.failSourceClose = true
        assertThrows(IOException::class.java) { f.owner.advance() }
        assertTrue(f.owner.hasUnreleasedResources)
        assertThrows(IOException::class.java) { f.owner.advance() }; assertEquals(0, f.rows.inserts)
        f.failSourceClose = false
        assertEquals(DngImportResult.READY, f.owner.advance())
        assertFalse(f.owner.hasUnreleasedResources)
    }

    @Test fun writeFailureStaysPrimaryWithOutputCloseFailureSuppressed() {
        val f = Fixture(); f.rows.failWrite = true; f.rows.failOutputClose = true
        val error = assertThrows(IOException::class.java) { f.owner.advance() }
        assertEquals("space", error.message); assertTrue(error.suppressed.isNotEmpty())
        assertTrue(f.owner.hasUnreleasedResources); assertEquals(0, f.rows.publishes)
        assertThrows(IOException::class.java) { f.owner.advance() }; assertEquals(1, f.rows.inserts)
        f.rows.failWrite = false; f.rows.failOutputClose = false
        assertEquals(DngImportResult.READY, f.owner.advance())
    }

    @Test fun readbackCloseFailureBlocksPublicationUntilReleased() {
        val f = Fixture(); f.rows.failReadClose = true
        assertThrows(IOException::class.java) { f.owner.advance() }
        assertTrue(f.owner.hasUnreleasedResources); assertEquals(0, f.rows.publishes)
        f.rows.failReadClose = false
        assertEquals(DngImportResult.READY, f.owner.advance())
    }

    @Test fun completePendingCanRecoverWithoutSourcePermission() {
        val f = Fixture(); f.partial(); f.rows.bytes = original
        f.access = false
        f.journal.grant(f.journal.read()!!, DngImportGrant.UNAVAILABLE)
        assertEquals(DngImportResult.READY, f.restart().advance())
    }

    @Test fun incompletePendingWithRevokedGrantWaitsForSameMaterial() {
        val f = Fixture(); f.partial()
        f.journal.grant(f.journal.read()!!, DngImportGrant.UNAVAILABLE)
        assertEquals(DngImportResult.WAIT_SOURCE, f.restart().advance())
        f.journal.grant(f.journal.read()!!, DngImportGrant.SESSION_ONLY)
        assertEquals(DngImportResult.READY, f.restart().advance()); assertEquals(1, f.rows.inserts)
    }

    @Test fun deathAfterPublishReconcilesWithoutSourceOrRepublish() {
        val f = Fixture(); f.rows.afterPublish = { throw Death() }
        assertThrows(Death::class.java) { f.owner.advance() }
        assertEquals(DngImportPhase.PUBLISHING, f.journal.read()!!.phase)
        f.access = false; f.rows.afterPublish = {}
        assertEquals(DngImportResult.READY, f.restart().advance())
        assertEquals(1, f.rows.inserts); assertEquals(1, f.rows.publishes)
    }

    @Test fun unknownPublicationOrChangedPublishedContentCannotSucceed() {
        for (mode in listOf("missing", "unknown", "changed")) {
            val f = Fixture(); f.rows.afterPublish = { throw Death() }
            assertThrows(Death::class.java) { f.owner.advance() }
            when (mode) {
                "missing" -> f.rows.row = null
                "unknown" -> f.rows.row = f.rows.row!!.copy(pending = null)
                else -> f.rows.bytes = byteArrayOf(9)
            }
            if (mode == "changed") assertThrows(DngContentChanged::class.java) { f.restart().advance() }
            else assertEquals(DngImportResult.HOLD, f.restart().advance())
            assertEquals(1, f.rows.inserts); assertEquals(1, f.rows.publishes)
        }
    }

    @Test fun cancelBeforeInsertDoesNotTouchDestination() {
        val f = Fixture(); f.owner.requestStop()
        assertEquals(DngImportResult.CANCELLED, f.owner.advance()); assertEquals(0, f.rows.inserts)
    }

    @Test fun cancelDuringCopyWaitsForCloseThenDiscardsOnlyPending() {
        val f = Fixture(); f.rows.onWrite = { f.owner.requestStop() }
        assertThrows(DngVerificationStopped::class.java) { f.owner.advance() }
        assertEquals(0, f.rows.discards); assertFalse(f.owner.hasUnreleasedResources)
        assertEquals(DngImportResult.CANCELLED, f.owner.advance())
        assertEquals(1, f.rows.discards); assertEquals(0, f.rows.publishes)
        assertArrayEquals(original, f.sourceBytes)
    }

    @Test fun cancellationAfterPublicationKeepsOriginalAndStopIntent() {
        val f = Fixture(); f.rows.afterPublish = { f.owner.requestStop() }
        assertEquals(DngImportResult.READY, f.owner.advance())
        assertTrue(f.journal.read()!!.stopRequested); assertEquals(0, f.rows.discards)
        assertArrayEquals(original, f.rows.bytes)
    }

    @Test fun cancelAfterPublishResponseLostAlsoKeepsOriginal() {
        val f = Fixture(); f.rows.afterPublish = { throw Death() }
        assertThrows(Death::class.java) { f.owner.advance() }
        val owner = f.restart(); owner.requestStop()
        assertEquals(DngImportResult.READY, owner.advance()); assertEquals(0, f.rows.discards)
    }

    @Test fun discardResponseLostRecoversWithDurableDiscardIntent() {
        val f = Fixture(); f.partial(); f.owner.requestStop(); f.rows.afterDiscard = { throw Death() }
        assertThrows(Death::class.java) { f.owner.advance() }
        assertEquals(DngImportPhase.DISCARDING, f.journal.read()!!.phase)
        assertEquals(DngImportResult.CANCELLED, f.restart().advance()); assertEquals(1, f.rows.discards)
    }

    @Test fun cancelUnknownInsertDoesNotClaimAbsentDestination() {
        val f = Fixture(); f.journal.advance(f.journal.read()!!, DngImportPhase.INSERTING)
        f.owner.requestStop()
        assertEquals(DngImportResult.HOLD, f.owner.advance()); assertEquals(0, f.rows.discards)
    }

    @Test fun staleRevisionAndConflictingMaterialCannotChangeJournal() {
        val f = Fixture(); val old = f.journal.read()!!
        f.journal.grant(old, DngImportGrant.ACQUIRED)
        assertThrows(IOException::class.java) { f.journal.stop(old) }
        assertThrows(IOException::class.java) { f.journal.prepare(record().copy(fingerprint = fingerprint(byteArrayOf(9)))) }
        assertEquals(old.fingerprint, f.journal.read()!!.fingerprint)
    }

    @Test fun sameMaterialReselectionKeepsDestinationAndDifferentMaterialIsRejected() {
        val f = Fixture(); f.partial()
        val old = f.journal.read()!!
        assertThrows(IllegalArgumentException::class.java) {
            f.journal.reselect(old, "content://test/new", fingerprint(byteArrayOf(8)), DngImportGrant.SESSION_ONLY)
        }
        val selected = f.journal.reselect(old, "content://test/new", fingerprint(original), DngImportGrant.SESSION_ONLY)
        assertEquals(old.uri, selected.uri); assertEquals(old.target, selected.target)
        assertEquals(DngImportResult.READY, f.restart().advance()); assertEquals(1, f.rows.inserts)
    }

    @Test fun committedButThrownJournalWriteNeverRepeatsInsert() {
        val f = Fixture(); f.storage.afterWrite = { if (f.journal.read()!!.phase == DngImportPhase.INSERTING) throw Death() }
        assertThrows(Death::class.java) { f.owner.advance() }
        f.storage.afterWrite = {}
        assertEquals(DngImportResult.HOLD, f.restart().advance()); assertEquals(0, f.rows.inserts)
    }

    @Test fun codecRoundTripAndMalformedMetadataFailClosed() {
        val r = record().copy(sourceUri = "content://test/document/合成")
        val bytes = DngImportCodec.encode(r)
        assertEquals(r, DngImportCodec.decode(bytes))
        for (bad in listOf(bytes.copyOf(12), bytes + 0, bytes.copyOf().also { it[8] = 99 })) {
            assertThrows(IOException::class.java) { DngImportCodec.decode(bad) }
        }
        fun signed(payload: ByteArray) = payload + MessageDigest.getInstance("SHA-256").digest(payload)
        val payload = bytes.copyOf(bytes.size - 32)
        assertThrows(IOException::class.java) { DngImportCodec.decode(signed(payload + 0)) }
        assertThrows(IOException::class.java) { DngImportCodec.decode(signed(payload.copyOf().also { it[7] = 2 })) }
        assertThrows(IllegalArgumentException::class.java) { DngImportCodec.encode(r.copy(sourceUri = "content://test/" + "a".repeat(5000))) }
        assertThrows(IllegalArgumentException::class.java) { r.copy(fingerprint = OriginalFingerprint(DngImportRecord.MAX_SOURCE_BYTES + 1, "b".repeat(64))) }
    }

    @Test fun reentrantOwnerCannotDuplicateEffects() {
        val f = Fixture(); f.rows.afterInsert = {
            assertThrows(IllegalStateException::class.java) { f.owner.advance() }
        }
        assertEquals(DngImportResult.READY, f.owner.advance()); assertEquals(1, f.rows.inserts)
    }

    @Test fun backgroundInterruptionRetainsSameTargetForExplicitResume() {
        val f = Fixture()
        var paused = false
        val operation = f.restart { paused }
        f.rows.onWrite = { paused = true }
        assertThrows(DngVerificationStopped::class.java) { operation.advance() }
        operation.releaseResources()
        assertFalse(operation.hasUnreleasedResources)
        assertFalse(f.journal.read()!!.stopRequested)
        assertEquals(0, f.rows.publishes)
        paused = false
        f.rows.onWrite = {}
        assertEquals(DngImportResult.READY, operation.advance())
        assertEquals(1, f.rows.inserts)
        assertArrayEquals(original, f.rows.bytes)
    }

    @Test fun cancellationOfPartialCopyDoesNotRequireCapacityOrSource() {
        val f = Fixture(); f.partial()
        f.space = false; f.access = false
        f.journal.stop(f.journal.read()!!)
        assertEquals(DngImportResult.CANCELLED, f.restart().advance())
        assertEquals(1, f.rows.inserts)
        assertEquals(1, f.rows.discards)
        assertEquals(0, f.rows.publishes)
    }

    @Test fun pendingRowWithoutDataIsCopiedWithoutAnotherInsert() {
        val f = Fixture(); f.rows.noData = true
        assertEquals(DngImportResult.READY, f.owner.advance())
        assertEquals(1, f.rows.inserts); assertEquals(1, f.rows.publishes)
        assertArrayEquals(original, f.rows.bytes)
    }
    @Test fun restartAfterCopyingCommitBeforeOutputOpenUsesSamePending() {
        val f = Fixture(); f.rows.noData = true
        var r = f.journal.advance(f.journal.read()!!, DngImportPhase.INSERTING)
        val pending = f.rows.insert(r.target)
        f.journal.advance(r, DngImportPhase.COPYING, pending)
        assertEquals(DngImportResult.READY, f.restart().advance())
        assertEquals(pending, f.journal.read()!!.uri)
        assertEquals(1, f.rows.inserts); assertEquals(1, f.rows.publishes)
    }
    @Test fun unreadablePendingMustStillMatchAfterFailureBeforeRecopy() {
        for (change in listOf("published", "foreign", "missing")) {
            val f = Fixture(); f.rows.noData = true
            f.rows.onNoData = { f.rows.row = when (change) {
                "published" -> f.rows.row!!.copy(pending = false)
                "foreign" -> f.rows.row!!.copy(ownedByApp = false)
                else -> null
            } }
            assertEquals(DngImportResult.HOLD, f.owner.advance())
            assertEquals(0, f.rows.outputs); assertEquals(0, f.rows.publishes)
        }
    }
    @Test fun unreadablePendingWithUncertainCleanupOrOtherFailureNeverRecopies() {
        for (error in listOf(IOException("provider"), SecurityException("denied"),
            FileNotFoundException("not opened").apply { addSuppressed(IOException("close unknown")) })) {
            val f = Fixture(); f.rows.readFailure = error
            assertThrows(error.javaClass) { f.owner.advance() }
            assertEquals(0, f.rows.outputs); assertEquals(0, f.rows.publishes)
        }
    }
    @Test fun pendingWithoutDataStillRequiresReadableUnchangedSource() {
        for (revoked in listOf(true, false)) {
            val f = Fixture(); f.rows.noData = true
            f.rows.afterInsert = { if (revoked) f.access = false else f.sourceBytes = byteArrayOf(5, 6, 7, 8) }
            assertThrows(IOException::class.java) { f.owner.advance() }
            assertEquals(0, f.rows.publishes)
            assertEquals(DngImportPhase.COPYING, f.journal.read()!!.phase)
            assertFalse(f.owner.hasUnreleasedResources)
        }
    }
    @Test fun publishingReadFailureNeverReturnsToCopying() {
        val f = Fixture(); f.rows.afterPublish = { f.rows.noData = true }
        assertThrows(FileNotFoundException::class.java) { f.owner.advance() }
        assertEquals(DngImportPhase.PUBLISHING, f.journal.read()!!.phase)
        val outputs = f.rows.outputs
        assertThrows(FileNotFoundException::class.java) { f.restart().advance() }
        assertEquals(outputs, f.rows.outputs); assertEquals(1, f.rows.publishes)
    }

    @Test fun missingPublishedDataIsNeverRecreated() {
        val f = Fixture()
        assertEquals(DngImportResult.READY, f.owner.advance())
        val outputs = f.rows.outputs
        f.rows.noData = true
        assertThrows(FileNotFoundException::class.java) { f.restart().advance() }
        assertEquals(outputs, f.rows.outputs); assertEquals(1, f.rows.inserts)
        assertEquals(1, f.rows.publishes)
    }

    private class Death : Error("simulated process death")
    private class MemoryStorage : AtomicJournalStorage {
        var bytes: ByteArray? = null
        var afterWrite: () -> Unit = {}
        override fun read(maxBytes: Int) = bytes?.copyOf()
        override fun replace(contents: ByteArray) { bytes = contents.copyOf(); afterWrite() }
    }
    private inner class Fixture(grant: DngImportGrant = DngImportGrant.SESSION_ONLY) {
        val storage = MemoryStorage()
        val journal = DngImportJournal("import-1", storage).also { it.prepare(record(grant)) }
        var sourceBytes = original.copyOf()
        var access = true; var space = true; var failSourceClose = false; var zeroRead = false
        val rows = Rows()
        val source = DngImportSource {
            if (!access) throw IOException("revoked")
            object : ByteArrayInputStream(sourceBytes) {
                override fun read(b: ByteArray, off: Int, len: Int) = if (zeroRead) 0 else super.read(b, off, len)
                override fun close() { if (failSourceClose) throw IOException("source close") }
            }
        }
        val owner = restart()
        fun restart(interrupted: () -> Boolean = { false }) = DngImportOwner(journal, source, rows, interrupted) { space }
        fun partial() {
            rows.failWrite = true
            assertThrows(IOException::class.java) { owner.advance() }
            rows.failWrite = false
        }
        inner class Rows : DngImportRows {
            var row: DngPendingRow? = null
            var bytes = byteArrayOf()
            var inserts = 0; var publishes = 0; var discards = 0; var outputs = 0
            var noData = false; var onNoData: () -> Unit = {}; var readFailure: Exception? = null
            var findMode = "normal"
            var failWrite = false; var failOutputClose = false; var failReadClose = false
            var afterInsert: () -> Unit = {}; var afterPublish: () -> Unit = {}; var afterDiscard: () -> Unit = {}; var onWrite: () -> Unit = {}
            private fun sample(target: DngImportTarget) = DngPendingRow(uri, target.name, "external_primary", target.path, target.mime, true, true, false)
            override fun insert(target: DngImportTarget): String {
                assertEquals(DngImportPhase.INSERTING, journal.read()!!.phase)
                inserts++; row = sample(target); afterInsert(); return uri
            }
            override fun find(target: DngImportTarget): List<DngPendingRow> = when (findMode) {
                "zero" -> emptyList()
                "multiple" -> listOf(sample(target), sample(target))
                "unavailable" -> throw IOException("query")
                "foreign" -> listOf(sample(target).copy(ownedByApp = false))
                else -> listOfNotNull(row)
            }
            override fun inspect(uri: String) = row
            private fun check(target: DngImportTarget, uri: String, pending: Boolean) {
                check(row?.uri == uri && target.accepts(requireNotNull(row), pending))
            }
            override fun openRead(target: DngImportTarget, uri: String, pending: Boolean): InputStream {
                check(target, uri, pending)
                readFailure?.let { throw it }
                if (noData) { onNoData(); throw FileNotFoundException("Pending data not yet opened") }
                return object : ByteArrayInputStream(bytes) { override fun close() { if (failReadClose) throw IOException("read close") } }
            }
            override fun openPendingOutput(target: DngImportTarget, uri: String): OutputStream {
                check(target, uri, true)
                outputs++; noData = false
                bytes = byteArrayOf()
                return object : ByteArrayOutputStream() {
                    override fun write(b: ByteArray, off: Int, len: Int) {
                        if (failWrite) throw IOException("space")
                        super.write(b, off, len); bytes = toByteArray(); onWrite()
                    }
                    override fun close() { if (failOutputClose) throw IOException("output close") }
                }
            }
            override fun publish(target: DngImportTarget, uri: String) {
                check(target, uri, true); assertArrayEquals(original, bytes)
                publishes++; row = row!!.copy(pending = false); afterPublish()
            }
            override fun discardPending(target: DngImportTarget, uri: String) {
                check(target, uri, true); assertEquals(DngImportPhase.DISCARDING, journal.read()!!.phase)
                discards++; row = null; bytes = byteArrayOf(); afterDiscard()
            }
        }
    }
}
