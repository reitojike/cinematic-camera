package dev.cinematic.p0.rawcapture

import dev.cinematic.p0.jpegrender.original.Original24Request
import java.io.IOException
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class ManualOriginalTrialSaveOwnerTest {
    @Test fun durableCancellationIsGrantedOnlyAfterFinalReleaseAndExplicitFinalization() {
        val intent = Storage(); val ledger = Storage()
        val backend = Backend().also { it.renderFailure = confirmedCancellation() }
        val owner = durableOwner(backend, intent, ledger)
        assertEquals(ManualOriginalTrialSavePhase.SAVING, owner.advance().phase)
        assertEquals(ManualTrialAttemptPhase.STARTED, ManualTrialAttemptCodec.decode(ledger.bytes!!).phase)
        assertFalse(owner.canSupersedeConfirmedCancellation())
        owner.releaseResources()
        assertEquals(ManualTrialAttemptPhase.STARTED, ManualTrialAttemptCodec.decode(ledger.bytes!!).phase)
        owner.confirmCancellationAfterRelease()
        assertEquals(ManualOriginalTrialSavePhase.CANCELLED, owner.state.value.phase)
        assertTrue(owner.canSupersedeConfirmedCancellation())
        backend.releaseFailure = IOException("must never close again after clean commit")
        owner.releaseResources()
        val restoredBackend = Backend()
        val restored = durableOwner(restoredBackend, intent, ledger)
        restored.restoreDurableState()
        assertEquals(ManualOriginalTrialSavePhase.CANCELLED, restored.state.value.phase)
        assertEquals(ManualOriginalTrialSavePhase.CANCELLED, restored.advance().phase)
        assertEquals(0, restoredBackend.renders)
        restoredBackend.beforeRender = {
            val record = ManualTrialAttemptCodec.decode(ledger.bytes!!)
            assertEquals(2L, record.attempt); assertEquals(ManualTrialAttemptPhase.STARTED, record.phase)
        }
        assertEquals(ManualOriginalTrialSavePhase.PUBLISHED, restored.advance(retryCancellation = true).phase)
        assertEquals(1, restoredBackend.renders)
    }

    @Test fun processLossBeforeFinalCleanAndLegacyIntentNeverRecover() {
        for (point in listOf("legacy", "started", "cancelCandidate", "released", "releaseFailed")) {
            val intent = Storage(); val ledger = Storage()
            val backend = Backend().also { it.renderFailure = confirmedCancellation() }
            val owner = durableOwner(backend, intent, ledger)
            if (point == "legacy") intent.bytes = ManualOriginalTrialRequestCodec.encode(request)
            else {
                owner.advance()
                if (point == "released") owner.releaseResources()
                if (point == "releaseFailed") {
                    backend.releaseFailure = IOException("last release")
                    assertThrows(IOException::class.java) { owner.releaseResources() }
                    owner.confirmCancellationAfterRelease()
                }
            }
            val restartedBackend = Backend()
            val restarted = durableOwner(restartedBackend, intent, ledger)
            restarted.restoreDurableState()
            assertEquals(point, ManualOriginalTrialSavePhase.HOLD, restarted.state.value.phase)
            assertFalse(restarted.canSupersedeConfirmedCancellation())
            restarted.advance(retryCancellation = true)
            assertEquals(0, restartedBackend.renders)
        }
    }

    @Test fun cleanWriteFailuresNeverAuthorizeInProcessButRestartReadsOnlyAtomicOutcome() {
        for (mode in listOf("before", "after", "readback")) {
            val intent = Storage(); val ledger = Storage()
            val owner = durableOwner(Backend().also { it.renderFailure = confirmedCancellation() }, intent, ledger)
            owner.advance(); owner.releaseResources()
            ledger.mode = mode
            owner.confirmCancellationAfterRelease()
            assertEquals(ManualOriginalTrialSavePhase.HOLD, owner.state.value.phase)
            assertFalse(owner.canSupersedeConfirmedCancellation())
            ledger.mode = "none"
            val restored = durableOwner(Backend(), intent, ledger)
            restored.restoreDurableState()
            // "readback" here fails the pre-write read: the old STARTED must remain held.
            assertEquals(if (mode == "after") ManualOriginalTrialSavePhase.CANCELLED else ManualOriginalTrialSavePhase.HOLD,
                restored.state.value.phase)
        }
    }

    @Test fun retryStartFailureCannotReuseCleanProofOrRunProducer() {
        for (mode in listOf("before", "after", "retryReadback", "retryMismatch")) {
            val intent = Storage(); val ledger = Storage()
            val owner = durableOwner(Backend().also { it.renderFailure = confirmedCancellation() }, intent, ledger)
            owner.advance(); owner.releaseResources(); owner.confirmCancellationAfterRelease()
            val backend = Backend()
            val restarted = durableOwner(backend, intent, ledger)
            restarted.restoreDurableState()
            ledger.mode = mode
            assertEquals(ManualOriginalTrialSavePhase.HOLD, restarted.advance(retryCancellation = true).phase)
            assertEquals(0, backend.renders)
            ledger.mode = "none"
            restarted.advance(retryCancellation = true)
            assertEquals(0, backend.renders)
            val afterKill = durableOwner(Backend(), intent, ledger)
            afterKill.restoreDurableState()
            assertEquals(if (mode == "after") ManualOriginalTrialSavePhase.HOLD else ManualOriginalTrialSavePhase.CANCELLED,
                afterKill.state.value.phase)
        }
    }

    @Test fun restartedCleanCanSupersedeButOldOwnerCannotRenderAndUnknownPublicationWins() {
        val intent = Storage(); val ledger = Storage(); var current = true
        val owner = durableOwner(Backend().also { it.renderFailure = confirmedCancellation() }, intent, ledger)
        owner.advance(); owner.releaseResources(); owner.confirmCancellationAfterRelease()
        val backend = Backend()
        val recovered = ManualOriginalTrialSaveOwner(request, backend, intent,
            ManualOriginalTrialAttemptJournal(request, ledger, intent)) { current }
        recovered.restoreDurableState()
        assertTrue(recovered.canSupersedeConfirmedCancellation())
        current = false
        assertEquals(ManualOriginalTrialSavePhase.HOLD, recovered.advance(retryCancellation = true).phase)
        assertEquals(0, backend.renders)
        for (phase in JpegPublicationPhase.entries) {
            val journalBackend = Backend().also { it.existing = record(phase) }
            val restored = durableOwner(journalBackend, intent, ledger)
            restored.restoreDurableState()
            assertFalse(restored.canSupersedeConfirmedCancellation())
            assertEquals(0, journalBackend.renders)
            if (phase == JpegPublicationPhase.UNKNOWN) {
                assertEquals(ManualOriginalTrialSaveReason.PUBLICATION_UNKNOWN, restored.state.value.reason)
                restored.advance(retryCancellation = true)
                assertEquals(0, journalBackend.renders)
            }
        }
    }

    @Test fun durableProofRejectsOrphansCorruptionAndIntentMismatch() {
        val intent = Storage(); val ledger = Storage()
        val owner = durableOwner(Backend().also { it.renderFailure = confirmedCancellation() }, intent, ledger)
        owner.advance(); owner.releaseResources(); owner.confirmCancellationAfterRelease()
        val goodIntent = intent.bytes!!.clone(); val goodLedger = ledger.bytes!!.clone()
        for (mode in listOf("noIntent", "intentMismatch", "badProof", "readFailure")) {
            intent.bytes = goodIntent.clone(); ledger.bytes = goodLedger.clone(); ledger.mode = "none"
            when (mode) {
                "noIntent" -> intent.bytes = null
                "intentMismatch" -> intent.bytes = byteArrayOf(1)
                "badProof" -> ledger.bytes = byteArrayOf(1)
                "readFailure" -> ledger.mode = "readback"
            }
            val backend = Backend(); val restored = durableOwner(backend, intent, ledger)
            restored.restoreDurableState()
            assertEquals(mode, ManualOriginalTrialSavePhase.HOLD, restored.state.value.phase)
            restored.advance(retryCancellation = true)
            assertEquals(0, backend.renders)
        }
    }

    private fun durableOwner(backend: Backend, intent: Storage, ledger: Storage) =
        ManualOriginalTrialSaveOwner(request, backend, intent, ManualOriginalTrialAttemptJournal(request, ledger, intent)) { true }

    @Test fun unconfirmedSlotReleaseCannotPersistCleanProof() {
        val intent = Storage(); val ledger = Storage()
        val owner = durableOwner(Backend().also { it.renderFailure = confirmedCancellation() }, intent, ledger)
        owner.advance(); owner.releaseResources()
        owner.confirmCancellationAfterRelease(slotReleased = false)
        assertEquals(ManualOriginalTrialSavePhase.HOLD, owner.state.value.phase)
        assertEquals(ManualTrialAttemptPhase.STARTED, ManualTrialAttemptCodec.decode(ledger.bytes!!).phase)
        val restarted = durableOwner(Backend(), intent, ledger)
        restarted.restoreDurableState()
        assertEquals(ManualOriginalTrialSavePhase.HOLD, restarted.state.value.phase)
    }

    @Test fun durableCancellationSuccessorCommitKeepsOldIntentAndFailsClosedOnUncertainCursor() {
        for (fault in listOf("none", "before", "after", "retryReadback")) {
            val metadata = Storage(); val intent = Storage(); val ledger = Storage()
            lateinit var recovered: ManualOriginalTrialSaveOwner
            val requests = ManualOriginalTrialRequests(source, metadata) { recovered.canSupersedeConfirmedCancellation() }
            val first = requests.prepare(request.strength)
            val initial = durableOwner(Backend().also { it.renderFailure = confirmedCancellation() }, intent, ledger)
            initial.advance(); initial.releaseResources(); initial.confirmCancellationAfterRelease()
            val backend = Backend()
            recovered = ManualOriginalTrialSaveOwner(first, backend, intent,
                ManualOriginalTrialAttemptJournal(first, ledger, intent)) { requests.isCurrent(first) }
            recovered.restoreDurableState()
            val preservedIntent = intent.bytes!!.clone(); val preservedProof = ledger.bytes!!.clone()
            metadata.mode = fault
            if (fault == "none") {
                val next = requests.prepare(.4)
                assertEquals(first.generation + 1, next.generation)
                assertEquals(next, requests.prepare(.4))
            } else assertThrows(IOException::class.java) { requests.prepare(.4) }
            assertFalse(requests.isCurrent(first))
            assertEquals(ManualOriginalTrialSavePhase.HOLD, recovered.advance(retryCancellation = true).phase)
            assertEquals(0, backend.renders)
            metadata.mode = "none"
            val coldCursor = ManualOriginalTrialRequests(source, metadata) { false }.current()!!
            assertEquals(if (fault == "before") first.generation else first.generation + 1, coldCursor.generation)
            assertArrayEquals(preservedIntent, intent.bytes)
            assertArrayEquals(preservedProof, ledger.bytes)
        }
    }

    @Test fun confirmedCancellationAllowsOneSuccessorWithoutErasingIntentOrRetryingOldRender() {
        val metadata = Storage()
        val intent = Storage()
        val backend = Backend().also { it.renderFailure = confirmedCancellation() }
        lateinit var owner: ManualOriginalTrialSaveOwner
        val requests = ManualOriginalTrialRequests(source, metadata) {
            assertEquals(owner.request, it)
            owner.canSupersedeConfirmedCancellation()
        }
        val first = requests.prepare(request.strength)
        owner = ManualOriginalTrialSaveOwner(first, backend, intent) { requests.isCurrent(first) }
        assertFalse(owner.canSupersedeConfirmedCancellation())
        assertEquals(ManualOriginalTrialSavePhase.CANCELLED, owner.advance().phase)
        val preserved = intent.bytes!!.clone()
        repeat(2) { assertTrue(owner.canSupersedeConfirmedCancellation()) }
        assertEquals(first, requests.prepare(first.strength))
        val next = requests.prepare(.4)
        assertEquals(first.generation + 1, next.generation)
        assertEquals(first.source, next.source)
        assertNotEquals(first.recipe, next.recipe)
        repeat(2) { assertEquals(next, requests.prepare(.4)) }
        assertEquals(2, metadata.writes)
        assertEquals(1, intent.writes)
        assertArrayEquals(preserved, intent.bytes)
        assertFalse(requests.isCurrent(first))
        backend.renderFailure = null
        val retired = owner.advance(retryCancellation = true)
        assertEquals(ManualOriginalTrialSavePhase.HOLD, retired.phase)
        assertEquals(ManualOriginalTrialSaveReason.SUPERSEDED, retired.reason)
        assertEquals(1, backend.renders)
        assertEquals(0, backend.publisherCreations)
        assertArrayEquals(preserved, intent.bytes)
        val nextRender = render.copy(request = render.request.copy(generation = next.generation, recipe = next.recipe),
            appliedStrength = next.strength)
        val nextBackend = Backend().also {
            it.generated = nextRender
            it.publication.result = JpegPublicationRecord(target.copy(generation = next.generation), nextRender,
                nextRender.outputFingerprint, phase = JpegPublicationPhase.PUBLISHED, uri = "content://media/external_primary/images/media/2")
        }
        val nextIntent = Storage()
        val nextOwner = ManualOriginalTrialSaveOwner(next, nextBackend, nextIntent) { requests.isCurrent(next) }
        assertEquals(ManualOriginalTrialSavePhase.PUBLISHED, nextOwner.advance().phase)
        assertEquals("content://media/external_primary/images/media/2", nextOwner.state.value.uri)
        assertEquals(1, nextBackend.renders)
        assertEquals(1, nextBackend.publisherCreations)
        assertArrayEquals(preserved, intent.bytes)
        assertArrayEquals(ManualOriginalTrialRequestCodec.encode(next), nextIntent.bytes)
    }

    @Test fun cancellationGenerationFenceRequiresLivePermitMatchingIntentAndNoPublicationOrResources() {
        for (changed in listOf("restart", "missing", "corrupt", "different", "resources", "unknown", "published", "readFailure")) {
            val backend = Backend().also { it.renderFailure = confirmedCancellation() }
            val intent = Storage()
            var owner = ManualOriginalTrialSaveOwner(request, backend, intent) { true }
            assertEquals(ManualOriginalTrialSavePhase.CANCELLED, owner.advance().phase)
            when (changed) {
                "restart" -> owner = ManualOriginalTrialSaveOwner(request, Backend(), intent) { true }
                "missing" -> intent.bytes = null
                "corrupt" -> intent.bytes = byteArrayOf(1, 2)
                "different" -> intent.bytes = ManualOriginalTrialRequestCodec.encode(request.copy(generation = 2))
                "resources" -> backend.held = true
                "unknown" -> backend.existing = record(JpegPublicationPhase.UNKNOWN)
                "published" -> backend.existing = record(JpegPublicationPhase.PUBLISHED)
                "readFailure" -> intent.mode = "readback"
            }
            if (changed == "readFailure") assertThrows(IOException::class.java) { owner.canSupersedeConfirmedCancellation() }
            else assertFalse(changed, owner.canSupersedeConfirmedCancellation())
            assertEquals(1, intent.writes)
            assertEquals(1, backend.renders)
            assertEquals(0, backend.publisherCreations)
        }
    }

    @Test fun gpuFailureAndUnconfirmedCancellationNeverAuthorizeSuccessorEvenAfterCleanup() {
        for (failure in listOf(gpuFailure(), CancellationException("JPEG entry unknown"),
            confirmedCancellation().also { it.addSuppressed(IOException("cleanup unknown")) })) {
            val backend = Backend().also { it.renderFailure = failure }
            val owner = ManualOriginalTrialSaveOwner(request, backend, Storage()) { true }
            owner.advance()
            owner.releaseResources()
            assertFalse(owner.canSupersedeConfirmedCancellation())
            assertEquals(1, backend.renders)
        }
    }

    @Test fun sameRecipeRetryStillWorksAfterInspectingCancellationGenerationFence() {
        val backend = Backend().also { it.renderFailure = confirmedCancellation() }
        val owner = ManualOriginalTrialSaveOwner(request, backend, Storage()) { true }
        owner.advance()
        assertTrue(owner.canSupersedeConfirmedCancellation())
        backend.renderFailure = null
        assertEquals(ManualOriginalTrialSavePhase.PUBLISHED, owner.advance(retryCancellation = true).phase)
        assertFalse(owner.canSupersedeConfirmedCancellation())
        assertEquals(2, backend.renders)
        assertEquals(1, backend.publisherCreations)
    }

    @Test fun cleanupFailureAfterConfirmedCancellationRevokesSuccessorAndRetryPermanently() {
        val backend = Backend().also { it.renderFailure = confirmedCancellation() }
        val intent = Storage()
        val owner = ManualOriginalTrialSaveOwner(request, backend, intent) { true }
        owner.advance()
        assertTrue(owner.canSupersedeConfirmedCancellation())
        backend.releaseFailure = IOException("cleanup readback failed")
        assertThrows(IOException::class.java) { owner.releaseResources() }
        backend.releaseFailure = null
        backend.renderFailure = null
        owner.releaseResources()
        assertFalse(owner.canSupersedeConfirmedCancellation())
        assertEquals(ManualOriginalTrialSavePhase.HOLD, owner.advance(retryCancellation = true).phase)
        assertEquals(1, backend.renders)
        assertEquals(1, intent.writes)
        assertArrayEquals(ManualOriginalTrialRequestCodec.encode(request), intent.bytes)
    }

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

    @Test fun cleanGpuFailureRecordsTheReasonAndCountWithoutAnImplicitRetry() {
        for (reason in GpuSaveFailureReason.entries) {
            val failure = gpuFailure(reason)
            val backend = Backend().also { it.renderFailure = failure }
            val storage = Storage()
            val owner = ManualOriginalTrialSaveOwner(request, backend, storage) { true }
            val failed = owner.advance()
            assertEquals(ManualOriginalTrialSavePhase.GPU_FAILED, failed.phase)
            assertEquals(ManualOriginalTrialSaveReason.OPERATION_FAILED, failed.reason)
            assertEquals(GpuSaveFailure(reason, 1), failed.gpuFailure)
            assertNull(failed.uri)
            assertSame(failure, owner.lastFailure)
            assertFalse(owner.hasUnreleasedResources)
            assertArrayEquals(ManualOriginalTrialRequestCodec.encode(request), storage.bytes)
            backend.renderFailure = null
            owner.releaseResources()
            repeat(3) { assertEquals(failed, owner.advance()) }
            assertEquals(1, backend.publicationReads)
            assertEquals(1, backend.renders)
            assertEquals(1, storage.writes)
            assertEquals(0, backend.publisherCreations)
        }
    }

    @Test fun explicitGpuRetryRevalidatesTheIntentAndPublishesOnlyTheSuccessfulRender() {
        val backend = Backend().also { it.renderFailure = gpuFailure() }
        val storage = Storage()
        val owner = ManualOriginalTrialSaveOwner(request, backend, storage) { true }
        owner.advance()
        val intent = storage.bytes!!.clone()
        backend.beforeRender = {
            assertArrayEquals(intent, storage.bytes)
            assertEquals(0, backend.publisherCreations)
        }
        backend.renderFailure = null
        val published = owner.advance(retryGpuFailure = true)
        assertEquals(ManualOriginalTrialSavePhase.PUBLISHED, published.phase)
        assertEquals(GpuSaveFailure(GpuSaveFailureReason.RENDER_FAILED, 1), published.gpuFailure)
        assertEquals(uri, published.uri)
        assertEquals(2, backend.renders)
        assertEquals(2, storage.writes)
        assertArrayEquals(intent, storage.bytes)
        assertEquals(1, backend.publisherCreations)
        repeat(2) { assertEquals(published, owner.advance(retryGpuFailure = true)) }
        assertEquals(2, backend.renders)
        assertEquals(1, backend.publisherCreations)
    }

    @Test fun eachExplicitGpuFailureUpdatesTheCountAndLatestReasonOnce() {
        val backend = Backend()
        val owner = ManualOriginalTrialSaveOwner(request, backend, Storage()) { true }
        for ((index, reason) in GpuSaveFailureReason.entries.withIndex()) {
            backend.renderFailure = gpuFailure(reason)
            val failed = owner.advance(retryGpuFailure = index > 0)
            assertEquals(ManualOriginalTrialSavePhase.GPU_FAILED, failed.phase)
            assertEquals(GpuSaveFailure(reason, index + 1L), failed.gpuFailure)
            repeat(2) { assertEquals(failed, owner.advance()) }
            assertEquals(index + 1, backend.renders)
            assertEquals(0, backend.publisherCreations)
        }
    }

    @Test fun gpuRetryPermissionDoesNotSurviveRestartEvenWithExplicitRetryRequested() {
        for (explicit in listOf(false, true)) {
            val backend = Backend().also { it.renderFailure = gpuFailure() }
            val storage = Storage()
            val owner = ManualOriginalTrialSaveOwner(request, backend, storage) { true }
            assertEquals(ManualOriginalTrialSavePhase.GPU_FAILED, owner.advance().phase)
            val restartedBackend = Backend()
            val restarted = ManualOriginalTrialSaveOwner(request, restartedBackend, storage) { true }
            val held = restarted.advance(retryGpuFailure = explicit)
            assertEquals(ManualOriginalTrialSavePhase.HOLD, held.phase)
            assertEquals(ManualOriginalTrialSaveReason.GENERATION_STARTED, held.reason)
            assertEquals(held, restarted.advance(retryGpuFailure = true))
            assertEquals(0, restartedBackend.renders)
            assertEquals(0, restartedBackend.publisherCreations)
            assertEquals(1, storage.writes)
        }
    }

    @Test fun explicitRetryCannotBypassMissingCorruptOrForeignIntentAfterGpuFailure() {
        for (changed in listOf<ByteArray?>(null, byteArrayOf(1, 2, 3),
            ManualOriginalTrialRequestCodec.encode(request.copy(generation = 2)),
            ManualOriginalTrialRequestCodec.encode(request.copy(strength = .5)))) {
            val backend = Backend().also { it.renderFailure = gpuFailure() }
            val storage = Storage()
            val owner = ManualOriginalTrialSaveOwner(request, backend, storage) { true }
            owner.advance()
            storage.bytes = changed
            backend.renderFailure = null
            val held = owner.advance(retryGpuFailure = true)
            assertEquals(ManualOriginalTrialSavePhase.HOLD, held.phase)
            assertEquals(ManualOriginalTrialSaveReason.GENERATION_STARTED, held.reason)
            storage.bytes = ManualOriginalTrialRequestCodec.encode(request)
            assertEquals(held, owner.advance(retryGpuFailure = true))
            assertEquals(1, backend.renders)
            assertEquals(1, storage.writes)
            assertEquals(0, backend.publisherCreations)
        }
    }

    @Test fun gpuRetryIntentWriteOrReadbackAmbiguityIsTerminalWithoutAnotherRender() {
        for (mode in listOf("before", "after", "readback", "retryReadback", "retryMismatch")) {
            val backend = Backend().also { it.renderFailure = gpuFailure() }
            val storage = Storage()
            val owner = ManualOriginalTrialSaveOwner(request, backend, storage) { true }
            owner.advance()
            backend.renderFailure = null
            storage.mode = mode
            val held = owner.advance(retryGpuFailure = true)
            assertEquals(ManualOriginalTrialSavePhase.HOLD, held.phase)
            assertEquals(ManualOriginalTrialSaveReason.OPERATION_FAILED, held.reason)
            storage.mode = "none"
            assertEquals(held, owner.advance(retryGpuFailure = true))
            assertEquals(1, backend.renders)
            assertEquals(0, backend.publisherCreations)
        }
    }

    @Test fun gpuFailureWithUnconfirmedCleanupSuppressedFailureOrLiveResourcesIsTerminal() {
        for (ambiguity in listOf("cleanup", "suppressed", "resources")) {
            val failure = gpuFailure(cleanupConfirmed = ambiguity != "cleanup")
            if (ambiguity == "suppressed") failure.addSuppressed(IOException("cleanup unconfirmed"))
            val backend = Backend().also {
                it.renderFailure = failure
                if (ambiguity == "resources") it.beforeRender = { it.held = true }
            }
            val owner = ManualOriginalTrialSaveOwner(request, backend, Storage()) { true }
            val held = owner.advance()
            assertEquals(ManualOriginalTrialSavePhase.HOLD, held.phase)
            assertEquals(GpuSaveFailure(GpuSaveFailureReason.RENDER_FAILED, 1), held.gpuFailure)
            backend.renderFailure = null
            backend.beforeRender = {}
            owner.releaseResources()
            assertFalse(owner.hasUnreleasedResources)
            assertEquals(held, owner.advance(retryGpuFailure = true))
            assertEquals(1, backend.renders)
            assertEquals(0, backend.publisherCreations)
        }
    }

    @Test fun ordinaryFailureAfterExplicitGpuRetryDoesNotCreateAnotherRetryPermit() {
        val backend = Backend().also { it.renderFailure = gpuFailure() }
        val owner = ManualOriginalTrialSaveOwner(request, backend, Storage()) { true }
        owner.advance()
        backend.renderFailure = IOException("JPEG or source verification failed")
        val held = owner.advance(retryGpuFailure = true)
        assertEquals(ManualOriginalTrialSavePhase.HOLD, held.phase)
        assertEquals(GpuSaveFailure(GpuSaveFailureReason.RENDER_FAILED, 1), held.gpuFailure)
        backend.renderFailure = null
        assertEquals(held, owner.advance(retryGpuFailure = true))
        assertEquals(2, backend.renders)
        assertEquals(0, backend.publisherCreations)
    }

    @Test fun gpuExceptionAfterProofOrPublisherCreationCannotAuthorizeRerender() {
        for (boundary in listOf("publisher", "publication")) {
            val backend = Backend()
            val failure = gpuFailure()
            if (boundary == "publisher") backend.publisherFailure = failure
            else backend.publication.failure = failure
            val owner = ManualOriginalTrialSaveOwner(request, backend, Storage()) { true }
            assertEquals(ManualOriginalTrialSavePhase.UNCONFIRMED, owner.advance().phase)
            backend.publisherFailure = null
            backend.publication.failure = null
            assertEquals(ManualOriginalTrialSavePhase.PUBLISHED, owner.advance(retryGpuFailure = true).phase)
            assertEquals(1, backend.renders)
        }
    }

    @Test fun cancellationOrSupersessionAtGpuFailureCannotOfferRetry() {
        for (cancelled in listOf(false, true)) {
            var stopped = false
            var current = true
            val backend = Backend().also {
                it.renderFailure = gpuFailure()
                it.beforeRender = { if (cancelled) stopped = true else current = false }
            }
            val owner = ManualOriginalTrialSaveOwner(request, backend, Storage()) { current }
            val held = owner.advance { stopped }
            assertEquals(ManualOriginalTrialSavePhase.HOLD, held.phase)
            assertEquals(GpuSaveFailure(GpuSaveFailureReason.RENDER_FAILED, 1), held.gpuFailure)
            stopped = false; current = true; backend.renderFailure = null
            assertEquals(held, owner.advance(retryGpuFailure = true))
            assertEquals(1, backend.renders)
            assertEquals(0, backend.publisherCreations)
        }
    }

    @Test fun stoppedExplicitRetryDoesNotWriteIntentOrConsumeAnotherRender() {
        val backend = Backend().also { it.renderFailure = gpuFailure() }
        val storage = Storage()
        val owner = ManualOriginalTrialSaveOwner(request, backend, storage) { true }
        owner.advance()
        backend.renderFailure = null
        val stopped = owner.advance(retryGpuFailure = true) { true }
        assertEquals(ManualOriginalTrialSavePhase.GPU_FAILED, stopped.phase)
        assertEquals(ManualOriginalTrialSaveReason.STOPPED, stopped.reason)
        assertEquals(1, backend.renders)
        assertEquals(1, storage.writes)
        assertEquals(stopped, owner.advance())
        assertEquals(ManualOriginalTrialSavePhase.PUBLISHED, owner.advance(retryGpuFailure = true).phase)
        assertEquals(2, backend.renders)
    }

    @Test fun supersededGpuFailureCannotBeRetriedEvenIfAuthorityLaterReturns() {
        var current = true
        val backend = Backend().also { it.renderFailure = gpuFailure() }
        val owner = ManualOriginalTrialSaveOwner(request, backend, Storage()) { current }
        owner.advance()
        backend.renderFailure = null
        current = false
        val held = owner.advance(retryGpuFailure = true)
        assertEquals(ManualOriginalTrialSavePhase.HOLD, held.phase)
        assertEquals(ManualOriginalTrialSaveReason.SUPERSEDED, held.reason)
        current = true
        assertEquals(held, owner.advance(retryGpuFailure = true))
        assertEquals(1, backend.renders)
        assertEquals(0, backend.publisherCreations)
    }

    @Test fun cancellationOrSupersessionAfterRetryIntentReadbackPreventsAnotherRender() {
        for (cancelled in listOf(false, true)) {
            var stopped = false
            var current = true
            val backend = Backend().also { it.renderFailure = gpuFailure() }
            val storage = Storage()
            val owner = ManualOriginalTrialSaveOwner(request, backend, storage) { current }
            owner.advance()
            backend.renderFailure = null
            storage.afterReplace = { if (cancelled) stopped = true else current = false }
            val held = owner.advance(retryGpuFailure = true) { stopped }
            assertEquals(if (cancelled) ManualOriginalTrialSavePhase.CANCELLED else ManualOriginalTrialSavePhase.HOLD, held.phase)
            stopped = false; current = true; storage.afterReplace = {}
            // A GPU-failure retry flag cannot reuse a new cancellation permit.
            assertEquals(held, owner.advance(retryGpuFailure = true))
            assertEquals(1, backend.renders)
            assertEquals(2, storage.writes)
            assertEquals(0, backend.publisherCreations)
        }
    }

    @Test fun explicitRetryWithRecoveredPublishedOrUnknownProofNeverRendersAgain() {
        for (phase in listOf(JpegPublicationPhase.PUBLISHED, JpegPublicationPhase.UNKNOWN)) {
            val backend = Backend().also { it.renderFailure = gpuFailure() }
            val storage = Storage()
            val owner = ManualOriginalTrialSaveOwner(request, backend, storage) { true }
            owner.advance()
            backend.renderFailure = null
            backend.existing = record(phase)
            val recovered = owner.advance(retryGpuFailure = true)
            assertEquals(if (phase == JpegPublicationPhase.PUBLISHED) ManualOriginalTrialSavePhase.PUBLISHED
                else ManualOriginalTrialSavePhase.UNCONFIRMED, recovered.phase)
            assertEquals(1, backend.renders)
            assertEquals(1, storage.writes)
            assertEquals(if (phase == JpegPublicationPhase.PUBLISHED) 1 else 0, backend.publisherCreations)
        }
    }

    @Test fun conflictingPublicationAfterGpuFailureHoldsWithoutRetryingTheProducer() {
        val backend = Backend().also { it.renderFailure = gpuFailure() }
        val storage = Storage()
        val owner = ManualOriginalTrialSaveOwner(request, backend, storage) { true }
        owner.advance()
        backend.existing = record(JpegPublicationPhase.PUBLISHED).copy(render = render.copy(
            request = render.request.copy(source = source.copy(uri = "content://test/original/2"))))
        val held = owner.advance(retryGpuFailure = true)
        assertEquals(ManualOriginalTrialSavePhase.HOLD, held.phase)
        assertEquals(ManualOriginalTrialSaveReason.OPERATION_FAILED, held.reason)
        backend.existing = null; backend.renderFailure = null
        assertEquals(held, owner.advance(retryGpuFailure = true))
        assertEquals(1, backend.renders)
        assertEquals(0, backend.publisherCreations)
        assertEquals(1, storage.writes)
    }

    @Test fun explicitRetryCannotBypassAnOrdinaryProducerFailure() {
        val backend = Backend().also { it.renderFailure = IOException("JPEG close unconfirmed") }
        val owner = ManualOriginalTrialSaveOwner(request, backend, Storage()) { true }
        val held = owner.advance()
        backend.renderFailure = null
        assertEquals(ManualOriginalTrialSavePhase.HOLD, held.phase)
        assertNull(held.gpuFailure)
        assertEquals(held, owner.advance(retryGpuFailure = true))
        assertEquals(1, backend.renders)
        assertEquals(0, backend.publisherCreations)
    }

    @Test fun explicitRetryWaitsForResourcesAndCleanupAloneDoesNotRetry() {
        val backend = Backend().also { it.renderFailure = gpuFailure() }
        val storage = Storage()
        val owner = ManualOriginalTrialSaveOwner(request, backend, storage) { true }
        owner.advance()
        backend.renderFailure = null
        backend.held = true
        val held = owner.advance(retryGpuFailure = true)
        assertEquals(ManualOriginalTrialSavePhase.HOLD, held.phase)
        assertEquals(ManualOriginalTrialSaveReason.RESOURCES_HELD, held.reason)
        assertEquals(1, backend.renders)
        assertEquals(1, storage.writes)
        owner.releaseResources()
        assertEquals(held, owner.advance())
        assertEquals(1, backend.renders)
        assertEquals(ManualOriginalTrialSavePhase.PUBLISHED, owner.advance(retryGpuFailure = true).phase)
        assertEquals(2, backend.renders)
        assertEquals(1, backend.publisherCreations)
    }

    @Test fun successfulRetryStoppedAfterPrivateProofReusesProofWithoutAnotherRender() {
        val backend = Backend().also { it.renderFailure = gpuFailure() }
        val owner = ManualOriginalTrialSaveOwner(request, backend, Storage()) { true }
        owner.advance()
        var stopped = false
        backend.renderFailure = null
        backend.beforeRender = { stopped = true }
        val paused = owner.advance(retryGpuFailure = true) { stopped }
        assertEquals(ManualOriginalTrialSavePhase.UNCONFIRMED, paused.phase)
        assertEquals(ManualOriginalTrialSaveReason.STOPPED, paused.reason)
        assertEquals(0, backend.publisherCreations)
        stopped = false
        backend.beforeRender = {}
        assertEquals(ManualOriginalTrialSavePhase.PUBLISHED, owner.advance().phase)
        assertEquals(2, backend.renders)
        assertEquals(1, backend.publisherCreations)
    }

    @Test fun successfulGpuRetryCannotPublishMismatchedProofOrRetryThatConflict() {
        val backend = Backend().also { it.renderFailure = gpuFailure() }
        val owner = ManualOriginalTrialSaveOwner(request, backend, Storage()) { true }
        owner.advance()
        backend.renderFailure = null
        backend.generated = render.copy(request = render.request.copy(generation = 2))
        val held = owner.advance(retryGpuFailure = true)
        assertEquals(ManualOriginalTrialSavePhase.HOLD, held.phase)
        assertEquals(ManualOriginalTrialSaveReason.OPERATION_FAILED, held.reason)
        backend.generated = render
        assertEquals(held, owner.advance(retryGpuFailure = true))
        assertEquals(2, backend.renders)
        assertEquals(0, backend.publisherCreations)
    }

    @Test fun confirmedCancellationWaitsForExplicitRetryAndKeepsTheImmutableIntent() {
        val cancellation = confirmedCancellation()
        var stopped = false
        val backend = Backend().also {
            it.renderFailure = cancellation
            it.beforeRender = { stopped = true }
        }
        val storage = Storage()
        val owner = ManualOriginalTrialSaveOwner(request, backend, storage) { true }
        val cancelled = owner.advance { stopped }
        assertEquals(ManualOriginalTrialSavePhase.CANCELLED, cancelled.phase)
        assertEquals(ManualOriginalTrialSaveReason.STOPPED, cancelled.reason)
        assertNull(cancelled.uri)
        assertNull(cancelled.gpuFailure)
        assertSame(cancellation, owner.lastFailure)
        val intent = storage.bytes!!.clone()
        owner.releaseResources()
        backend.renderFailure = null
        backend.beforeRender = {}
        stopped = false
        repeat(3) { assertEquals(cancelled, owner.advance()) }
        assertEquals(cancelled, owner.advance(retryGpuFailure = true))
        assertEquals(1, backend.publicationReads)
        assertEquals(1, backend.renders)
        assertEquals(0, backend.publisherCreations)
        assertEquals(1, storage.writes)
        backend.beforeRender = { assertArrayEquals(intent, storage.bytes) }
        val published = owner.advance(retryCancellation = true)
        assertEquals(ManualOriginalTrialSavePhase.PUBLISHED, published.phase)
        assertEquals(uri, published.uri)
        assertEquals(2, backend.renders)
        assertEquals(1, backend.publisherCreations)
        assertEquals(2, storage.writes)
        assertArrayEquals(intent, storage.bytes)
        assertSame(request, owner.request)
        repeat(2) { assertEquals(published, owner.advance(retryCancellation = true)) }
        assertEquals(2, backend.renders)
        assertEquals(1, backend.publisherCreations)
    }

    @Test fun cancellationWithoutPositivePreJpegCleanupEvidenceIsTerminal() {
        for (ambiguity in listOf("unproved", "resources", "suppressed", "cause")) {
            val failure = if (ambiguity == "unproved") CancellationException("JPEG state unknown")
                else confirmedCancellation()
            if (ambiguity == "suppressed") failure.addSuppressed(IOException("output close unconfirmed"))
            if (ambiguity == "cause") failure.cause!!.addSuppressed(IOException("scratch cleanup unconfirmed"))
            val backend = Backend().also {
                it.renderFailure = failure
                if (ambiguity == "resources") it.beforeRender = { it.held = true }
            }
            val owner = ManualOriginalTrialSaveOwner(request, backend, Storage()) { true }
            val held = owner.advance()
            assertEquals(ambiguity, ManualOriginalTrialSavePhase.HOLD, held.phase)
            backend.renderFailure = null
            backend.beforeRender = {}
            owner.releaseResources()
            assertEquals(held, owner.advance(retryCancellation = true, retryGpuFailure = true))
            assertEquals(1, backend.renders)
            assertEquals(0, backend.publisherCreations)
        }
    }

    @Test fun cancellationRetryPermitDoesNotSurviveProcessRestart() {
        val storage = Storage()
        val backend = Backend().also { it.renderFailure = confirmedCancellation() }
        val owner = ManualOriginalTrialSaveOwner(request, backend, storage) { true }
        assertEquals(ManualOriginalTrialSavePhase.CANCELLED, owner.advance().phase)
        for (explicit in listOf(false, true)) {
            val restartedBackend = Backend()
            val restarted = ManualOriginalTrialSaveOwner(request, restartedBackend, storage) { true }
            assertEquals(ManualOriginalTrialSavePhase.HOLD, restarted.advance(retryCancellation = explicit).phase)
            assertEquals(0, restartedBackend.renders)
            assertEquals(0, restartedBackend.publisherCreations)
        }
        assertEquals(1, storage.writes)
    }

    @Test fun explicitCancellationRetryRejectsMissingCorruptOrChangedIntentPermanently() {
        for (changed in listOf<ByteArray?>(null, byteArrayOf(1, 2),
            ManualOriginalTrialRequestCodec.encode(request.copy(generation = 2)),
            ManualOriginalTrialRequestCodec.encode(request.copy(strength = .5)),
            ManualOriginalTrialRequestCodec.encode(source.copy(uri = "content://test/original/2").let {
                request.copy(captureId = ManualOriginalTrialRequests.identity(it), source = it) }))) {
            val backend = Backend().also { it.renderFailure = confirmedCancellation() }
            val storage = Storage()
            val owner = ManualOriginalTrialSaveOwner(request, backend, storage) { true }
            owner.advance()
            storage.bytes = changed
            backend.renderFailure = null
            val held = owner.advance(retryCancellation = true)
            assertEquals(ManualOriginalTrialSavePhase.HOLD, held.phase)
            assertEquals(ManualOriginalTrialSaveReason.GENERATION_STARTED, held.reason)
            storage.bytes = ManualOriginalTrialRequestCodec.encode(request)
            assertEquals(held, owner.advance(retryCancellation = true))
            assertEquals(1, backend.renders)
            assertEquals(1, storage.writes)
            assertEquals(0, backend.publisherCreations)
        }
    }

    @Test fun cancellationRetryIntentWriteOrReadbackFailureNeverStartsAnotherRender() {
        for (mode in listOf("before", "after", "readback", "retryReadback", "retryMismatch")) {
            val backend = Backend().also { it.renderFailure = confirmedCancellation() }
            val storage = Storage()
            val owner = ManualOriginalTrialSaveOwner(request, backend, storage) { true }
            owner.advance()
            backend.renderFailure = null
            storage.mode = mode
            val held = owner.advance(retryCancellation = true)
            assertEquals(ManualOriginalTrialSavePhase.HOLD, held.phase)
            storage.mode = "none"
            assertEquals(held, owner.advance(retryCancellation = true))
            assertEquals(1, backend.renders)
            assertEquals(0, backend.publisherCreations)
        }
    }

    @Test fun stoppedCancellationRetryDoesNotConsumeThePermitOrWriteIntent() {
        val backend = Backend().also { it.renderFailure = confirmedCancellation() }
        val storage = Storage()
        val owner = ManualOriginalTrialSaveOwner(request, backend, storage) { true }
        val cancelled = owner.advance()
        backend.renderFailure = null
        assertEquals(cancelled, owner.advance(retryCancellation = true) { true })
        assertEquals(1, backend.renders)
        assertEquals(1, storage.writes)
        assertEquals(ManualOriginalTrialSavePhase.PUBLISHED, owner.advance(retryCancellation = true).phase)
        assertEquals(2, backend.renders)
    }

    @Test fun cancellationAfterConfirmedIntentBeforeProducerRequiresExplicitRetry() {
        val backend = Backend()
        val storage = Storage()
        var stopped = false
        storage.afterReplace = { stopped = true }
        val owner = ManualOriginalTrialSaveOwner(request, backend, storage) { true }
        val cancelled = owner.advance { stopped }
        assertEquals(ManualOriginalTrialSavePhase.CANCELLED, cancelled.phase)
        assertEquals(0, backend.renders)
        assertEquals(1, storage.writes)
        assertArrayEquals(ManualOriginalTrialRequestCodec.encode(request), storage.bytes)
        stopped = false
        storage.afterReplace = {}
        assertEquals(cancelled, owner.advance())
        assertEquals(ManualOriginalTrialSavePhase.PUBLISHED, owner.advance(retryCancellation = true).phase)
        assertEquals(1, backend.renders)
    }

    @Test fun repeatedConfirmedCancellationRequiresANewExplicitRetryWithoutGpuFailureCount() {
        val backend = Backend().also { it.renderFailure = confirmedCancellation() }
        val owner = ManualOriginalTrialSaveOwner(request, backend, Storage()) { true }
        repeat(3) { index ->
            val cancelled = owner.advance(retryCancellation = index > 0)
            assertEquals(ManualOriginalTrialSavePhase.CANCELLED, cancelled.phase)
            assertNull(cancelled.gpuFailure)
            assertEquals(cancelled, owner.advance())
            assertEquals(index + 1, backend.renders)
            assertEquals(0, backend.publisherCreations)
        }
    }

    @Test fun cancellationAndGpuFailurePermitsDoNotAuthorizeEachOther() {
        val backend = Backend().also { it.renderFailure = gpuFailure() }
        val owner = ManualOriginalTrialSaveOwner(request, backend, Storage()) { true }
        val gpuFailed = owner.advance()
        assertEquals(gpuFailed, owner.advance(retryCancellation = true))
        backend.renderFailure = confirmedCancellation()
        val cancelled = owner.advance(retryGpuFailure = true)
        assertEquals(ManualOriginalTrialSavePhase.CANCELLED, cancelled.phase)
        assertEquals(gpuFailed.gpuFailure, cancelled.gpuFailure)
        assertEquals(cancelled, owner.advance(retryGpuFailure = true))
        backend.renderFailure = gpuFailure()
        val failedAgain = owner.advance(retryCancellation = true)
        assertEquals(ManualOriginalTrialSavePhase.GPU_FAILED, failedAgain.phase)
        assertEquals(2L, failedAgain.gpuFailure!!.count)
        assertEquals(failedAgain, owner.advance(retryCancellation = true))
        backend.renderFailure = null
        assertEquals(ManualOriginalTrialSavePhase.PUBLISHED, owner.advance(retryGpuFailure = true).phase)
        assertEquals(4, backend.renders)
    }

    @Test fun cancellationAfterPrivateProofCannotAuthorizeRerender() {
        for (boundary in listOf("publisher", "publication")) {
            val backend = Backend()
            if (boundary == "publisher") backend.publisherFailure = confirmedCancellation()
            else backend.publication.failure = confirmedCancellation()
            val owner = ManualOriginalTrialSaveOwner(request, backend, Storage()) { true }
            assertEquals(ManualOriginalTrialSavePhase.UNCONFIRMED, owner.advance().phase)
            backend.publisherFailure = null
            backend.publication.failure = null
            assertEquals(ManualOriginalTrialSavePhase.PUBLISHED, owner.advance(retryCancellation = true).phase)
            assertEquals(1, backend.renders)
        }
    }

    @Test fun cancelledOwnerWithRecoveredPublicationReconcilesWithoutRerender() {
        for (phase in listOf(JpegPublicationPhase.PUBLISHED, JpegPublicationPhase.UNKNOWN)) {
            val backend = Backend().also { it.renderFailure = confirmedCancellation() }
            val storage = Storage()
            val owner = ManualOriginalTrialSaveOwner(request, backend, storage) { true }
            owner.advance()
            backend.renderFailure = null
            backend.existing = record(phase)
            val recovered = owner.advance(retryCancellation = true)
            assertEquals(if (phase == JpegPublicationPhase.PUBLISHED) ManualOriginalTrialSavePhase.PUBLISHED
                else ManualOriginalTrialSavePhase.UNCONFIRMED, recovered.phase)
            assertEquals(1, backend.renders)
            assertEquals(1, storage.writes)
            backend.existing = record(JpegPublicationPhase.PUBLISHED)
            assertEquals(ManualOriginalTrialSavePhase.PUBLISHED, owner.advance().phase)
            assertEquals(1, backend.renders)
        }
    }

    @Test fun supersededCancellationCannotRegainRenderAuthority() {
        for (duringCancellation in listOf(false, true)) {
            var current = true
            val backend = Backend().also {
                it.renderFailure = confirmedCancellation()
                if (duringCancellation) it.beforeRender = { current = false }
            }
            val owner = ManualOriginalTrialSaveOwner(request, backend, Storage()) { current }
            val result = owner.advance()
            current = false
            val held = if (duringCancellation) result else owner.advance(retryCancellation = true)
            assertEquals(ManualOriginalTrialSavePhase.HOLD, held.phase)
            current = true
            backend.beforeRender = {}
            backend.renderFailure = null
            assertEquals(held, owner.advance(retryCancellation = true))
            assertEquals(1, backend.renders)
        }
    }

    @Test fun unknownReconciliationIoFailureRemainsRecheckableWithoutRerender() {
        val backend = Backend().also {
            it.existing = record(JpegPublicationPhase.UNKNOWN)
            it.reconciliationFailure = IOException("Synthetic query or receipt failure")
        }
        val owner = ManualOriginalTrialSaveOwner(request, backend, Storage()) { true }
        assertEquals(ManualOriginalTrialSavePhase.UNCONFIRMED, owner.advance().phase)
        assertEquals(0, backend.renders)
        backend.reconciliationFailure = null
        backend.reconciled = record(JpegPublicationPhase.PUBLISHING)
        assertEquals(ManualOriginalTrialSavePhase.PUBLISHED, owner.advance().phase)
        assertEquals(2, backend.reconciliationCalls)
        assertEquals(0, backend.renders)
    }

    private fun confirmedCancellation() = ConfirmedSaveCancellationException(CancellationException("Producer stopped"))

    private fun gpuFailure(reason: GpuSaveFailureReason = GpuSaveFailureReason.RENDER_FAILED,
        cleanupConfirmed: Boolean = true) = GpuSaveRenderException(reason, cleanupConfirmed, IOException("GPU producer failed"))

    private inner class Backend : ManualOriginalTrialSaveBackend {
        override fun pinDurableGpuEngine() = Unit
        var existing: JpegPublicationRecord? = null
        var generated = render
        var renders = 0
        var publisherCreations = 0
        var publicationReads = 0
        var renderFailure: Exception? = null
        var publisherFailure: Exception? = null
        var releaseFailure: Exception? = null
        var reconciliationFailure: Exception? = null
        var reconciled: JpegPublicationRecord? = null
        var reconciliationCalls = 0
        var beforeRender: () -> Unit = {}
        var held = false
        override val hasUnreleasedResources get() = held
        val publication = Publication()
        override fun readPublication(): JpegPublicationRecord? { publicationReads++; return existing }
        override fun reconcilePublication(stop: () -> Boolean): JpegPublicationRecord? {
            reconciliationCalls++
            reconciliationFailure?.let { throw it }
            return reconciled ?: existing
        }
        override fun render(stop: () -> Boolean): ManualOriginalTrialRender {
            renders++; beforeRender(); renderFailure?.let { throw it }
            return generated
        }
        override fun publisher(render: ManualOriginalTrialRender): ManualOriginalTrialPublication {
            publisherCreations++
            publisherFailure?.let { throw it }
            return publication
        }
        override fun releaseResources() { releaseFailure?.let { throw it }; held = false }
    }
    private inner class Publication : ManualOriginalTrialPublication {
        var result: JpegPublicationRecord? = null
        var advances = 0
        var failure: Exception? = null
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
            return result ?: record(JpegPublicationPhase.PUBLISHED)
        }
        override fun releaseResources() { held = false }
    }
    private class Storage : AtomicJournalStorage {
        var bytes: ByteArray? = null
        var writes = 0
        var mode = "none"
        var afterReplace: () -> Unit = {}
        override fun read(maxBytes: Int): ByteArray? {
            if ((mode == "readback" && writes > 0) || (mode == "retryReadback" && writes > 1))
                throw IOException("readback")
            if (mode == "retryMismatch" && writes > 1) return byteArrayOf(1, 2, 3)
            return bytes?.clone()
        }
        override fun replace(contents: ByteArray) {
            writes++
            if (mode == "before") throw IOException("before")
            bytes = contents.clone()
            if (mode == "after") throw IOException("after")
            afterReplace()
        }
    }
}
