package dev.cinematic.p0.gpu

import dev.cinematic.p0.gpu.analysis.GpuAnalysis
import dev.cinematic.p0.gpu.diag.GpuStageTap
import dev.cinematic.p0.raw.PhotoAdjustments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class GpuContractTest {
    @Test fun stageTapOrderMatchesContract() {
        assertEquals(
            listOf("INPUT", "WARMTH", "R7_PROFILE", "R7_TONE", "R7_FOUNDATION", "R7_FINAL",
                "SKIN_WEIGHT", "GLOW", "MIST", "ORTON", "CHARACTER", "SKIN_CAP", "SHOULDER", "DISPLAY8"),
            GpuStageTap.Point.entries.map { it.name },
        )
    }

    @Test fun renderParamsKeepExistingAdjustmentType() {
        val adjustments = PhotoAdjustments(warmth = -100.0, exposureEv = 2.0)
        val params = GpuRenderParams(strength = 0.75, adjustments = adjustments)
        assertEquals(0.75, params.strength, 0.0)
        assertSame(adjustments, params.adjustments)
        assertEquals(GpuDisplayOptions(true, 270), GpuDisplayOptions(true, 270).copy())
    }

    @Test fun frameStatsAllowUnavailableGpuTimer() {
        val decisions = GpuDecisions(mistAutoStrength = 0.5, faceCutFactor = 1.0)
        val stats = GpuFrameStats(gpuNanos = null, cpuSubmitNanos = 42L, decisions = decisions)
        assertEquals(null, stats.gpuNanos)
        assertEquals(42L, stats.cpuSubmitNanos)
        assertSame(decisions, stats.decisions)
        assertEquals(GpuPhotoAnalysis(0.25, 0.75f), GpuPhotoAnalysis(0.25, 0.75f).copy())
    }

    @Test fun analysisSlotsKeepTextureSizeAndReturnTypes() {
        val types = arrayOf(GpuTexture::class.java, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)
        assertEquals(DoubleArray::class.java,
            GpuAnalysis::class.java.getDeclaredMethod("localToneQuantiles", *types).returnType)
        assertEquals(Long::class.javaPrimitiveType,
            GpuAnalysis::class.java.getDeclaredMethod("nearWhiteCount", *types).returnType)
    }
}
