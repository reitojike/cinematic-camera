package dev.cinematic.p0.jpegrender.original

import dev.cinematic.p0.raw.LinearSrgbImage
import dev.cinematic.p0.raw.PhotoAdjustments
import dev.cinematic.p0.raw.R7Base
import dev.cinematic.p0.raw.R7PreviewInputs
import dev.cinematic.p0.raw.R7StageTiming
import dev.cinematic.p0.raw.RawPreviewTiming
import dev.cinematic.p0.raw.PreviewPixelExecution
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

class Original24PreviewFoundationTest {
    @Test fun optionalTimingKeepsFoundationOutputAndMeterInSerialAndParallel() {
        val input = LinearSrgbImage(24, 16, FloatArray(24 * 16 * 3) { (it * 37 % 240) / 100f })
        val edits = PhotoAdjustments(65.0, -.7)
        val expected = original24PreviewFoundation(input, edits) { false }
        for (workers in listOf(1, 2)) {
            val lines = mutableListOf<String>()
            var tick = 0L
            val timing = R7StageTiming(RawPreviewTiming.Kind.EXACT, 1, workers, { tick++ }, lines::add)
            val execution = if (workers == 2) PreviewPixelExecution(2) else null
            val actual = try { original24PreviewFoundation(input, edits, { false }, execution, timing) }
                finally { execution?.close() }
            timing.finish(R7StageTiming.Outcome.SUCCESS)
            assertEquals(expected.externalEv.toRawBits(), actual.externalEv.toRawBits())
            assertArrayEquals(expected.image.copyPixels().map { it.toRawBits() }.toIntArray(),
                actual.image.copyPixels().map { it.toRawBits() }.toIntArray())
            for (stage in R7StageTiming.Stage.entries) assertTrue(lines.single().contains("${stage}Ns="))
        }
    }

    @Test fun warmthUsesUnalteredMeterAndUserEvIsAddedWithoutRemetering() {
        val input = LinearSrgbImage(16, 12, FloatArray(16 * 12 * 3) { channel ->
            val pixel = channel / 3
            val ramp = .04f + (pixel % 16) * .006f + (pixel / 16) * .003f
            when (channel % 3) { 0 -> ramp * 3.2f; 1 -> ramp * .7f; else -> ramp * .3f }
        })
        val adjustments = PhotoAdjustments(100.0, .7)
        fun meter(image: LinearSrgbImage) = R7PreviewInputs.prepare(R7PreviewInputs.AsShotInput(image,
            R7PreviewInputs.Source.SYNTHETIC_AS_SHOT), emptyList())
        val unchangedMeter = meter(input)
        val warmed = adjustments.applyWarmth(input)
        val expected = R7Base.render(warmed, unchangedMeter.foundationSkinRoi,
            unchangedMeter.exposure.externalBaseEv + adjustments.exposureEv).copyPixels()
        val observed = original24PreviewFoundation(input, adjustments) { false }
        assertEquals(unchangedMeter.exposure.externalBaseEv + adjustments.exposureEv, observed.externalEv, 0.0)
        val actual = observed.image.copyPixels()
        assertArrayEquals(expected, actual, 0f)

        // This strongly coloured input distinguishes the old order: metering after warmth changes EV
        // and partially compensates the user's colour change. The accepted order must differ from it.
        val warmedMeter = meter(warmed)
        assertTrue(abs(unchangedMeter.exposure.externalBaseEv - warmedMeter.exposure.externalBaseEv) > .05)
        val compensated = R7Base.render(warmed, warmedMeter.foundationSkinRoi,
            warmedMeter.exposure.externalBaseEv + adjustments.exposureEv).copyPixels()
        assertTrue(actual.indices.maxOf { abs(actual[it] - compensated[it]) } > .001f)
    }
}
