package dev.cinematic.p0.rawdiagnostic

import dev.cinematic.p0.raw.original.OriginalStage
import dev.cinematic.p0.raw.original.OriginalStageTrace
import dev.cinematic.p0.raw.original.originalStage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import org.junit.Assert.*
import org.junit.Test

class DiagnosticCancellationLatencyTest {
    private var now = 0L
    private fun tracker() = DiagnosticCancellationLatency { now }
    private fun requestInStage(t: DiagnosticCancellationLatency, stage: OriginalStage = OriginalStage.GLOW,
        origin: DiagnosticCancellationLatency.Origin = DiagnosticCancellationLatency.Origin.USER_BUTTON) {
        t.renderStarted(); t.stageStarted(stage); now = 100; t.request(origin)
    }
    private fun finish(t: DiagnosticCancellationLatency,
        outcome: DiagnosticCancellationLatency.RenderOutcome = DiagnosticCancellationLatency.RenderOutcome.CANCELLED,
        owners: Boolean = true, harness: Boolean = true) {
        now = 200; t.renderExited(outcome)
        now = 250; t.attemptScopeExited(owners)
        now = 300; t.harnessFinished(harness)
    }
    @Test fun glowAndMistKeepReceiptRenderCloseAndHarnessIntervalsSeparate() {
        for (stage in listOf(OriginalStage.GLOW, OriginalStage.MIST)) {
            val t = tracker(); requestInStage(t, stage); finish(t)
            val o = t.observation()
            assertEquals("OBSERVED_TARGET_STAGE_CANCEL", o.status)
            assertEquals(stage.name, o.stageAtRequest)
            assertEquals("USER_BUTTON", o.origin)
            assertEquals(100L, o.requestToRenderExitNanos)
            assertEquals(150L, o.requestToAttemptScopeExitNanos)
            assertEquals(150L, o.requestToOwnersReleasedNanos)
            assertEquals(200L, o.requestToHarnessQuiescentNanos)
        }
    }
    @Test fun firstReceiptIsNotResetByRepeatedClickOrLifecycle() {
        val t = tracker(); requestInStage(t)
        now = 130; t.request(DiagnosticCancellationLatency.Origin.USER_BUTTON)
        now = 170; t.request(DiagnosticCancellationLatency.Origin.LIFECYCLE)
        finish(t)
        assertEquals("USER_BUTTON", t.observation().origin)
        assertEquals(100L, t.observation().requestToRenderExitNanos)
    }
    @Test fun lifecycleCannotBecomeAUserButtonResult() {
        val t = tracker(); requestInStage(t, origin = DiagnosticCancellationLatency.Origin.LIFECYCLE)
        now = 150; t.request(DiagnosticCancellationLatency.Origin.USER_BUTTON); finish(t)
        assertEquals("NON_USER_REQUEST", t.observation().status)
        assertEquals("LIFECYCLE", t.observation().origin)
    }
    @Test fun missingRequestDoesNotInventZeroLatency() {
        val t = tracker(); finish(t)
        val o = t.observation()
        assertEquals("NO_REQUEST", o.status)
        assertNull(o.requestToRenderExitNanos); assertNull(o.requestToAttemptScopeExitNanos)
        assertNull(o.requestToOwnersReleasedNanos); assertNull(o.requestToHarnessQuiescentNanos)
    }
    @Test fun successfulOrFailedRenderIsNotCancellation() {
        for (outcome in listOf(DiagnosticCancellationLatency.RenderOutcome.SUCCESS,
            DiagnosticCancellationLatency.RenderOutcome.FAILED)) {
            val t = tracker(); requestInStage(t); finish(t, outcome)
            assertEquals("RENDER_NOT_CANCELLED", t.observation().status)
            assertEquals(outcome, t.observation().renderOutcome)
        }
    }
    @Test fun heldOwnersAndHarnessNeverClaimQuiescence() {
        val held = tracker(); requestInStage(held); finish(held, owners = false, harness = false)
        assertEquals("HOLD_NOT_QUIESCENT", held.observation().status)
        assertNotNull(held.observation().requestToRenderExitNanos)
        assertNotNull(held.observation().requestToAttemptScopeExitNanos)
        assertNull(held.observation().requestToOwnersReleasedNanos)
        assertNull(held.observation().requestToHarnessQuiescentNanos)
        val io = tracker(); requestInStage(io); finish(io, harness = false)
        assertEquals("HOLD_NOT_QUIESCENT", io.observation().status)
        assertNotNull(io.observation().requestToOwnersReleasedNanos)
        assertNull(io.observation().requestToHarnessQuiescentNanos)
    }
    @Test fun beforeRenderAndAfterTargetAreOutsideTargetNotReconstructedFromLastStage() {
        val before = tracker(); now = 100; before.request(DiagnosticCancellationLatency.Origin.USER_BUTTON)
        finish(before)
        assertEquals("OUTSIDE_GLOW_MIST", before.observation().status)
        assertFalse(before.observation().duringRender)
        val after = tracker(); after.renderStarted(); after.stageStarted(OriginalStage.GLOW)
        after.stageFinished(OriginalStage.GLOW); now = 100; after.request(DiagnosticCancellationLatency.Origin.USER_BUTTON)
        finish(after)
        assertEquals("OUTSIDE_GLOW_MIST", after.observation().stageAtRequest)
        assertEquals("OUTSIDE_GLOW_MIST", after.observation().status)
    }
    @Test fun lateClickCannotMeasureEarlierRenderExit() {
        val t = tracker(); t.renderStarted(); now = 50
        t.renderExited(DiagnosticCancellationLatency.RenderOutcome.SUCCESS)
        now = 100; t.request(DiagnosticCancellationLatency.Origin.USER_BUTTON)
        now = 150; t.attemptScopeExited(true); now = 180; t.harnessFinished(true)
        assertEquals("OUTSIDE_GLOW_MIST", t.observation().status)
        assertNull(t.observation().requestToRenderExitNanos)
        assertEquals(50L, t.observation().requestToAttemptScopeExitNanos)
    }
    @Test fun anotherThreadCanRequestStopInsideAnObservedSyntheticAction() {
        val active = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val t = DiagnosticCancellationLatency()
        val trace = object : OriginalStageTrace {
            override fun started(stage: OriginalStage) = t.stageStarted(stage)
            override fun finished(stage: OriginalStage, elapsedNanos: Long, completed: Boolean) = t.stageFinished(stage)
        }
        try {
            val future = executor.submit {
                t.renderStarted()
                try {
                    originalStage(trace, OriginalStage.MIST) {
                        active.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                        throw CancellationException()
                    }
                } catch (_: CancellationException) {
                    t.renderExited(DiagnosticCancellationLatency.RenderOutcome.CANCELLED)
                }
                t.attemptScopeExited(true); t.harnessFinished(true)
            }
            assertTrue(active.await(5, TimeUnit.SECONDS))
            t.request(DiagnosticCancellationLatency.Origin.USER_BUTTON)
            release.countDown(); future.get(5, TimeUnit.SECONDS)
            assertEquals("OBSERVED_TARGET_STAGE_CANCEL", t.observation().status)
            assertEquals("MIST", t.observation().stageAtRequest)
        } finally { release.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)) }
    }
}
