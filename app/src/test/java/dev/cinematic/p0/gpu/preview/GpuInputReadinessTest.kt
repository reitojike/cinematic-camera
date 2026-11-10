package dev.cinematic.p0.gpu.preview

import dev.cinematic.p0.photoroll.PhotoRollObservationMetadata
import dev.cinematic.p0.photoroll.PhotoRollObservedCamera
import dev.cinematic.p0.rawcapture.AutoDevelopConditions
import dev.cinematic.p0.rawcapture.AutoDevelopCoordinator
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.Callable
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

/** Pure request/publication tests. Never publishes facts to process or CPU admission. */
class GpuInputReadinessTest {
    private val ready = AutoDevelopConditions(true, false, false, false, true, true, true, false, true)
    private fun clock() = System.nanoTime() / 1_000_000
    private fun request(ms: Long = 1_000) = GpuInputRequestDeadline(ms, ::clock)
    private fun publication(sequence: Long = 1, state: PhotoRollObservedCamera = PhotoRollObservedCamera.AVAILABLE,
        conditions: AutoDevelopConditions = if (state == PhotoRollObservedCamera.AVAILABLE) ready else ready.copy(cameraSessionInUse = true)) =
        GpuInputPublication(sequence, PhotoRollObservationMetadata(conditions, 1, 1, true,
            state != PhotoRollObservedCamera.UNKNOWN, state == PhotoRollObservedCamera.IN_USE, true))
    private fun refusal(): GpuInputAdmissionRefused {
        val coordinator = AutoDevelopCoordinator().apply { updateConditions(ready) }
        return GpuInputAdmissionRefused(coordinator.tryStartGpuDiagnosticPreviewObserved(ready.copy(cameraSessionInUse = true)).snapshot)
    }
    private fun accepted(request: GpuInputRequestDeadline): Deferred<GpuInputAdmissionResult> {
        assertNotNull(request.tryAdmit { Unit })
        return CompletableDeferred(GpuInputAdmissionResult(null))
    }

    @Test fun alreadyReadyValuesCanAdmitWithoutAnotherPublication() = runBlocking {
        val request = request()
        var starts = 0
        awaitGpuInputReadiness(MutableStateFlow(publication()), MutableStateFlow(true), request) {
            starts++; accepted(request)
        }
        assertEquals(1, starts)
        assertTrue(request.isAdmitted())
    }

    @Test fun surfaceAndInitialResourcePublicationAreBothRequiredInEitherOrder() = runBlocking {
        for (surfaceFirst in listOf(false, true)) {
            val publications = MutableStateFlow(GpuInputPublication())
            val surface = MutableStateFlow(surfaceFirst)
            val request = request()
            var starts = 0
            val job = launch { awaitGpuInputReadiness(publications, surface, request) { starts++; accepted(request) } }
            subscribed(publications, surface)
            assertEquals(0, starts)
            publications.value = publication()
            if (!surfaceFirst) surface.value = true
            withTimeout(1_000) { job.join() }
            assertEquals(1, starts)
            unsubscribed(publications, surface)
        }
    }

    @Test fun allPreviewBlockersStillBlockAndCannotBeWeakenedByMetadata() = runBlocking {
        for (conditions in listOf(ready.copy(foreground = false), ready.copy(recordingOrFinalizing = true),
            ready.copy(rebindInProgress = true), ready.copy(thermalAllowed = false), ready.copy(storageAllowed = false),
            ready.copy(rendererAvailable = false), ready.copy(previewMemoryAvailable = false))) {
            val result = runCatching { awaitGpuInputReadiness(MutableStateFlow(publication(conditions = conditions)),
                MutableStateFlow(true), request(20)) { error("Blocked input started") } }.exceptionOrNull()
            assertTrue(result is GpuInputReadinessTimeout)
        }
    }

    @Test fun unknownAndConfirmedBusyHaveDifferentNonCancellationTimeouts() = runBlocking {
        for ((state, reason) in listOf(PhotoRollObservedCamera.UNKNOWN to GpuInputWaitReason.CAMERA_UNKNOWN,
            PhotoRollObservedCamera.IN_USE to GpuInputWaitReason.CAMERA_IN_USE)) {
            val publications = MutableStateFlow(publication(state = state))
            val surface = MutableStateFlow(true)
            val result = runCatching { awaitGpuInputReadiness(publications, surface, request(20)) {
                error("Unready camera started")
            } }.exceptionOrNull()
            assertTrue(result is GpuInputReadinessTimeout)
            assertFalse(result is CancellationException)
            assertEquals(reason, (result as GpuInputReadinessTimeout).reason)
            unsubscribed(publications, surface)
        }
    }

