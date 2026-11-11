package dev.cinematic.p0.gpu.export

import android.opengl.GLES32
import dev.cinematic.p0.gpu.ComputePass
import dev.cinematic.p0.gpu.EglContexts
import dev.cinematic.p0.gpu.GpuFence
import dev.cinematic.p0.gpu.GpuTexture
import dev.cinematic.p0.gpu.TextureInput
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException
import kotlin.math.ceil
import kotlin.math.floor

/** #286: 原寸の平滑 log 輝度を保持せず、帯の2走査から正確な p50/p92 を取得する。 */
internal object GpuExportAnalysis {
    /**
     * [forEachBand] は同じ画素集合を2回、同じ GL thread 上で同期的に列挙する。
     * 各帯は幅 [width] の有限 R32F。所有権は呼び出し元にあり、コールバック後に解放できる。
     * 全帯を通した整数 histogram のため、帯の分割は分位値に影響しない。
     */
    fun quantiles(
        egl: EglContexts,
        width: Int,
        height: Int,
        stop: () -> Boolean,
        forEachBand: ((GpuTexture) -> Unit) -> Unit,
    ): DoubleArray {
        egl.requireCurrent()
        require(width > 0 && height > 0 && width.toLong() * height <= Int.MAX_VALUE)
        fun checkStopped() {
            if (stop()) throw CancellationException("GPU export analysis cancelled")
        }
        checkStopped()
        val count = width * height
        val bins = 65536
        val names = IntArray(1)
        GLES32.glGenBuffers(1, names, 0)
        check(names[0] != 0) { "Cannot allocate export histogram buffer" }
        try {
            fun histogram(targets: IntArray?): IntArray {
                checkStopped()
                val length = bins * (targets?.size ?: 1)
                val bytes = length * Int.SIZE_BYTES
                // 各走査の開始時だけ初期化し、全帯が同じ SSBO に累積する。
                // glBufferData(null) は未初期化になるため、ゼロ初期化された direct buffer を渡す。
                val zero = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
                GLES32.glBindBuffer(GLES32.GL_SHADER_STORAGE_BUFFER, names[0])
                GLES32.glBufferData(GLES32.GL_SHADER_STORAGE_BUFFER, bytes, zero, GLES32.GL_DYNAMIC_READ)
                egl.checkGl("allocate zeroed export histogram")
                val path = if (targets == null) "analysis/histogram_high.comp" else "analysis/histogram_low.comp"
                val program = egl.shaders().compute(path)
                var rows = 0
                forEachBand { input ->
                    checkStopped()
                    input.requireContext(egl)
                    require(input.format == GpuTexture.Format.R32F) { "Quantiles require R32F" }
                    require(input.width == width && input.height in 1..(height - rows)) {
                        "Export histogram band dimensions differ"
                    }
                    egl.passes().run(ComputePass(program, listOf(TextureInput("s_input", input, 0)),
                        groupsX = (width + 7) / 8, groupsY = (input.height + 7) / 8,
                        uniforms = { id ->
                            GLES32.glUniform2i(GLES32.glGetUniformLocation(id, "u_size"), width, input.height)
                            if (targets != null) {
                                GLES32.glUniform1i(GLES32.glGetUniformLocation(id, "u_targetCount"), targets.size)
                                GLES32.glUniform4uiv(GLES32.glGetUniformLocation(id, "u_highBins"), 1, targets.copyOf(4), 0)
                            }
                        },
                        // 帯の生成処理が別の SSBO を使っていても、各 dispatch で再設定する。
                        bindResources = { GLES32.glBindBufferBase(GLES32.GL_SHADER_STORAGE_BUFFER, 0, names[0]) },
                        memoryBarrier = GLES32.GL_SHADER_STORAGE_BARRIER_BIT or GLES32.GL_BUFFER_UPDATE_BARRIER_BIT))
                    rows += input.height
                    checkStopped()
                }
                checkStopped()
                check(rows == height) { "Export histogram bands are incomplete" }
                // 帯ごとに readback せず、全帯を累積した後に一度だけ待機する。
                GpuFence(egl).use { it.await(stop) }
                GLES32.glBindBuffer(GLES32.GL_SHADER_STORAGE_BUFFER, names[0])
                val mapped = GLES32.glMapBufferRange(GLES32.GL_SHADER_STORAGE_BUFFER, 0, bytes, GLES32.GL_MAP_READ_BIT)
                    as? ByteBuffer ?: error("Cannot map export histogram")
                val result = IntArray(length)
                try { mapped.order(ByteOrder.nativeOrder()).asIntBuffer().get(result) }
                finally { check(GLES32.glUnmapBuffer(GLES32.GL_SHADER_STORAGE_BUFFER)) { "Export histogram buffer corrupted" } }
                egl.checkGl("read export histogram")
                checkStopped()
                return result
            }
            // GpuAnalysis.localToneQuantiles と同じ順位選択・Float32 key 復元・線形補間。
            fun select(histogram: IntArray, start: Int, rank: Int): Pair<Int, Int> {
                var before = 0L
                for (bin in 0 until bins) {
                    val size = histogram[start + bin].toLong() and 0xffffffffL
                    if (rank < before + size) return bin to before.toInt()
                    before += size
                }
                error("Histogram rank is absent")
            }
            val high = histogram(null)
            require((0 until 128).all { high[it] == 0 } && (65408 until bins).all { high[it] == 0 }) {
                "Quantiles require finite Float32 values"
            }
            check(high.sumOf { it.toLong() and 0xffffffffL } == count.toLong()) { "Histogram sample count differs" }
            val ranks = doubleArrayOf((count - 1) * .5, (count - 1) * .92)
            val orders = ranks.flatMap { listOf(floor(it).toInt(), ceil(it).toInt()) }
            val selected = orders.map { select(high, 0, it) }
            val targets = selected.map { it.first }.distinct().toIntArray()
            val low = histogram(targets)
            for (i in targets.indices) {
                var samples = 0L
                for (bin in 0 until bins) samples += low[i * bins + bin].toLong() and 0xffffffffL
                check(samples == (high[targets[i]].toLong() and 0xffffffffL)) {
                    "Export histogram samples changed between scans"
                }
            }
            val values = orders.indices.map { index ->
                val (highBin, before) = selected[index]
                val lowBin = select(low, targets.indexOf(highBin) * bins, orders[index] - before).first
                val key = (highBin shl 16) or lowBin
                val bits = if (key < 0) key xor Int.MIN_VALUE else key.inv()
                Float.fromBits(bits).toDouble()
            }
            checkStopped()
            return DoubleArray(2) { i ->
                val lower = values[i * 2]; val upper = values[i * 2 + 1]
                lower + (upper - lower) * (ranks[i] - floor(ranks[i]))
            }
        } finally {
            egl.releaseResource {
                GLES32.glBindBufferBase(GLES32.GL_SHADER_STORAGE_BUFFER, 0, 0)
                GLES32.glBindBuffer(GLES32.GL_SHADER_STORAGE_BUFFER, 0)
                GLES32.glDeleteBuffers(1, names, 0)
            }
        }
    }
}
