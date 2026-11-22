package dev.cinematic.p0.gpu

import java.nio.ByteBuffer
import java.nio.FloatBuffer

/** Readback commands for tightly packed pixels in caller-owned buffers. */
internal interface GpuPixelTransferOperations {
    fun prepareReadback(framebuffer: Int)
    fun readRgbaFloat(width: Int, height: Int, destination: FloatBuffer)
    fun readRgba8(width: Int, height: Int, destination: ByteBuffer)
}
