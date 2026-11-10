package dev.cinematic.p0.gpu.analysis

import dev.cinematic.p0.gpu.GpuTexture
import android.opengl.GLES32
import dev.cinematic.p0.gpu.ComputePass
import dev.cinematic.p0.gpu.GpuFence
import dev.cinematic.p0.gpu.TextureInput
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 共有ファイル。各担当は自分の関数の本体だけを実装する（#276 §6.4）。 */
internal object GpuAnalysis {
    /** #280: 原寸の平滑 log 輝度から p50, p92 の順に2要素を返す。 */
    @Suppress("UNUSED_PARAMETER")
    fun localToneQuantiles(input: GpuTexture, width: Int, height: Int): DoubleArray {
        TODO("#280: localToneQuantiles")
    }

    /** #281: Glow 後の線形輝度が NEAR_WHITE を超える画素数を返す。 */
    @Suppress("UNUSED_PARAMETER")
    fun nearWhiteCount(input: GpuTexture, width: Int, height: Int): Long {
        require(width == input.width && height == input.height && width > 0 && height > 0)
        require(input.format == GpuTexture.Format.RGBA32F)
        val egl = input.context
        input.requireContext(egl)
        val buffer = IntArray(1)
        GLES32.glGenBuffers(1, buffer, 0)
        check(buffer[0] != 0) { "Near-white counter allocation failed" }
        try {
            GLES32.glBindBuffer(GLES32.GL_SHADER_STORAGE_BUFFER, buffer[0])
            val zero = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).putInt(0).apply { flip() }
            GLES32.glBufferData(GLES32.GL_SHADER_STORAGE_BUFFER, 4, zero, GLES32.GL_DYNAMIC_READ)
            egl.passes().run(ComputePass(egl.shaders().compute("analysis/count_near_white.comp"),
                listOf(TextureInput("s_glowed", input, 0)), (width + 15) / 16, (height + 15) / 16,
                uniforms = { program ->
                    val location = GLES32.glGetUniformLocation(program, "u_size")
                    check(location >= 0)
                    GLES32.glUniform2i(location, width, height)
                }, bindResources = { GLES32.glBindBufferBase(GLES32.GL_SHADER_STORAGE_BUFFER, 0, buffer[0]) },
                memoryBarrier = GLES32.GL_SHADER_STORAGE_BARRIER_BIT or GLES32.GL_BUFFER_UPDATE_BARRIER_BIT))
            GpuFence(egl).use { it.await() }
            GLES32.glBindBuffer(GLES32.GL_SHADER_STORAGE_BUFFER, buffer[0])
            val mapped = GLES32.glMapBufferRange(GLES32.GL_SHADER_STORAGE_BUFFER, 0, 4,
                GLES32.GL_MAP_READ_BIT) as? ByteBuffer ?: error("Near-white counter read failed")
            val count = try { mapped.order(ByteOrder.nativeOrder()).getInt(0).toLong() and 0xffffffffL }
            finally { check(GLES32.glUnmapBuffer(GLES32.GL_SHADER_STORAGE_BUFFER)) { "Near-white counter became invalid" } }
            egl.checkGl("read near-white scalar")
            check(count <= width.toLong() * height) { "Invalid near-white count" }
            return count
        } finally {
            egl.releaseResource {
                GLES32.glBindBufferBase(GLES32.GL_SHADER_STORAGE_BUFFER, 0, 0)
                GLES32.glBindBuffer(GLES32.GL_SHADER_STORAGE_BUFFER, 0)
                GLES32.glDeleteBuffers(1, buffer, 0)
            }
        }
    }
}
