package dev.cinematic.p0.raw

import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class RawPreviewTimingTest {
    @Test fun exclusiveDurationsAccumulateRepeatedPassesAndFinishOnce() {
        var now = 100L
        val lines = mutableListOf<String>()
        val timing = RawPreviewTiming(RawPreviewTiming.Kind.DRAFT, 7, { now }, lines::add)
        now = 110; timing.enter(RawPreviewTiming.Stage.MIST)
        now = 130; timing.enter(RawPreviewTiming.Stage.ORTON)
        now = 160; timing.enter(RawPreviewTiming.Stage.MIST)
        now = 180; timing.finish(RawPreviewTiming.Outcome.SUCCESS)
        timing.finish(RawPreviewTiming.Outcome.FAILED)
        assertEquals(listOf("kind=DRAFT sequence=7 outcome=SUCCESS totalNs=80 ADMISSIONNs=10 MISTNs=40 ORTONNs=30"), lines)
    }

    @Test fun diagnosticSinkFailureDoesNotEscape() {
        val timing = RawPreviewTiming(RawPreviewTiming.Kind.EXACT, 1, { 0L }) { error("sink unavailable") }
        timing.finish(RawPreviewTiming.Outcome.FAILED)
    }

    @Test fun diagnosticOutOfMemoryCannotEscapeOrPreventCallerCleanup() {
        assertNull(RawPreviewTiming.optional { throw OutOfMemoryError("synthetic allocation") })
        var calls = 0
        val timing = RawPreviewTiming(RawPreviewTiming.Kind.EXACT, 1, {
            if (++calls > 1) throw OutOfMemoryError("synthetic clock")
            0L
        }) { error("must not log after failure") }
        timing.enter(RawPreviewTiming.Stage.CLEANUP)
        timing.finish(RawPreviewTiming.Outcome.FAILED)
        val sinkFailure = RawPreviewTiming(RawPreviewTiming.Kind.EXACT, 2, { 0L }) {
            throw OutOfMemoryError("synthetic log")
        }
        sinkFailure.finish(RawPreviewTiming.Outcome.SUCCESS)
    }

    @Test fun instrumentationPreservesEveryFloatBitAndDecisionAcrossPasses() {
        val width = 32; val height = 24
        val pixels = FloatArray(width * height * 3) { i ->
            when {
                i / 3 % width < 5 -> if (i % 3 == 0) 2f else 1.2f
                i / 3 % width < 16 -> floatArrayOf(.65f, .35f, .22f)[i % 3]
                else -> (i * 37 % 200) / 200f
            }
        }
        val base = LinearSrgbImage(width, height, pixels)
        val prepared = PreparedStageClearGlow.prepare(base) {}
        val faces = FaceMask(width, height, FloatArray(width * height))
        val decisions = listOf(null, StageClearDecisions(.7, 1.0), StageClearDecisions(.7, .8, .6))
        for (strength in listOf(0.0, .5, 1.0)) for (override in decisions) {
            var callsBefore = 0; var callsAfter = 0
            val expected = prepared.render(faces, strength, override) { callsBefore++ }
            val lines = mutableListOf<String>()
            var tick = 0L
            val timing = RawPreviewTiming(RawPreviewTiming.Kind.EXACT, 1, { tick++ }, lines::add)
            val actual = prepared.render(faces, strength, override, timing) { callsAfter++ }
            timing.finish(RawPreviewTiming.Outcome.SUCCESS)
            assertArrayEquals(expected.image.copyPixels().map { it.toRawBits() }.toIntArray(),
                actual.image.copyPixels().map { it.toRawBits() }.toIntArray())
            assertEquals(expected.decisions, actual.decisions)
            assertEquals(expected.faceCutFactor, actual.faceCutFactor, 0.0)
            assertEquals(callsBefore, callsAfter)
            for (stage in listOf("GLOW", "MIST_AUTO", "MIST", "ORTON", "CHARACTER", "PROTECTIONS"))
                assertTrue(lines.single().contains("${stage}Ns="))
        }
        assertArrayEquals(pixels, base.copyPixels(), 0f)
    }

    @Test fun cancellationCheckpointOrderIsUnchanged() {
        val base = LinearSrgbImage(16, 12, FloatArray(16 * 12 * 3) { .5f })
        val prepared = PreparedStageClearGlow.prepare(base) {}
        val faces = FaceMask(16, 12, FloatArray(16 * 12))
        for (cancelAt in listOf(1, 5, 20, 30)) {
            var plainCalls = 0; var timedCalls = 0
            val plain = runCatching { prepared.render(faces, .5, null) {
                if (++plainCalls == cancelAt) throw CancellationException("synthetic")
            } }
            val timed = runCatching { prepared.render(faces, .5, null,
                RawPreviewTiming(RawPreviewTiming.Kind.EXACT, 1, sink = {})) {
                if (++timedCalls == cancelAt) throw CancellationException("synthetic")
            } }
            assertEquals(plain.exceptionOrNull()?.javaClass, timed.exceptionOrNull()?.javaClass)
            assertEquals(plainCalls, timedCalls)
        }
    }
}
