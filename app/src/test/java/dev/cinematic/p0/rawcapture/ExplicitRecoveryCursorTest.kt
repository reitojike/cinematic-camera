package dev.cinematic.p0.rawcapture

import dev.cinematic.p0.jpegrender.original.Original24Request
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class ExplicitRecoveryCursorTest {
    private val source = SharedOriginalReference("content://test/source/1", OriginalFingerprint(4, "a".repeat(64)), OriginalGrant.USER_SELECTED)
    private val previous = Original24Request(ManualOriginalTrialRequests.identity(source), 1, source, .5)
    private val consent = ExplicitRecoveryExport("b".repeat(32), previous, "c".repeat(64), null,
        previous.copy(generation = 2), ManualOriginalTrialRender.CONTRACT.renderer)

    @Test fun onlyDurableConsentCanInstallExactSuccessorAcrossOldHoldFence() {
        val storage = Storage()
        val requests = requests(storage)
        assertThrows(CaptureJournalConflict::class.java) { requests.installRecovery(consent) { false } }
        assertArrayEquals(ManualOriginalTrialRequestCodec.encode(previous), storage.bytes)
        assertEquals(consent.request, requests.installRecovery(consent) { true })
        repeat(3) { assertEquals(consent.request, requests.installRecovery(consent) { true }) }
        assertEquals(1, storage.writes)
        assertFalse(requests.isCurrent(previous))
        assertTrue(requests.isCurrent(consent.request))
    }

    @Test fun cursorWriteAndAckFailuresRecoverFixedGenerationWithoutReallocation() {
        for (fault in listOf("before", "after", "readback", "death")) {
            val storage = Storage().also { it.fault = fault }
            val requests = requests(storage)
            assertThrows(if (fault == "death") Death::class.java else IOException::class.java) { requests.installRecovery(consent) { true } }
            assertFalse(requests.isCurrent(previous))
            assertFalse(requests.isCurrent(consent.request))
            storage.fault = "none"
            assertEquals(consent.request, requests.installRecovery(consent) { true })
            assertEquals(consent.request, requests(storage).installRecovery(consent) { true })
            assertEquals(1, storage.writes)
        }
    }

    @Test fun foreignOrLaterCursorAndLostConsentAreNeverOverwritten() {
        val storage = Storage()
        val requests = requests(storage)
        requests.installRecovery(consent) { true }
        assertThrows(CaptureJournalConflict::class.java) { requests.installRecovery(consent) { false } }
        storage.bytes = ManualOriginalTrialRequestCodec.encode(previous.copy(generation = 3))
        val later = storage.bytes!!.clone()
        assertThrows(CaptureJournalConflict::class.java) { requests.installRecovery(consent) { true } }
        assertArrayEquals(later, storage.bytes)
    }

    private fun requests(storage: Storage) = ManualOriginalTrialRequests(source, storage) { false }
    private class Death : Error()
    private inner class Storage : AtomicJournalStorage {
        var bytes: ByteArray? = ManualOriginalTrialRequestCodec.encode(previous)
        var fault = "none"
        var writes = 0
        override fun read(maxBytes: Int): ByteArray? {
            if (fault == "readback" && writes > 0) throw IOException("Synthetic readback failure")
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
