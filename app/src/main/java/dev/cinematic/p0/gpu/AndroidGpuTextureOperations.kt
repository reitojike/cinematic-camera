package dev.cinematic.p0.gpu

import android.opengl.GLES32
import java.nio.Buffer

internal class AndroidGpuTextureOperations : GpuTextureOperations {
    override fun maxTextureSize(): Int =
        IntArray(1).also { GLES32.glGetIntegerv(GLES32.GL_MAX_TEXTURE_SIZE, it, 0) }[0]

    override fun generateTexture(): Int {
        val names = IntArray(1)
        GLES32.glGenTextures(1, names, 0)
        return names[0]
    }

    override fun allocateTexture(texture: Int, width: Int, height: Int, format: GpuTexture.Format) {
        GLES32.glBindTexture(GLES32.GL_TEXTURE_2D, texture)
        GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_MIN_FILTER, GLES32.GL_NEAREST)
        GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_MAG_FILTER, GLES32.GL_NEAREST)
        GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_WRAP_S, GLES32.GL_CLAMP_TO_EDGE)
        GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_WRAP_T, GLES32.GL_CLAMP_TO_EDGE)
        GLES32.glTexStorage2D(GLES32.GL_TEXTURE_2D, 1, format.internalFormat, width, height)
    }

    override fun prepareTextureUpload() {
        GLES32.glBindBuffer(GLES32.GL_PIXEL_UNPACK_BUFFER, 0)
        GLES32.glPixelStorei(GLES32.GL_UNPACK_ALIGNMENT, 1)
        GLES32.glPixelStorei(GLES32.GL_UNPACK_ROW_LENGTH, 0)
        GLES32.glPixelStorei(GLES32.GL_UNPACK_SKIP_PIXELS, 0)
        GLES32.glPixelStorei(GLES32.GL_UNPACK_SKIP_ROWS, 0)
    }

    override fun uploadTexture(texture: Int, width: Int, height: Int, format: GpuTexture.Format, data: Buffer) {
        GLES32.glBindTexture(GLES32.GL_TEXTURE_2D, texture)
        GLES32.glTexSubImage2D(GLES32.GL_TEXTURE_2D, 0, 0, 0, width, height, format.pixelFormat, format.pixelType, data)
    }

    override fun generateFramebuffer(): Int {
        val names = IntArray(1)
        GLES32.glGenFramebuffers(1, names, 0)
        return names[0]
    }

    override fun attachFramebufferTexture(framebuffer: Int, texture: Int) {
        GLES32.glBindFramebuffer(GLES32.GL_FRAMEBUFFER, framebuffer)
        GLES32.glFramebufferTexture2D(GLES32.GL_FRAMEBUFFER, GLES32.GL_COLOR_ATTACHMENT0, GLES32.GL_TEXTURE_2D, texture, 0)
    }

    override fun framebufferComplete(): Boolean =
        GLES32.glCheckFramebufferStatus(GLES32.GL_FRAMEBUFFER) == GLES32.GL_FRAMEBUFFER_COMPLETE

    override fun deleteFramebuffer(framebuffer: Int) {
        GLES32.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
    }

    override fun deleteTexture(texture: Int) {
        GLES32.glDeleteTextures(1, intArrayOf(texture), 0)
    }
}
