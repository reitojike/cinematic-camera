package dev.cinematic.p0.rawcapture

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CaptureDevelopIntentTest {
    @get:Rule val temporary = TemporaryFolder()
    private val pair = setOf(RawOutput.DNG, RawOutput.JPEG)
    private val intent = CaptureDevelopIntent(RenderRecipeSnapshot("synthetic-final-policy-v1", "strength=0.5;profile=unobserved"),
        "synthetic-final-renderer-v1")
    private val fingerprint = OriginalFingerprint(3, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")

    @Test fun defaultCaptureHasNoIntentAndCannotBeRetrofitted() {
        val storage = Memory()
        val journal = CaptureJournal("capture", storage)
        assertNull(journal.create(pair).developIntent)
        assertThrows(CaptureJournalConflict::class.java) { journal.create(pair, intent) }
        assertNull(journal.read()!!.developIntent)
    }

    @Test fun versionOneAndTwoRemainOptedOutAcrossUpgradeWithoutReadSideWrites() {
        listOf(1, 2).forEach { version ->
            val storage = Memory().apply { bytes = legacy(version) }
            val before = storage.bytes!!.clone()
            val journal = CaptureJournal("capture", storage)
            assertNull(journal.read()!!.developIntent)
            assertEquals(0, storage.writes)
            assertArrayEquals(before, storage.bytes)
            assertNull(journal.received(0, RawOutput.DNG, 3).developIntent)
            assertNull(journal.recordOriginalFingerprint(1, fingerprint).developIntent)
        }
    }

    @Test fun explicitIntentSurvivesPartialSuccessInterruptionLateCallbackFingerprintAndRestart() {
        val storage = Memory()
        val journal = CaptureJournal("capture", storage)
        assertEquals(intent, journal.create(pair, intent).developIntent)
        assertEquals(intent, journal.received(0, RawOutput.DNG, 3).developIntent)
        assertEquals(intent, journal.recordOriginalFingerprint(1, fingerprint).developIntent)
        assertEquals(intent, journal.interrupt(2).developIntent)
        val late = journal.received(3, RawOutput.JPEG, 2)
        assertEquals(CaptureReceiptPhase.INTERRUPTED, late.phase)
        assertEquals(intent, late.developIntent)
        val restarted = CaptureJournal("capture", storage).recoverAfterProcessDeath()!!
        assertEquals(intent, restarted.developIntent)
        assertEquals(fingerprint, restarted.originalFingerprint)
        assertEquals(late.revision, restarted.revision)
    }

    @Test fun versionTwoFingerprintIsPreservedWhenReceivingTheOtherFormat() {
        val storage = Memory().apply { bytes = legacy(2, hasFingerprint = true) }
        val journal = CaptureJournal("capture", storage)
        val old = journal.read()!!
        assertEquals(fingerprint, old.originalFingerprint)
        assertNull(old.developIntent)
        assertEquals(0, storage.writes)
        val updated = journal.received(0, RawOutput.JPEG, 2)
        assertEquals(CaptureReceiptPhase.FILES_RECEIVED, updated.phase)
        assertEquals(fingerprint, updated.originalFingerprint)
        assertNull(updated.developIntent)
        assertEquals(fingerprint, CaptureJournal("capture", storage).read()!!.originalFingerprint)
    }

    @Test fun fullyReceivedIntentSurvivesNormalRecoveryWithoutClaimingRendererSuccess() {
        val storage = Memory()
        val journal = CaptureJournal("capture", storage)
        journal.create(pair, intent)
        journal.received(0, RawOutput.JPEG, 2)
        journal.received(1, RawOutput.DNG, 3)
        val recovered = CaptureJournal("capture", storage).recoverAfterProcessDeath()!!
        assertEquals(CaptureReceiptPhase.FILES_RECEIVED, recovered.phase)
        assertEquals(intent, recovered.developIntent)
        assertNull(recovered.originalFingerprint)
        assertEquals(AutoRenderQuality.FINAL_ORIGINAL, recovered.developIntent!!.requestedQuality)
        assertFalse(CaptureDevelopIntent::class.java.methods.any { it.name in setOf("completed", "publish", "submit") })
    }

    @Test fun trialAndBoundedQualitiesAreNotAutomaticIntents() {
        listOf(AutoRenderQuality.PREVIEW, AutoRenderQuality.PRIVATE_TRIAL_1024,
            AutoRenderQuality.MANUAL_BOUNDED_1024_PRELIMINARY).forEach { quality ->
            assertThrows(IllegalArgumentException::class.java) { CaptureDevelopIntent(intent.recipe, intent.rendererContract, quality) }
        }
    }

    @Test fun intentCommitReadbackPrecedesEveryOutputOpen() {
        val storage = Memory()
        val root = temporary.newFolder()
        var opens = 0
        val store = RawCaptureStore(root, "capture", storage, openNewOutput = { file ->
            opens++
            assertEquals(intent, CaptureJournal("capture", storage).read()!!.developIntent)
            file.outputStream()
        })
        val session = RawCaptureSession(store, DngPublicationJournal("capture", Memory()), UnusedRows())
        session.prepare(pair, intent)
        assertEquals(2, opens)
        assertEquals(intent, session.interruptAndCloseWriters()!!.developIntent)
        assertFalse(session.hasUnreleasedWriters)
        val restarted = RawCaptureSession(RawCaptureStore(root, "capture", storage),
            DngPublicationJournal("capture", Memory()), UnusedRows())
        assertEquals(intent, restarted.recoverAfterProcessDeath()!!.developIntent)
    }

    @Test fun createFailureBeforeOrAfterCommitOrAtReadbackNeverOpensStreamsOrDropsCommittedIntent() {
        listOf("before", "after", "readback").forEach { fault ->
            val storage = Memory().apply { failure = fault }
            var opens = 0
            val root = temporary.newFolder()
            val store = RawCaptureStore(root, "capture", storage, openNewOutput = { opens++; it.outputStream() })
            val session = RawCaptureSession(store, DngPublicationJournal("capture", Memory()), UnusedRows())
            assertThrows(IOException::class.java) { session.prepare(pair, intent) }
            assertEquals(0, opens)
            assertFalse(File(root, "capture/original.dng").exists())
            storage.failure = null
            val persisted = CaptureJournal("capture", storage).read()
            if (fault == "before") assertNull(persisted)
            else assertEquals(intent, persisted!!.developIntent)
        }
    }

    @Test fun maximumRecipeIsBoundedAndCorruptOrUnknownIntentCannotBeReset() {
        val storage = Memory()
        val journal = CaptureJournal("capture", storage)
        val large = CaptureDevelopIntent(RenderRecipeSnapshot("synthetic-policy", "\u0000".repeat(1024)), "synthetic-renderer")
        journal.create(pair, large)
        assertTrue(storage.bytes!!.size <= CaptureReceiptCodec.MAX_BYTES)
        assertEquals(large, journal.read()!!.developIntent)
        val corrupt = storage.bytes!!.clone().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        storage.bytes = corrupt
        assertThrows(IOException::class.java) { journal.read() }
        assertThrows(IOException::class.java) { journal.create(pair, intent) }
        assertArrayEquals(corrupt, storage.bytes)
    }

    @Test fun unknownIntentFlagAndRequestedQualityAreHeldWithValidChecksums() {
        val none = CaptureReceiptCodec.encode(CaptureReceipt("capture", 0, pair, emptyMap(), CaptureReceiptPhase.CAPTURING))
        val explicit = CaptureReceiptCodec.encode(CaptureReceipt("capture", 0, pair, emptyMap(), CaptureReceiptPhase.CAPTURING,
            developIntent = intent))
        val intentOffset = none.size - 33 // Last payload byte is the optional-intent flag.
        listOf(none.copyOfRange(0, none.size - 32).also { it[intentOffset] = 2 },
            explicit.copyOfRange(0, explicit.size - 32).also { it[intentOffset + 1] = 1 }).forEach { payload ->
            val unknown = payload + MessageDigest.getInstance("SHA-256").digest(payload)
            val storage = Memory().apply { bytes = unknown }
            val journal = CaptureJournal("capture", storage)
            assertThrows(IOException::class.java) { journal.read() }
            assertThrows(IOException::class.java) { journal.create(pair, intent) }
            assertEquals(0, storage.writes)
            assertArrayEquals(unknown, storage.bytes)
        }
    }

    private fun legacy(version: Int, hasFingerprint: Boolean = false): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.writeInt(0x4343524a); out.writeInt(version)
            out.writeByte(7); out.writeBytes("capture"); out.writeLong(0)
            out.writeByte(3); out.writeByte(0); out.writeLong(if (hasFingerprint) 3 else -1); out.writeLong(-1)
            if (version >= 2) {
                out.writeByte(if (hasFingerprint) 1 else 0)
                if (hasFingerprint) fingerprint.sha256.chunked(2).forEach { out.writeByte(it.toInt(16)) }
            }
        }
        val payload = buffer.toByteArray()
        return payload + MessageDigest.getInstance("SHA-256").digest(payload)
    }
    private class Memory : AtomicJournalStorage {
        var bytes: ByteArray? = null
        var writes = 0
        var failure: String? = null
        override fun read(maxBytes: Int): ByteArray? {
            if (failure == "readback" && writes > 0) throw IOException("Synthetic readback failure")
            return bytes?.clone()
        }
        override fun replace(contents: ByteArray) {
            if (failure == "before") throw IOException("Synthetic write failure")
            bytes = contents.clone(); writes++
            if (failure == "after") throw IOException("Synthetic response loss")
        }
    }
    private class UnusedRows : DngHandoffRows {
        override fun insert(target: DngPublicationTarget): String = error("No handoff on prepare")
        override fun inspect(uri: String): DngPendingRow? = error("No handoff on prepare")
        override fun find(target: DngPublicationTarget): List<DngPendingRow> = error("No handoff on prepare")
        override fun openRead(target: DngPublicationTarget, uri: String, pending: Boolean): java.io.InputStream = error("No handoff")
        override fun openOutput(target: DngPublicationTarget, uri: String): java.io.OutputStream = error("No handoff")
        override fun publish(target: DngPublicationTarget, uri: String) { error("No handoff on prepare") }
    }
}
