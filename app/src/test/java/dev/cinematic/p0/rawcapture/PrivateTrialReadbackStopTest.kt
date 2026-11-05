package dev.cinematic.p0.rawcapture

import dev.cinematic.p0.jpegrender.JpegGenerationRecipe
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class PrivateTrialReadbackStopTest {
    @Test fun comparisonStopRunsOffCallerAndKeepsItsExactTargetAndSlotUntilReadReturns() {
        val original = OriginalFingerprint(3, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        val request = AutoDevelopRequest("comparison", 1,
            SharedOriginalReference("content://test/original/1", original, OriginalGrant.APP_OWNED),
            JpegGenerationRecipe.trial().snapshot, JpegGenerationRecipe.TRIAL_SCHEMA, 1024, 1024,
            AutoRenderQuality.PRIVATE_TRIAL_1024, RenderedFaceState.NOT_RUN, RenderedExposureState.DIAGNOSTIC, AutoInputProfile.UNKNOWN)
        val storage = object : AtomicJournalStorage {
            var bytes: ByteArray? = null
            override fun read(maxBytes: Int) = bytes?.clone()
            override fun replace(contents: ByteArray) { bytes = contents.clone() }
        }
        val journal = AutoDevelopJournal(request.captureId, 1, storage)
        val started = journal.start(journal.prepare(request).revision)
        journal.rendered(started.revision, original, AutoRenderReceipt(request, JpegOutputDescription(16, 12, 1, "sRGB", 3)))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val callbacks = ArrayDeque<Runnable>()
        var oldStops = 0
        var laterStops = 0
        var exactStop: () -> Unit = { oldStops++ }
        val backend = object : AutoDevelopBackend {
            override val hasUnreleasedResources get() = false
            override fun releaseResources() {}
            override fun supports(request: AutoDevelopRequest) = true
            override fun verifyOriginal(request: AutoDevelopRequest, shouldStop: () -> Boolean) = error("No render")
            override fun createAttempt(request: AutoDevelopRequest, attemptId: String): AutoRenderAttempt = error("No render")
            override fun inspect(request: AutoDevelopRequest, attemptId: String, shouldStop: () -> Boolean): JpegInspection = error("No render")
            override fun publication(record: AutoDevelopRecord, isCurrent: () -> Boolean): AutoDevelopPublication = error("No publication")
            override fun privatePreviewStopAction() = exactStop
            override fun privatePreview(record: AutoDevelopRecord, shouldStop: () -> Boolean): PrivateTrialPreview {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                throw IOException("Synthetic blocked read cancelled")
            }
        }
        val allowed = AutoDevelopConditions(true, false, false, false, true, true, true, true)
        val admission = AutoDevelopCoordinator().apply { updateConditions(allowed) }
        val runner = AutoDevelopRunner(journal, admission, backend, Executor { callbacks.add(it) },
            interactive = true, observations = { allowed }) { true }
        val io = Executors.newSingleThreadExecutor()
        try {
            val read = io.submit<PrivateTrialPreview> { runner.readPrivateTrial { false } }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            runner.requestStop()
            assertEquals(0, oldStops)
            assertEquals(1, callbacks.size)
            assertFalse(read.isDone)
            assertNull(admission.tryStartObserved(allowed))
            release.countDown()
            assertThrows(ExecutionException::class.java) { read.get(5, TimeUnit.SECONDS) }
            val next = admission.tryStartObserved(allowed)
            assertNotNull(next)
            admission.completeAfterResourcesClosed(next!!)
            exactStop = { laterStops++ }
            callbacks.removeFirst().run() // A delayed cancellation must not look up the next frame.
            assertEquals(1, oldStops)
            assertEquals(0, laterStops)
        } finally { release.countDown(); io.shutdownNow() }
    }
}