    @Test fun missingSurfaceHasPreparationTimeoutNotCameraBusy() = runBlocking {
        val result = runCatching { awaitGpuInputReadiness(MutableStateFlow(publication()), MutableStateFlow(false), request(20)) {
            error("Missing surface started")
        } }.exceptionOrNull() as GpuInputReadinessTimeout
        assertEquals(GpuInputWaitReason.OTHER_PREPARATION, result.reason)
    }

    @Test fun refusedAdmissionWaitsForNewPublicationEvenWhenItsFactsAreEqual() = runBlocking {
        val publications = MutableStateFlow(publication())
        val surface = MutableStateFlow(true)
        val request = request()
        val entered = CompletableDeferred<Unit>()
        val first = CompletableDeferred<GpuInputAdmissionResult>()
        var starts = 0
        val job = launch {
            awaitGpuInputReadiness(publications, surface, request) {
                starts++
                if (starts == 1) { entered.complete(Unit); first } else accepted(request)
            }
        }
        entered.await()
        first.complete(GpuInputAdmissionResult(refusal()))
        subscribed(publications, surface)
        publications.value = publication() // Same event is not a retry notification.
        assertEquals(1, starts)
        publications.value = publication(2) // The next REAL publication may have identical facts.
        withTimeout(1_000) { job.join() }
        assertEquals(2, starts)
        unsubscribed(publications, surface)
    }

    @Test fun publicationAfterIoRefusalBeforeMainResumptionIsNotLost() = runBlocking {
        val publications = MutableStateFlow(publication())
        val request = request()
        var starts = 0
        awaitGpuInputReadiness(publications, MutableStateFlow(true), request) {
            starts++
            if (starts == 1) {
                val rejected = GpuInputAdmissionResult(refusal())
                publications.value = publication(2)
                CompletableDeferred(rejected)
            } else accepted(request)
        }
        assertEquals(2, starts)
    }

    @Test fun publicationBeforeRefusalCallbackIsNotConsumedByThatRefusal() = runBlocking {
        val now = AtomicLong()
        val request = GpuInputRequestDeadline(10_000, now::get)
        val publications = MutableStateFlow(publication())
        var starts = 0
        withTimeout(1_000) {
            awaitGpuInputReadiness(publications, MutableStateFlow(true), request) {
                starts++
                if (starts == 1) {
                    val failure = refusal() // Coordinator has already refused this attempt.
                    now.set(9_999) // No time for another observer publication before the deadline.
                    publications.value = publication(2) // Arrives before the loader reports refusal.
                    CompletableDeferred(GpuInputAdmissionResult(failure))
                } else accepted(request)
            }
        }
        assertEquals(2, starts)
        assertEquals(9_999L, request.admittedAtMillis())
    }

    @Test fun cameraRejectionsDoNotResetTheSingleRequestDeadline() = runBlocking {
        val now = AtomicLong()
        val request = GpuInputRequestDeadline(10_000, now::get)
        val publications = MutableStateFlow(publication())
        var acquires = 0
        val failure = runCatching {
            awaitGpuInputReadiness(publications, MutableStateFlow(true), request) {
                request.tryAdmit<Any> { acquires++; null }
                val rejected = GpuInputAdmissionResult(refusal())
                now.addAndGet(4_000)
                publications.value = publication(it.sequence + 1)
                CompletableDeferred(rejected)
            }
        }.exceptionOrNull()
        assertTrue(failure is GpuInputReadinessTimeout)
        assertEquals(3, acquires)
        assertFalse(request.isAdmitted())
        assertEquals(10_000L, GPU_INPUT_READINESS_TIMEOUT_MS)
    }

