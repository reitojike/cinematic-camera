package dev.cinematic.p0.rawcapture

import dev.cinematic.p0.jpegrender.original.Original24Request
import dev.cinematic.p0.raw.PhotoAdjustments
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class ManualOriginalTrialAttemptJournalTest {
    private val source = SharedOriginalReference("content://test/original/1", OriginalFingerprint(3, "a".repeat(64)), OriginalGrant.USER_SELECTED)
    private val request = Original24Request(ManualOriginalTrialRequests.identity(source), 1, source, .7)

    @Test fun exactRequestV2RoundTripsWithoutChangingItsBytes() {
        val intent = Memory(ManualOriginalTrialRequestCodec.encode(request))
        val storage = Memory()
        val journal = ManualOriginalTrialAttemptJournal(request, storage, intent)
        val first = journal.start(null)
        assertEquals(1L, first.attempt)
        val clean = journal.confirmCancellation(first)
        assertEquals(clean, ManualOriginalTrialAttemptJournal(request, storage, intent).read())
        val next = journal.start(clean)
        assertEquals(2L, next.attempt)
        assertEquals(ManualTrialAttemptPhase.STARTED, next.phase)
        assertArrayEquals(ManualOriginalTrialRequestCodec.encode(request), intent.bytes)
        assertThrows(CaptureJournalConflict::class.java) { journal.start(clean) }
        assertThrows(CaptureJournalConflict::class.java) { journal.confirmCancellation(first) }
    }

    @Test fun everyRequestIdentityFieldMustMatch() {
        fun changedSource(source: SharedOriginalReference) = Original24Request(ManualOriginalTrialRequests.identity(source), 1, source, .7)
        val alternatives = listOf(
            changedSource(source.copy(uri = "content://test/original/2")),
            changedSource(source.copy(grant = OriginalGrant.APP_OWNED)),
            changedSource(source.copy(fingerprint = OriginalFingerprint(4, "a".repeat(64)))),
            changedSource(source.copy(fingerprint = OriginalFingerprint(3, "b".repeat(64)))),
            Original24Request(request.captureId, 2, source, .7),
            Original24Request(request.captureId, 1, source, .6),
            Original24Request(request.captureId, 1, source, .7, PhotoAdjustments(1.0, .1)),
        )
        for (other in alternatives) {
            val storage = Memory(ManualTrialAttemptCodec.encode(ManualTrialAttemptRecord(other, 1, ManualTrialAttemptPhase.CANCELLED_CLEAN)))
            val journal = ManualOriginalTrialAttemptJournal(request, storage, Memory(ManualOriginalTrialRequestCodec.encode(request)))
            assertThrows(CaptureJournalConflict::class.java) { journal.read() }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ManualTrialAttemptCodec.encode(ManualTrialAttemptRecord(request.copy(captureId = "TRIAL24_bad"), 1, ManualTrialAttemptPhase.STARTED))
        }
    }

    @Test fun unknownEngineVersionSchemaPhaseAttemptAndCorruptionAreRejected() {
        for (bytes in listOf(
            raw(engine = "CPU"), raw(engine = "UNKNOWN"), raw(version = "future"), raw(schema = 2),
            raw(attempt = 0), raw(attempt = -1), raw(phase = "PUBLISHED"), raw(trailing = true),
            raw().copyOf(20), raw().also { it[it.lastIndex] = (it.last() + 1).toByte() },
            ManualOriginalTrialRequestCodec.encode(request),
        )) assertThrows(IOException::class.java) { ManualTrialAttemptCodec.decode(bytes) }
        val storage = Memory(raw(attempt = Long.MAX_VALUE))
        val journal = ManualOriginalTrialAttemptJournal(request, storage, Memory(ManualOriginalTrialRequestCodec.encode(request)))
        assertThrows(IOException::class.java) { journal.start(journal.read()) }
    }

    @Test fun atomicWriteBoundariesKeepUncertaintyUntilANewReader() {
        for (phase in listOf("start", "clean", "retry")) for (fault in listOf("before", "after", "readback")) {
            val intent = Memory(ManualOriginalTrialRequestCodec.encode(request)); val storage = Memory()
            val journal = ManualOriginalTrialAttemptJournal(request, storage, intent)
            val prior = if (phase != "start") journal.start(null) else null
            val clean = if (phase == "retry") journal.confirmCancellation(prior!!) else null
            storage.fault = fault
            assertThrows(IOException::class.java) {
                if (phase == "clean") journal.confirmCancellation(prior!!) else journal.start(clean)
            }
            storage.fault = null
            assertThrows(IOException::class.java) { journal.read() }
            val restored = ManualOriginalTrialAttemptJournal(request, storage, intent).read()
            val committed = fault != "before"
            assertEquals(when (phase) {
                "start" -> if (committed) ManualTrialAttemptPhase.STARTED else null
                "clean" -> if (committed) ManualTrialAttemptPhase.CANCELLED_CLEAN else ManualTrialAttemptPhase.STARTED
                else -> if (committed) ManualTrialAttemptPhase.STARTED else ManualTrialAttemptPhase.CANCELLED_CLEAN
            }, restored?.phase)
        }
    }

    private fun raw(engine: String = "GPU", version: String = ManualTrialAttemptCodec.EXECUTION_VERSION,
        schema: Int = 1, attempt: Long = 1, phase: String = "STARTED", trailing: Boolean = false): ByteArray {
        val payload = ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use {
            it.writeInt(0x43434d41); it.writeInt(schema)
            val bytes = ManualOriginalTrialRequestCodec.encode(request)
            it.writeInt(bytes.size); it.write(bytes)
            it.writeUTF(engine); it.writeUTF(version); it.writeLong(attempt); it.writeUTF(phase)
            if (trailing) it.writeByte(1)
        } }.toByteArray()
        return payload + MessageDigest.getInstance("SHA-256").digest(payload)
    }

    private class Memory(var bytes: ByteArray? = null) : AtomicJournalStorage {
        var fault: String? = null
        private var unreadable = false
        override fun read(maxBytes: Int): ByteArray? {
            if (unreadable && fault != null) throw IOException("readback")
            return bytes?.clone()
        }
        override fun replace(contents: ByteArray) {
            if (fault == "before") throw IOException("before")
            bytes = contents.clone()
            if (fault == "after") throw IOException("after")
            if (fault == "readback") unreadable = true
        }
    }
}
