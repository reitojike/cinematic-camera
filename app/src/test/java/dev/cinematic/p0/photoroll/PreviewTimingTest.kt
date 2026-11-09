package dev.cinematic.p0.photoroll

import dev.cinematic.p0.raw.LinearSrgbImage
import org.junit.Assert.*
import org.junit.Test

class PreviewTimingTest {
    @Test fun restoringSavedValuesRemainsOpenEvenWhenEveryControlDiffers() {
        val defaults = dev.cinematic.p0.photoedit.PhotoEditValues(.5, 0.0, 0.0)
        val changes = listOf(defaults.copy(strength = .8), defaults.copy(temperature = 40.0),
            defaults.copy(brightness = .5), defaults.copy(strength = .7, temperature = 20.0, brightness = .2))
        val expected = listOf(PreviewOperation.STRENGTH, PreviewOperation.TEMPERATURE,
            PreviewOperation.BRIGHTNESS, PreviewOperation.MULTIPLE)
        changes.forEachIndexed { index, values ->
            assertEquals(PreviewOperation.OPEN, previewOperationForChange(defaults, values, restoring = true))
            assertEquals(expected[index], previewOperationForChange(defaults, values, restoring = false))
        }
    }
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

    @Test fun dispatchGateAndAdoptionAreSeparateExclusiveIntervals() {
        var now = 0L
        val lines = mutableListOf<String>()
        val timing = PreviewTiming(3, clock = { now }, sink = lines::add, initialStage = "IO_DISPATCH")
        for (stage in listOf("DRAFT_GATE_WAIT", "ADMISSION_WAIT", "CHECK_ORIGINAL", "LOAD_RECIPE",
            "BIND_DISPATCH", "BIND_STATE", "IO_RETURN", "BUILD_REQUEST", "RENDER", "VERIFY_RESULT",
            "UI_DISPATCH", "ADOPT_STATE")) { now += 10; timing.enter(stage) }
        now += 10; timing.finish("STATE_ADOPTED")
        assertTrue(lines.single().contains("totalNs=130"))
        for (stage in listOf("IO_DISPATCH", "DRAFT_GATE_WAIT", "CHECK_ORIGINAL", "UI_DISPATCH", "ADOPT_STATE"))
            assertTrue(lines.single().contains("${stage}Ns=10"))
    }

    @Test fun diagnosticFailureCannotEscapeOrReplaceCleanup() {
        assertNull(PreviewTiming.optional { throw OutOfMemoryError("allocation") })
        var calls = 0
        val failedClock = PreviewTiming(1, clock = {
            if (++calls > 1) throw OutOfMemoryError("clock") else 0L
        }, sink = { error("not reached") })
        failedClock.enter("DRAFT_GATE_WAIT")
        failedClock.finish("FAILED")
        val failedSink = PreviewTiming(2, clock = { 0L }, sink = { throw OutOfMemoryError("sink") })
        failedSink.finish("STATE_ADOPTED")
        failedSink.finish("NOT_ADOPTED")
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
