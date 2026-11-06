package dev.cinematic.p0.rawcapture

import dev.cinematic.p0.jpegrender.original.Original24Request
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class ManualOriginalTrialSaveOwnerTest {
    @Test fun stopBeforeAdmissionIsRetryableWithoutWritingAnIntentOrDuplicatingTheRender() {
        val backend = Backend()
        val storage = Storage()
        val owner = ManualOriginalTrialSaveOwner(request, backend, storage) { true }
        assertEquals(ManualOriginalTrialSavePhase.READY, owner.advance { true }.phase)
        assertEquals(0, backend.renders)
        assertEquals(0, storage.writes)
        assertEquals(ManualOriginalTrialSavePhase.PUBLISHED, owner.advance().phase)
        assertEquals(1, backend.renders)
        assertEquals(1, storage.writes)
    }

    @Test fun processDeathAfterPrivateProofButBeforeJournalCannotAuthorizeAnotherRender() {
        val backend = Backend()
        val storage = Storage()
        var stopped = false
        backend.beforeRender = { stopped = true }
        val owner = ManualOriginalTrialSaveOwner(request, backend, storage) { true }
        assertEquals(ManualOriginalTrialSavePhase.UNCONFIRMED, owner.advance { stopped }.phase)
        assertEquals(0, backend.publisherCreations)
        // In-process retry still has the verified private proof.
        assertEquals(ManualOriginalTrialSavePhase.PUBLISHED, owner.advance().phase)
        assertEquals(1, backend.renders)
        // A simulated restart without a durable publication record has only the intent.
        val restartedBackend = Backend()
        val restarted = ManualOriginalTrialSaveOwner(request, restartedBackend, storage) { true }
        assertEquals(ManualOriginalTrialSavePhase.HOLD, restarted.advance().phase)
        assertEquals(0, restartedBackend.renders)
        assertEquals(0, restartedBackend.publisherCreations)
    }

    private val source = SharedOriginalReference("content://test/original/1", OriginalFingerprint(3, "a".repeat(64)), OriginalGrant.USER_SELECTED)
    private val request = Original24Request(ManualOriginalTrialRequests.identity(source), 1, source, .7)
    private val output = JpegOutputDescription(16, 12, 1, "sRGB", 4)
    private val render = ManualOriginalTrialRender(RenderedJpegRequest(request.captureId, request.generation, source, request.recipe, output),
        OriginalRasterGeometry(16, 12, 1, 16, 12, true), OriginalFingerprint(4, "b".repeat(64)), request.strength, .2)
    private val target = JpegPublicationTarget(request.captureId, request.generation, "a".repeat(32))
    private val uri = "content://media/external_primary/images/media/1"
    private fun record(phase: JpegPublicationPhase) = JpegPublicationRecord(target, render, render.outputFingerprint, phase = phase,
        uri = if (phase in setOf(JpegPublicationPhase.PREPARED, JpegPublicationPhase.INSERTING)) null else uri)

    @Test fun constructAndSourceMatchingDoNotRenderAndStrengthRemainsImmutable() {
        val backend = Backend()
        val storage = Storage()
        val owner = ManualOriginalTrialSaveOwner(request, backend, storage) { true }
        assertEquals(ManualOriginalTrialSavePhase.READY, owner.state.value.phase)
        assertEquals(.7, owner.appliedStrength, 0.0)
        assertTrue(owner.matches(source))
        assertFalse(owner.matches(source.copy(uri = "content://test/original/2")))
        assertEquals(0, backend.renders)
        assertEquals(0, storage.writes)
    }

    @Test fun explicitSavePersistsIntentBeforeRenderingAndRetainsOnePublisher() {
        val backend = Backend()
        val storage = Storage()
        backend.beforeRender = { assertArrayEquals(ManualOriginalTrialRequestCodec.encode(request), storage.bytes) }
        val owner = ManualOriginalTrialSaveOwner(request, backend, storage) { true }
        assertEquals(ManualOriginalTrialSavePhase.PUBLISHED, owner.advance().phase)
        assertEquals(uri, owner.state.value.uri)
        assertEquals(ManualOriginalTrialSavePhase.PUBLISHED, owner.advance().phase)
        assertEquals(1, backend.renders)
        assertEquals(1, backend.publisherCreations)
        assertEquals(2, backend.publication.advances)
        assertEquals(1, storage.writes)
    }

    @Test fun persistedProofRecoversPublicationWithoutGeneratingOrWritingIntent() {
        for (phase in listOf(JpegPublicationPhase.PREPARED, JpegPublicationPhase.COPYING, JpegPublicationPhase.PUBLISHING, JpegPublicationPhase.PUBLISHED)) {
            val backend = Backend().also { it.existing = record(phase) }
            val storage = Storage()
            val owner = ManualOriginalTrialSaveOwner(request, backend, storage) { true }
            assertEquals(ManualOriginalTrialSavePhase.PUBLISHED, owner.advance().phase)
            assertEquals(0, backend.renders)
            assertEquals(1, backend.publication.advances)
            assertEquals(0, storage.writes)
        }
    }

    @Test fun unknownPublicationNeverCreatesPublisherOrGenerates() {
        val backend = Backend().also { it.existing = record(JpegPublicationPhase.UNKNOWN) }
        val owner = ManualOriginalTrialSaveOwner(request, backend, Storage()) { true }
        repeat(2) { assertEquals(ManualOriginalTrialSavePhase.UNCONFIRMED, owner.advance().phase) }
        assertEquals(uri, owner.state.value.uri)
        assertEquals(0, backend.renders)
        assertEquals(0, backend.publisherCreations)
    }

    @Test fun failureAndRestartWithIntentCannotAcquireFreshRenderAttempt() {
        val backend = Backend().also { it.renderFailure = IOException("render failed") }
        val storage = Storage()
        val owner = ManualOriginalTrialSaveOwner(request, backend, storage) { true }
        assertEquals(ManualOriginalTrialSavePhase.HOLD, owner.advance().phase)
        backend.renderFailure = null
        assertEquals(ManualOriginalTrialSavePhase.HOLD, owner.advance().phase)
        val restartedBackend = Backend()
        val restarted = ManualOriginalTrialSaveOwner(request, restartedBackend, storage) { true }
        assertEquals(ManualOriginalTrialSavePhase.HOLD, restarted.advance().phase)
        assertEquals(1, backend.renders)
        assertEquals(0, restartedBackend.renders)
        assertEquals(0, backend.publisherCreations)
    }

    @Test fun intentReplaceOrReadbackFailureNeverStartsHeavyWork() {
        for (mode in listOf("before", "after", "readback")) {
            val storage = Storage().also { it.mode = mode }
            val backend = Backend()
            val owner = ManualOriginalTrialSaveOwner(request, backend, storage) { true }
            assertEquals(ManualOriginalTrialSavePhase.HOLD, owner.advance().phase)
            storage.mode = "none"
            assertEquals(ManualOriginalTrialSavePhase.HOLD, owner.advance().phase)
            assertEquals(0, backend.renders)
            assertEquals(0, backend.publisherCreations)
            assertEquals(1, storage.writes)
        }
    }

    @Test fun uncertainPublisherReusesProofAndOwnerWithoutRenderingAgain() {
        val backend = Backend().also { it.publication.failure = IOException("lost publish acknowledgement") }
        val owner = ManualOriginalTrialSaveOwner(request, backend, Storage()) { true }
        assertEquals(ManualOriginalTrialSavePhase.UNCONFIRMED, owner.advance().phase)
        backend.publication.failure = null
        assertEquals(ManualOriginalTrialSavePhase.PUBLISHED, owner.advance().phase)
        assertEquals(1, backend.renders)
        assertEquals(1, backend.publisherCreations)
        assertEquals(2, backend.publication.advances)
    }

    @Test fun uncertainProducerCloseHoldsEvenIfItReturnedProof() {
        val backend = Backend().also { it.beforeRender = { it.held = true } }
        val owner = ManualOriginalTrialSaveOwner(request, backend, Storage()) { true }
        assertEquals(ManualOriginalTrialSavePhase.HOLD, owner.advance().phase)
        owner.releaseResources()
        assertFalse(owner.hasUnreleasedResources)
        assertEquals(ManualOriginalTrialSavePhase.HOLD, owner.advance().phase)
        assertEquals(1, backend.renders)
        assertEquals(0, backend.publisherCreations)
    }

    @Test fun uncertainPublisherCloseHoldsUntilSameOwnerResourcesAreReleased() {
        val backend = Backend().also { it.publication.holdOnAdvance = true }
        val owner = ManualOriginalTrialSaveOwner(request, backend, Storage()) { true }
        assertEquals(ManualOriginalTrialSavePhase.HOLD, owner.advance().phase)
        assertTrue(owner.hasUnreleasedResources)
        assertEquals(ManualOriginalTrialSavePhase.HOLD, owner.advance().phase)
        assertEquals(1, backend.publication.advances)
        owner.releaseResources()
        backend.publication.holdOnAdvance = false
        assertEquals(ManualOriginalTrialSavePhase.PUBLISHED, owner.advance().phase)
        assertEquals(1, backend.renders)
        assertEquals(1, backend.publisherCreations)
        assertEquals(2, backend.publication.advances)
    }

    @Test fun existingResourcesMustBeReleasedBeforeAnyNewWork() {
        val backend = Backend().also { it.held = true }
        val owner = ManualOriginalTrialSaveOwner(request, backend, Storage()) { true }
        assertEquals(ManualOriginalTrialSavePhase.HOLD, owner.advance().phase)
        assertEquals(0, backend.renders)
        owner.releaseResources()
        assertEquals(ManualOriginalTrialSavePhase.PUBLISHED, owner.advance().phase)
    }

    @Test fun sourceGenerationAndAppliedStrengthMismatchHoldBeforePublication() {
        for (foreign in listOf(
            render.copy(request = render.request.copy(source = source.copy(uri = "content://test/original/2"))),
            render.copy(request = render.request.copy(generation = 2)),
            render.copy(request = render.request.copy(recipe = ManualOriginalTrialRender.recipe(.5)), appliedStrength = .5))) {
            val backend = Backend().also { it.generated = foreign }
            val owner = ManualOriginalTrialSaveOwner(request, backend, Storage()) { true }
            assertEquals(ManualOriginalTrialSavePhase.HOLD, owner.advance().phase)
            assertEquals(0, backend.publisherCreations)
        }
    }

    @Test fun stoppedOrSupersededRequestsCannotStartRendering() {
        val backend = Backend()
        val storage = Storage()
        val stopped = ManualOriginalTrialSaveOwner(request, backend, storage) { true }
        assertEquals(ManualOriginalTrialSavePhase.READY, stopped.advance { true }.phase)
        assertEquals(0, storage.writes)
        val stale = ManualOriginalTrialSaveOwner(request, backend, storage) { false }
        assertEquals(ManualOriginalTrialSavePhase.HOLD, stale.advance().phase)
        assertEquals(0, backend.renders)
    }

    @Test fun reentrantAdvanceIsRejectedWithoutSecondRenderOrPublisher() {
        val backend = Backend()
        val owner = ManualOriginalTrialSaveOwner(request, backend, Storage()) { true }
        backend.beforeRender = { assertThrows(IOException::class.java) { owner.advance() } }
        assertEquals(ManualOriginalTrialSavePhase.PUBLISHED, owner.advance().phase)
        assertEquals(1, backend.renders)
    }

    @Test fun cancellationAfterPublicationOrDuringResourceInspectionCannotReturnPublished() {
        for (boundary in listOf("publication", "resources")) {
            val backend = Backend()
            var stopped = false
            if (boundary == "publication") backend.publication.afterAdvance = { stopped = true }
            else backend.publication.beforeResourceCheck = { stopped = true }
            val owner = ManualOriginalTrialSaveOwner(request, backend, Storage()) { true }
            val cancelled = owner.advance { stopped }
            assertEquals(ManualOriginalTrialSavePhase.UNCONFIRMED, cancelled.phase)
            assertEquals(ManualOriginalTrialSaveReason.STOPPED, cancelled.reason)
            assertEquals(uri, cancelled.uri)
            assertEquals(cancelled, owner.state.value)
            assertEquals(1, backend.renders)
            assertEquals(1, backend.publisherCreations)
            // Retry rechecks the same publication rather than generating or allocating again.
            backend.publication.afterAdvance = {}; backend.publication.beforeResourceCheck = {}
            stopped = false
            assertEquals(ManualOriginalTrialSavePhase.PUBLISHED, owner.advance().phase)
            assertEquals(1, backend.renders)
            assertEquals(1, backend.publisherCreations)
            assertEquals(2, backend.publication.advances)
        }
    }

    @Test fun supersededAfterPublicationOrDuringResourceInspectionHoldsInsteadOfReturningPublished() {
        for (boundary in listOf("publication", "resources")) {
            val backend = Backend()
            var current = true
            if (boundary == "publication") backend.publication.afterAdvance = { current = false }
            else backend.publication.beforeResourceCheck = { current = false }
            val owner = ManualOriginalTrialSaveOwner(request, backend, Storage()) { current }
            val stale = owner.advance()
            assertEquals(ManualOriginalTrialSavePhase.HOLD, stale.phase)
            assertEquals(ManualOriginalTrialSaveReason.SUPERSEDED, stale.reason)
            assertEquals(uri, stale.uri)
            assertEquals(stale, owner.state.value)
            current = true
            assertEquals(stale, owner.advance()) // Superseded generations never regain authority.
            assertEquals(1, backend.renders)
            assertEquals(1, backend.publisherCreations)
            assertEquals(1, backend.publication.advances)
        }
    }

    private inner class Backend : ManualOriginalTrialSaveBackend {
        var existing: JpegPublicationRecord? = null
        var generated = render
        var renders = 0
        var publisherCreations = 0
        var renderFailure: IOException? = null
        var beforeRender: () -> Unit = {}
        var held = false
        override val hasUnreleasedResources get() = held
        val publication = Publication()
        override fun readPublication() = existing
        override fun render(stop: () -> Boolean): ManualOriginalTrialRender {
            renders++; beforeRender(); renderFailure?.let { throw it }
            return generated
        }
        override fun publisher(render: ManualOriginalTrialRender): ManualOriginalTrialPublication {
            publisherCreations++
            return publication
        }
        override fun releaseResources() { held = false }
    }
    private inner class Publication : ManualOriginalTrialPublication {
        var advances = 0
        var failure: IOException? = null
        var holdOnAdvance = false
        var afterAdvance: () -> Unit = {}
        var beforeResourceCheck: () -> Unit = {}
        private var held = false
        override val hasUnreleasedResources get(): Boolean {
            if (advances > 0) beforeResourceCheck()
            return held
        }
        override fun advance(stop: () -> Boolean): JpegPublicationRecord {
            advances++
            if (holdOnAdvance) { held = true; throw IOException("close unconfirmed") }
            failure?.let { throw it }
            afterAdvance()
            return record(JpegPublicationPhase.PUBLISHED)
        }
        override fun releaseResources() { held = false }
    }
    private class Storage : AtomicJournalStorage {
        var bytes: ByteArray? = null
        var writes = 0
        var mode = "none"
        override fun read(maxBytes: Int): ByteArray? {
            if (mode == "readback" && writes > 0) throw IOException("readback")
            return bytes?.clone()
        }
        override fun replace(contents: ByteArray) {
            writes++
            if (mode == "before") throw IOException("before")
            bytes = contents.clone()
            if (mode == "after") throw IOException("after")
        }
    }
}
