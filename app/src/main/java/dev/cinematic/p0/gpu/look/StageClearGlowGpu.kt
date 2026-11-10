package dev.cinematic.p0.gpu.look

import android.opengl.GLES32
import dev.cinematic.p0.gpu.FragmentPass
import dev.cinematic.p0.gpu.GpuTexture
import dev.cinematic.p0.gpu.TextureInput
import dev.cinematic.p0.raw.StageClearGlowV1
import dev.cinematic.p0.gpu.resample.GpuResample

/** #281: immutable prepared fields, ordered Float bloom and strength-dependent Glow. */
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
            buffers += GpuTexture.create(egl, source.memoryLedger, source.width, source.height,
                GpuTexture.Format.RGBA32F)
            var previous = buffers[0]
            // The second accumulator is not needed while the first blur is being built.
            var destination: GpuTexture? = null
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
                    val target = destination ?: GpuTexture.create(egl, source.memoryLedger,
                        source.width, source.height, GpuTexture.Format.RGBA32F).also(buffers::add)
                    graph.run(FragmentPass(program,
                        listOf(TextureInput("s_previous", previous, 0), TextureInput("s_blurred", it, 1)),
                        target, uniforms = { GLES32.glUniform1f(weightLocation, weight) }))
                    val old = previous
                    previous = target
                    destination = old
                }
            }
            retained = previous
            return previous
        } finally {
            buffers.filter { it !== retained }.forEach { it.close() }
        }
    }

    /** Base and resampler are borrowed. Prepared fields remain owned until close. */
    fun prepare(base: GpuTexture, resample: GpuResample, onBlur: (String) -> Unit = {}): Prepared {
        base.requireOpen()
        require(base.format == GpuTexture.Format.RGBA32F)
        val owned = mutableListOf<GpuTexture>()
        try {
            val bloom: GpuTexture
            val halation: GpuTexture
            draw(base, "look/glow_source.frag", listOf("s_base" to base)).use { source ->
                bloom = accumulateBloom(source) { input, radius ->
                    onBlur("bloom"); resample.blurRgb(input, radius).let { if (it === input) copyOwned(it) else it }
                }.also(owned::add)
                draw(base, "look/glow_excess.frag", listOf("s_source" to source), GpuTexture.Format.R32F).use { excess ->
                    onBlur("halation")
                    halation = resample.blurPlane(excess, StageClearGlowV1.HALATION_RADIUS)
                        .let { if (it === excess) copyOwned(it) else it }.also(owned::add)
                }
            }
            val skin = draw(base, "look/skin_weight.frag", listOf("s_base" to base), GpuTexture.Format.R32F).use { raw ->
                onBlur("skin")
                val blurred = resample.blurPlane(raw, StageClearGlowV1.SKIN_RADIUS)
                try { draw(base, "look/glow_skin_clip.frag", listOf("s_blurredSkin" to blurred), GpuTexture.Format.R32F) }
                finally { if (blurred !== raw) blurred.close() }
            }.also(owned::add)
            return Prepared(base, resample, skin, bloom, halation, onBlur)
        } catch (failure: Throwable) {
            owned.asReversed().forEach { runCatching { it.close() }.exceptionOrNull()?.let(failure::addSuppressed) }
            throw failure
        }
    }

    internal class Prepared internal constructor(
        private val base: GpuTexture,
        private val resample: GpuResample,
        val skinWeight: GpuTexture,
        private val bloom: GpuTexture,
        private val halation: GpuTexture,
        private val onBlur: (String) -> Unit,
    ) : AutoCloseable {
        private var closed = false
        /** Zero returns the borrowed base. Every nonzero result is caller-owned. */
        fun render(strength: Double): GpuTexture {
            check(!closed); base.requireOpen()
            require(strength.isFinite() && strength in 0.0..1.0)
            if (strength == 0.0) return base
            val inputs = listOf("s_base" to base, "s_skinWeight" to skinWeight,
                "s_bloom" to bloom, "s_halation" to halation)
            val uniforms: (Int) -> Unit = { program ->
                uniform(program, "u_bloomAmount", (StageClearGlowV1.BLOOM * strength).toFloat())
                uniform(program, "u_halationAmount", (StageClearGlowV1.HALATION * strength).toFloat())
            }
            val glowed = draw(base, "look/glow_compose.frag", inputs, uniforms = uniforms)
            val amount = (StageClearGlowV1.DIFFUSION * strength).toFloat()
            if (amount == 0f) return glowed
            var soft: GpuTexture? = null
            try {
                onBlur("diffusion")
                soft = resample.blurRgb(glowed, StageClearGlowV1.DIFFUSION_RADIUS)
                // The final pass recomputes the same precise glowed expression. This avoids
                // retaining glowed + soft + output alongside all immutable prepared fields.
                if (soft !== glowed) glowed.close()
                return draw(base, "look/glow_diffusion.frag", inputs + ("s_soft" to soft), uniforms = { program ->
                    uniforms(program); uniform(program, "u_diffusionAmount", amount)
                })
            } finally {
                soft?.close()
                if (soft !== glowed) glowed.close()
            }
        }

        override fun close() {
            if (closed) return
            try { skinWeight.close() }
            finally { try { halation.close() } finally { bloom.close() } }
            closed = true
        }
    }

    private fun copyOwned(source: GpuTexture): GpuTexture {
        val output = GpuTexture.create(source.context, source.memoryLedger, source.width, source.height, source.format)
        try {
            GLES32.glCopyImageSubData(source.textureId, GLES32.GL_TEXTURE_2D, 0, 0, 0, 0,
                output.textureId, GLES32.GL_TEXTURE_2D, 0, 0, 0, 0, source.width, source.height, 1)
            source.context.checkGl("copy aliased Glow field")
            return output
        } catch (failure: Throwable) { output.close(); throw failure }
    }
    private fun uniform(program: Int, name: String, value: Float) {
        val location = GLES32.glGetUniformLocation(program, name)
        check(location >= 0) { "Missing Glow uniform $name" }
        GLES32.glUniform1f(location, value)
    }

    private fun draw(base: GpuTexture, shader: String, inputs: List<Pair<String, GpuTexture>>,
        format: GpuTexture.Format = GpuTexture.Format.RGBA32F, uniforms: (Int) -> Unit = {}): GpuTexture {
        val egl = base.context
        val output = GpuTexture.create(egl, base.memoryLedger, base.width, base.height, format)
        try {
            egl.passes().run(FragmentPass(egl.shaders().fragment(shader),
                inputs.mapIndexed { unit, (name, texture) -> TextureInput(name, texture, unit) },
                output, uniforms = uniforms))
            return output
        } catch (failure: Throwable) { output.close(); throw failure }
    }

}
