package dev.cinematic.p0.gpu.look

import android.opengl.GLES32
import dev.cinematic.p0.gpu.FragmentPass
import dev.cinematic.p0.gpu.GpuTexture
import dev.cinematic.p0.gpu.TextureInput
import dev.cinematic.p0.raw.StageClearGlowV1

/** #281 の Glow パス。全工程の配線は共通リサンプルの受入後に追加する。 */
internal object StageClearGlowGpu {
    /**
     * source は借用、戻り値の合算済み RGBA32F は呼び出し側が所有する。
     * blurKernel は指定された半径の独立した RGBA32F を返し、この関数が各加算直後に解放する。
     * ぼかし済み3枚を保持せず、Kotlin と同じ全カーネル・宣言順・Float 重みで加算する。
     */
    fun accumulateBloom(source: GpuTexture, blurKernel: (GpuTexture, Double) -> GpuTexture): GpuTexture {
        source.requireOpen()
        require(source.format == GpuTexture.Format.RGBA32F)
        val egl = source.context
        val program = egl.shaders().fragment("look/glow_bloom_accumulate.frag")
        val graph = egl.passes()
        val weightLocation = GLES32.glGetUniformLocation(program.id, "u_kernelWeight")
        check(weightLocation >= 0) { "Bloom kernel weight uniform is missing" }
        val buffers = mutableListOf<GpuTexture>()
        var retained: GpuTexture? = null
        try {
            repeat(2) {
                buffers += GpuTexture.create(egl, source.memoryLedger, source.width, source.height,
                    GpuTexture.Format.RGBA32F)
            }
            var previous = buffers[0]
            var destination = buffers[1]
            GLES32.glBindFramebuffer(GLES32.GL_FRAMEBUFFER, previous.framebufferId)
            GLES32.glDisable(GLES32.GL_SCISSOR_TEST)
            GLES32.glColorMask(true, true, true, true)
            GLES32.glClearBufferfv(GLES32.GL_COLOR, 0, floatArrayOf(0f, 0f, 0f, 0f), 0)
            egl.checkGl("clear bloom accumulator")

            // Never hard-code a kernel count or reorder by radius/weight. The generated Kotlin
            // Float goes directly to glUniform1f, without a Double round trip or GLSL literal.
            for ((radius, weight) in StageClearGlowV1.BLOOM_KERNELS) {
                val blurred = blurKernel(source, radius)
                require(blurred !== source && blurred !in buffers) { "Bloom blur must return an owned texture" }
                blurred.use {
                    require(it.width == source.width && it.height == source.height &&
                        it.format == GpuTexture.Format.RGBA32F) { "Bloom blur dimensions/format differ" }
                    graph.run(FragmentPass(program,
                        listOf(TextureInput("s_previous", previous, 0), TextureInput("s_blurred", it, 1)),
                        destination, uniforms = { GLES32.glUniform1f(weightLocation, weight) }))
                }
                val old = previous
                previous = destination
                destination = old
            }
            retained = previous
            return previous
        } finally {
            buffers.filter { it !== retained }.forEach { it.close() }
        }
    }
}
