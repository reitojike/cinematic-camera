package dev.cinematic.p0.rawcapture

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.jpegrender.JpegGenerationRecipe
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual default Android factory/AtomicFile recovery. No renderer, camera or shared-media write. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class AutoDevelopInventoryInstrumentationTest {
    @Test fun defaultFactoryRestoresExistingPrivateRequestBeforeTheNextPrepareWithoutRendering() {
        check(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        check(Build.HARDWARE in setOf("goldfish", "ranchu"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.noBackupFilesDir, "raw-captures")
        val source = SharedOriginalReference("content://synthetic/inventory/${UUID.randomUUID()}",
            OriginalFingerprint(3, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"), OriginalGrant.USER_SELECTED)
        val id = ManualDevelopRequests.identity(source)
        val capture = File(root, id)
        check(!capture.exists())
        try {
            val allocator = ManualDevelopRequests(source, AndroidAtomicJournalStorage(root, id, CaptureJournalFile.MANUAL_REQUEST))
            val request = allocator.prepare(JpegGenerationRecipe.trial().snapshot)
            val generation = RenderedJpegFiles(root, id, request.generation).generationRoot()
            val journal = AutoDevelopJournal(id, request.generation,
                AndroidAtomicJournalStorage(requireNotNull(generation.parentFile), request.generation.toString(), CaptureJournalFile.AUTO_DEVELOP))
            journal.prepare(request)
            val interrupted = journal.start(0)
            val file = File(generation, CaptureJournalFile.AUTO_DEVELOP.fileName)
            file.copyTo(File(file.path + ".bak"))
            check(file.delete())
            val retained = File(capture, "provisional.jpg").also { it.writeBytes(byteArrayOf(1, 2, 3)) }
            val owner = AndroidAutoDevelopQueue.get(context)
            val handle = AndroidAutoDevelopQueue.preparePrivateTrial(context, source).get(20, TimeUnit.SECONDS)
            assertSame(owner, AndroidAutoDevelopQueue.get(context))
            assertEquals(PrivateTrialPhase.RETRYABLE, handle.status().get(20, TimeUnit.SECONDS).phase)
            val recovered = journal.read()!!
            assertEquals(AutoJobPhase.RETRYABLE, recovered.phase)
            assertEquals(AutoJobReason.INTERRUPTED, recovered.reason)
            assertEquals(interrupted.attemptId, recovered.attemptId)
            assertEquals(1, recovered.attemptCount)
            assertNull(recovered.outputFingerprint)
            assertFalse(File(generation, CaptureJournalFile.JPEG_PUBLICATION.fileName).exists())
            assertArrayEquals(byteArrayOf(1, 2, 3), retained.readBytes())
        } finally {
            // Only this unique synthetic operation; never the shared root or any existing capture.
            check(capture.canonicalFile.parentFile == root.canonicalFile)
            check(capture.deleteRecursively())
        }
    }
}
