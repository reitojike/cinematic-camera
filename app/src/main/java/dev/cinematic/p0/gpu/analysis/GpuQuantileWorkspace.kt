package dev.cinematic.p0.gpu.analysis

import android.opengl.GLES32
import dev.cinematic.p0.gpu.EglContexts
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Photo/R7-owned scratch, never global or shared across contexts. Not texture-ledger storage.
 * At most 1MiB GL + 1MiB zero data + 1MiB CPU counts per live owner, allocated lazily.
 */
internal class GpuQuantileWorkspace(private val egl: EglContexts) : AutoCloseable {
    private val names = IntArray(1)
    private var zero: ByteBuffer? = null
    private var counts: IntArray? = null
    private var closed = false
    internal var allocations = 0; private set
    internal val retainedGlBytes: Int get() = if (names[0] == 0) 0 else MAX_BYTES

    fun requireContext(expected: EglContexts) {
        egl.requireCurrent(); check(!closed) { "Quantile workspace is closed" }
        check(egl === expected) { "Quantile workspace belongs to another context" }
    }
    fun clear(length: Int) {
        requireContext(egl)
        require(length in HistogramRanks.BINS..MAX_COUNTS)
        if (names[0] == 0) {
            val zeros = zero ?: ByteBuffer.allocateDirect(MAX_BYTES).order(ByteOrder.nativeOrder()).also { zero = it }
            if (counts == null) counts = IntArray(MAX_COUNTS)
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
    /** Borrowed until next read; only the requested prefix contains this histogram. */
    fun read(length: Int): IntArray {
        requireContext(egl); require(length in HistogramRanks.BINS..MAX_COUNTS); check(names[0] != 0)
        GLES32.glBindBuffer(GLES32.GL_SHADER_STORAGE_BUFFER, names[0])
        val mapped = GLES32.glMapBufferRange(GLES32.GL_SHADER_STORAGE_BUFFER, 0, length * Int.SIZE_BYTES,
            GLES32.GL_MAP_READ_BIT) as? ByteBuffer ?: error("Cannot map histogram")
        val result = checkNotNull(counts)
        try { mapped.order(ByteOrder.nativeOrder()).asIntBuffer().get(result, 0, length) }
        finally { check(GLES32.glUnmapBuffer(GLES32.GL_SHADER_STORAGE_BUFFER)) { "Histogram buffer corrupted" } }
        egl.checkGl("read histogram")
        return result
    }
    override fun close() {
        egl.glThread.requireCurrent()
        if (closed) return
        // Keep ownership if a live context rejects cleanup; destroyed/lost contexts return normally.
        egl.releaseResource {
            if (names[0] != 0) GLES32.glDeleteBuffers(1, names, 0)
        }
        names[0] = 0; zero = null; counts = null; closed = true
    }
    companion object {
        private const val MAX_COUNTS = HistogramRanks.BINS * 4
        private const val MAX_BYTES = MAX_COUNTS * Int.SIZE_BYTES
    }
}
