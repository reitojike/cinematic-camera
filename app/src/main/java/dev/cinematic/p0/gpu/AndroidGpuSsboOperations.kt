package dev.cinematic.p0.gpu

import android.opengl.GLES32
import java.nio.ByteBuffer

internal object AndroidGpuSsboOperations : GpuSsboOperations {
    override fun generateBuffers(count: Int, names: IntArray) {
        GLES32.glGenBuffers(count, names, 0)
    }

    override fun bindBuffer(name: Int) {
        GLES32.glBindBuffer(GLES32.GL_SHADER_STORAGE_BUFFER, name)
    }

    override fun bindBufferBase(binding: Int, name: Int) {
        GLES32.glBindBufferBase(GLES32.GL_SHADER_STORAGE_BUFFER, binding, name)
    }

    override fun allocateDynamicRead(bytes: Int, data: ByteBuffer?) {
        GLES32.glBufferData(GLES32.GL_SHADER_STORAGE_BUFFER, bytes, data, GLES32.GL_DYNAMIC_READ)
    }

    override fun updateBuffer(offset: Int, bytes: Int, data: ByteBuffer) {
        GLES32.glBufferSubData(GLES32.GL_SHADER_STORAGE_BUFFER, offset, bytes, data)
    }

    override fun mapRead(offset: Int, bytes: Int): ByteBuffer? =
        GLES32.glMapBufferRange(GLES32.GL_SHADER_STORAGE_BUFFER, offset, bytes, GLES32.GL_MAP_READ_BIT) as? ByteBuffer

    override fun unmapBuffer(): Boolean =
        GLES32.glUnmapBuffer(GLES32.GL_SHADER_STORAGE_BUFFER)

    override fun deleteBuffers(count: Int, names: IntArray) {
        GLES32.glDeleteBuffers(count, names, 0)
    }
}
