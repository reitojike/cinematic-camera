package dev.cinematic.p0.photoroll

import dev.cinematic.p0.raw.LinearSrgbImage
import org.junit.Assert.*
import org.junit.Test

class PreviewTimingTest {
    @Test fun sectionsSumToTotalAndFinishIsOnce() {
        var now = 10L
        val output = mutableListOf<String>()
        val timing = PreviewTiming(7, PreviewOperation.STRENGTH, { now }, output::add)
        now = 20; timing.enter("LOOK")
        now = 35; timing.enter("ADOPT_STATE")
        now = 40; timing.finish("STATE_ADOPTED")
        now = 50; timing.finish("CANCELLED"); timing.enter("LOOK")
        assertEquals(1, output.size)
        assertEquals("operation=STRENGTH revision=7 outcome=STATE_ADOPTED last=ADOPT_STATE totalNs=30 CHECK_ORIGINALNs=10 LOOKNs=15 ADOPT_STATENs=5", output.single())
    }

    @Test fun cancellationKeepsLastBoundaryAndAccountsRepeatedStages() {
        var now = 0L
        var result = ""
        val timing = PreviewTiming(2, clock = { now }, sink = { result = it })
        now = 1; timing.enter("LOOK")
        now = 3; timing.enter("LOOK")
        now = 8; timing.finish("CANCELLED")
        assertTrue(result.contains("outcome=CANCELLED last=LOOK totalNs=8"))
        assertTrue(result.contains("CHECK_ORIGINALNs=1 LOOKNs=7"))
    }

    @Test fun observingRealSyntheticPipelineDoesNotChangeAnyOutputBits() {
        val input = LinearSrgbImage(8, 6, FloatArray(144) { .03f + ((it * 17) % 113) / 160f })
        val source = input.copyPixels()
        val reference = StageClearTrialPipeline.render(input, { false }).copyPixels()
        var now = 0L
        var result = ""
        val timing = PreviewTiming(0, clock = { now++ }, sink = { result = it })
        val observed = StageClearTrialPipeline.render(input, { false }) { timing.enter(it.name) }.copyPixels()
        timing.finish("STATE_ADOPTED")
        assertArrayEquals(source, input.copyPixels(), 0f)
        assertArrayEquals(reference.map { it.toRawBits() }.toIntArray(), observed.map { it.toRawBits() }.toIntArray())
        assertTrue(result.contains("LOOKNs="))
    }
}
