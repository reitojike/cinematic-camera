package dev.cinematic.p0.gpu.look

import android.opengl.GLES32
import dev.cinematic.p0.gpu.FragmentPass
import dev.cinematic.p0.gpu.GpuTexture
import dev.cinematic.p0.gpu.TextureInput
import dev.cinematic.p0.gpu.analysis.GpuAnalysis
import dev.cinematic.p0.gpu.resample.GpuResample
import dev.cinematic.p0.raw.StageClearBlur
import dev.cinematic.p0.raw.StageClearMistV1
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** #281: borrowed inputs; only a nonzero-strength result image is caller-owned. */
internal object StageClearMistGpu {
    /** Kotlin と同じ写真の判定値。strength=0 でも独立して求められる。 */
    fun autoStrength(glowed: GpuTexture): Double {
        glowed.requireOpen()
        require(glowed.format == GpuTexture.Format.RGBA32F)
        val fraction = GpuAnalysis.nearWhiteCount(glowed, glowed.width, glowed.height).toDouble() /
            (glowed.width.toLong() * glowed.height)
        val ratio = ((fraction - StageClearMistV1.AUTO_START) / StageClearMistV1.AUTO_SPAN).coerceIn(0.0, 1.0)
        return 1 - StageClearMistV1.AUTO_REDUCTION * ratio
    }

    fun render(glowed: GpuTexture, skinWeight: GpuTexture, strength: Double,
        resample: GpuResample, autoStrength: Double = autoStrength(glowed)): MistGpuResult {
        glowed.requireOpen(); skinWeight.requireContext(glowed.context)
        require(glowed.format == GpuTexture.Format.RGBA32F && skinWeight.format == GpuTexture.Format.R32F)
        require(skinWeight.width == glowed.width && skinWeight.height == glowed.height)
        require(skinWeight.memoryLedger === glowed.memoryLedger)
        require(strength.isFinite() && strength in 0.0..1.0)
        require(autoStrength.isFinite() && autoStrength in 0.0..1.0)
        val amount = autoStrength * strength
        if (strength == 0.0) return MistGpuResult(glowed, amount, skinWeight)
        val halos = mutableListOf<Halo>()
        try {
            draw(glowed, "look/mist_source.frag", listOf(TextureInput("s_glowed", glowed, 0))).use { source ->
                for ((radius, _) in StageClearMistV1.KERNELS) halos += horizontalHalo(source, radius, resample)
            }
            check(halos.size == 2) { "Mist shader must match every declared kernel" }
            val inputs = mutableListOf(TextureInput("s_glowed", glowed, 0), TextureInput("s_skinWeight", skinWeight, 1))
            halos.forEachIndexed { index, halo ->
                val unit = 2 + index * 3
                inputs += TextureInput("s_halo$index", halo.horizontal, unit)
                inputs += TextureInput("s_weights$index", halo.weights, unit + 1)
                inputs += TextureInput("s_first$index", halo.first, unit + 2)
            }
            val output = draw(glowed, "look/mist_compose.frag", inputs) { program ->
                val location = GLES32.glGetUniformLocation(program, "u_amount")
                check(location >= 0)
                GLES32.glUniform1f(location, amount.toFloat())
            }
            return MistGpuResult(output, amount, skinWeight)
        } finally { halos.asReversed().forEach { it.close() } }
    }

    private class Halo(val horizontal: GpuTexture, val weights: GpuTexture, val first: GpuTexture) : AutoCloseable {
        override fun close() {
            try { horizontal.close() } finally { try { weights.close() } finally { first.close() } }
        }
    }

    /** Keep the exact BOX result and horizontal Float boundary from GpuResample.
     * The unchanged common cs_resampleAxis evaluates the final vertical pass in
     * mist_compose, so two full-size halos never overlap the immutable Glow cache.
     */
    private fun horizontalHalo(source: GpuTexture, radius: Double, resample: GpuResample): Halo {
        val (w, h) = StageClearBlur.reducedSize(source.width, source.height, radius)
        val reduced = resample.reduce(source, w, h)
        var horizontal: GpuTexture? = null
        var weights: GpuTexture? = null
        var first: GpuTexture? = null
        try {
            val resized = resample.resize(reduced, source.width, h, StageClearBlur.Filter.BILINEAR)
            horizontal = if (resized !== source) resized else {
                // Tiny dimensions can alias. Own a copy before the source scope ends.
                GpuTexture.create(source.context, source.memoryLedger, source.width, source.height, source.format).also {
                    try { GLES32.glCopyImageSubData(source.textureId, GLES32.GL_TEXTURE_2D, 0, 0, 0, 0,
                        it.textureId, GLES32.GL_TEXTURE_2D, 0, 0, 0, 0, source.width, source.height, 1)
                    source.context.checkGl("copy aliased Mist halo")
                    } catch (failure: Throwable) { it.close(); throw failure }
                }
            }
            val samples = StageClearBlur.samples(h, source.height, StageClearBlur.Filter.BILINEAR)
            val taps = samples.maxOf { it.weights.size }
            weights = GpuTexture.create(source.context, source.memoryLedger, taps, source.height, GpuTexture.Format.R32F)
            first = GpuTexture.create(source.context, source.memoryLedger, 1, source.height, GpuTexture.Format.R32I)
            val values = ByteBuffer.allocateDirect(taps * source.height * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            val starts = ByteBuffer.allocateDirect(source.height * 4).order(ByteOrder.nativeOrder()).asIntBuffer()
            samples.forEach { sample ->
                starts.put(sample.first)
                repeat(taps) { values.put(if (it < sample.weights.size) sample.weights[it].toFloat() else 0f) }
            }
            values.flip(); starts.flip(); weights.upload(values); first.upload(starts)
            return Halo(horizontal, weights, first)
        } catch (failure: Throwable) {
            first?.close(); weights?.close(); horizontal?.close()
            throw failure
        } finally { if (reduced !== source && reduced !== horizontal) reduced.close() }
    }

    private fun draw(source: GpuTexture, path: String, inputs: List<TextureInput>,
        uniforms: (Int) -> Unit = {}): GpuTexture {
        val output = GpuTexture.create(source.context, source.memoryLedger,
            source.width, source.height, GpuTexture.Format.RGBA32F)
        try {
            source.context.passes().run(FragmentPass(source.context.shaders().fragment(path), inputs, output, uniforms = uniforms))
            return output
        } catch (failure: Throwable) { output.close(); throw failure }
    }
}
