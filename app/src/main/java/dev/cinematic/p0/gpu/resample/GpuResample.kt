package dev.cinematic.p0.gpu.resample

import android.opengl.GLES32
import dev.cinematic.p0.gpu.ComputePass
import dev.cinematic.p0.gpu.EglContexts
import dev.cinematic.p0.gpu.FragmentPass
import dev.cinematic.p0.gpu.GpuMemoryLedger
import dev.cinematic.p0.gpu.GpuTexture
import dev.cinematic.p0.gpu.GpuTexturePool
import dev.cinematic.p0.gpu.TextureInput
import dev.cinematic.p0.raw.StageClearBlur
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 写真ごとに所有し、写真を閉じる際に close する。すべて同じ GL thread/context 上で呼ぶ。
 * 入力は借用。寸法不変なら入力そのもの、それ以外は呼出側が close する新しい出力を返す。
 * 中間 texture と重み cache はこのクラスが所有し、RGB を平面へ分離しない。
 */
internal class GpuResample(
    private val egl: EglContexts,
    private val ledger: GpuMemoryLedger,
    private val pool: GpuTexturePool? = null,
) : AutoCloseable {
    private data class Key(val input: Int, val output: Int, val filter: StageClearBlur.Filter)
    internal class Weights(val values: GpuTexture, val first: GpuTexture) : AutoCloseable {
        override fun close() { try { values.close() } finally { first.close() } }
    }
    private val cache = mutableMapOf<Key, Weights>()
    private var closed = false
    internal val coefficientTableCount get() = cache.size

    fun resize(input: GpuTexture, w: Int, h: Int, filter: StageClearBlur.Filter): GpuTexture =
        resizeImpl(input, w, h, filter, releaseInputAfterFirstAxis = false)

    /** Only blur's owned reduced field may be released before the second axis. */
    private fun resizeImpl(input: GpuTexture, w: Int, h: Int, filter: StageClearBlur.Filter,
        releaseInputAfterFirstAxis: Boolean): GpuTexture {
        requireInput(input)
        require(w > 0 && h > 0 && w.toLong() * h <= Int.MAX_VALUE)
        if (input.width == w && input.height == h) return input
        val heightCut = input.height.toLong() - h
        val widthCut = input.width.toLong() - w
        val verticalFirst = heightCut > 0 && heightCut > 2 * widthCut
        val intermediate = if (verticalFirst) axis(input, h, false, filter) else axis(input, w, true, filter)
        try {
            // The first pass has consumed the owned field. Keep only its Float32 axis result.
            // A no-op first axis still aliases input and must remain alive for the second pass.
            if (releaseInputAfterFirstAxis && intermediate !== input) input.close()
            val output = if (verticalFirst) axis(intermediate, w, true, filter) else axis(intermediate, h, false, filter)
            if (intermediate !== input && intermediate !== output) intermediate.close()
            return output
        } catch (failure: Throwable) {
            if (intermediate !== input) intermediate.close()
            throw failure
        }
    }

    fun blurPlane(input: GpuTexture, radiusFraction: Double): GpuTexture {
        require(input.format == GpuTexture.Format.R32F)
        return blur(input, radiusFraction)
    }
    fun blurRgb(input: GpuTexture, radiusFraction: Double): GpuTexture {
        require(input.format == GpuTexture.Format.RGBA32F)
        return blur(input, radiusFraction)
    }
    fun reduce(input: GpuTexture, w: Int, h: Int): GpuTexture = resize(input, w, h, StageClearBlur.Filter.BOX)
    fun upsampleFrom(field: GpuTexture, w: Int, h: Int, filter: StageClearBlur.Filter): GpuTexture =
        resize(field, w, h, filter)

    private fun blur(input: GpuTexture, radiusFraction: Double): GpuTexture {
        requireInput(input)
        val (w, h) = StageClearBlur.reducedSize(input.width, input.height, radiusFraction)
        val reduced = reduce(input, w, h)
        try { return resizeImpl(reduced, input.width, input.height, StageClearBlur.Filter.BILINEAR,
            releaseInputAfterFirstAxis = reduced !== input) }
        finally { if (reduced !== input) reduced.close() }
    }

    private fun requireInput(input: GpuTexture) {
        egl.requireCurrent(); check(!closed) { "GPU resampler is closed" }
        input.requireContext(egl)
        require(input.memoryLedger === ledger) { "Texture belongs to another photo ledger" }
        require(input.format == GpuTexture.Format.R32F || input.format == GpuTexture.Format.RGBA32F)
    }

    internal fun weights(input: Int, output: Int, filter: StageClearBlur.Filter): Weights =
        cache.getOrPut(Key(input, output, filter)) {
            val samples = StageClearBlur.samples(input, output, filter)
            val taps = samples.maxOf { it.weights.size }
            val values = GpuTexture.create(egl, ledger, taps, output, GpuTexture.Format.R32F, pool)
            var first: GpuTexture? = null
            try {
                first = GpuTexture.create(egl, ledger, 1, output, GpuTexture.Format.R32I, pool)
                val data = ByteBuffer.allocateDirect(Math.multiplyExact(Math.multiplyExact(taps, output), 4))
                    .order(ByteOrder.nativeOrder()).asFloatBuffer()
                val starts = ByteBuffer.allocateDirect(Math.multiplyExact(output, 4)).order(ByteOrder.nativeOrder()).asIntBuffer()
                samples.forEach { sample ->
                    starts.put(sample.first)
                    repeat(taps) { data.put(if (it < sample.weights.size) sample.weights[it].toFloat() else 0f) }
                }
                data.flip(); starts.flip()
                values.upload(data); first.upload(starts)
                Weights(values, first)
            } catch (failure: Throwable) {
                first?.close(); values.close()
                throw failure
            }
        }

    private fun axis(input: GpuTexture, size: Int, horizontal: Boolean, filter: StageClearBlur.Filter): GpuTexture {
        val oldSize = if (horizontal) input.width else input.height
        if (oldSize == size) return input
        val weights = weights(oldSize, size, filter)
        val output = input.createSibling(
            if (horizontal) size else input.width, if (horizontal) input.height else size, input.format)
        try {
            val inputs = listOf(TextureInput("s_input", input, 0), TextureInput("s_weights", weights.values, 1),
                TextureInput("s_first", weights.first, 2))
            val uniforms: (Int) -> Unit = { program ->
                GLES32.glUniform1i(GLES32.glGetUniformLocation(program, "u_horizontal"), if (horizontal) 1 else 0)
            }
            if (filter == StageClearBlur.Filter.BOX) {
                val path = if (input.format == GpuTexture.Format.R32F) "analysis/reduce_plane.comp" else "analysis/reduce_rgb.comp"
                egl.passes().run(ComputePass(egl.shaders().compute(path), inputs,
                    groupsX = if (horizontal) output.height else output.width,
                    uniforms = { program ->
                        uniforms(program)
                        GLES32.glUniform2i(GLES32.glGetUniformLocation(program, "u_outputSize"), output.width, output.height)
                    }, bindResources = {
                        GLES32.glBindImageTexture(0, output.textureId, 0, false, 0, GLES32.GL_WRITE_ONLY, output.format.internalFormat)
                    }))
            } else {
                egl.passes().run(FragmentPass(egl.shaders().fragment("common/upsample_axis.frag"), inputs, output, uniforms = uniforms))
            }
            return output
        } catch (failure: Throwable) { output.close(); throw failure }
    }

    override fun close() {
        egl.glThread.requireCurrent()
        if (closed) return
        cache.values.forEach { it.close() }; cache.clear(); closed = true
    }
}