    @Test fun slotCaptureExportAndNonCameraFailuresAreNeverRetried() = runBlocking {
        val camera = refusal().snapshot
        val failures = listOf(GpuInputAdmissionRefused(camera.copy(hasActiveSlot = true)),
            GpuInputAdmissionRefused(camera.copy(captureCount = 1)),
            GpuInputAdmissionRefused(camera.copy(hasPendingExport = true)),
            GpuInputAdmissionRefused(camera.copy(freshPreviewBlockers = setOf(dev.cinematic.p0.rawcapture.AutoDevelopBlocker.THERMAL))),
            IOException("synthetic IO failure"))
        for (failure in failures) {
            var starts = 0
            val actual = runCatching {
                awaitGpuInputReadiness(MutableStateFlow(publication()), MutableStateFlow(true), request()) {
                    starts++; CompletableDeferred(GpuInputAdmissionResult(failure))
                }
            }.exceptionOrNull()
            assertEquals(failure.javaClass, actual?.javaClass)
            // Coroutine debug stacktrace recovery may copy the same exception type. The original
            // must still be preserved as its cause; a wrapper or a different failure is not accepted.
            assertSame(failure, if (actual === failure) actual else actual?.cause)
            assertEquals(1, starts)
        }
    }

    @Test fun actualAdmissionEndsPreparationWithoutWaitingForNativeCompletion() = runBlocking {
        val request = request(100)
        val native = CompletableDeferred<Unit>()
        var starts = 0
        awaitGpuInputReadiness(MutableStateFlow(publication()), MutableStateFlow(true), request) {
            starts++; accepted(request)
        }
        assertFalse(native.isCompleted)
        assertFalse(request.expire())
        native.completeExceptionally(IOException("synthetic native failure"))
        assertEquals(1, starts)
    }

    @OptIn(InternalCoroutinesApi::class)
    @Test fun admissionCompleteBeforeMainResumesCannotBecomePreparationTimeout() {
        val dispatcher = PausedDispatcher()
        val admission = CompletableDeferred<GpuInputAdmissionResult>()
        val request = request(1_000)
        var succeeded = false
        var failure: Throwable? = null
        val job = CoroutineScope(dispatcher).launch {
            try {
                awaitGpuInputReadiness(MutableStateFlow(publication()), MutableStateFlow(true), request) { admission }
                succeeded = true
            } catch (caught: Throwable) { failure = caught }
        }
        try {
            dispatcher.drain()
            val timeout = job.children.single()
            val deadlineFired = CountDownLatch(1)
            timeout.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { deadlineFired.countDown() }
            assertNotNull(request.tryAdmit { Unit })
            val admittedAt = request.admittedAtMillis()
            admission.complete(GpuInputAdmissionResult(null)) // Resume queued; do not run Main yet.
            assertTrue("Deadline callback did not run", deadlineFired.await(5, TimeUnit.SECONDS))
            dispatcher.drain()
            assertTrue(job.isCompleted)
            assertTrue(succeeded)
            assertNull(failure)
            assertEquals(admittedAt, request.admittedAtMillis())
            assertFalse(request.expire())
        } finally { job.cancel(); dispatcher.drain() }
    }

    @Test fun deadlineOrUserCancelBeforeQueuedIoPreventsAnySlotAcquisition() {
        val now = AtomicLong()
        val expired = GpuInputRequestDeadline(10, now::get)
        now.set(10)
        var acquires = 0
        assertTrue(runCatching { expired.tryAdmit { acquires++; Unit } }.exceptionOrNull() is GpuInputDeadlineElapsed)
        val cancelled = GpuInputRequestDeadline(10, now::get)
        cancelled.cancel()
        assertTrue(runCatching { cancelled.tryAdmit { acquires++; Unit } }.exceptionOrNull() is CancellationException)
        assertEquals(0, acquires)
    }

    @Test fun admissionImmediatelyBeforeDeadlineKeepsItsRecordedCommit() {
        val now = AtomicLong()
        val request = GpuInputRequestDeadline(10, now::get)
        now.set(9)
        assertNotNull(request.tryAdmit { Unit })
        assertEquals(9L, request.admittedAtMillis())
        now.set(10)
        assertFalse(request.expire())
        assertEquals(9L, request.admittedAtMillis())
    }

