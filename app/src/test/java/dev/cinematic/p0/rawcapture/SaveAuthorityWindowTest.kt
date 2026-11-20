package dev.cinematic.p0.rawcapture

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

class SaveAuthorityWindowTest {
    @Test fun memoryGuardIsRecheckedOnEveryCacheHit() {
        val window = SaveAuthorityWindow { true }
        assertTrue(window.check(false) { true })
        assertFalse(window.check(false) { false })
        assertFalse(window.check(true) { true })
    }

    @Test fun exactThresholdAndForceAlwaysRead() {
        var time = 0L
        var reads = 0
        val window = SaveAuthorityWindow({ time }) { reads++; true }
        assertTrue(window.check(false))
        time = 49_999_999L
        assertTrue(window.check(false))
        assertEquals(1, reads)
        time = 50_000_000L
        assertTrue(window.check(false))
        assertEquals(2, reads)
        assertTrue(window.check(true))
        assertEquals(3, reads)
    }

    @Test fun slowIoNeverExtendsTheValidityWindow() {
        var time = 0L; var reads = 0
        val window = SaveAuthorityWindow({ time }) { reads++; time += 60_000_000; true }
        window.check(false); window.check(false)
        assertEquals(2, reads)
    }
    @Test fun falseAndExceptionsAreLatchedEvenForForce() {
        var reads = 0
        val denied = SaveAuthorityWindow { reads++; false }
        assertFalse(denied.check(false)); assertFalse(denied.check(true)); assertEquals(1, reads)
        val error = IOException("synthetic")
        val failed = SaveAuthorityWindow { reads++; throw error }
        try { failed.check(false); fail() } catch (actual: IOException) { assertSame(error, actual) }
        assertFalse(failed.check(true))
        assertEquals(2, reads)
    }
    @Test fun backwardClockIsNotAHitAndSignedNanoWrapUsesElapsedDifference() {
        var time = 10L; var reads = 0
        val window = SaveAuthorityWindow({ time }) { reads++; true }
        window.check(false); time = 0; window.check(false); assertEquals(2, reads)
        time = Long.MAX_VALUE - 10; window.check(true)
        time = Long.MIN_VALUE + 10; window.check(false); assertEquals(3, reads)
    }
    @Test fun cacheDoesNotCrossAttempts() {
        var reads = 0
        repeat(2) { val own = SaveAuthorityWindow { reads++; true }; own.check(false); own.check(false) }
        assertEquals(2, reads)
    }
    @Test fun scopesRestoreOnFailureAndForceOverridesNestedTimed() {
        assertTrue(SaveAuthorityBoundary.force)
        try { SaveAuthorityBoundary.timed {
            assertFalse(SaveAuthorityBoundary.force)
            SaveAuthorityBoundary.durable { SaveAuthorityBoundary.timed { assertTrue(SaveAuthorityBoundary.force) } }
            assertFalse(SaveAuthorityBoundary.force)
            throw IOException()
        } } catch (_: IOException) { }
        assertTrue(SaveAuthorityBoundary.force)
        var reads = 0
        SaveAuthorityBoundary.observing({ reads++ }) { SaveAuthorityBoundary.checkpoint() }
        SaveAuthorityBoundary.checkpoint(); assertEquals(1, reads)
    }
    @Test fun concurrentTimedChecksHaveOnlyOneFlightAndForceDoesNotCoalesce() {
        val pool = Executors.newFixedThreadPool(2)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        var reads = 0
        val window = SaveAuthorityWindow({ 0L }) {
            reads++; entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); true
        }
        try {
            val a = pool.submit<Boolean> { window.check(false) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val b = pool.submit<Boolean> { window.check(false) }
            release.countDown(); assertTrue(a.get(5, TimeUnit.SECONDS)); assertTrue(b.get(5, TimeUnit.SECONDS))
            assertEquals(1, reads)
            val c = pool.submit<Boolean> { window.check(true) }; val d = pool.submit<Boolean> { window.check(true) }
            c.get(5, TimeUnit.SECONDS); d.get(5, TimeUnit.SECONDS); assertEquals(3, reads)
        } finally { release.countDown(); pool.shutdownNow() }
    }
    @Test fun cancellationIsCheckedOnEveryHitAndNoFalseValueBecomesSuccess() {
        var cancelled = false; var checks = 0; var reads = 0
        val window = SaveAuthorityWindow({ 0L }) { reads++; true }
        fun stop(): Boolean { checks++; return cancelled || !window.check(false) }
        assertFalse(stop()); assertFalse(stop()); cancelled = true; assertTrue(stop())
        assertEquals(3, checks); assertEquals(1, reads)
    }
    @Test fun stageWithoutTraceStillChecksButFailedAuthorityCannotSkipCleanup() {
        var checks = 0; var closed = false
        SaveAuthorityBoundary.observing({ checks++; throw IOException("synthetic") }) {
            try { dev.cinematic.p0.raw.original.originalStage(null,
                dev.cinematic.p0.raw.original.OriginalStage.RESOURCE_CLOSE) { closed = true }; fail() }
            catch (_: IOException) { }
        }
        assertTrue(closed); assertEquals(1, checks)
    }
}
