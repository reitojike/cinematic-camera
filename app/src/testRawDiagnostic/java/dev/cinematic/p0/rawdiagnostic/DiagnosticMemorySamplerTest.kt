package dev.cinematic.p0.rawdiagnostic

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class DiagnosticMemorySamplerTest {
    private fun inlineThread(runnable: Runnable) = object : Thread(runnable) {
        override fun start() { run() }
    }

    @Test fun samplesAt100msAndKeepsIndependentObservedMaximaAndCollectionCost() {
        var now = 0L
        var reads = 0
        lateinit var sampler: DiagnosticMemorySampler
        sampler = DiagnosticMemorySampler(
            readMemory = {
                reads++
                now += 2_000_000
                if (reads == 3) sampler.requestStop()
                DiagnosticMemorySampler.Reading(reads * 10L, 100L - reads)
            },
            nanoTime = { now }, sleepNanos = { now += it }, threadFactory = ::inlineThread,
        )
        assertTrue(sampler.start())
        val result = sampler.stopAndJoin()
        assertTrue(result.quiescent)
        assertFalse(result.failed)
        assertEquals(3L, result.sampleCount)
        assertEquals(30L, result.observedMaxManagedBytes)
        assertEquals(99L, result.observedMaxNativeBytes)
        assertEquals(100_000_000L, result.meanIntervalNanos)
        assertEquals(100_000_000L, result.maxGapNanos)
        assertEquals(202_000_000L, result.elapsedNanos)
        assertEquals(6_000_000L, result.collectionTotalNanos)
        assertEquals(2_000_000L, result.collectionMaxNanos)
        assertEquals(0L, result.skippedIntervals)
    }

    @Test fun slowCollectionSkipsSlotsInsteadOfUnboundedCatchup() {
        var now = 0L
        var reads = 0
        lateinit var sampler: DiagnosticMemorySampler
        sampler = DiagnosticMemorySampler(readMemory = {
            now += 250_000_000
            if (++reads == 2) sampler.requestStop()
            DiagnosticMemorySampler.Reading(1, 2)
        }, nanoTime = { now }, sleepNanos = { now += it }, threadFactory = ::inlineThread)
        assertTrue(sampler.start())
        val result = sampler.stopAndJoin()
        assertEquals(2L, result.sampleCount)
        assertEquals(4L, result.skippedIntervals)
        assertEquals(300_000_000L, result.meanIntervalNanos)
        assertEquals(300_000_000L, result.maxGapNanos)
    }

    @Test fun readerFailureIsNotAZeroByteSuccessfulSample() {
        val sampler = DiagnosticMemorySampler(readMemory = { error("not reported") },
            nanoTime = { 0L }, threadFactory = ::inlineThread)
        assertTrue(sampler.start())
        val result = sampler.stopAndJoin()
        assertTrue(result.failed)
        assertTrue(result.quiescent)
        assertEquals(1L, result.failedSamples)
        assertEquals(0L, result.sampleCount)
        assertNull(result.observedMaxManagedBytes)
        assertNull(result.observedMaxNativeBytes)
        assertNull(result.meanIntervalNanos)
    }

    @Test fun startFailureStillAllowsCleanup() {
        val sampler = DiagnosticMemorySampler(readMemory = { error("unused") },
            threadFactory = { throw IllegalStateException() })
        assertFalse(sampler.start())
        val result = sampler.stopAndJoin()
        assertTrue(result.failed)
        assertTrue(result.quiescent)
    }

    @Test fun blockedReaderRequiresHoldUntilThreadActuallyExits() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val sampler = DiagnosticMemorySampler(readMemory = {
            entered.countDown()
            while (release.count > 0) {
                try { release.await() } catch (_: InterruptedException) { /* Deliberately stuck reader. */ }
            }
            DiagnosticMemorySampler.Reading(1, 2)
        })
        try {
            assertTrue(sampler.start())
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertFalse(sampler.stopAndJoin(10).quiescent)
        } finally {
            release.countDown()
            assertTrue(sampler.stopAndJoin(2_000).quiescent)
        }
    }

    @Test fun cancellationDuringSleepStopsWithoutAnotherRead() {
        val entered = CountDownLatch(1)
        val sampler = DiagnosticMemorySampler(readMemory = { DiagnosticMemorySampler.Reading(1, 2) },
            sleepNanos = { entered.countDown(); Thread.sleep(10_000) })
        try {
            assertTrue(sampler.start())
            assertTrue(entered.await(2, TimeUnit.SECONDS))
        } finally {
            val result = sampler.stopAndJoin(2_000)
            assertTrue(result.quiescent)
            assertFalse(result.failed)
            assertEquals(1L, result.sampleCount)
        }
    }

    @Test fun interruptedJoinPreservesInterruptAndDoesNotClaimQuiescence() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val sampler = DiagnosticMemorySampler(readMemory = {
            entered.countDown()
            while (release.count > 0) {
                try { release.await() } catch (_: InterruptedException) { /* Still owns reader. */ }
            }
            DiagnosticMemorySampler.Reading(1, 2)
        })
        try {
            assertTrue(sampler.start())
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            Thread.currentThread().interrupt()
            assertFalse(sampler.stopAndJoin().quiescent)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
            release.countDown()
            assertTrue(sampler.stopAndJoin(2_000).quiescent)
        }
    }
}
