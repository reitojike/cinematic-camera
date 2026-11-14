package dev.cinematic.p0.gpu.analysis

import android.opengl.GLES32
import dev.cinematic.p0.gpu.EglContexts
import dev.cinematic.p0.gpu.ComputePass
import dev.cinematic.p0.gpu.GpuFence
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Photo/R7-owned scratch, never global or shared across contexts. Not texture-ledger storage.
 * At most 1MiB histogram + 8KiB tiles + 64B results on GL, and 1MiB zero data.
 * CPU reads only four selections (64B) per stage; storage is allocated lazily.
 */
internal class GpuQuantileWorkspace(private val egl: EglContexts) : AutoCloseable {
    private val names = IntArray(1)
    private val selectionNames = IntArray(2)
    private val selectionAllocated = BooleanArray(2)
    private var zero: ByteBuffer? = null
    private var closed = false
    internal var allocations = 0; private set
    internal val retainedGlBytes: Int get() = (if (names[0] == 0) 0 else MAX_BYTES) +
        (if (selectionNames[0] == 0) 0 else TILE_BYTES) + (if (selectionNames[1] == 0) 0 else RESULT_BYTES)

    fun requireContext(expected: EglContexts) {
        egl.requireCurrent(); check(!closed) { "Quantile workspace is closed" }
        check(egl === expected) { "Quantile workspace belongs to another context" }
    }
    fun clear(length: Int) {
        requireContext(egl)
        require(length in HistogramRanks.BINS..MAX_COUNTS)
        if (names[0] == 0) {
            val zeros = zero ?: ByteBuffer.allocateDirect(MAX_BYTES).order(ByteOrder.nativeOrder()).also { zero = it }
            GLES32.glGenBuffers(1, names, 0)
            check(names[0] != 0) { "Cannot allocate histogram buffer" }
            try {
                GLES32.glBindBuffer(GLES32.GL_SHADER_STORAGE_BUFFER, names[0])
                GLES32.glBufferData(GLES32.GL_SHADER_STORAGE_BUFFER, MAX_BYTES, null, GLES32.GL_DYNAMIC_READ)
                egl.checkGl("allocate histogram storage")
                zeros.clear()
                allocations++
            } catch (failure: Throwable) {
                runCatching { egl.releaseResource { GLES32.glDeleteBuffers(1, names, 0) } }.exceptionOrNull()?.let(failure::addSuppressed)
                names[0] = 0
                throw failure
            }
        }
        val bytes = length * Int.SIZE_BYTES
        val zeros = checkNotNull(zero).apply { clear(); limit(bytes) }
        GLES32.glBindBuffer(GLES32.GL_SHADER_STORAGE_BUFFER, names[0])
        GLES32.glBufferSubData(GLES32.GL_SHADER_STORAGE_BUFFER, 0, bytes, zeros)
        egl.checkGl("clear histogram")
    }
    fun bind() {
        requireContext(egl); check(names[0] != 0)
        GLES32.glBindBufferBase(GLES32.GL_SHADER_STORAGE_BUFFER, 0, names[0])
    }
    /** Select four ranks without transferring the full histogram to the CPU. */
    fun select(rows: IntArray, ranks: IntArray, expectedCounts: LongArray,
        histogramCount: Int, finite: Boolean = false): List<HistogramRanks.Selection> {
        requireContext(egl); check(names[0] != 0)
        require(histogramCount in 1..4 && rows.size == 4 && ranks.size == 4 && expectedCounts.size == 4)
        require(rows.all { it in 0 until histogramCount } && ranks.all { it >= 0 })
        require(expectedCounts.all { it in 1..Int.MAX_VALUE.toLong() })
        for (index in selectionNames.indices) {
            if (selectionAllocated[index]) continue
            if (selectionNames[index] == 0) {
                val name = IntArray(1)
                GLES32.glGenBuffers(1, name, 0)
                check(name[0] != 0) { "Cannot allocate quantile selection buffer" }
                // Retain ownership even if storage allocation fails; a retry allocates storage again.
                selectionNames[index] = name[0]
            }
            GLES32.glBindBuffer(GLES32.GL_SHADER_STORAGE_BUFFER, selectionNames[index])
            GLES32.glBufferData(GLES32.GL_SHADER_STORAGE_BUFFER,
                if (index == 0) TILE_BYTES else RESULT_BYTES, null, GLES32.GL_DYNAMIC_READ)
            egl.checkGl("allocate quantile selection storage")
            selectionAllocated[index] = true
        }
        val tiles = egl.shaders().compute("analysis/histogram_tiles.comp")
        egl.passes().run(ComputePass(tiles, emptyList(), 256, histogramCount,
            uniforms = { GLES32.glUniform1i(tiles.uniformLocation("u_finite"), if (finite) 1 else 0) },
            bindResources = ::bindSelection, memoryBarrier = GLES32.GL_SHADER_STORAGE_BARRIER_BIT))
        val selection = egl.shaders().compute("analysis/histogram_select.comp")
        egl.passes().run(ComputePass(selection, emptyList(), 1,
            uniforms = {
                GLES32.glUniform4uiv(selection.uniformLocation("u_ranks"), 1, ranks, 0)
                GLES32.glUniform4uiv(selection.uniformLocation("u_rows"), 1, rows, 0)
            }, bindResources = ::bindSelection,
            memoryBarrier = GLES32.GL_SHADER_STORAGE_BARRIER_BIT or GLES32.GL_BUFFER_UPDATE_BARRIER_BIT))
        GpuFence(egl).use { it.await() }
        GLES32.glBindBuffer(GLES32.GL_SHADER_STORAGE_BUFFER, selectionNames[1])
        val mapped = GLES32.glMapBufferRange(GLES32.GL_SHADER_STORAGE_BUFFER, 0, RESULT_BYTES,
            GLES32.GL_MAP_READ_BIT) as? ByteBuffer ?: error("Cannot map quantile selections")
        val values = IntArray(16)
        try { mapped.order(ByteOrder.nativeOrder()).asIntBuffer().get(values) }
        finally { check(GLES32.glUnmapBuffer(GLES32.GL_SHADER_STORAGE_BUFFER)) { "Quantile selections corrupted" } }
        egl.checkGl("read quantile selections")
        return List(4) { i ->
            val total = values[i * 4 + 3].toLong() and 0xffffffffL
            if (finite) require(total != 0xffffffffL) { "Quantiles require finite Float32 values" }
            check(total == expectedCounts[i]) { "Histogram sample count differs" }
            val bin = values[i * 4]
            val before = values[i * 4 + 1].toLong() and 0xffffffffL
            val count = values[i * 4 + 2].toLong() and 0xffffffffL
            check(bin in 0 until HistogramRanks.BINS && before <= ranks[i] && ranks[i] < before + count &&
                before + count <= total) { "Histogram rank is absent" }
            HistogramRanks.Selection(bin, before, count)
        }
    }
    private fun bindSelection() {
        bind()
        GLES32.glBindBufferBase(GLES32.GL_SHADER_STORAGE_BUFFER, 1, selectionNames[0])
        GLES32.glBindBufferBase(GLES32.GL_SHADER_STORAGE_BUFFER, 2, selectionNames[1])
    }
    override fun close() {
        egl.glThread.requireCurrent()
        if (closed) return
        // Keep ownership if a live context rejects cleanup; destroyed/lost contexts return normally.
        egl.releaseResource {
            if (names[0] != 0) GLES32.glDeleteBuffers(1, names, 0)
            GLES32.glDeleteBuffers(2, selectionNames, 0)
        }
        names[0] = 0; selectionNames.fill(0); selectionAllocated.fill(false); zero = null; closed = true
    }
    companion object {
        private const val TILE_BYTES = 4 * 256 * 2 * Int.SIZE_BYTES
        private const val RESULT_BYTES = 4 * 4 * Int.SIZE_BYTES
        private const val MAX_COUNTS = HistogramRanks.BINS * 4
        private const val MAX_BYTES = MAX_COUNTS * Int.SIZE_BYTES
    }
}
