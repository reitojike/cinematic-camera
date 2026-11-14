package dev.cinematic.p0.rawcapture

import dev.cinematic.p0.jpegrender.JpegGenerationRecipe
import dev.cinematic.p0.raw.PhotoAdjustments
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test

/** Host-only durable-contract tests. An empty catalog must not revive retired CPU producers.
 * Historical observations are synthetic journal fixtures, not a claim of JPEG/device validation.
 * PRIVATE_TRIAL has never been publishable: the published case is explicitly MANUAL_BOUNDED.
 */
class RetiredCpuJournalCompatibilityTest {
    private val source = SharedOriginalReference("content://test/retired-cpu/original",
        OriginalFingerprint(3, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"),
        OriginalGrant.USER_SELECTED)
    private val allowed = AutoDevelopConditions(true, false, false, false, true, true, true, true)

    @Test fun canonicalPrivateRequestsRemainReadableButCannotStartRetiredCpuRendering() {
        for (recipe in listOf(JpegGenerationRecipe.trial(),
            JpegGenerationRecipe.trial(.65, PhotoAdjustments(20.0, .3)))) {
            val selection = Memory()
            val request = ManualDevelopRequests(source, selection).prepare(recipe.snapshot)
            val selectedBytes = requireNotNull(selection.bytes).clone()
            assertTrue(request.boundedTrial)
            assertEquals(AutoRenderQuality.PRIVATE_TRIAL_1024, request.quality)
            assertFalse(request.publishable)
            assertEquals(recipe.snapshot,
                requireNotNull(JpegGenerationRecipe.fromTrialSnapshot(request.recipe)).snapshot)
            assertEquals(request, AutoDevelopCodec.decode(selectedBytes).request)
            assertEquals(request, ManualDevelopRequests(source, selection).prepare(recipe.snapshot))
            assertArrayEquals(selectedBytes, selection.bytes)

            val state = Memory()
            val journal = journalFor(request, state)
            val queued = journal.prepare(request)
            assertEquals(queued, AutoDevelopCodec.decode(requireNotNull(state.bytes)))
            val backend = NoRetiredIo()
            assertNull(backend.catalog.find(request))
            val runner = runnerFor(journal, backend)
            assertEquals(AutoRunStatus.HELD, runner.advance().status)
            val held = requireNotNull(journal.read())
            assertEquals(AutoJobReason.UNSUPPORTED, held.reason)
            assertEquals(AutoJobPhase.HELD, held.phase)
            assertEquals(request, held.request)
            assertEquals(0, held.attemptCount)
            assertNull(held.attemptId)
            assertNull(held.outputFingerprint)
            assertNull(held.observed)
            val heldBytes = requireNotNull(state.bytes).clone()
            val freshJournal = journalFor(request, state)
            assertEquals(held, freshJournal.recoverAfterProcessDeath())
            repeat(2) { assertEquals(AutoRunStatus.HELD, runnerFor(freshJournal, backend).advance().status) }
            assertArrayEquals(heldBytes, state.bytes)
            assertArrayEquals(selectedBytes, selection.bytes)
            assertFalse(runner.hasUnreleasedResources)
            backend.assertNoIo()
        }
    }

    @Test fun renderedPrivateTrialIsHeldWithoutLosingItsArtifactOrBecomingPublished() {
        val request = ManualDevelopRequests(source, Memory()).prepare(JpegGenerationRecipe.trial().snapshot)
        val state = Memory()
        val journal = journalFor(request, state)
        val queued = journal.prepare(request)
        val started = journal.start(queued.revision)
        val observation = AutoRenderReceipt(request, JpegOutputDescription(16, 12, 1, "sRGB", source.fingerprint.bytes),
            RenderedFaceState.NOT_RUN, RenderedExposureState.DIAGNOSTIC)
        assertTrue(request.accepts(observation))
        val rendered = journal.rendered(started.revision, source.fingerprint, observation)
        val renderedBytes = requireNotNull(state.bytes).clone()
        assertEquals(rendered, journalFor(request, state).recoverAfterProcessDeath())
        assertThrows(CaptureJournalConflict::class.java) { journal.publishing(rendered.revision) }
        assertArrayEquals(renderedBytes, state.bytes)

        val backend = NoRetiredIo()
        val runner = runnerFor(journalFor(request, state), backend)
        assertEquals(AutoRunStatus.HELD, runner.advance().status)
        val held = requireNotNull(journal.read())
        assertEquals(AutoJobReason.UNSUPPORTED, held.reason)
        assertEquals(request, held.request)
        assertEquals(rendered.attemptId, held.attemptId)
        assertEquals(rendered.attemptCount, held.attemptCount)
        assertEquals(rendered.outputFingerprint, held.outputFingerprint)
        assertEquals(observation, held.observed)
        assertThrows(IllegalStateException::class.java) { held.completed() }
        assertThrows(CaptureJournalConflict::class.java) { journal.publishing(held.revision) }
        val heldBytes = requireNotNull(state.bytes).clone()
        assertEquals(AutoRunStatus.HELD, runner.advance().status)
        assertArrayEquals(heldBytes, state.bytes)
        backend.assertNoIo()
        assertFalse(runner.hasUnreleasedResources)
    }

    @Test fun historicalPublishedManualBoundedRecordSurvivesEmptyCatalogWithoutRerenderOrRepublish() {
        // A real canonical bounded recipe, not a PRIVATE_TRIAL whose enum/schema is rewritten.
        val recipe = JpegGenerationRecipe("test-bounded-input-v1", RenderRecipeSnapshot("test-policy-v1", "frozen"))
        val request = ManualDevelopRequests(source, Memory()).prepare(recipe.snapshot)
        assertTrue(request.boundedManual)
        assertFalse(request.boundedTrial)
        val state = Memory()
        val journal = journalFor(request, state)
        val queued = journal.prepare(request)
        val started = journal.start(queued.revision)
        val observation = AutoRenderReceipt(request, JpegOutputDescription(16, 12, 1, "sRGB", source.fingerprint.bytes),
            RenderedFaceState.VERIFIED_NONE, RenderedExposureState.SUPPORTED)
        assertTrue(request.accepts(observation))
        // Exercise all validated durable transitions; never manufacture a terminal phase by copy().
        val rendered = journal.rendered(started.revision, source.fingerprint, observation)
        val publishing = journal.publishing(rendered.revision)
        val published = journal.published(publishing.revision)
        assertEquals(listOf(AutoJobPhase.QUEUED, AutoJobPhase.RENDERING, AutoJobPhase.RENDERED,
            AutoJobPhase.PUBLISHING, AutoJobPhase.PUBLISHED), state.phases)
        val bytes = requireNotNull(state.bytes).clone()
        val writes = state.writes
        assertEquals(published, AutoDevelopCodec.decode(bytes))
        assertEquals(observation.output, published.completed().request.output)

        val backend = NoRetiredIo()
        assertNull(backend.catalog.find(request))
        repeat(3) {
            val restored = journalFor(request, state)
            assertEquals(published, restored.recoverAfterProcessDeath())
            val runner = runnerFor(restored, backend)
            val result = runner.advance()
            assertEquals(AutoRunStatus.PUBLISHED, result.status)
            assertEquals(published, result.record)
            assertFalse(runner.hasUnreleasedResources)
            assertArrayEquals(bytes, state.bytes)
            assertEquals(writes, state.writes)
        }
        assertEquals(0, backend.supportChecks) // Terminal read precedes even renderer enrollment checks.
        backend.assertNoIo()
    }

    private fun journalFor(request: AutoDevelopRequest, state: Memory) =
        AutoDevelopJournal(request.captureId, request.generation, state) { "a".repeat(32) }

    private fun runnerFor(journal: AutoDevelopJournal, backend: NoRetiredIo): AutoDevelopRunner {
        val coordinator = AutoDevelopCoordinator().also { it.updateConditions(allowed) }
        return AutoDevelopRunner(journal, coordinator, backend, cancellationIo = Executor { it.run() },
            interactive = true, observations = { allowed }, isCurrent = { true })
    }

    private class Memory : AtomicJournalStorage {
        var bytes: ByteArray? = null
        var writes = 0
        val phases = mutableListOf<AutoJobPhase>()
        override fun read(maxBytes: Int): ByteArray? = bytes?.also { check(it.size <= maxBytes) }?.clone()
        override fun replace(contents: ByteArray) {
            phases += AutoDevelopCodec.decode(contents).phase
            bytes = contents.clone()
            writes++
        }
    }

    private class NoRetiredIo : AutoDevelopBackend {
        val catalog = AutoRendererCatalog(emptyList())
        var supportChecks = 0
        private val attemptedIo = mutableListOf<String>()
        override val hasUnreleasedResources = false
        override fun releaseResources() = Unit // Runner always executes its final ownership check.
        override fun supports(request: AutoDevelopRequest): Boolean {
            supportChecks++
            return catalog.find(request) != null
        }
        private fun forbidden(operation: String): Nothing {
            attemptedIo += operation
            throw AssertionError("Retired CPU path touched $operation")
        }
        override fun verifyOriginal(request: AutoDevelopRequest, shouldStop: () -> Boolean): Unit = forbidden("original")
        override fun createAttempt(request: AutoDevelopRequest, attemptId: String): AutoRenderAttempt = forbidden("render/output")
        override fun inspect(request: AutoDevelopRequest, attemptId: String, shouldStop: () -> Boolean): JpegInspection = forbidden("output readback")
        override fun publication(record: AutoDevelopRecord, isCurrent: () -> Boolean): AutoDevelopPublication = forbidden("publication")
        override fun privatePreview(record: AutoDevelopRecord, shouldStop: () -> Boolean): PrivateTrialPreview = forbidden("private preview")
        fun assertNoIo() { assertTrue("Unexpected IO: $attemptedIo", attemptedIo.isEmpty()) }
    }
}

