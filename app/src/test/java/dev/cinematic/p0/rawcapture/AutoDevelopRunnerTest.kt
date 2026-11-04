package dev.cinematic.p0.rawcapture

import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class AutoDevelopRunnerTest {
    private val fingerprint = OriginalFingerprint(3, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    private fun request() = AutoDevelopRequest("capture", 1,
        SharedOriginalReference("content://test/original/1", fingerprint, OriginalGrant.APP_OWNED),
        RenderRecipeSnapshot("test-v1", "fixed-strength-0.5"), "test-renderer-v1", 2000, 1500,
        AutoRenderQuality.FINAL_ORIGINAL, RenderedFaceState.VERIFIED_NONE, RenderedExposureState.SUPPORTED,
        AutoInputProfile.SUPPORTED)
    private val allowed = AutoDevelopConditions(true, false, false, false, true, true, true, true)

    @Test fun unknownConditionsAndCaptureReservationsDoNotBeginOrTouchSource() {
        val f = Fixture()
        assertEquals(AutoRunStatus.BLOCKED, f.runner.advance().status)
        f.admission.updateConditions(allowed)
        val capture = f.admission.reserveCapture()
        assertEquals(AutoRunStatus.BLOCKED, f.runner.advance().status)
        assertEquals(0, f.backend.verifications)
        assertEquals(0, f.backend.starts)
        f.admission.releaseCapture(capture)
        assertEquals(AutoRunStatus.PUBLISHED, f.runner.advance().status)
        assertEquals(1, f.backend.starts)
        assertEquals(1, f.backend.publishes)
    }

    @Test fun fullResultPublishesOnlyAfterClosedOutputAndDurableReceipt() {
        val f = Fixture()
        f.admission.updateConditions(allowed)
        f.backend.beforePublish = {
            assertTrue(f.backend.closed)
            assertEquals(AutoJobPhase.PUBLISHING, f.journal.read()!!.phase)
            assertEquals(fingerprint, f.journal.read()!!.outputFingerprint)
        }
        assertEquals(AutoRunStatus.PUBLISHED, f.runner.advance().status)
        val done = f.journal.read()!!
        assertEquals(AutoJobPhase.PUBLISHED, done.phase)
        assertNotNull(done.attemptId)
        assertEquals(1, f.backend.starts)
        assertEquals(1, f.backend.publishes)
        assertFalse(f.runner.hasUnreleasedResources)
        assertEquals(AutoRunStatus.PUBLISHED, f.runner.advance().status)
        assertEquals(1, f.backend.publishes)
    }

    @Test fun previewUnknownFaceExposureAndProfileNeverStartAutomaticWork() {
        for (request in listOf(request().copy(quality = AutoRenderQuality.PREVIEW),
            request().copy(face = RenderedFaceState.NOT_RUN), request().copy(exposure = RenderedExposureState.DIAGNOSTIC),
            request().copy(profile = AutoInputProfile.UNKNOWN))) {
            val f = Fixture(request)
            f.admission.updateConditions(allowed)
            assertEquals(AutoRunStatus.HELD, f.runner.advance().status)
            assertEquals(0, f.backend.starts)
            assertEquals(0, f.backend.verifications)
            assertEquals(0, f.backend.publishes)
        }
        val f = Fixture()
        f.backend.adapterQuality = AutoRenderQuality.PREVIEW
        f.admission.updateConditions(allowed)
        assertEquals(AutoRunStatus.HELD, f.runner.advance().status)
        assertEquals(0, f.backend.starts)
    }

    @Test fun captureStopDoesNotWaitForRendererAndLateSuccessIsNotPublished() {
        val f = Fixture()
        f.admission.updateConditions(allowed)
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        f.backend.onRender = { entered.countDown(); check(finish.await(5, TimeUnit.SECONDS)) }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val running = executor.submit<AutoRunResult> { f.runner.advance() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val capture = f.admission.reserveCapture() // Returns while deliberately non-cooperative render is still blocked.
            assertFalse(running.isDone)
            assertNull(f.admission.tryStart())
            finish.countDown()
            assertEquals(AutoRunStatus.RETRYABLE, running.get(5, TimeUnit.SECONDS).status)
            assertEquals(AutoJobPhase.RETRYABLE, f.journal.read()!!.phase)
            assertEquals(0, f.backend.publishes)
            f.admission.releaseCapture(capture)
            assertEquals(AutoRunStatus.RETRYABLE, f.runner.advance().status) // No immediate retry spin.
            f.journal.retry(f.journal.read()!!.revision)
            f.backend.onRender = {}
            assertEquals(AutoRunStatus.PUBLISHED, f.runner.advance().status)
            assertEquals(2, f.backend.starts)
            assertEquals(2, f.backend.attempts.distinct().size)
        } finally { finish.countDown(); executor.shutdownNow() }
    }

    @Test fun closeFailureRetainsAttemptAndSlotUntilActualAcknowledgement() {
        val f = Fixture()
        f.admission.updateConditions(allowed)
        f.backend.closeFails = true
        assertEquals(AutoRunStatus.RESOURCES_HELD, f.runner.advance().status)
        assertTrue(f.runner.hasUnreleasedResources)
        assertNull(f.admission.tryStart())
        assertEquals(AutoJobPhase.RENDERING, f.journal.read()!!.phase)
        assertEquals(0, f.backend.publishes)
        assertEquals(AutoRunStatus.RESOURCES_HELD, f.runner.advance().status)
        assertEquals(1, f.backend.starts)
        f.backend.closeFails = false
        assertEquals(AutoRunStatus.PUBLISHED, f.runner.advance().status)
        assertEquals(1, f.backend.starts)
        assertEquals(1, f.backend.publishes)
        assertFalse(f.runner.hasUnreleasedResources)
        val slot = f.admission.tryStart()!!
        assertTrue(f.admission.completeAfterResourcesClosed(slot))
    }

    @Test fun blockedNativeCancellationCallbackDoesNotBlockCaptureOrReleaseItsOwnerEarly() {
        val cancelIo = Executors.newSingleThreadExecutor()
        val renderIo = Executors.newSingleThreadExecutor()
        val renderEntered = CountDownLatch(1)
        val renderFinish = CountDownLatch(1)
        val cancelEntered = CountDownLatch(1)
        val cancelFinish = CountDownLatch(1)
        val f = Fixture(cancellationIo = cancelIo)
        f.backend.onRender = { renderEntered.countDown(); check(renderFinish.await(5, TimeUnit.SECONDS)) }
        f.backend.onCancel = { cancelEntered.countDown(); check(cancelFinish.await(5, TimeUnit.SECONDS)) }
        f.admission.updateConditions(allowed)
        try {
            val running = renderIo.submit<AutoRunResult> { f.runner.advance() }
            assertTrue(renderEntered.await(5, TimeUnit.SECONDS))
            val capture = f.admission.reserveCapture()
            f.runner.requestStop() // The occupied cancellation callback must not execute on this caller.
            assertTrue(cancelEntered.await(5, TimeUnit.SECONDS))
            assertFalse(running.isDone)
            renderFinish.countDown()
            assertEquals(AutoRunStatus.RESOURCES_HELD, running.get(5, TimeUnit.SECONDS).status)
            assertFalse(f.backend.closed)
            assertNull(f.admission.tryStart())
            cancelFinish.countDown()
            cancelIo.submit {}.get(5, TimeUnit.SECONDS) // Cancellation callback's finally has acknowledged its return.
            assertEquals(AutoRunStatus.RETRYABLE, f.runner.advance().status)
            assertTrue(f.backend.closed)
            f.admission.releaseCapture(capture)
            val slot = f.admission.tryStart()!!
            f.admission.completeAfterResourcesClosed(slot)
        } finally { renderFinish.countDown(); cancelFinish.countDown(); renderIo.shutdownNow(); cancelIo.shutdownNow() }
    }

    @Test fun delayedStopSnapshotNeverCancelsTheNextNativeAttemptOfTheSameRunner() {
        val f = Fixture(cancellationIo = java.util.concurrent.Executor { it.run() })
        val entered = java.util.concurrent.LinkedBlockingQueue<Unit>()
        val finish = java.util.concurrent.Semaphore(0)
        val cancellations = java.util.concurrent.atomic.AtomicInteger()
        val worker = Executors.newSingleThreadExecutor()
        f.backend.onRender = { entered.put(Unit); check(finish.tryAcquire(5, TimeUnit.SECONDS)) }
        f.backend.onCancel = { cancellations.incrementAndGet() }
        f.backend.renderFails = true
        f.admission.updateConditions(allowed)
        try {
            val first = worker.submit<AutoRunResult> { f.runner.advance() }
            assertNotNull(entered.poll(5, TimeUnit.SECONDS))
            val oldTarget = f.runner.stopTarget()!!
            finish.release()
            assertEquals(AutoRunStatus.RETRYABLE, first.get(5, TimeUnit.SECONDS).status)
            f.journal.retry(f.journal.read()!!.revision)
            f.backend.renderFails = false
            val second = worker.submit<AutoRunResult> { f.runner.advance() }
            assertNotNull(entered.poll(5, TimeUnit.SECONDS))
            f.runner.requestStop(oldTarget)
            assertEquals(0, cancellations.get())
            finish.release()
            assertEquals(AutoRunStatus.PUBLISHED, second.get(5, TimeUnit.SECONDS).status)
        } finally { finish.release(2); worker.shutdownNow() }
    }

    @Test fun failedRenderRequiresExplicitRetryAndEveryRetryRechecksOriginal() {
        val f = Fixture()
        f.admission.updateConditions(allowed)
        f.backend.renderFails = true
        assertEquals(AutoRunStatus.RETRYABLE, f.runner.advance().status)
        assertEquals(0, f.backend.publishes)
        assertEquals(AutoRunStatus.RETRYABLE, f.runner.advance().status)
        assertEquals(1, f.backend.starts)
        f.journal.retry(f.journal.read()!!.revision)
        f.backend.renderFails = false
        f.backend.sourceChanged = true
        assertEquals(AutoRunStatus.HELD, f.runner.advance().status)
        assertEquals(1, f.backend.starts)
        assertEquals(2, f.backend.verifications)
    }

    @Test fun invalidOrPreviewReceiptNeverBecomesRenderedOrPublished() {
        for (change in listOf<(AutoRenderReceipt) -> AutoRenderReceipt>(
            { it.copy(request = it.request.copy(generation = 2)) },
            { it.copy(request = it.request.copy(recipe = it.request.recipe.copy(payload = "different"))) },
            { it.copy(request = it.request.copy(quality = AutoRenderQuality.PREVIEW)) },
            { it.copy(output = it.output.copy(width = 512, height = 384)) },
        )) {
            val f = Fixture()
            f.admission.updateConditions(allowed)
            f.backend.receiptChange = change
            assertEquals(AutoRunStatus.HELD, f.runner.advance().status)
            assertEquals(AutoJobPhase.HELD, f.journal.read()!!.phase)
            assertEquals(0, f.backend.publishes)
        }
    }

    @Test fun unsupportedDetectedByTheActualRendererIsHeldAfterClosingItsInputs() {
        val f = Fixture()
        f.admission.updateConditions(allowed)
        f.backend.renderUnsupported = true
        assertEquals(AutoRunStatus.HELD, f.runner.advance().status)
        assertEquals(AutoJobReason.UNSUPPORTED, f.journal.read()!!.reason)
        assertTrue(f.backend.closed)
        assertFalse(f.runner.hasUnreleasedResources)
        assertEquals(0, f.backend.publishes)
        assertThrows(CaptureJournalConflict::class.java) { f.journal.retry(f.journal.read()!!.revision) }
    }

    @Test fun publicationAcknowledgementLossRetriesSameArtifactWithoutRerendering() {
        val f = Fixture()
        f.admission.updateConditions(allowed)
        f.backend.publishFails = true
        assertEquals(AutoRunStatus.PUBLICATION_PENDING, f.runner.advance().status)
        val record = f.journal.read()!!
        assertEquals(AutoJobPhase.PUBLISHING, record.phase)
        assertThrows(CaptureJournalConflict::class.java) { f.journal.retry(record.revision) }
        f.backend.publishFails = false
        assertEquals(AutoRunStatus.PUBLISHED, f.runner.advance().status)
        assertEquals(record.attemptId, f.journal.read()!!.attemptId)
        assertEquals(1, f.backend.starts)
        assertEquals(1, f.backend.publisherInstances)
    }

    @Test fun publicationCloseFailureKeepsSlotAndSamePublisher() {
        val f = Fixture()
        f.admission.updateConditions(allowed)
        f.backend.publicationCloseFails = true
        assertEquals(AutoRunStatus.RESOURCES_HELD, f.runner.advance().status)
        assertNull(f.admission.tryStart())
        assertEquals(1, f.backend.publisherInstances)
        f.backend.publicationCloseFails = false
        assertEquals(AutoRunStatus.PUBLISHED, f.runner.advance().status)
        assertEquals(1, f.backend.publisherInstances)
        assertEquals(1, f.backend.starts)
    }

    @Test fun detectedContentChangeDuringPublicationHoldsInsteadOfRepeatingHeavyIoOnEveryWake() {
        val f = Fixture()
        f.admission.updateConditions(allowed)
        f.backend.beforePublish = { throw DngContentChanged() }
        assertEquals(AutoRunStatus.HELD, f.runner.advance().status)
        assertEquals(AutoJobReason.CONTENT_CHANGED, f.journal.read()!!.reason)
        repeat(3) { assertEquals(AutoRunStatus.HELD, f.runner.advance().status) }
        assertEquals(1, f.backend.starts)
        assertEquals(0, f.backend.publishes)
        assertFalse(f.runner.hasUnreleasedResources)
    }

    @Test fun processRecoveryDoesNotPromoteUnacknowledgedRenderOutput() {
        val f = Fixture()
        val rendering = f.journal.start(f.journal.read()!!.revision)
        val before = rendering.attemptId
        val recovered = AutoDevelopJournal("capture", 1, f.storage).recoverAfterProcessDeath()
        assertEquals(AutoJobPhase.RETRYABLE, recovered!!.phase)
        assertEquals(before, recovered.attemptId)
        assertNull(recovered.outputFingerprint)
        assertEquals(AutoRunStatus.RETRYABLE, f.runner.advance().status)
        f.journal.retry(recovered.revision)
        f.admission.updateConditions(allowed)
        assertEquals(AutoRunStatus.PUBLISHED, f.runner.advance().status)
        assertNotEquals(before, f.journal.read()!!.attemptId)
    }

    @Test fun interruptedDecisionSurvivesCommitFaultAndResourceSlotReleaseUntilExplicitRetry() {
        for (closeFailure in listOf(false, true)) for (fault in listOf("before", "after", "readback")) {
            val f = Fixture()
            f.admission.updateConditions(allowed)
            var capture: AutoDevelopCoordinator.CaptureReservation? = null
            f.backend.onRender = { capture = f.admission.reserveCapture() } // Renderer still returns a late success.
            if (closeFailure) {
                f.backend.closeFails = true
                assertEquals(AutoRunStatus.RESOURCES_HELD, f.runner.advance().status)
                f.backend.closeFails = false
            }
            f.storage.interruptedFault = fault
            assertThrows(IOException::class.java) { f.runner.advance() }
            assertFalse(f.runner.hasUnreleasedResources) // Do not retain a closed physical slot merely for metadata retry.
            f.admission.releaseCapture(capture!!)
            f.backend.onRender = {}
            assertEquals(AutoRunStatus.RETRYABLE, f.runner.advance().status)
            assertEquals(AutoJobReason.INTERRUPTED, f.journal.read()!!.reason)
            assertEquals(1, f.backend.starts)
            assertEquals(0, f.backend.publisherInstances)
            assertEquals(0, f.backend.publishes)
            f.journal.retry(f.journal.read()!!.revision)
            assertEquals(AutoRunStatus.PUBLISHED, f.runner.advance().status)
            assertEquals(2, f.backend.starts)
            assertEquals(2, f.backend.attempts.distinct().size)
        }
    }

    @Test fun immutableRequestCorruptionUnknownSchemaAndCasCannotResetTheJob() {
        val f = Fixture()
        val initial = f.journal.read()!!
        assertEquals(initial, AutoDevelopCodec.decode(AutoDevelopCodec.encode(initial)))
        assertThrows(CaptureJournalConflict::class.java) { f.journal.prepare(request().copy(recipe = request().recipe.copy(payload = "new"))) }
        f.journal.start(initial.revision)
        assertThrows(CaptureJournalConflict::class.java) { f.journal.start(initial.revision) }
        val before = f.storage.bytes!!.clone()
        f.storage.bytes!![9] = (f.storage.bytes!![9].toInt() xor 1).toByte()
        assertThrows(IOException::class.java) { f.journal.read() }
        assertThrows(IOException::class.java) { f.journal.prepare(request()) }
        f.storage.bytes = before
        assertThrows(IOException::class.java) { AutoDevelopJournal("other", 1, f.storage).read() }
    }

    private inner class Fixture(request: AutoDevelopRequest = request(), cancellationIo: java.util.concurrent.Executor? = null) {
        val storage = MemoryStorage()
        val journal = AutoDevelopJournal("capture", 1, storage)
        val admission = AutoDevelopCoordinator()
        val backend = Backend(request)
        val runner = if (cancellationIo == null) AutoDevelopRunner(journal, admission, backend) { true }
            else AutoDevelopRunner(journal, admission, backend, cancellationIo) { true }
        init { journal.prepare(request) }
    }
    private class MemoryStorage : AtomicJournalStorage {
        var bytes: ByteArray? = null
        var interruptedFault = "none"
        private var failNextRead = false
        override fun read(maxBytes: Int): ByteArray? {
            if (failNextRead) { failNextRead = false; throw IOException("readback fault") }
            return bytes?.clone()
        }
        override fun replace(contents: ByteArray) {
            val record = AutoDevelopCodec.decode(contents)
            val fault = interruptedFault.takeIf { record.phase == AutoJobPhase.RETRYABLE && record.reason == AutoJobReason.INTERRUPTED } ?: "none"
            if (fault != "none") interruptedFault = "none"
            if (fault == "before") throw IOException("before interrupted commit")
            bytes = contents.clone()
            if (fault == "after") throw IOException("after interrupted commit")
            if (fault == "readback") failNextRead = true
        }
    }

    private inner class Backend(private val request: AutoDevelopRequest) : AutoDevelopBackend {
        var starts = 0
        var publishes = 0
        var publisherInstances = 0
        var verifications = 0
        var closed = false
        var closeFails = false
        var renderFails = false
        var renderUnsupported = false
        var sourceChanged = false
        var publishFails = false
        var publicationCloseFails = false
        var adapterQuality = AutoRenderQuality.FINAL_ORIGINAL
        val attempts = mutableListOf<String>()
        var onRender: () -> Unit = {}
        var onCancel: () -> Unit = {}
        var beforePublish: () -> Unit = {}
        var receiptChange: (AutoRenderReceipt) -> AutoRenderReceipt = { it }
        override val hasUnreleasedResources get() = false
        override fun releaseResources() {}
        override fun supports(request: AutoDevelopRequest) = adapterQuality == AutoRenderQuality.FINAL_ORIGINAL
        override fun verifyOriginal(request: AutoDevelopRequest, shouldStop: () -> Boolean) {
            verifications++
            if (sourceChanged) throw DngContentChanged()
            DngContentVerifier.verify(fingerprint, { ByteArrayInputStream("abc".toByteArray()) }, shouldStop)
        }
        override fun createAttempt(request: AutoDevelopRequest, attemptId: String): AutoRenderAttempt {
            attempts += attemptId
            closed = false
            return object : AutoRenderAttempt {
                override fun render(shouldStop: () -> Boolean): AutoRenderReceipt {
                    starts++
                    onRender()
                    if (renderUnsupported) throw AutoRenderUnsupported()
                    if (renderFails) throw IOException("render fault")
                    return receiptChange(AutoRenderReceipt(request, JpegOutputDescription(2000, 1500, 1, "sRGB", 3)))
                }
                override fun close() { if (closeFails) throw IOException("close fault"); closed = true }
                override fun requestStop() = onCancel()
            }
        }
        override fun inspect(request: AutoDevelopRequest, attemptId: String, shouldStop: () -> Boolean) =
            JpegInspection(JpegOutputDescription(2000, 1500, 1, "sRGB", 3), fingerprint)
        override fun publication(record: AutoDevelopRecord, isCurrent: () -> Boolean): AutoDevelopPublication {
            publisherInstances++
            return object : AutoDevelopPublication {
                override val hasUnreleasedResources get() = publicationCloseFails
                override fun releaseResources() { if (publicationCloseFails) throw IOException("publication close fault") }
                override fun advance(shouldStop: () -> Boolean): JpegPublicationPhase {
                    beforePublish()
                    if (publishFails) throw IOException("lost acknowledgement")
                    publishes++
                    return JpegPublicationPhase.PUBLISHED
                }
            }
        }
    }
}