    @Test fun guardWaiterChecksDeadlineAfterAnEarlierRefusalReleasesTheGuard() {
        val now = AtomicLong()
        val request = GpuInputRequestDeadline(10, now::get)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        var laterAcquires = 0
        try {
            val first = pool.submit(Callable { request.tryAdmit<Any> {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); null
            } })
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val second = pool.submit(Callable {
                secondEntered.countDown()
                runCatching { request.tryAdmit { laterAcquires++; Unit } }.exceptionOrNull()
            })
            assertTrue(secondEntered.await(5, TimeUnit.SECONDS))
            now.set(10)
            release.countDown()
            assertNull(first.get(5, TimeUnit.SECONDS))
            assertTrue(second.get(5, TimeUnit.SECONDS) is GpuInputDeadlineElapsed)
            assertEquals(0, laterAcquires)
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test fun completedAcquireWinsAgainstExpiryBeforeGuardBookkeepingReturns() {
        val now = AtomicLong()
        val request = GpuInputRequestDeadline(10, now::get)
        val acquired = CountDownLatch(1)
        val release = CountDownLatch(1)
        val expiryEntered = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            now.set(9)
            val owner = pool.submit(Callable { request.tryAdmit {
                val slot = Unit // Existing coordinator has already returned its positive decision.
                acquired.countDown()
                check(release.await(5, TimeUnit.SECONDS)) // Test-only thread preemption before bookkeeping.
                slot
            } })
            assertTrue(acquired.await(5, TimeUnit.SECONDS))
            now.set(10)
            val expiry = pool.submit(Callable { expiryEntered.countDown(); request.expire() })
            assertTrue(expiryEntered.await(5, TimeUnit.SECONDS))
            release.countDown()
            assertNotNull(owner.get(5, TimeUnit.SECONDS))
            assertFalse(expiry.get(5, TimeUnit.SECONDS))
            assertTrue(request.isAdmitted())
            assertEquals(10L, request.admittedAtMillis()) // Recorded after acquire, not a new deadline test.
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test fun pauseAndNewSelectionCancelOnlyTheOldWaitAndLateIo() = runBlocking {
        val publications = MutableStateFlow(GpuInputPublication())
        val surface = MutableStateFlow(true)
        val oldRequest = request()
        var oldStarts = 0
        val old = launch { awaitGpuInputReadiness(publications, surface, oldRequest) { oldStarts++; accepted(oldRequest) } }
        subscribed(publications, surface)
        old.cancelAndJoin()
        val newer = request()
        var newStarts = 0
        val latest = launch { awaitGpuInputReadiness(publications, surface, newer) { newStarts++; accepted(newer) } }
        publications.value = publication()
        withTimeout(1_000) { latest.join() }
        assertEquals(0, oldStarts)
        assertEquals(1, newStarts)
        assertTrue(runCatching { oldRequest.tryAdmit { error("Old queued IO acquired") } }.exceptionOrNull() is CancellationException)
        assertTrue(newer.isAdmitted())
    }

    @Test fun userCancellationAfterAdmissionStillCancelsTheRequest() = runBlocking {
        val request = request()
        val admission = CompletableDeferred<GpuInputAdmissionResult>()
        val entered = CompletableDeferred<Unit>()
        val job = launch {
            awaitGpuInputReadiness(MutableStateFlow(publication()), MutableStateFlow(true), request) {
                request.tryAdmit { Unit }; entered.complete(Unit); admission
            }
        }
        entered.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertTrue(request.isAdmitted()) // Historical ownership is not erased by cancellation.
        assertTrue(runCatching { request.tryAdmit { error("Cancelled owner reacquired") } }.exceptionOrNull() is CancellationException)
    }

    @Test fun enclosingTimeoutIsCancellationNotAnInputReadinessFailure() = runBlocking {
        val request = request(5_000)
        val failure = runCatching {
            withTimeout(20) { awaitGpuInputReadiness(MutableStateFlow(GpuInputPublication()), MutableStateFlow(false), request) {
                error("Unready request started")
            } }
        }.exceptionOrNull()
        assertTrue(failure is TimeoutCancellationException)
        assertTrue(runCatching { request.tryAdmit { Unit } }.exceptionOrNull() is CancellationException)
    }

    private suspend fun subscribed(publications: MutableStateFlow<GpuInputPublication>, surface: MutableStateFlow<Boolean>) {
        withTimeout(1_000) { combine(publications.subscriptionCount, surface.subscriptionCount) { p, s -> p > 0 && s > 0 }.first { it } }
    }
    private fun unsubscribed(publications: MutableStateFlow<GpuInputPublication>, surface: MutableStateFlow<Boolean>) {
        assertEquals(0, publications.subscriptionCount.value)
        assertEquals(0, surface.subscriptionCount.value)
    }
    private class PausedDispatcher : CoroutineDispatcher() {
        private val pending = ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { pending.add(block) }
        fun drain() { while (true) (pending.poll() ?: return).run() }
    }
}
