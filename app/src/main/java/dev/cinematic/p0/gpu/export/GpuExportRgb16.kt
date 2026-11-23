package dev.cinematic.p0.gpu.export

import dev.cinematic.p0.gpu.EglContexts
import dev.cinematic.p0.gpu.FragmentPass
import dev.cinematic.p0.gpu.GpuTexture
import dev.cinematic.p0.gpu.TextureInput
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer

internal object GpuExportRgb16 {
    const val LUT_SIDE = 256

    fun lutBits(): IntBuffer {
        val bits = ByteBuffer.allocateDirect(LUT_SIDE * LUT_SIDE * Int.SIZE_BYTES)
            .order(ByteOrder.nativeOrder()).asIntBuffer()
        repeat(LUT_SIDE * LUT_SIDE) { bits.put((it / 65535f).toRawBits()) }
        bits.flip()
        return bits
    }

    fun decode(egl: EglContexts, rgb16: GpuTexture, lut: GpuTexture, output: GpuTexture) {
        egl.passes().run(FragmentPass(
            egl.shaders().fragment("export/rgb16_to_rgba32f.frag"),
            listOf(TextureInput("s_rgb16", rgb16, 0), TextureInput("s_lut", lut, 1)),
            output,
        ))
    }
}
