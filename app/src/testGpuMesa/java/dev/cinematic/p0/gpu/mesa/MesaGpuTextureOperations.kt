package dev.cinematic.p0.gpu.mesa

import dev.cinematic.p0.gpu.GpuTexture
import dev.cinematic.p0.gpu.GpuTextureOperations
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.ShortBuffer
import org.lwjgl.opengles.GLES32.*

internal class MesaGpuTextureOperations : GpuTextureOperations {
    override fun maxTextureSize(): Int =
        glGetInteger(GL_MAX_TEXTURE_SIZE)

    override fun generateTexture(): Int = glGenTextures()

    override fun allocateTexture(texture: Int, width: Int, height: Int, format: GpuTexture.Format) {
        glBindTexture(GL_TEXTURE_2D, texture)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        glTexStorage2D(GL_TEXTURE_2D, 1, format.internalFormat, width, height)
    }

    override fun prepareTextureUpload() {
        glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0)
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1)
        glPixelStorei(GL_UNPACK_ROW_LENGTH, 0)
        glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0)
        glPixelStorei(GL_UNPACK_SKIP_ROWS, 0)
    }

    override fun uploadTexture(texture: Int, width: Int, height: Int, format: GpuTexture.Format, data: Buffer) {
        glBindTexture(GL_TEXTURE_2D, texture)
        val pixelFormat = format.pixelFormat
        val pixelType = format.pixelType
        when (data) {
            is ByteBuffer ->
                glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, pixelFormat, pixelType, data)
            is ShortBuffer ->
                glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, pixelFormat, pixelType, data)
            is IntBuffer ->
                glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, pixelFormat, pixelType, data)
            is FloatBuffer ->
                glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, pixelFormat, pixelType, data)
            else -> throw IllegalArgumentException("Unsupported texture upload buffer: ${data.javaClass.name}")
        }
    }

    override fun generateFramebuffer(): Int = glGenFramebuffers()

    override fun attachFramebufferTexture(framebuffer: Int, texture: Int) {
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer)
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0)
    }

    override fun framebufferComplete(): Boolean =
        glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE

    override fun deleteFramebuffer(framebuffer: Int) {
        glDeleteFramebuffers(framebuffer)
    }

    override fun deleteTexture(texture: Int) {
        glDeleteTextures(texture)
    }
}
