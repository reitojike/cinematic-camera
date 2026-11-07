package dev.cinematic.p0.photoroll

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PreviewRequestGateTest {
    @Test fun cancelledCallerWaitsForQueueOwnerAndDoesNotReplaceItsFailure() {
        val future = java.util.concurrent.CompletableFuture<Unit>()
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val waiter = Thread {
            entered.countDown()
            awaitPreviewOwnerCompletion(future)
            released.countDown()
        }
        waiter.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertFalse(released.await(50, TimeUnit.MILLISECONDS))
            future.completeExceptionally(IllegalStateException("synthetic owner failure"))
            assertTrue(released.await(5, TimeUnit.SECONDS))
        } finally { future.complete(Unit); waiter.join(5000) }
        assertFalse(waiter.isAlive)
    }
    @Test fun enabledGatePreservesSyntheticPreviewBitsAndSource() = runBlocking {
        val source = dev.cinematic.p0.raw.LinearSrgbImage(8, 6,
            FloatArray(144) { .03f + ((it * 17) % 113) / 160f })
        val original = source.copyPixels()
        val expected = StageClearTrialPipeline.render(source, { false }).copyPixels()
        val actual = PreviewRequestGate().run(true) {
            StageClearTrialPipeline.render(source, { false }).copyPixels()
        }
        assertArrayEquals(original, source.copyPixels(), 0f)
        assertArrayEquals(expected.map { it.toRawBits() }.toIntArray(), actual.map { it.toRawBits() }.toIntArray())
    }
    @Test fun cancelledIntermediateRequestsNeverRunAndLatestResultMatchesDirect() = runBlocking {
        val gate = PreviewRequestGate()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val released = AtomicInteger()
        val computed = Collections.synchronizedList(mutableListOf<Int>())
        val shown = Collections.synchronizedList(mutableListOf<Int>())
        val first = launch(Dispatchers.IO) {
            try {
                val value = gate.run(true) {
                    peak.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                    computed += 0
                    entered.countDown()
                    try { check(release.await(5, TimeUnit.SECONDS)); 0 }
                    finally { active.decrementAndGet() }
                }
                currentCoroutineContext().ensureActive()
                shown += value
            } finally { released.incrementAndGet() }
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        first.cancel()
        val jobs = mutableListOf<Job>()
        try {
            for (id in 1..20) {
                jobs.lastOrNull()?.cancel()
                jobs += launch(start = CoroutineStart.UNDISPATCHED) {
                    val value = gate.run(true) {
                        peak.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                        try { computed += id; id * id } finally { active.decrementAndGet() }
                    }
                    currentCoroutineContext().ensureActive()
                    shown += value
                }
            }
            // Cancelling the old job must not release a non-cooperative executing block's permit.
            assertEquals(listOf(0), computed.toList())
        } finally { release.countDown() }
        first.join(); jobs.joinAll()
        assertEquals(1, peak.get())
        assertEquals(0, active.get())
        assertEquals(1, released.get())
        assertEquals(listOf(0, 20), computed.toList())
        assertEquals(listOf(20 * 20), shown.toList())
    }

    @Test fun failureAndCooperativeCancellationReleaseThePermit() = runBlocking {
        val gate = PreviewRequestGate()
        try { gate.run(true) { error("synthetic failure") }; fail("Expected failure") }
        catch (_: IllegalStateException) { }
        val entered = CompletableDeferred<Unit>()
        val released = AtomicInteger()
        val job = launch {
            gate.run(true) {
                try { entered.complete(Unit); awaitCancellation() }
                finally { released.incrementAndGet() }
            }
        }
        entered.await(); job.cancelAndJoin()
        assertEquals(1, released.get())
        assertEquals(42, gate.run(true) { 42 })
    }

    @Test fun disabledPathDoesNotWaitForEnabledOwner() = runBlocking {
        val gate = PreviewRequestGate()
        val entered = CompletableDeferred<Unit>()
        val holder = launch { gate.run(true) { entered.complete(Unit); awaitCancellation() } }
        entered.await()
        try { assertEquals(7, gate.run(false) { 7 }) }
        finally { holder.cancelAndJoin() }
    }
}
