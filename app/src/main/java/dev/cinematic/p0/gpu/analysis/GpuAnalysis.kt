package dev.cinematic.p0.gpu.analysis

import android.opengl.GLES32
import dev.cinematic.p0.gpu.ComputePass
import dev.cinematic.p0.gpu.GpuFence
import dev.cinematic.p0.gpu.GpuTexture
import dev.cinematic.p0.gpu.TextureInput
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.floor

/** 共有ファイル。各担当は自分の関数の本体だけを実装する（#276 §6.4）。 */
internal object GpuAnalysis {
    /** #280: 原寸の平滑 log 輝度から p50, p92 の順に2要素を返す。 */
    fun localToneQuantiles(input: GpuTexture, width: Int, height: Int): DoubleArray =
        GpuQuantileWorkspace(input.context).use { localToneQuantiles(input, width, height, it) }

    fun localToneQuantiles(input: GpuTexture, width: Int, height: Int,
        workspace: GpuQuantileWorkspace): DoubleArray {
        val egl = input.context
        input.requireContext(egl); workspace.requireContext(egl)
        require(input.format == GpuTexture.Format.R32F) { "Quantiles require R32F" }
        require(width == input.width && height == input.height && width.toLong() * height <= Int.MAX_VALUE)
        val count = width * height
        val bins = HistogramRanks.BINS
        fun histogram(targets: IntArray?) {
            val length = bins * (targets?.size ?: 1)
            workspace.clear(length)
            val path = if (targets == null) "analysis/histogram_high.comp" else "analysis/histogram_low.comp"
            val program = egl.shaders().compute(path)
            egl.passes().run(ComputePass(program, listOf(TextureInput("s_input", input, 0)),
                groupsX = (width + 7) / 8, groupsY = (height + 7) / 8,
                uniforms = {
                    GLES32.glUniform2i(program.uniformLocation("u_size"), width, height)
                    if (targets != null) {
                        GLES32.glUniform1i(program.uniformLocation("u_targetCount"), targets.size)
                        GLES32.glUniform4uiv(program.uniformLocation("u_highBins"), 1, targets.copyOf(4), 0)
                    }
                }, bindResources = workspace::bind,
                memoryBarrier = GLES32.GL_SHADER_STORAGE_BARRIER_BIT or GLES32.GL_BUFFER_UPDATE_BARRIER_BIT))
        }
        val ranks = doubleArrayOf((count - 1) * .5, (count - 1) * .92)
        val orders = ranks.flatMap { listOf(floor(it).toInt(), ceil(it).toInt()) }.toIntArray()
        histogram(null)
        val selected = workspace.select(IntArray(4), orders, LongArray(4) { count.toLong() }, 1, finite = true)
        val targets = selected.map { it.bin }.distinct().toIntArray()
        histogram(targets)
        val rows = IntArray(4) { targets.indexOf(selected[it].bin) }
        val lowRanks = IntArray(4) { orders[it] - selected[it].before.toInt() }
        val lower = workspace.select(rows, lowRanks, LongArray(4) { selected[it].count }, targets.size)
        val keys = IntArray(4) { (selected[it].bin shl 16) or lower[it].bin }
        val values = keys.map { key ->
            val bits = if (key < 0) key xor Int.MIN_VALUE else key.inv()
            Float.fromBits(bits).toDouble()
        }
        return DoubleArray(2) { i ->
            val lower = values[i * 2]; val upper = values[i * 2 + 1]
            lower + (upper - lower) * (ranks[i] - floor(ranks[i]))
        }
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
                    val location = egl.shaders().uniformLocation(program, "u_size")
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
