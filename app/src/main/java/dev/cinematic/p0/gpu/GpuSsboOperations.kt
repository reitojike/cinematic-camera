package dev.cinematic.p0.gpu

import java.nio.ByteBuffer

/** Shader storage buffer commands. The caller owns context checks and resource lifetime. */
internal interface GpuSsboOperations {
    fun generateBuffers(count: Int, names: IntArray)
    fun bindBuffer(name: Int)
    fun bindBufferBase(binding: Int, name: Int)
    fun allocateDynamicRead(bytes: Int, data: ByteBuffer?)
    fun updateBuffer(offset: Int, bytes: Int, data: ByteBuffer)
    fun mapRead(offset: Int, bytes: Int): ByteBuffer?
    fun unmapBuffer(): Boolean
    fun deleteBuffers(count: Int, names: IntArray)
}
