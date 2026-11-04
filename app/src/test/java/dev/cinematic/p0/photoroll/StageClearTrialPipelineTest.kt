package dev.cinematic.p0.photoroll

import dev.cinematic.p0.raw.LinearSrgbImage
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class StageClearTrialPipelineTest {
    private fun image(): LinearSrgbImage = LinearSrgbImage(8, 6, FloatArray(8 * 6 * 3) { i ->
        .03f + ((i * 17) % 113) / 160f
    })

    @Test fun realLookChangesAsShotInputWithoutChangingItsSamples() {
        val source = image()
        val before = source.copyPixels()
        val stages = mutableListOf<PhotoRollRenderStage>()
        val result = StageClearTrialPipeline.render(source, { false }, stages::add)
        assertEquals(listOf(PhotoRollRenderStage.INPUTS, PhotoRollRenderStage.BASE, PhotoRollRenderStage.LOOK), stages)
        assertEquals(source.width, result.width)
        assertEquals(source.height, result.height)
        assertArrayEquals(before, source.copyPixels(), 0f)
        assertTrue(result.copyPixels().all { it.isFinite() })
        assertTrue(before.indices.any { kotlin.math.abs(result[it] - before[it]) > .001f })
    }

    @Test fun cancellationAtEachStageNeverReturnsAnImageAndNextRequestWorks() {
        for (stopAt in listOf(PhotoRollRenderStage.INPUTS, PhotoRollRenderStage.BASE, PhotoRollRenderStage.LOOK)) {
            val source = image()
            val before = source.copyPixels()
            var stop = false
            val stages = mutableListOf<PhotoRollRenderStage>()
            try {
                StageClearTrialPipeline.render(source, { stop }) { stages += it; if (it == stopAt) stop = true }
                fail("Expected cancellation at $stopAt")
            } catch (_: CancellationException) { /* No image escapes. */ }
            assertEquals(stopAt, stages.last())
            assertArrayEquals(before, source.copyPixels(), 0f)
            assertEquals(source.width, StageClearTrialPipeline.render(source, { false }).width)
        }
    }

    @Test fun preCancelledAndOversizedRequestsDoNotStartAnyStage() {
        var calls = 0
        try { StageClearTrialPipeline.render(image(), { true }) { calls++ }; fail("Expected cancellation") }
        catch (_: CancellationException) { }
        try {
            StageClearTrialPipeline.render(LinearSrgbImage(513, 1, FloatArray(513 * 3)), { false }) { calls++ }
            fail("Expected size rejection")
        } catch (_: IllegalArgumentException) { }
        assertEquals(0, calls)
    }

    @Test fun fullTrialSizeCompletesWithFiniteDisplayInput() {
        // A bounded execution smoke test, not a Pixel latency or memory-peak measurement.
        val width = StageClearTrialPipeline.MAX_EDGE
        val source = LinearSrgbImage(width, 384, FloatArray(width * 384 * 3) { i ->
            .02f + ((i / 3) % width).toFloat() / width * .8f
        })
        val result = StageClearTrialPipeline.render(source, { false })
        assertEquals(width, result.width)
        assertEquals(384, result.height)
        assertTrue(result.copyPixels().all { it.isFinite() })
    }
}
