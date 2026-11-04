package dev.cinematic.p0.photoroll

import dev.cinematic.p0.rawcapture.*
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class PhotoRollJpegSaveTest {
    private val source = SharedOriginalReference("content://media/external_primary/images/media/7",
        OriginalFingerprint(16, "1".repeat(64)), OriginalGrant.APP_OWNED)
    private val render = CompletedRender(RenderedJpegRequest("synthetic_save", 1, source,
        RenderRecipeSnapshot("synthetic-recipe-v1", "synthetic"), JpegOutputDescription(32, 24, 1, "sRGB", 12)),
        "synthetic-renderer-v1", RenderedResultKind.COMPLETE_STAGE_CLEAR, RenderedFaceState.VERIFIED_NONE,
        RenderedExposureState.SUPPORTED)
    private val supported = setOf(RendererPublicationContract(render.rendererContract, render.request.recipe.schema))
    private fun record(value: CompletedRender = render, phase: JpegPublicationPhase = JpegPublicationPhase.PUBLISHED) =
        JpegPublicationRecord(JpegPublicationTarget(value.request.captureId, value.request.generation, "a".repeat(32)),
            value, OriginalFingerprint(12, "2".repeat(64)), phase = phase,
            uri = if (phase == JpegPublicationPhase.UNKNOWN) null else "content://media/external_primary/images/media/9")

    private fun prepare(current: () -> Boolean = { true }, publisher: () -> PhotoRollJpegPublisher) =
        PhotoRollJpegSave.prepare(source.uri, source.fingerprint, PhotoRollPreviewMode.STAGE_CLEAR,
            render, supported, current, publisher)

    @Test fun trialModesAreRejectedBeforeCreatingAnyPublisher() {
        var factories = 0
        for (mode in listOf(PhotoRollPreviewMode.DNG_TRIAL, PhotoRollPreviewMode.STAGE_CLEAR_TRIAL)) {
            try {
                PhotoRollJpegSave.prepare(source.uri, source.fingerprint, mode, render, supported, { true }) {
                    factories++; PhotoRollJpegPublisher { record() }
                }
                fail("Trial must not create a JPEG operation")
            } catch (_: IllegalArgumentException) { }
        }
        assertEquals(0, factories)
    }

    @Test fun UnknownFaceExposureKindAndContractsRejectBeforeFactory() {
        var factories = 0
        for (candidate in listOf(render.copy(kind = RenderedResultKind.TRIAL_PREVIEW),
            render.copy(face = RenderedFaceState.NOT_RUN), render.copy(face = RenderedFaceState.FAILED),
            render.copy(exposure = RenderedExposureState.DIAGNOSTIC), render.copy(exposure = RenderedExposureState.UNKNOWN),
            render.copy(rendererContract = "unknown"),
            render.copy(request = render.request.copy(recipe = RenderRecipeSnapshot("unknown", "synthetic"))))) {
            try {
                PhotoRollJpegSave.prepare(source.uri, source.fingerprint, PhotoRollPreviewMode.STAGE_CLEAR,
                    candidate, supported, { true }) { factories++; PhotoRollJpegPublisher { record() } }
                fail("Unsupported completion must be rejected")
            } catch (_: IOException) { }
        }
        try {
            PhotoRollJpegSave.prepare(source.uri, source.fingerprint, PhotoRollPreviewMode.STAGE_CLEAR,
                render, emptySet(), { true }) { factories++; PhotoRollJpegPublisher { record() } }
            fail("No production contracts are enabled by default")
        } catch (_: IOException) { }
        assertEquals(0, factories)
    }

    @Test fun differentOriginalIsRejectedBeforeFactoryAndBindingIsExact() {
        var factories = 0
        for ((uri, fingerprint) in listOf(source.uri + "0" to source.fingerprint,
            source.uri to source.fingerprint.copy(sha256 = "3".repeat(64)))) {
            try {
                PhotoRollJpegSave.prepare(uri, fingerprint, PhotoRollPreviewMode.STAGE_CLEAR, render, supported, { true }) {
                    factories++; PhotoRollJpegPublisher { record() }
                }
                fail("Different original must not get a save operation")
            } catch (_: IllegalArgumentException) { }
        }
        assertEquals(0, factories)
        val owner = prepare { PhotoRollJpegPublisher { record() } }
        assertTrue(owner.matches(source.uri, source.fingerprint, PhotoRollPreviewMode.STAGE_CLEAR))
        assertFalse(owner.matches(source.uri, source.fingerprint, PhotoRollPreviewMode.STAGE_CLEAR_TRIAL))
        assertFalse(owner.matches(source.uri + "0", source.fingerprint, PhotoRollPreviewMode.STAGE_CLEAR))
    }

    @Test fun lostAcknowledgementAndOpenRecheckReuseOnePublisher() {
        var factories = 0; var calls = 0
        val owner = prepare { factories++; PhotoRollJpegPublisher {
            calls++; if (calls == 1) throw IOException("Synthetic acknowledgement loss") else record()
        } }
        assertEquals(PhotoRollSavePhase.UNCONFIRMED, owner.advance { false }.phase)
        assertNull(owner.snapshot.uri)
        val saved = owner.advance { false }
        assertEquals(PhotoRollSavePhase.PUBLISHED, saved.phase)
        assertEquals(saved, owner.advance { false }) // Opening requires a new readback by the same publisher.
        assertEquals(1, factories); assertEquals(3, calls)
    }

    @Test fun unknownAndForeignRecordsHoldWithoutInventingSuccess() {
        for (returned in listOf(record(phase = JpegPublicationPhase.UNKNOWN),
            record(render.copy(request = render.request.copy(generation = 2))))) {
            var calls = 0
            val owner = prepare { PhotoRollJpegPublisher { calls++; returned } }
            assertEquals(PhotoRollSavePhase.HOLD, owner.advance { false }.phase)
            assertNull(owner.snapshot.uri)
            assertEquals(PhotoRollSavePhase.HOLD, owner.advance { false }.phase)
            assertEquals(1, calls)
        }
    }

    @Test fun deletionAfterPublicationRemovesOldResultUriWithoutRecreatingOwner() {
        var factories = 0; var calls = 0
        val owner = prepare { factories++; PhotoRollJpegPublisher {
            calls++; if (calls == 1) record() else throw IOException("Synthetic external deletion")
        } }
        assertNotNull(owner.advance { false }.uri)
        repeat(2) { assertEquals(PhotoRollSavePhase.UNCONFIRMED, owner.advance { false }.phase); assertNull(owner.snapshot.uri) }
        assertEquals(1, factories); assertEquals(3, calls)
    }

    @Test fun stopBeforeAndAfterPublicationDoesNotExposeAReceipt() {
        var stop = true; var calls = 0
        val owner = prepare { PhotoRollJpegPublisher { calls++; stop = true; record() } }
        assertEquals(PhotoRollSavePhase.UNCONFIRMED, owner.advance { stop }.phase)
        assertEquals(0, calls)
        stop = false
        assertEquals(PhotoRollSavePhase.UNCONFIRMED, owner.advance { stop }.phase)
        assertNull(owner.snapshot.uri); assertEquals(1, calls)
    }

    @Test fun staleAuthorityBlocksEvenAPreviouslyPublishedOwner() {
        var current = true; var calls = 0
        val owner = prepare({ current }) { PhotoRollJpegPublisher { calls++; record() } }
        assertNotNull(owner.advance { false }.uri)
        current = false
        assertEquals(PhotoRollSavePhase.UNCONFIRMED, owner.advance { false }.phase)
        assertNull(owner.snapshot.uri); assertEquals(1, calls)
    }

    @Test fun overlappingClicksCannotEnterThePublisherTogether() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        var calls = 0
        val owner = prepare { PhotoRollJpegPublisher { calls++; entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); record() } }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val first = executor.submit<PhotoRollSaveSnapshot> { owner.advance { false } }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val contender = owner.advance { false }
            assertEquals(PhotoRollSavePhase.SAVING, contender.phase)
            assertNull(contender.uri)
            release.countDown()
            assertEquals(PhotoRollSavePhase.PUBLISHED, first.get(5, TimeUnit.SECONDS).phase)
            assertEquals(1, calls)
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test fun memoryFailureIsUnconfirmedAndRetryKeepsTheSamePublisher() {
        var calls = 0; var factories = 0
        val owner = prepare { factories++; PhotoRollJpegPublisher {
            if (++calls == 1) throw OutOfMemoryError("Synthetic") else record()
        } }
        assertEquals(PhotoRollSavePhase.UNCONFIRMED, owner.advance { false }.phase)
        assertNull(owner.snapshot.uri)
        assertEquals(PhotoRollSavePhase.PUBLISHED, owner.advance { false }.phase)
        assertEquals(1, factories)
    }

    @Test fun concurrentCallCannotReceivePublishedWithoutDoingItsOwnConfirmation() {
        val confirmedHere = ThreadLocal.withInitial { false }
        val unexpectedReceipts = AtomicInteger()
        val calls = AtomicInteger()
        val owner = prepare { PhotoRollJpegPublisher { confirmedHere.set(true); calls.incrementAndGet(); record() } }
        assertNotNull(owner.advance { false }.uri) // A previous published receipt exists before contention.
        val ready = CountDownLatch(2); val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = List(2) { executor.submit {
                ready.countDown(); check(start.await(5, TimeUnit.SECONDS))
                try { repeat(2_000) {
                    confirmedHere.set(false)
                    val result = owner.advance { false }
                    if (result.phase == PhotoRollSavePhase.PUBLISHED && confirmedHere.get() != true) unexpectedReceipts.incrementAndGet()
                    if (result.phase == PhotoRollSavePhase.SAVING && result.uri != null) unexpectedReceipts.incrementAndGet()
                } } finally { confirmedHere.remove() }
            } }
            assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown()
            futures.forEach { it.get(5, TimeUnit.SECONDS) }
            assertTrue(calls.get() > 1)
            assertEquals(0, unexpectedReceipts.get())
        } finally { start.countDown(); executor.shutdownNow(); confirmedHere.remove() }
    }

    @Test fun sharedWorkerCompletionIsObservableWithoutStartingAnotherSave() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val owner = prepare { PhotoRollJpegPublisher { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); record() } }
        val observed = mutableListOf<PhotoRollSaveSnapshot>()
        val observer = launch(start = CoroutineStart.UNDISPATCHED) { owner.snapshots.take(3).toList(observed) }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val worker = executor.submit<PhotoRollSaveSnapshot> { owner.advance { false } }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            yield() // Consume SAVING before allowing the publisher to finish.
            assertEquals(PhotoRollSavePhase.SAVING, observed.last().phase)
            val contender = owner.advance { false }
            assertEquals(PhotoRollSavePhase.SAVING, contender.phase); assertNull(contender.uri)
            release.countDown()
            assertEquals(PhotoRollSavePhase.PUBLISHED, worker.get(5, TimeUnit.SECONDS).phase)
            withTimeout(5_000) { observer.join() }
            assertEquals(listOf(PhotoRollSavePhase.READY, PhotoRollSavePhase.SAVING, PhotoRollSavePhase.PUBLISHED), observed.map { it.phase })
            assertNotNull(observed.last().uri)
        } finally { release.countDown(); observer.cancel(); executor.shutdownNow() }
    }
}
