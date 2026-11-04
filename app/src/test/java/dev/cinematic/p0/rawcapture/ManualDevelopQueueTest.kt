package dev.cinematic.p0.rawcapture

import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test

class ManualDevelopQueueTest {
    private val fp = OriginalFingerprint(3, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    private val source = SharedOriginalReference("content://test/original/1", fp, OriginalGrant.USER_SELECTED)
    private val recipe = RenderRecipeSnapshot("stage-clear-bounded-jpeg-v1", "fixed")
    private val observations = AutoDevelopConditions(true, false, false, false, true, true, true, true)
    private class Memory : AtomicJournalStorage {
        var bytes: ByteArray? = null
        var fault = false
        override fun read(maxBytes: Int) = bytes?.clone()
        override fun replace(contents: ByteArray) { bytes = contents.clone(); if (fault) throw IOException("ack lost") }
    }
    private class Serial : Executor {
        val pending = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { pending.add(command) }
        fun drain() { var n = 0; while (pending.isNotEmpty()) { check(n++ < 100); pending.removeFirst().run() } }
    }
    private inner class Fixture {
        val io = Serial()
        val admission = AutoDevelopCoordinator()
        val selection = Memory()
        val registry = ManualDevelopRequests(source, selection)
        val stores = mutableMapOf<Long, Memory>()
        var starts = 0
        var publicationOwners = 0
        var publishes = 0
        var confirms = 0
        var supported = true
        var unavailable = false
        var deleted = false
        var failClose = false
        var publicationPhase = JpegPublicationPhase.PUBLISHED
        var onRender: () -> Unit = {}
        var onConfirm: () -> Unit = {}
        val queue = AutoDevelopQueue(io, admission, { request ->
            AutoDevelopJournal(request.captureId, request.generation, stores.getOrPut(request.generation) { Memory() })
        }, { request -> object : AutoDevelopBackend {
            override val hasUnreleasedResources = false
            override fun releaseResources() {}
            override fun supports(request: AutoDevelopRequest) = supported
            override fun verifyOriginal(request: AutoDevelopRequest, shouldStop: () -> Boolean) {
                if (unavailable) throw SecurityException("grant expired")
            }
            override fun createAttempt(request: AutoDevelopRequest, attemptId: String) = object : AutoRenderAttempt {
                override fun render(shouldStop: () -> Boolean): AutoRenderReceipt {
                    starts++; onRender()
                    return AutoRenderReceipt(request, JpegOutputDescription(768, 1024, 1, "sRGB", 3),
                        RenderedFaceState.VERIFIED_NONE, RenderedExposureState.SUPPORTED)
                }
                override fun close() { if (failClose) throw IOException("close unknown") }
            }
            override fun inspect(request: AutoDevelopRequest, attemptId: String, shouldStop: () -> Boolean) =
                JpegInspection(JpegOutputDescription(768, 1024, 1, "sRGB", 3), fp)
            override fun publication(record: AutoDevelopRecord, isCurrent: () -> Boolean): AutoDevelopPublication {
                publicationOwners++
                return object : AutoDevelopPublication {
                    override val hasUnreleasedResources = false
                    override fun releaseResources() {}
                    override fun advance(shouldStop: () -> Boolean): JpegPublicationPhase { publishes++; return publicationPhase }
                    override fun confirmPublished(shouldStop: () -> Boolean): JpegPublicationRecord {
                        confirms++; onConfirm()
                        if (deleted) throw IOException("public row deleted")
                        return JpegPublicationRecord(JpegPublicationTarget(request.captureId, request.generation, "a".repeat(32)),
                            record.completed(), fp, phase = JpegPublicationPhase.PUBLISHED,
                            uri = "content://media/external_primary/images/media/1")
                    }
                }
            }
        } }, Executor { it.run() })
        fun prepare(fixed: RenderRecipeSnapshot = recipe): ManualDevelopHandle {
            val accepted = queue.prepareManual(registry.captureId) { registry.prepare(fixed) }
            io.drain()
            return accepted.join()
        }
        fun phase(handle: ManualDevelopHandle): AutoJobPhase {
            val state = handle.status(); io.drain(); return state.join().phase
        }
    }

    @Test fun prepareDoesNotRunAndStartDoesNotPublishUntilExplicitSave() {
        val f = Fixture()
        val handle = f.prepare()
        f.queue.updateConditions(observations.copy(rendererAvailable = false)); f.io.drain()
        assertEquals(0, f.starts)
        handle.start(observations); f.io.drain()
        assertEquals(AutoJobPhase.RENDERED, f.phase(handle))
        assertEquals(1, f.starts); assertEquals(0, f.publicationOwners)
        val duplicate = f.prepare()
        assertEquals(handle.request, duplicate.request)
        assertEquals(AutoJobPhase.RENDERED, f.phase(duplicate))
        assertThrows(IllegalArgumentException::class.java) { f.queue.submit(handle.request) }
        handle.publish(observations); f.io.drain()
        assertEquals(AutoJobPhase.PUBLISHED, f.phase(handle))
        assertEquals(1, f.publicationOwners); assertEquals(1, f.publishes)
        val checked = handle.confirmPublished(observations) { false }; f.io.drain()
        assertEquals(JpegPublicationPhase.PUBLISHED, checked.join().phase)
        assertEquals(1, f.confirms); assertEquals(1, f.publicationOwners)
        f.deleted = true
        val missing = handle.confirmPublished(observations) { false }; f.io.drain()
        assertTrue(missing.isCompletedExceptionally)
        assertEquals(2, f.confirms); assertEquals(1, f.starts); assertEquals(1, f.publishes)
    }

    @Test fun cancellationBeforeQueuedStartAndDuringRenderNeverPublishesOrReleasesUnclosedOwner() {
        val f = Fixture()
        val handle = f.prepare()
        handle.start(observations); handle.requestStop(); f.io.drain()
        assertEquals(0, f.starts)
        f.failClose = true
        f.onRender = { handle.requestStop() }
        handle.start(observations); f.io.drain()
        assertEquals(1, f.starts)
        assertNull(f.admission.tryStartObserved(observations))
        f.failClose = false
        f.queue.wake(); f.io.drain()
        assertEquals(AutoJobPhase.RETRYABLE, f.phase(handle))
        assertEquals(0, f.publicationOwners)
        assertNotNull(f.admission.tryStartObserved(observations))
    }

    @Test fun uncertainNewSelectionFencesOldGenerationAndNeverPublishesIt() {
        val f = Fixture()
        val old = f.prepare()
        old.start(observations); f.io.drain()
        assertEquals(AutoJobPhase.RENDERED, f.phase(old))
        f.selection.fault = true
        val failed = f.queue.prepareManual(f.registry.captureId) { f.registry.prepare(recipe.copy(payload = "new")) }
        f.io.drain(); assertTrue(failed.isCompletedExceptionally)
        val publishOld = old.publish(observations); f.io.drain()
        assertTrue(publishOld.isCompletedExceptionally)
        assertEquals(0, f.publicationOwners)
        f.selection.fault = false
        val new = f.prepare(recipe.copy(payload = "new"))
        assertEquals(old.request.generation + 1, new.request.generation)
        new.start(observations); f.io.drain()
        assertEquals(AutoJobPhase.RENDERED, f.phase(new))
        assertEquals(2, f.starts)
    }

    @Test fun missingProviderNeverRunsAndReselectedReadGrantCanRetryWithoutNewIdentity() {
        val f = Fixture()
        val handle = f.prepare()
        f.supported = false
        val unsupported = handle.start(observations); f.io.drain()
        assertTrue(unsupported.isCompletedExceptionally)
        f.queue.wake(); f.io.drain()
        assertEquals(AutoJobPhase.QUEUED, f.phase(handle)); assertEquals(0, f.starts)
        f.supported = true; f.unavailable = true
        handle.start(observations); f.io.drain()
        assertEquals(AutoJobPhase.HELD, f.phase(handle)); assertEquals(0, f.starts)
        f.unavailable = false
        val reselected = f.prepare()
        assertEquals(handle.request, reselected.request)
        reselected.retry(); f.io.drain()
        assertEquals(AutoJobPhase.RENDERED, f.phase(reselected)); assertEquals(1, f.starts)
    }

    @Test fun cancelDuringFreshConfirmationCannotReturnAnOldSuccessUri() {
        val f = Fixture()
        val handle = f.prepare()
        handle.start(observations); f.io.drain()
        handle.publish(observations); f.io.drain()
        f.onConfirm = { handle.requestStop() }
        val cancelled = handle.confirmPublished(observations) { false }; f.io.drain()
        assertTrue(cancelled.isCompletedExceptionally)
        assertEquals(1, f.confirms); assertEquals(1, f.publicationOwners)
    }

    @Test fun stopAfterQueuedRetryCannotBeOverriddenByItsOlderIoTask() {
        val f = Fixture()
        val handle = f.prepare()
        f.onRender = { handle.requestStop() }
        handle.start(observations); f.io.drain()
        assertEquals(AutoJobPhase.RETRYABLE, f.phase(handle))
        f.onRender = {}
        handle.retry(); handle.requestStop(); f.io.drain()
        assertEquals(1, f.starts)
        assertEquals(0, f.publicationOwners)
    }

    @Test fun aNewStartCannotReviveACancelledQueuedSaveIntent() {
        val f = Fixture()
        val handle = f.prepare()
        handle.start(observations); f.io.drain()
        val cancelledSave = handle.publish(observations)
        handle.requestStop()
        handle.start(observations)
        f.io.drain()
        assertEquals(0, f.publicationOwners)
        assertEquals(AutoJobPhase.RENDERED, f.phase(handle))
        assertTrue(cancelledSave.isCompletedExceptionally)
    }

    @Test fun aNewStartCannotReviveACancelledQueuedConfirmation() {
        val f = Fixture()
        val handle = f.prepare()
        handle.start(observations); f.io.drain()
        handle.publish(observations); f.io.drain()
        val cancelled = handle.confirmPublished(observations) { false }
        handle.requestStop()
        handle.start(observations)
        f.io.drain()
        assertTrue(cancelled.isCompletedExceptionally)
        assertEquals(0, f.confirms)
    }

    @Test fun aNewStartCannotReviveACancelledQueuedRetry() {
        val f = Fixture()
        val handle = f.prepare()
        f.onRender = { handle.requestStop() }
        handle.start(observations); f.io.drain()
        assertEquals(AutoJobPhase.RETRYABLE, f.phase(handle))
        f.onRender = {}
        val cancelled = handle.retry()
        handle.requestStop(); handle.start(observations); f.io.drain()
        assertTrue(cancelled.isCompletedExceptionally)
        assertEquals(AutoJobPhase.RETRYABLE, f.phase(handle))
        assertEquals(1, f.starts)
    }

    @Test fun cancelledPendingPublicationNeedsANewSaveIntentAndReusesItsOwner() {
        val f = Fixture()
        val handle = f.prepare()
        handle.start(observations); f.io.drain()
        f.publicationPhase = JpegPublicationPhase.PUBLISHING
        handle.publish(observations); f.io.drain()
        assertEquals(AutoJobPhase.PUBLISHING, f.phase(handle)); assertEquals(1, f.publishes)
        handle.requestStop(); handle.start(observations); f.io.drain()
        assertEquals(1, f.publishes)
        f.publicationPhase = JpegPublicationPhase.PUBLISHED
        handle.publish(observations); f.io.drain()
        assertEquals(AutoJobPhase.PUBLISHED, f.phase(handle))
        assertEquals(2, f.publishes); assertEquals(1, f.publicationOwners); assertEquals(1, f.starts)
    }
}
