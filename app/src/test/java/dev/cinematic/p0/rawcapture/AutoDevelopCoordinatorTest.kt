package dev.cinematic.p0.rawcapture

import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class AutoDevelopCoordinatorTest {
    private fun ready() = AutoDevelopCoordinator().apply { updateConditions(permittedConditions()) }

    @Test fun defaultBlocksAndOnlyOneAttemptCanOwnTheSlot() {
        val coordinator = AutoDevelopCoordinator()
        assertNull(coordinator.tryStart())
        coordinator.updateConditions(permittedConditions())
        val attempt = requireNotNull(coordinator.tryStart())
        assertFalse(coordinator.shouldStop(attempt))
        assertNull(coordinator.tryStart())
    }

    @Test fun captureReservationWinsBeforeStartAndDuplicateReleaseDoesNotOpenAnotherReservation() {
        val coordinator = ready()
        val first = coordinator.reserveCapture()
        val second = coordinator.reserveCapture()
        assertNull(coordinator.tryStart())
        assertTrue(coordinator.releaseCapture(first))
        assertFalse(coordinator.releaseCapture(first))
        assertNull(coordinator.tryStart())
        assertTrue(coordinator.releaseCapture(second))
        assertNotNull(coordinator.tryStart())
    }

    @Test fun captureAfterAdmissionRequestsStopButDoesNotPretendResourcesAreReleased() {
        val coordinator = ready()
        val attempt = requireNotNull(coordinator.tryStart())
        val capture = coordinator.reserveCapture() // Does not wait for worker completion.
        assertTrue(coordinator.shouldStop(attempt))
        assertTrue(coordinator.releaseCapture(capture))
        assertNull(coordinator.tryStart())
        assertTrue(coordinator.completeAfterResourcesClosed(attempt))
        assertNotNull(coordinator.tryStart())
    }

    @Test fun unsafeOrUnknownUpdateLatchesStopEvenIfConditionsRecover() {
        val conditions = listOf(
            permittedConditions().copy(foreground = false),
            permittedConditions().copy(cameraSessionInUse = true),
            permittedConditions().copy(recordingOrFinalizing = true),
            permittedConditions().copy(rebindInProgress = true),
            permittedConditions().copy(thermalAllowed = null),
            permittedConditions().copy(storageAllowed = false),
            permittedConditions().copy(rendererAvailable = false),
        )
        conditions.forEach { blocked ->
            val coordinator = ready()
            val attempt = requireNotNull(coordinator.tryStart())
            coordinator.updateConditions(blocked)
            assertTrue(coordinator.shouldStop(attempt))
            coordinator.updateConditions(permittedConditions())
            assertTrue(coordinator.shouldStop(attempt))
            assertNull(coordinator.tryStart())
            assertTrue(coordinator.completeAfterResourcesClosed(attempt))
            assertNotNull(coordinator.tryStart())
        }
    }

    @Test fun explicitStopIsIdempotentAndLateCompletionCannotReleaseNewAttempt() {
        val coordinator = ready()
        coordinator.requestStop()
        val old = requireNotNull(coordinator.tryStart())
        coordinator.requestStop()
        coordinator.requestStop()
        assertTrue(coordinator.shouldStop(old))
        assertNull(coordinator.tryStart())
        assertTrue(coordinator.completeAfterResourcesClosed(old))
        val current = requireNotNull(coordinator.tryStart())
        assertFalse(coordinator.completeAfterResourcesClosed(old))
        assertTrue(coordinator.shouldStop(old))
        assertFalse(coordinator.shouldStop(current))
        assertNull(coordinator.tryStart())
    }

    @Test fun foreignTicketsAndReservationsCannotReleaseCurrentOwners() {
        val coordinator = ready()
        val other = ready()
        val current = requireNotNull(coordinator.tryStart())
        val foreignAttempt = requireNotNull(other.tryStart())
        val ownCapture = coordinator.reserveCapture()
        assertFalse(coordinator.releaseCapture(other.reserveCapture()))
        assertFalse(coordinator.completeAfterResourcesClosed(foreignAttempt))
        assertTrue(coordinator.shouldStop(foreignAttempt))
        assertNull(coordinator.tryStart())
        assertTrue(coordinator.completeAfterResourcesClosed(current))
        assertNull(coordinator.tryStart()) // Own capture still blocks.
        coordinator.releaseCapture(ownCapture)
        assertNotNull(coordinator.tryStart())
    }

    @Test fun workerFailureDoesNotOpenSlotUntilResourceOwnerReportsClosure() {
        val coordinator = ready()
        val attempt = requireNotNull(coordinator.tryStart())
        assertThrows(IOException::class.java) {
            try { throw IOException("Injected worker failure before resource closure") }
            finally { coordinator.requestStop() }
        }
        val capture = coordinator.reserveCapture()
        assertTrue(coordinator.shouldStop(attempt))
        assertNull(coordinator.tryStart())
        assertTrue(coordinator.releaseCapture(capture))
        assertNull(coordinator.tryStart())
        assertTrue(coordinator.completeAfterResourcesClosed(attempt))
        assertNotNull(coordinator.tryStart())
    }

    @Test fun recreatedCoordinatorDoesNotRestoreLeasesOrTrustOldConditionsAndTickets() {
        val beforeDeath = ready()
        val old = requireNotNull(beforeDeath.tryStart())
        val oldCapture = beforeDeath.reserveCapture()
        val afterDeath = AutoDevelopCoordinator()
        assertTrue(afterDeath.shouldStop(old))
        assertFalse(afterDeath.completeAfterResourcesClosed(old))
        assertFalse(afterDeath.releaseCapture(oldCapture))
        assertNull(afterDeath.tryStart())
        afterDeath.updateConditions(permittedConditions())
        assertNotNull(afterDeath.tryStart())
    }

    @Test fun concurrentStartRequestsAdmitExactlyOneOwner() {
        val coordinator = ready()
        val executor = Executors.newFixedThreadPool(4)
        val go = CountDownLatch(1)
        try {
            val futures = List(32) { executor.submit(Callable { go.await(); coordinator.tryStart() }) }
            go.countDown()
            val attempts = futures.mapNotNull { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, attempts.size)
            assertFalse(coordinator.shouldStop(attempts.single()))
        } finally { executor.shutdownNow() }
    }

    @Test fun startAndCaptureRaceEitherBlocksAdmissionOrCancelsTheAdmittedOwner() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(50) {
                val coordinator = ready()
                val go = CountDownLatch(1)
                val start = executor.submit(Callable { go.await(); coordinator.tryStart() })
                val reserve = executor.submit(Callable { go.await(); coordinator.reserveCapture() })
                go.countDown()
                val attempt = start.get(5, TimeUnit.SECONDS)
                val capture = reserve.get(5, TimeUnit.SECONDS)
                assertNull(coordinator.tryStart())
                if (attempt != null) {
                    assertTrue(coordinator.shouldStop(attempt))
                    assertTrue(coordinator.completeAfterResourcesClosed(attempt))
                }
                assertTrue(coordinator.releaseCapture(capture))
                assertNotNull(coordinator.tryStart())
            }
        } finally { executor.shutdownNow() }
    }
}
