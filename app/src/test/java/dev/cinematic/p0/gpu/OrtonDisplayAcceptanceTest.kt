package dev.cinematic.p0.gpu

import dev.cinematic.p0.gpu.diag.OrtonDisplayAcceptance
import org.junit.Assert.*
import org.junit.Test

class OrtonDisplayAcceptanceTest {
    private fun evaluate(n: Int = 10000, cpu: FloatArray = FloatArray(n * 3),
        gpu: FloatArray = FloatArray(n * 3), actual: IntArray = IntArray(n),
        expected: IntArray = IntArray(n)) =
        OrtonDisplayAcceptance.evaluate(n, 1, { cpu[it] }, { gpu[it] }, expected, actual)

    @Test fun cpuOnlyMaskAndInclusiveCountLimit() {
        val cpu = FloatArray(30000); cpu[1] = 1f
        val gpu = FloatArray(30000); gpu[3] = 1f
        val actual = IntArray(10000); actual[0] = 5 shl 16; actual[1] = 2
        val result = evaluate(cpu = cpu, gpu = gpu, actual = actual)
        assertTrue(result.passed)
        assertEquals(1, result.maskedPixels.size)
        assertEquals(0, result.maskedPixels.single().x)
        assertEquals(5, result.maxDiffAll)
        assertEquals(2, result.maxDiffOutsideMask)
        assertEquals(0.0, result.p999AllRgb, 0.0)
        assertEquals(listOf(5, 0, 0), result.maskedPixels.single().displayDiff)
        assertEquals(2, result.logLines().count())
    }
    @Test fun oneOverCapFailsEvenWithIdenticalDisplay() {
        val cpu = FloatArray(30000); cpu[0] = 1f; cpu[3] = 1f
        assertFalse(evaluate(cpu = cpu).passed)
        val small = FloatArray(9999 * 3); small[0] = 1f
        assertFalse(evaluate(n = 9999, cpu = small).maskWithinLimit)
    }
    @Test fun epsilonUsesActualFloatValueAndAnyCpuComponent() {
        val cpu = FloatArray(30000)
        cpu[2] = 1f + 0.000009f
        cpu[3] = 1f + 0.000011f
        cpu[6] = 1f - 0.000011f
        assertEquals(1, evaluate(cpu = cpu).maskedPixels.size)
        cpu[2] = 1f - 0.000009f
        assertEquals(1, evaluate(cpu = cpu).maskedPixels.size)
    }
    @Test fun percentileIncludesMaskedSamples() {
        // Deliberately exceeds the count cap to expose percentile inclusion independently.
        val cpu = FloatArray(3000)
        val actual = IntArray(1000)
        repeat(2) { p -> repeat(3) { cpu[p * 3 + it] = 1f }; actual[p] = 0x050505 }
        val result = evaluate(n = 1000, cpu = cpu, actual = actual)
        assertEquals(0, result.maxDiffOutsideMask)
        assertEquals(5.0, result.p999AllRgb, 0.0)
        assertFalse(result.passed)
    }
    @Test fun outsideMaskMaximumAndAllRgbPercentileRemainGates() {
        val actual = IntArray(10000); actual[0] = 3
        assertFalse(evaluate(actual = actual).passed)
        actual.fill(0x020202)
        val result = evaluate(actual = actual)
        assertEquals(2, result.maxDiffOutsideMask)
        assertEquals(2.0, result.p999AllRgb, 0.0)
        assertFalse(result.passed)
    }
    @Test fun alphaIgnoredAndZeroMaskStillLogged() {
        val result = evaluate(actual = IntArray(10000) { -16777216 })
        assertTrue(result.passed)
        assertEquals(1, result.logLines().count())
    }
    @Test(expected = IllegalArgumentException::class) fun nonFiniteInputRejected() {
        val gpu = FloatArray(30000); gpu[0] = Float.NaN
        evaluate(gpu = gpu)
    }
}
