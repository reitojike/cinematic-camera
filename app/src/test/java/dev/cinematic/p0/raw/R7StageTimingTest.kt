package dev.cinematic.p0.raw

import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class R7StageTimingTest {
    @Test fun exclusiveIntervalsSumToTotalAndRepeatedStagesAccumulate() {
        var now = 100L
        val lines = mutableListOf<String>()
        val timing = timer({ now }, lines::add)
        now = 110; timing.enter(R7StageTiming.Stage.LOCAL_BOX)
        now = 130; timing.enter(R7StageTiming.Stage.LOCAL_QUANTILES)
        now = 160; timing.enter(R7StageTiming.Stage.LOCAL_BOX)
        now = 180; timing.finish(R7StageTiming.Outcome.SUCCESS)
        timing.finish(R7StageTiming.Outcome.FAILED)
        timing.enter(R7StageTiming.Stage.SKIN)
        assertEquals(listOf("kind=EXACT sequence=7 workers=1 outcome=SUCCESS r7Visited=false totalNs=80 " +
            "SETUPNs=10 LOCAL_BOXNs=40 LOCAL_QUANTILESNs=30"), lines)
        assertEquals(80L, durations(lines.single()).values.sum())
        assertEquals(R7StageTiming.Category.STATISTICS, R7StageTiming.Stage.LOCAL_QUANTILES.category)
        assertEquals(R7StageTiming.Category.NEIGHBOURHOOD, R7StageTiming.Stage.LOCAL_BICUBIC.category)
        assertEquals(R7StageTiming.Category.PER_PIXEL, R7StageTiming.Stage.LOCAL_APPLY.category)
    }

    @Test fun noVisitedStageIsNotReportedAsZeroDurationWork() {
        val lines = mutableListOf<String>()
        val timing = timer({ 0 }, lines::add)
        timing.finish(R7StageTiming.Outcome.SUCCESS)
        assertEquals(setOf("SETUP"), durations(lines.single()).keys)
        assertTrue(lines.single().contains("r7Visited=false"))
    }

    @Test fun setupClockAndSinkFailuresDoNotEscapeOrChangeRendering() {
        assertNull(R7StageTiming.optional { throw OutOfMemoryError("synthetic setup") })
        val input = image()
        val roi = FloatPlane(input.width, input.height, FloatArray(input.pixelCount))
        val expected = R7Base.render(input, roi, .5).copyPixels()
        for (throwAt in listOf(1, 2, 5, 12, 13)) {
            var calls = 0
            var lines = 0
            val timing = R7StageTiming.optional { timer({
                if (++calls == throwAt) throw OutOfMemoryError("synthetic clock")
                calls.toLong()
            }) { lines++ } }
            val actual = R7Base.render(input, roi, .5, null, timing) { false }
            timing?.finish(R7StageTiming.Outcome.SUCCESS)
            assertBits(expected, actual.copyPixels())
            assertEquals(0, lines)
        }
        timer({ 0 }) { throw OutOfMemoryError("synthetic sink") }.finish(R7StageTiming.Outcome.SUCCESS)
    }

    @Test fun measuredPreparationAndAllR7StagesKeepEveryFloatBitAndCheckpoints() {
        val input = image()
        val before = input.copyPixels()
        val regions = listOf(emptyList(), listOf(R7PreviewInputs.FoundationEllipse(.5f, .4f, .3f, .4f)))
        for (ellipses in regions) for (warmth in listOf(-100.0, 0.0, 100.0)) for (ev in listOf(-2.0, 0.0, 2.0)) {
            val adjustments = PhotoAdjustments(warmth, ev)
            var plainCalls = 0; var timedCalls = 0
            val plainStop = { plainCalls++; false }
            val timedStop = { timedCalls++; false }
            val expectedPrepared = R7PreviewInputs.prepare(asShot(input), ellipses, plainStop)
            val expected = R7Base.render(adjustments.applyWarmth(input, plainStop), expectedPrepared.foundationSkinRoi,
                expectedPrepared.exposure.externalBaseEv + ev, plainStop)
            var tick = 0L
            val lines = mutableListOf<String>()
            val timing = timer({ tick++ }, lines::add)
            val actualPrepared = R7PreviewInputs.prepare(asShot(input), ellipses, timing, timedStop)
            timing.enter(R7StageTiming.Stage.WARMTH)
            val actual = R7Base.render(adjustments.applyWarmth(input, timedStop), actualPrepared.foundationSkinRoi,
                actualPrepared.exposure.externalBaseEv + ev, null, timing, timedStop)
            timing.finish(R7StageTiming.Outcome.SUCCESS)
            assertEquals(expectedPrepared.exposure, actualPrepared.exposure)
            assertBits(expectedPrepared.foundationSkinRoi.copyValues(), actualPrepared.foundationSkinRoi.copyValues())
            assertBits(expected.copyPixels(), actual.copyPixels())
            assertEquals(plainCalls, timedCalls)
            assertEquals(R7StageTiming.Stage.entries.map { it.name }.toSet(), durations(lines.single()).keys)
            assertEquals(tick - 1, durations(lines.single()).values.sum())
            assertTrue(lines.single().contains("r7Visited=true"))
        }
        assertBits(before, input.copyPixels())
    }

    @Test fun cancellationAndValidationFailureKeepExistingOutcomeAndCheckpointOrder() {
        val input = image()
        val roi = FloatPlane(input.width, input.height, FloatArray(input.pixelCount))
        for (stopAt in 1..7) {
            var plainCalls = 0; var timedCalls = 0
            val lines = mutableListOf<String>()
            val timing = timer(System::nanoTime, lines::add)
            val plain = runCatching { R7Base.render(input, roi, .5) { ++plainCalls == stopAt } }
            val timed = runCatching { R7Base.render(input, roi, .5, null, timing) { ++timedCalls == stopAt } }
            timing.finish(if (timed.isSuccess) R7StageTiming.Outcome.SUCCESS else R7StageTiming.Outcome.FAILED)
            assertTrue(plain.exceptionOrNull() is CancellationException)
            assertEquals(plain.exceptionOrNull()?.javaClass, timed.exceptionOrNull()?.javaClass)
            assertEquals(plainCalls, timedCalls)
            assertTrue(lines.single().contains("outcome=FAILED"))
        }
        val regions = emptyList<R7PreviewInputs.FoundationEllipse>()
        var checkpoints = 0
        R7PreviewInputs.prepare(asShot(input), regions) { checkpoints++; false }
        for (stopAt in 1..checkpoints) {
            var plainCalls = 0; var timedCalls = 0
            val plain = runCatching { R7PreviewInputs.prepare(asShot(input), regions) { ++plainCalls == stopAt } }
            val timed = runCatching { R7PreviewInputs.prepare(asShot(input), regions, timer({ 0 }, {})) { ++timedCalls == stopAt } }
            assertEquals(plain.exceptionOrNull()?.javaClass, timed.exceptionOrNull()?.javaClass)
            assertEquals(plainCalls, timedCalls)
        }
        for (ev in listOf(Double.NaN, Double.POSITIVE_INFINITY, 2000.0)) {
            val plain = runCatching { R7Base.render(input, roi, ev) }
            val timed = runCatching { R7Base.render(input, roi, ev, null, timer({ 0 }, {})) { false } }
            assertEquals(plain.exceptionOrNull()?.javaClass, timed.exceptionOrNull()?.javaClass)
            assertTrue(timed.isFailure)
        }
    }

    private fun timer(clock: () -> Long, sink: (String) -> Unit) =
        R7StageTiming(RawPreviewTiming.Kind.EXACT, 7, 1, clock, sink)
    private fun asShot(input: LinearSrgbImage) =
        R7PreviewInputs.AsShotInput(input, R7PreviewInputs.Source.SYNTHETIC_AS_SHOT)
    private fun image(): LinearSrgbImage = LinearSrgbImage(32, 24, FloatArray(32 * 24 * 3) { i ->
        when (i / 3 % 5) {
            0 -> -.02f
            1 -> 0f
            2 -> 1e-6f
            3 -> floatArrayOf(.65f, .35f, .22f)[i % 3]
            else -> (i * 37 % 400) / 100f
        }
    })
    private fun assertBits(expected: FloatArray, actual: FloatArray) =
        assertArrayEquals(expected.map { it.toRawBits() }.toIntArray(), actual.map { it.toRawBits() }.toIntArray())
    private fun durations(line: String): Map<String, Long> = line.split(' ').mapNotNull { field ->
        val parts = field.split('=')
        if (parts.size == 2 && parts[0].endsWith("Ns") && parts[0] != "totalNs")
            parts[0].removeSuffix("Ns") to parts[1].toLong() else null
    }.toMap()
}
