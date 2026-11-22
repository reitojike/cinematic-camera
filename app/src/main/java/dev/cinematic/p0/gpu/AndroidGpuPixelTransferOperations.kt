package dev.cinematic.p0.gpu

import android.opengl.GLES32
import java.nio.ByteBuffer
import java.nio.FloatBuffer

internal object AndroidGpuPixelTransferOperations : GpuPixelTransferOperations {
    override fun prepareReadback(framebuffer: Int) {
        GLES32.glBindFramebuffer(GLES32.GL_FRAMEBUFFER, framebuffer)
        GLES32.glReadBuffer(GLES32.GL_COLOR_ATTACHMENT0)
        GLES32.glBindBuffer(GLES32.GL_PIXEL_PACK_BUFFER, 0)
        GLES32.glPixelStorei(GLES32.GL_PACK_ALIGNMENT, 1)
        GLES32.glPixelStorei(GLES32.GL_PACK_ROW_LENGTH, 0)
        GLES32.glPixelStorei(GLES32.GL_PACK_SKIP_PIXELS, 0)
        GLES32.glPixelStorei(GLES32.GL_PACK_SKIP_ROWS, 0)
    }

    override fun readRgbaFloat(width: Int, height: Int, destination: FloatBuffer) {
        GLES32.glReadPixels(0, 0, width, height, GLES32.GL_RGBA, GLES32.GL_FLOAT, destination)
    }

    override fun readRgba8(width: Int, height: Int, destination: ByteBuffer) {
        GLES32.glReadPixels(0, 0, width, height, GLES32.GL_RGBA, GLES32.GL_UNSIGNED_BYTE, destination)
    }
}
