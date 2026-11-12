package dev.cinematic.p0.photoroll

import dev.cinematic.p0.gpu.preview.GpuInputPublication
import dev.cinematic.p0.rawcapture.AutoDevelopConditions
import dev.cinematic.p0.rawcapture.AutoDevelopCoordinator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class DngImportReadinessTest {
    private val ready = AutoDevelopConditions(true, false, false, false, true, true, true, true, true)
    private fun event(sequence: Long, epoch: Long = 2, observed: Boolean = true,
        conditions: AutoDevelopConditions = ready, generation: Long = 1) =
        GpuInputPublication(sequence, PhotoRollObservationMetadata(conditions, generation, epoch, observed, true, false, true))

    @Test fun resumeResetWaitsForCoordinatorPublicationThenAdmitsOnce() = runBlocking {
        val coordinator = AutoDevelopCoordinator()
        coordinator.updateConditions(AutoDevelopConditions(foreground = true))
        assertNull(coordinator.tryStartObserved(ready))
        val flow = MutableStateFlow(event(2, observed = false, conditions = AutoDevelopConditions(foreground = true)))
        var attempts = 0
        val job = launch {
            awaitDngImportObservation(flow)
            attempts++
            val token = requireNotNull(coordinator.tryStartObserved(ready))
            assertTrue(coordinator.completeAfterResourcesClosed(token))
        }
        withTimeout(1000) { flow.subscriptionCount.first { it > 0 } }
        assertEquals(0, attempts)
        coordinator.updateConditions(ready) // Real observer updates coordinator before metadata.
        flow.value = event(3)
        withTimeout(1000) { job.join() }
        flow.value = event(4)
        assertEquals(1, attempts)
    }

    @Test fun oldEpochAndBackgroundCannotReleasePendingSelection() = runBlocking {
        val flow = MutableStateFlow(event(4, epoch = 4, observed = false))
        var dispatched = false
        val job = launch { awaitDngImportObservation(flow); dispatched = true }
        withTimeout(1000) { flow.subscriptionCount.first { it > 0 } }
        flow.value = event(5, epoch = 3)
        yield()
        assertFalse(dispatched)
        flow.value = event(6, epoch = 4, conditions = ready.copy(foreground = false))
        yield()
        assertFalse(dispatched)
        flow.value = event(7, epoch = 4)
        withTimeout(1000) { job.join() }
        assertTrue(dispatched)
    }

    @Test fun firstObservedBlockerStillRefusesWithoutWaitingForLaterRecovery() = runBlocking {
        val blocked = ready.copy(memoryBudgetConfirmed = false)
        val coordinator = AutoDevelopCoordinator().apply { updateConditions(blocked) }
        val flow = MutableStateFlow(event(3, conditions = blocked))
        awaitDngImportObservation(flow)
        assertNull(coordinator.tryStartObserved(blocked))
        assertEquals(0, flow.subscriptionCount.value)
        coordinator.updateConditions(ready)
        flow.value = event(4)
    }

    @Test fun cancelledSelectionNeverDispatchesOnLaterPublication() = runBlocking {
        val flow = MutableStateFlow(event(2, observed = false))
        var dispatched = false
        val job = launch { awaitDngImportObservation(flow); dispatched = true }
        withTimeout(1000) { flow.subscriptionCount.first { it > 0 } }
        job.cancelAndJoin()
        flow.value = event(3)
        assertFalse(dispatched)
        assertEquals(0, flow.subscriptionCount.value)
    }

    @Test fun synchronouslyInvalidatedSelectionCannotDispatchBeforeEffectCancellation() = runBlocking {
        val flow = MutableStateFlow(event(2, observed = false))
        var revision = 1
        var dispatched = false
        val job = launch {
            val requested = revision
            if (awaitDngImportObservation(flow, stillSelected = { revision == requested })) dispatched = true
        }
        withTimeout(1000) { flow.subscriptionCount.first { it > 0 } }
        revision++ // Cancel/close or same-URI replacement, without cancelling the effect job yet.
        flow.value = event(3)
        withTimeout(1000) { job.join() }
        assertFalse(dispatched)
    }

    @Test fun conflatedPauseResumeCannotDispatchInANewerForegroundEpoch() = runBlocking {
        for (newRegistration in listOf(false, true)) {
            val flow = MutableStateFlow(event(2, epoch = 2, observed = false))
            var dispatched = false
            val job = launch { if (awaitDngImportObservation(flow)) dispatched = true }
            withTimeout(1000) { flow.subscriptionCount.first { it > 0 } }
            // Skip the intermediate pause event entirely, as StateFlow/Compose may conflate it.
            flow.value = event(5, epoch = if (newRegistration) 1 else 4, generation = if (newRegistration) 2 else 1)
            withTimeout(1000) { job.join() }
            assertFalse(dispatched)
        }
    }

    @Test fun unavailableObservationEndsWithinExistingBoundWithoutAdmission() = runBlocking {
        val flow = MutableStateFlow(event(2, observed = false))
        val failure = runCatching { awaitDngImportObservation(flow, timeoutMillis = 20) }.exceptionOrNull()
        assertTrue(failure is TimeoutCancellationException)
        assertEquals(0, flow.subscriptionCount.value)
    }
}
