package dev.cinematic.p0.gpu.mesa

import dev.cinematic.p0.gpu.GpuPixelTransferOperations
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import org.lwjgl.opengles.GLES32.*

internal object MesaGpuPixelTransferOperations : GpuPixelTransferOperations {
    override fun prepareReadback(framebuffer: Int) {
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer)
        glReadBuffer(GL_COLOR_ATTACHMENT0)
        glBindBuffer(GL_PIXEL_PACK_BUFFER, 0)
        glPixelStorei(GL_PACK_ALIGNMENT, 1)
        glPixelStorei(GL_PACK_ROW_LENGTH, 0)
        glPixelStorei(GL_PACK_SKIP_PIXELS, 0)
        glPixelStorei(GL_PACK_SKIP_ROWS, 0)
    }

    override fun readRgbaFloat(width: Int, height: Int, destination: FloatBuffer) {
        glReadPixels(0, 0, width, height, GL_RGBA, GL_FLOAT, destination)
    }

    override fun readRgba8(width: Int, height: Int, destination: ByteBuffer) {
        glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, destination)
    }
}
