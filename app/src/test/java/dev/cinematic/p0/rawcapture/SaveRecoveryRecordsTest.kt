package dev.cinematic.p0.rawcapture

import dev.cinematic.p0.jpegrender.original.Original24Request
import java.io.IOException
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class SaveRecoveryRecordsTest {
    private val source = SharedOriginalReference("content://test/source/1", OriginalFingerprint(4, "a".repeat(64)), OriginalGrant.USER_SELECTED)
    private val previous = Original24Request(ManualOriginalTrialRequests.identity(source), 1, source, .5)
    private val render = ManualOriginalTrialRender(RenderedJpegRequest(previous.captureId, 1, source,
        previous.recipe, JpegOutputDescription(2, 2, 1, "sRGB", 4)), OriginalRasterGeometry(2, 2, 1, 2, 2, true),
        OriginalFingerprint(4, "b".repeat(64)), .5, 0.0)
    private val journal = JpegPublicationRecord(JpegPublicationTarget(previous.captureId, 1, "c".repeat(32)),
        render, render.outputFingerprint, 8, JpegPublicationPhase.UNKNOWN,
        "content://media/external_primary/images/media/12")
    private val observation = JpegReconciliationObservation(journal, JpegReconciliationFinding.PUBLIC_BYTES_MATCH, journal.uri)
    private val export = ExplicitRecoveryExport("d".repeat(32), previous, "e".repeat(64), journal,
        previous.copy(generation = 2), ManualOriginalTrialRender.CONTRACT.renderer)

    @Test fun recordsRoundTripWithoutChangingOldUnknownAndIntentIdentity() {
        assertEquals(observation, SaveRecoveryCodec.readObservation(SaveRecoveryCodec.observation(observation)))
        for (value in listOf(export, export.copy(previousPublication = null))) {
            val bytes = SaveRecoveryCodec.export(value)
            assertEquals(value, SaveRecoveryCodec.readExport(bytes))
            assertArrayEquals(bytes, SaveRecoveryCodec.export(SaveRecoveryCodec.readExport(bytes)))
            assertEquals(previous.source, value.request.source)
            assertEquals("e".repeat(64), value.previousIntentSha256)
        }
        assertEquals(JpegPublicationPhase.UNKNOWN, journal.phase)
    }

    @Test fun corruptTruncatedOversizedForeignVersionAndKindAreRejected() {
        val encoded = SaveRecoveryCodec.export(export)
        val corrupt = encoded.clone().also { it[20] = (it[20].toInt() xor 1).toByte() }
        val future = encoded.copyOfRange(0, encoded.size - 32).also { ByteBuffer.wrap(it).putInt(4, 2) }
        val negativeBlob = encoded.copyOfRange(0, encoded.size - 32).also {
            ByteBuffer.wrap(it).putInt(12 + 2 + 32, -1)
        }
        for (bytes in listOf(corrupt, encoded.copyOf(encoded.size - 1), ByteArray(SaveRecoveryCodec.MAX_BYTES + 1),
            future + SaveRecoveryCodec.digest(future), negativeBlob + SaveRecoveryCodec.digest(negativeBlob),
            SaveRecoveryCodec.observation(observation))) {
            assertThrows(IOException::class.java) { SaveRecoveryCodec.readExport(bytes) }
        }
        assertThrows(IOException::class.java) { SaveRecoveryCodec.readObservation(encoded) }
    }

    @Test fun allFindingsHaveCanonicalUriPresence() {
        for (finding in JpegReconciliationFinding.entries) {
            val needsUri = finding in setOf(JpegReconciliationFinding.PUBLIC_BYTES_MATCH,
                JpegReconciliationFinding.PENDING_BYTES_MATCH, JpegReconciliationFinding.PENDING_BYTES_DIFFER,
                JpegReconciliationFinding.PUBLIC_BYTES_DIFFER)
            val value = observation.copy(finding = finding, uri = if (needsUri) journal.uri else null)
            assertEquals(value, SaveRecoveryCodec.readObservation(SaveRecoveryCodec.observation(value)))
            assertThrows(IllegalArgumentException::class.java) {
                SaveRecoveryCodec.observation(value.copy(uri = if (needsUri) null else journal.uri))
            }
        }
    }

    @Test fun receiptReadbackAndRestartRetainOriginalEvidenceWithoutOverwrite() {
        for (fault in listOf("before", "after", "readback", "death")) {
            val storage = Storage().also { it.fault = fault }
            val receipt = JpegReconciliationReceipt(storage)
            assertThrows(if (fault == "death") Death::class.java else IOException::class.java) { receipt.commit(observation) {} }
            storage.fault = "none"
            val restarted = JpegReconciliationReceipt(storage)
            assertEquals(observation, restarted.commit(observation) {})
            val saved = storage.bytes!!.clone()
            assertThrows(CaptureJournalConflict::class.java) {
                restarted.commit(observation.copy(finding = JpegReconciliationFinding.PENDING_BYTES_MATCH)) {}
            }
            assertArrayEquals(saved, storage.bytes)
            assertEquals(observation, restarted.read())
        }
    }

    @Test fun receiptAuthorityFailureBeforeOrAfterCommitCannotReturnSuccess() {
        for (failAt in listOf(1, 2)) {
            val storage = Storage()
            var checks = 0
            assertThrows(IOException::class.java) {
                JpegReconciliationReceipt(storage).commit(observation) { if (++checks == failAt) throw IOException("Stale revision") }
            }
            assertEquals(failAt == 2, storage.bytes != null)
        }
    }

    @Test fun doubleTapAndRestartReturnSameRequestButNeverPermitOldAndNewTogether() {
        val storage = Storage()
        val owner = ExplicitRecoveryExports(storage)
        assertTrue(owner.oldMayRun())
        assertFalse(owner.newMayRun(export))
        repeat(3) { assertEquals(export, owner.accept(export) {}) }
        assertEquals(1, storage.writes)
        for (authority in listOf(owner, ExplicitRecoveryExports(storage))) {
            assertFalse(authority.oldMayRun())
            assertTrue(authority.newMayRun(export))
            assertEquals(export, authority.accept(export) {})
            assertThrows(CaptureJournalConflict::class.java) { authority.accept(export.copy(key = "f".repeat(32))) {} }
            assertFalse(authority.newMayRun(export.copy(key = "f".repeat(32))))
        }
    }

    @Test fun acceptanceKillAndIoBoundariesNeverAllocateSecondIdentity() {
        for (fault in listOf("before", "after", "readback", "death")) {
            val storage = Storage().also { it.fault = fault }
            val owner = ExplicitRecoveryExports(storage)
            assertThrows(if (fault == "death") Death::class.java else IOException::class.java) { owner.accept(export) {} }
            assertFalse(owner.oldMayRun())
            assertFalse(owner.newMayRun(export))
            storage.fault = "none"
            if (fault == "before") assertThrows(IOException::class.java) { owner.accept(export) {} }
            else assertEquals(export, owner.accept(export) {})
            val restarted = ExplicitRecoveryExports(storage)
            assertEquals(export, restarted.accept(export) {})
            assertFalse(restarted.oldMayRun())
            assertTrue(restarted.newMayRun(export))
            assertEquals(1, storage.writes)
        }
    }

    @Test fun authorityCallbackFailureBeforeAndAfterCommitBlocksAcceptance() {
        val before = Storage()
        assertThrows(IOException::class.java) { ExplicitRecoveryExports(before).accept(export) { throw IOException("Authority unavailable") } }
        assertEquals(0, before.writes)
        val storage = Storage()
        val owner = ExplicitRecoveryExports(storage)
        var checks = 0
        assertThrows(IOException::class.java) { owner.accept(export) { if (++checks == 2) throw IOException("Authority changed") } }
        assertFalse(owner.oldMayRun())
        assertFalse(owner.newMayRun(export))
        assertEquals(export, owner.accept(export) {})
    }

    @Test fun fixedConsentCannotBeReinterpretedWithAnotherRecipeRendererOrOldEvidence() {
        val storage = Storage()
        val owner = ExplicitRecoveryExports(storage)
        owner.accept(export) {}
        val originalBytes = storage.bytes!!.clone()
        for (changed in listOf(export.copy(request = export.request.copy(strength = .75)),
            export.copy(rendererContract = "another-renderer"),
            export.copy(previousIntentSha256 = "f".repeat(64)),
            export.copy(previousPublication = journal.copy(revision = 9)))) {
            assertThrows(CaptureJournalConflict::class.java) { owner.accept(changed) {} }
            assertFalse(owner.newMayRun(changed))
            assertFalse(owner.oldMayRun())
            assertArrayEquals(originalBytes, storage.bytes)
        }
    }

    @Test fun concurrentTapsOnTheRetainedAuthorityCommitOnlyOnce() {
        val storage = Storage()
        val owner = ExplicitRecoveryExports(storage)
        val results = java.util.Collections.synchronizedList(mutableListOf<ExplicitRecoveryExport>())
        val failures = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val gate = java.util.concurrent.CountDownLatch(1)
        val threads = List(4) { Thread {
            try { gate.await(); results += owner.accept(export) {} } catch (error: Throwable) { failures += error }
        }.apply { start() } }
        gate.countDown()
        threads.forEach { it.join(5000); assertFalse(it.isAlive) }
        assertTrue(failures.isEmpty())
        assertEquals(List(4) { export }, results)
        assertEquals(1, storage.writes)
        assertFalse(owner.oldMayRun())
    }

    @Test fun sourceAliasingGenerationReuseAndNonUnknownPublicationAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { export.copy(request = previous) }
        assertThrows(IllegalArgumentException::class.java) { export.copy(request = previous.copy(generation = 3)) }
        assertThrows(IllegalArgumentException::class.java) { export.copy(request = export.request.copy(source = source.copy(uri = "content://test/source/2"))) }
        assertThrows(IllegalArgumentException::class.java) { export.copy(previousPublication = journal.copy(phase = JpegPublicationPhase.PUBLISHED)) }
        assertThrows(IllegalArgumentException::class.java) { export.copy(previousIntentSha256 = "") }
        assertThrows(IllegalArgumentException::class.java) { export.copy(rendererContract = "") }
    }

    @Test fun damagedOrInaccessibleConsentSlotDoesNotGrantEitherOwnerAdmission() {
        val storage = Storage()
        val owner = ExplicitRecoveryExports(storage)
        storage.bytes = byteArrayOf(1)
        assertThrows(IOException::class.java) { owner.oldMayRun() }
        assertThrows(IOException::class.java) { owner.newMayRun(export) }
        assertThrows(IOException::class.java) { owner.accept(export) {} }
        assertEquals(0, storage.writes)
        storage.fault = "read"
        assertThrows(IOException::class.java) { owner.oldMayRun() }
        assertThrows(IOException::class.java) { owner.newMayRun(export) }
    }

    private class Death : Error()
    private class Storage : AtomicJournalStorage {
        var bytes: ByteArray? = null
        var fault = "none"
        var writes = 0
        override fun read(maxBytes: Int): ByteArray? {
            if (fault == "read" || fault == "readback" && writes > 0) throw IOException("Synthetic read failure")
            return bytes?.clone()
        }
        override fun replace(contents: ByteArray) {
            if (fault == "before") throw IOException("Synthetic precommit failure")
            bytes = contents.clone(); writes++
            if (fault == "after") throw IOException("Synthetic ack failure")
            if (fault == "death") throw Death()
        }
    }
}
