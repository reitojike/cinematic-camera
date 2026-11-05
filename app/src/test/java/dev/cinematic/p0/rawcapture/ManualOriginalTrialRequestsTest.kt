package dev.cinematic.p0.rawcapture

import dev.cinematic.p0.raw.PhotoAdjustments
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class ManualOriginalTrialRequestsTest {
    private val source = SharedOriginalReference("content://test/original/1", OriginalFingerprint(3, "a".repeat(64)), OriginalGrant.USER_SELECTED)

    @Test fun sameCanonicalStrengthReusesRequestAndChangeRequiresGenerationFence() {
        val storage = Storage()
        var allowed = false
        var fencedGeneration = 0L
        val requests = ManualOriginalTrialRequests(source, storage) { fencedGeneration = it.generation; allowed }
        val first = requests.prepare(-0.0)
        assertEquals(0.0.toRawBits(), first.strength.toRawBits())
        assertEquals(first, requests.prepare(0.0))
        assertEquals(1, storage.writes)
        assertTrue(requests.isCurrent(first))
        assertThrows(IOException::class.java) { requests.prepare(.7) }
        assertEquals(1L, fencedGeneration)
        assertEquals(1, storage.writes)
        allowed = true
        val second = requests.prepare(.7)
        assertEquals(2L, second.generation)
        assertEquals(.7, second.strength, 0.0)
        assertFalse(requests.isCurrent(first))
        assertTrue(requests.isCurrent(second))
        assertEquals(second, ManualOriginalTrialRequests(source, storage) { false }.prepare(.7))
    }

    @Test fun unknownPublicationOrResourceHoldCannotBeSkippedByNewStrength() {
        for (reason in listOf("unknown", "in-progress", "resources")) {
            val storage = Storage()
            val requests = ManualOriginalTrialRequests(source, storage) { false }
            val first = requests.prepare(.2)
            assertThrows(reason, IOException::class.java) { requests.prepare(.8) }
            assertTrue(requests.isCurrent(first))
            assertEquals(1, storage.writes)
        }
    }

    @Test fun strengthWarmthAndEvTogetherDefineIdempotentGenerationAcrossRestart() {
        val storage = Storage()
        var allowed = false
        var fences = 0
        val requests = ManualOriginalTrialRequests(source, storage) { fences++; allowed }
        val edits = PhotoAdjustments(30.0, .75)
        val first = requests.prepare(.5, edits)
        assertEquals(edits, first.adjustments)
        assertEquals(first, ManualOriginalTrialRequestCodec.decode(storage.bytes!!))
        assertEquals(first, requests.prepare(.5, PhotoAdjustments(30.0, .75)))
        assertEquals(first, ManualOriginalTrialRequests(source, storage) { false }.prepare(.5, edits))
        assertEquals(1, storage.writes)
        assertEquals(0, fences)
        for (changed in listOf(PhotoAdjustments(31.0, .75), PhotoAdjustments(30.0, .5))) {
            assertThrows(IOException::class.java) { requests.prepare(.5, changed) }
            assertTrue(requests.isCurrent(first))
        }
        assertEquals(1, storage.writes)
        allowed = true
        val warm = requests.prepare(.5, PhotoAdjustments(31.0, .75))
        assertEquals(2L, warm.generation)
        val exposure = requests.prepare(.5, PhotoAdjustments(31.0, .5))
        assertEquals(3L, exposure.generation)
        val strength = requests.prepare(.6, exposure.adjustments)
        assertEquals(4L, strength.generation)
        assertFalse(requests.isCurrent(exposure))
        assertTrue(requests.isCurrent(strength))
    }

    @Test fun signedZeroAdjustmentsCanonicalizeBeforePersistenceAndComparison() {
        val storage = Storage()
        val requests = ManualOriginalTrialRequests(source, storage) { false }
        val first = requests.prepare(-0.0, PhotoAdjustments(-0.0, -0.0))
        assertEquals(0.0.toRawBits(), first.adjustments.warmth.toRawBits())
        assertEquals(0.0.toRawBits(), first.adjustments.exposureEv.toRawBits())
        assertEquals(first, requests.prepare(0.0, PhotoAdjustments.NONE))
        assertEquals(1, storage.writes)
        assertThrows(IllegalArgumentException::class.java) {
            ManualOriginalTrialRequestCodec.encode(first.copy(adjustments = PhotoAdjustments(-0.0, 0.0)))
        }
    }

    @Test fun encodedAdjustmentValuesMustAgreeWithTheCompleteCanonicalRecipe() {
        val storage = Storage()
        val requests = ManualOriginalTrialRequests(source, storage) { true }
        val request = requests.prepare(.5, PhotoAdjustments(25.0, -.75))
        val encoded = storage.bytes!!.clone()
        val payload = encoded.copyOfRange(0, encoded.size - 32)
        val input = java.io.DataInputStream(java.io.ByteArrayInputStream(payload))
        input.readInt(); input.readInt(); input.readUTF(); input.readLong()
        input.readUTF(); input.readUTF(); input.readLong(); input.readUTF(); input.readDouble()
        val warmthOffset = payload.size - input.available()
        input.close()
        for (offset in listOf(warmthOffset, warmthOffset + 8)) {
            val altered = payload.clone().also { ByteBuffer.wrap(it).putDouble(offset, .25) }
            storage.bytes = checksum(altered)
            assertThrows(IOException::class.java) { ManualOriginalTrialRequestCodec.decode(storage.bytes!!) }
            assertThrows(IOException::class.java) { requests.prepare(request.strength, request.adjustments) }
            assertEquals(1, storage.writes)
        }
    }

    @Test fun replaceFailureBeforeCommitFencesSourceWithoutRewriting() {
        val storage = Storage().also { it.failBefore = true }
        val requests = ManualOriginalTrialRequests(source, storage) { true }
        assertThrows(IOException::class.java) { requests.prepare(.5) }
        storage.failBefore = false
        assertThrows(IOException::class.java) { requests.prepare(.5) }
        assertThrows(IOException::class.java) { requests.prepare(.8) }
        assertEquals(1, storage.writes)
        assertNull(storage.bytes)
    }

    @Test fun lostAcknowledgementIsOnlyResolvedByExactReadback() {
        val storage = Storage().also { it.failAfter = true }
        val requests = ManualOriginalTrialRequests(source, storage) { false }
        assertThrows(IOException::class.java) { requests.prepare(.5) }
        val persisted = ManualOriginalTrialRequestCodec.decode(storage.bytes!!)
        assertFalse(requests.isCurrent(persisted))
        storage.failAfter = false
        assertEquals(persisted, requests.prepare(.5))
        assertTrue(requests.isCurrent(persisted))
        assertEquals(1, storage.writes)
    }

    @Test fun readbackMismatchNeverReturnsAnUnconfirmedRequest() {
        val storage = Storage().also { it.hideReadback = true }
        val requests = ManualOriginalTrialRequests(source, storage) { true }
        assertThrows(IOException::class.java) { requests.prepare(.3) }
        assertEquals(1, storage.writes)
        assertThrows(IOException::class.java) { requests.prepare(.9) }
        storage.hideReadback = false
        assertEquals(.3, requests.prepare(.3).strength, 0.0)
        assertEquals(1, storage.writes)
    }

    @Test fun sourceIdentityIsBoundedAndIncludesGrantUriAndFingerprint() {
        val id = ManualOriginalTrialRequests.identity(source)
        assertTrue(id.matches(Regex("TRIAL24_[0-9a-f]{56}")))
        for (other in listOf(source.copy(uri = "content://test/original/2"), source.copy(grant = OriginalGrant.APP_OWNED),
            source.copy(fingerprint = OriginalFingerprint(4, "b".repeat(64))))) {
            assertNotEquals(id, ManualOriginalTrialRequests.identity(other))
            val storage = Storage()
            ManualOriginalTrialRequests(source, storage) { true }.prepare(.5)
            assertThrows(CaptureJournalConflict::class.java) { ManualOriginalTrialRequests(other, storage) { true }.prepare(.5) }
            assertEquals(1, storage.writes)
        }
    }

    @Test fun corruptFutureAndNoncanonicalRecordsAreNeverReinitialized() {
        val storage = Storage()
        val requests = ManualOriginalTrialRequests(source, storage) { true }
        requests.prepare(.5)
        val original = storage.bytes!!.clone()
        for (mode in listOf("corrupt", "future", "legacy", "trailing")) {
            val payload = original.copyOfRange(0, original.size - 32)
            storage.bytes = when (mode) {
                "corrupt" -> original.clone().also { it[12] = (it[12].toInt() xor 1).toByte() }
                "future" -> payload.also { ByteBuffer.wrap(it).putInt(4, 3) }.let(::checksum)
                "legacy" -> payload.also { ByteBuffer.wrap(it).putInt(4, 1) }.let(::checksum)
                else -> checksum(payload + byteArrayOf(1))
            }
            val invalid = storage.bytes!!.clone()
            assertThrows(IOException::class.java) { requests.prepare(.8) }
            assertArrayEquals(invalid, storage.bytes)
            assertEquals(1, storage.writes)
        }
    }

    @Test fun nonFiniteAndOutOfRangeStrengthDoNotWrite() {
        val storage = Storage()
        val requests = ManualOriginalTrialRequests(source, storage) { true }
        for (strength in listOf(Double.NaN, Double.POSITIVE_INFINITY, -.1, 1.1))
            assertThrows(IllegalArgumentException::class.java) { requests.prepare(strength) }
        assertEquals(0, storage.writes)
    }

    private fun checksum(payload: ByteArray) = payload + MessageDigest.getInstance("SHA-256").digest(payload)
    private class Storage : AtomicJournalStorage {
        var bytes: ByteArray? = null
        var writes = 0
        var failBefore = false
        var failAfter = false
        var hideReadback = false
        override fun read(maxBytes: Int) = if (hideReadback && writes > 0) null else bytes?.clone()
        override fun replace(contents: ByteArray) {
            writes++
            if (failBefore) throw IOException("before")
            bytes = contents.clone()
            if (failAfter) throw IOException("after")
        }
    }
}
