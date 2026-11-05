package dev.cinematic.p0.rawcapture

import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class AutoDevelopJournalTest {
    private val fp = OriginalFingerprint(3, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    private fun request() = AutoDevelopRequest("capture", 1, SharedOriginalReference("content://test/original/1", fp, OriginalGrant.APP_OWNED),
        RenderRecipeSnapshot("test-v1", "{\"fixed\":true}"), "test-final-v1", 16, 12, AutoRenderQuality.FINAL_ORIGINAL,
        RenderedFaceState.VERIFIED_NONE, RenderedExposureState.SUPPORTED, AutoInputProfile.SUPPORTED)

    @Test fun everyPhaseCommitFailurePreservesItsExactKnownOrUnknownDurableState() {
        for (phase in AutoJobPhase.entries) for (mode in listOf("before", "after", "readback")) {
            val storage = FaultStorage()
            val journal = AutoDevelopJournal("capture", 1, storage) { "a".repeat(32) }
            fun prepare() = journal.prepare(request())
            if (phase != AutoJobPhase.QUEUED) prepare()
            if (phase in setOf(AutoJobPhase.RETRYABLE, AutoJobPhase.RENDERED, AutoJobPhase.PUBLISHING, AutoJobPhase.PUBLISHED)) journal.start(0)
            if (phase in setOf(AutoJobPhase.PUBLISHING, AutoJobPhase.PUBLISHED)) journal.rendered(1, fp, observation())
            if (phase == AutoJobPhase.PUBLISHED) journal.publishing(2)
            val previous = journal.read()
            storage.phase = phase; storage.mode = mode
            assertThrows(IOException::class.java) {
                when (phase) {
                    AutoJobPhase.QUEUED -> prepare()
                    AutoJobPhase.RENDERING -> journal.start(0)
                    AutoJobPhase.RETRYABLE -> journal.retryable(1, AutoJobReason.INTERRUPTED)
                    AutoJobPhase.RENDERED -> journal.rendered(1, fp)
                    AutoJobPhase.PUBLISHING -> journal.publishing(2)
                    AutoJobPhase.PUBLISHED -> journal.published(3)
                    AutoJobPhase.HELD -> journal.hold(0, AutoJobReason.UNSUPPORTED)
                }
            }
            storage.mode = "none"; storage.failRead = false
            val observed = AutoDevelopJournal("capture", 1, storage).read()
            if (mode == "before") assertEquals(previous, observed) else assertEquals(phase, observed!!.phase)
            if (observed?.phase == AutoJobPhase.RENDERING) {
                assertEquals(AutoJobPhase.RETRYABLE, journal.recoverAfterProcessDeath()!!.phase)
                assertNull(journal.read()!!.outputFingerprint)
            }
            if (observed?.phase in setOf(AutoJobPhase.RENDERED, AutoJobPhase.PUBLISHING, AutoJobPhase.PUBLISHED)) {
                assertEquals(observed, journal.recoverAfterProcessDeath())
                assertEquals(fp, journal.read()!!.outputFingerprint)
            }
        }
    }

    @Test fun checksummedFutureSchemaAndCorruptionAreNeverReinitialized() {
        val storage = FaultStorage()
        val journal = AutoDevelopJournal("capture", 1, storage)
        journal.prepare(request())
        val original = storage.bytes!!.clone()
        val payload = original.copyOfRange(0, original.size - 32)
        ByteBuffer.wrap(payload).putInt(4, 3)
        storage.bytes = payload + MessageDigest.getInstance("SHA-256").digest(payload)
        val unknown = storage.bytes!!.clone()
        assertThrows(IOException::class.java) { journal.read() }
        assertThrows(IOException::class.java) { journal.prepare(request()) }
        assertArrayEquals(unknown, storage.bytes)
        storage.bytes = original
        storage.bytes!![12] = (storage.bytes!![12].toInt() xor 1).toByte()
        val corrupt = storage.bytes!!.clone()
        assertThrows(IOException::class.java) { journal.prepare(request()) }
        assertArrayEquals(corrupt, storage.bytes)
    }

    @Test fun publishedAndHeldOperationsCannotResetOrAcquireAnotherRenderAttempt() {
        val storage = FaultStorage()
        val journal = AutoDevelopJournal("capture", 1, storage)
        journal.prepare(request())
        journal.start(0); journal.rendered(1, fp, observation()); journal.publishing(2); journal.published(3)
        assertThrows(CaptureJournalConflict::class.java) { journal.retry(4) }
        assertThrows(CaptureJournalConflict::class.java) { journal.start(4) }
        assertThrows(CaptureJournalConflict::class.java) { journal.hold(4, AutoJobReason.RENDER_FAILED) }
        assertEquals(AutoJobPhase.PUBLISHED, journal.read()!!.phase)
    }
    @Test fun legacyVersionOneFullOriginalRecordRemainsReadableWithoutInventingObservations() {
        val record = AutoDevelopRecord(request())
        val encoded = AutoDevelopCodec.encode(record)
        // v1 ended at reason; v2 appends the optional observation flag for this no-output record.
        val legacyPayload = encoded.copyOfRange(0, encoded.size - 33)
        ByteBuffer.wrap(legacyPayload).putInt(4, 1)
        val legacy = legacyPayload + MessageDigest.getInstance("SHA-256").digest(legacyPayload)
        assertEquals(record, AutoDevelopCodec.decode(legacy))
        assertNull(AutoDevelopCodec.decode(legacy).observed)
    }

    @Test fun missingObservationIsReadableButCannotBecomeCompletedOrBeginPublication() {
        val journal = AutoDevelopJournal("capture", 1, FaultStorage())
        journal.prepare(request()); journal.start(0)
        val rendered = journal.rendered(1, fp)
        assertEquals(rendered, AutoDevelopCodec.decode(AutoDevelopCodec.encode(rendered)))
        assertThrows(IOException::class.java) { rendered.completed() }
        assertThrows(CaptureJournalConflict::class.java) { journal.publishing(rendered.revision) }
        assertEquals(rendered, journal.read())
    }

    @Test fun observedOutputSurvivesRestartAndIsUsedInsteadOfRequestedDefaults() {
        val storage = FaultStorage()
        val journal = AutoDevelopJournal("capture", 1, storage)
        journal.prepare(request()); journal.start(0); journal.rendered(1, fp, observation())
        val record = AutoDevelopJournal("capture", 1, storage).read()!!
        assertEquals(observation().output, record.completed().request.output)
        assertEquals(observation().face, record.completed().face)
        assertEquals(AutoJobPhase.PUBLISHING, journal.publishing(record.revision).phase)
    }

    @Test fun versionOneTerminalRecordKeepsItsArtifactWithoutInventingACompletion() {
        val record = AutoDevelopRecord(request(), revision = 2, phase = AutoJobPhase.RENDERED,
            attemptId = "a".repeat(32), attemptCount = 1, outputFingerprint = fp)
        val encoded = AutoDevelopCodec.encode(record)
        val payload = encoded.copyOfRange(0, encoded.size - 33) // Remove v2's absent-observation flag and checksum.
        ByteBuffer.wrap(payload).putInt(4, 1)
        val legacy = payload + MessageDigest.getInstance("SHA-256").digest(payload)
        val restored = AutoDevelopCodec.decode(legacy)
        assertEquals(record, restored)
        assertEquals(fp, restored.outputFingerprint)
        assertThrows(IOException::class.java) { restored.completed() }
    }

    private fun observation() = AutoRenderReceipt(request(), JpegOutputDescription(16, 12, 1, "sRGB", fp.bytes),
        RenderedFaceState.VERIFIED_NONE, RenderedExposureState.SUPPORTED)
    private class FaultStorage : AtomicJournalStorage {
        var bytes: ByteArray? = null
        var phase: AutoJobPhase? = null
        var mode = "none"
        var failRead = false
        override fun read(maxBytes: Int): ByteArray? { if (failRead) throw IOException("readback fault"); return bytes?.clone() }
        override fun replace(contents: ByteArray) {
            val record = AutoDevelopCodec.decode(contents)
            if (phase == record.phase && mode == "before") throw IOException("before commit")
            bytes = contents.clone()
            if (phase == record.phase) {
                if (mode == "after") throw IOException("after commit")
                if (mode == "readback") failRead = true
            }
        }
    }
}
