package dev.cinematic.p0.rawcapture

import android.os.Build
import android.util.AtomicFile
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.jpegrender.original.Original24Request
import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

/** Real AtomicFile on fresh private synthetic fixtures; never edits a product capture directory. */
class ManualTrialAttemptStorageAndroidTest {
    @Test fun unfinishedReplacementCannotConsumeCleanProofAndCommittedStartedCannotRestoreIt() {
        val root = fixture()
        val source = SharedOriginalReference("content://synthetic/durable/1", OriginalFingerprint(3, "a".repeat(64)), OriginalGrant.APP_OWNED)
        val request = Original24Request(ManualOriginalTrialRequests.identity(source), 1, source, .65)
        fun store(kind: CaptureJournalFile) = AndroidAtomicJournalStorage(root, request.captureId, kind)
        val intent = store(CaptureJournalFile.MANUAL_REQUEST)
        intent.replace(ManualOriginalTrialRequestCodec.encode(request))
        val journal = ManualOriginalTrialAttemptJournal(request, store(CaptureJournalFile.MANUAL_ATTEMPT), intent)
        val clean = journal.confirmCancellation(journal.start(null))
        val started = clean.copy(attempt = 2, phase = ManualTrialAttemptPhase.STARTED)
        val atomic = AtomicFile(CaptureJournalPaths.journalFile(root, request.captureId, CaptureJournalFile.MANUAL_ATTEMPT))
        // Emulate a writer lost before finishWrite. No producer could pass the required readback.
        atomic.startWrite().use { it.write(ManualTrialAttemptCodec.encode(started)); it.fd.sync() }
        assertEquals(clean, ManualOriginalTrialAttemptJournal(request, store(CaptureJournalFile.MANUAL_ATTEMPT), intent).read())
        val reader = ManualOriginalTrialAttemptJournal(request, store(CaptureJournalFile.MANUAL_ATTEMPT), intent)
        assertEquals(started, reader.start(clean))
        assertEquals(started, ManualOriginalTrialAttemptJournal(request, store(CaptureJournalFile.MANUAL_ATTEMPT), intent).read())
        assertArrayEquals(ManualOriginalTrialRequestCodec.encode(request), intent.read(ManualOriginalTrialRequestCodec.MAX_BYTES))
    }

    @Test fun beforeCommitAfterCommitAndReadbackFailuresPreserveTheExactDurableOutcome() {
        for (phase in listOf("start", "clean", "retry")) for (fault in listOf("before", "after", "readback")) {
            val root = fixture()
            val source = SharedOriginalReference("content://synthetic/durable/2", OriginalFingerprint(3, "b".repeat(64)), OriginalGrant.APP_OWNED)
            val request = Original24Request(ManualOriginalTrialRequests.identity(source), 1, source, .65)
            val intent = AndroidAtomicJournalStorage(root, request.captureId, CaptureJournalFile.MANUAL_REQUEST)
            intent.replace(ManualOriginalTrialRequestCodec.encode(request))
            val actual = AndroidAtomicJournalStorage(root, request.captureId, CaptureJournalFile.MANUAL_ATTEMPT)
            var armed = false; var wrote = false
            val failing = object : AtomicJournalStorage {
                override fun read(maxBytes: Int): ByteArray? {
                    if (armed && wrote && fault == "readback") throw IOException("readback")
                    return actual.read(maxBytes)
                }
                override fun replace(contents: ByteArray) {
                    if (armed && fault == "before") throw IOException("before")
                    actual.replace(contents)
                    if (armed) { wrote = true; if (fault == "after") throw IOException("after") }
                }
            }
            val journal = ManualOriginalTrialAttemptJournal(request, failing, intent)
            val prior = if (phase != "start") journal.start(null) else null
            val clean = if (phase == "retry") journal.confirmCancellation(prior!!) else null
            armed = true
            assertThrows(IOException::class.java) {
                if (phase == "clean") journal.confirmCancellation(prior!!) else journal.start(clean)
            }
            assertThrows(IOException::class.java) { journal.read() }
            val record = ManualOriginalTrialAttemptJournal(request, actual, intent).read()
            assertEquals(when (phase) {
                "start" -> if (fault == "before") null else ManualTrialAttemptPhase.STARTED
                "clean" -> if (fault == "before") ManualTrialAttemptPhase.STARTED else ManualTrialAttemptPhase.CANCELLED_CLEAN
                else -> if (fault == "before") ManualTrialAttemptPhase.CANCELLED_CLEAN else ManualTrialAttemptPhase.STARTED
            }, record?.phase)
        }
    }

    private fun fixture(): File {
        check(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        return File(app.cacheDir, "durable-attempt-fixture-${java.util.UUID.randomUUID()}").also { check(it.mkdirs()) }
    }
}
