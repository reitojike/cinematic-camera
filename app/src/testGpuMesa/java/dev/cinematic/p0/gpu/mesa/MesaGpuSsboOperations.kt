package dev.cinematic.p0.gpu.mesa

import dev.cinematic.p0.gpu.GpuSsboOperations
import java.lang.ref.Reference.reachabilityFence
import java.nio.ByteBuffer
import org.lwjgl.opengles.GLES32.*
import org.lwjgl.system.MemoryStack.stackPush
import org.lwjgl.system.MemoryUtil.memAddress
import org.lwjgl.system.MemoryUtil.memAddressSafe

internal object MesaGpuSsboOperations : GpuSsboOperations {
    override fun generateBuffers(count: Int, names: IntArray) {
        require(count in 0..names.size)
        stackPush().use { stack ->
            val generated = stack.mallocInt(count)
            glGenBuffers(generated)
            generated.get(names, 0, count)
        }
    }

    override fun bindBuffer(name: Int) {
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, name)
    }

    override fun bindBufferBase(binding: Int, name: Int) {
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, binding, name)
    }

    override fun allocateDynamicRead(bytes: Int, data: ByteBuffer?) {
        require(data == null || data.isDirect && bytes <= data.remaining())
        // The typed LWJGL overload derives the size from remaining(), while this contract supplies it.
        nglBufferData(GL_SHADER_STORAGE_BUFFER, bytes.toLong(), memAddressSafe(data), GL_DYNAMIC_READ)
        reachabilityFence(data)
    }

    override fun updateBuffer(offset: Int, bytes: Int, data: ByteBuffer) {
        require(data.isDirect && bytes <= data.remaining())
        nglBufferSubData(GL_SHADER_STORAGE_BUFFER, offset.toLong(), bytes.toLong(), memAddress(data))
        reachabilityFence(data)
    }

    override fun mapRead(offset: Int, bytes: Int): ByteBuffer? =
        glMapBufferRange(GL_SHADER_STORAGE_BUFFER, offset.toLong(), bytes.toLong(), GL_MAP_READ_BIT)

    override fun unmapBuffer(): Boolean = glUnmapBuffer(GL_SHADER_STORAGE_BUFFER)

    override fun deleteBuffers(count: Int, names: IntArray) {
        require(count in 0..names.size)
        stackPush().use { stack ->
            val selected = stack.mallocInt(count)
            selected.put(names, 0, count).flip()
            glDeleteBuffers(selected)
        }
    }
}
