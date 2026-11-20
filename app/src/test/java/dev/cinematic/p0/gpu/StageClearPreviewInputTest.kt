package dev.cinematic.p0.gpu

import dev.cinematic.p0.raw.LinearSrgbImage
import dev.cinematic.p0.raw.R7PreviewInputs
import java.nio.ByteOrder
import java.util.concurrent.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StageClearPreviewInputTest {
    @Test fun analysisMatchesAsShotMeteringAndHonorsCancellation() {
        val input = LinearSrgbImage(4, 4, FloatArray(48) { (it + 1) / 53f })
        val expected = R7PreviewInputs.prepare(R7PreviewInputs.AsShotInput(input,
            R7PreviewInputs.Source.LIBRAW_PRELIMINARY_AS_SHOT), emptyList()).exposure

        val actual = StageClearPreviewInput.analyze(input) { false }
        assertEquals(expected.externalBaseEv.toRawBits(), actual.meteredBaseEv.toRawBits())
        assertEquals(expected.centralP97.toRawBits(), actual.centralP97.toRawBits())
        assertThrows(CancellationException::class.java) {
            StageClearPreviewInput.analyze(input) { true }
        }
    }

    @Test fun packingPreservesRowOrderChannelBitsAndOpaqueAlpha() {
        val rgb = floatArrayOf(-0.0f, 0.4f, 2f, 0.3f, 0.8f, 0f,
            0.9f, 0.7f, 0.2f, 0.1f, 0.5f, 0.6f)
        val input = LinearSrgbImage(2, 2, rgb)
        var checkpoints = 0
        val packed = StageClearPreviewInput.packRgba(input) { checkpoints++ }
        assertTrue(packed.isDirect)
        assertEquals(ByteOrder.nativeOrder(), packed.order())
        assertEquals(2, checkpoints)
        assertEquals(0, packed.position())
        assertEquals(16, packed.remaining())
        val bits = IntArray(packed.remaining()) { packed.get().toRawBits() }
        val expected = IntArray(16) { channel ->
            val component = channel % 4
            if (component == 3) 1f.toRawBits() else rgb[(channel / 4) * 3 + component].toRawBits()
        }
        assertArrayEquals(expected, bits)
    }

    @Test fun packingStopsAtTheRequestedRowBoundary() {
        val input = LinearSrgbImage(2, 3, FloatArray(18))
        val cancelled = CancellationException("stop")
        var checkpoints = 0
        val observed = assertThrows(CancellationException::class.java) {
            StageClearPreviewInput.packRgba(input) {
                if (++checkpoints == 2) throw cancelled
            }
        }
        assertSame(cancelled, observed)
        assertEquals(2, checkpoints)
    }
}
