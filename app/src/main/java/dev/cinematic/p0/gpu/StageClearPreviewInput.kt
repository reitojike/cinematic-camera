package dev.cinematic.p0.gpu

import dev.cinematic.p0.raw.LinearSrgbImage
import dev.cinematic.p0.raw.R7PreviewInputs
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** CPU-side AS_SHOT preparation; the caller owns cancellation timing and the GL upload. */
internal object StageClearPreviewInput {
    fun analyze(input: LinearSrgbImage, stop: () -> Boolean): GpuPhotoAnalysis {
        val prepared = R7PreviewInputs.prepare(R7PreviewInputs.AsShotInput(input,
            R7PreviewInputs.Source.LIBRAW_PRELIMINARY_AS_SHOT), emptyList(), stop)
        return GpuPhotoAnalysis(prepared.exposure.externalBaseEv, prepared.exposure.centralP97)
    }

    fun packRgba(input: LinearSrgbImage, checkpoint: () -> Unit): FloatBuffer {
        val rgba = ByteBuffer.allocateDirect(Math.multiplyExact(input.pixelCount, 16))
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (y in 0 until input.height) {
            checkpoint()
            for (x in 0 until input.width) {
                val i = (y * input.width + x) * 3
                rgba.put(input[i]); rgba.put(input[i + 1]); rgba.put(input[i + 2]); rgba.put(1f)
            }
        }
        rgba.flip()
        return rgba
    }
}
