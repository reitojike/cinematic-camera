package dev.cinematic.p0.gpu.diag

import android.opengl.GLES32
import dev.cinematic.p0.gpu.EglContexts
import dev.cinematic.p0.gpu.GpuFence
import dev.cinematic.p0.gpu.GpuTexture
import dev.cinematic.p0.raw.FloatPlane
import dev.cinematic.p0.raw.LinearSrgbImage
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 試験・診断専用。製品プレビューは読み戻しを呼ばない。 */
internal object GpuStageTap {
    /** 工程名と順序は #277 PR1 の契約として固定する。 */
    enum class Point {
        INPUT,
        WARMTH,
        R7_PROFILE,
        R7_TONE,
        R7_FOUNDATION,
        R7_FINAL,
        SKIN_WEIGHT,
        GLOW,
        MIST,
        ORTON,
        CHARACTER,
        SKIN_CAP,
        SHOULDER,
        DISPLAY8,
    }

    fun readRgb(egl: EglContexts, texture: GpuTexture, stop: () -> Boolean = { false }): LinearSrgbImage {
        require(texture.format == GpuTexture.Format.RGBA32F)
        val rgba = readRgba(egl, texture, stop)
        val rgb = FloatArray(texture.width * texture.height * 3)
        for (i in 0 until texture.width * texture.height) for (c in 0..2) rgb[i * 3 + c] = rgba.get(i * 4 + c)
        return LinearSrgbImage.takeOwnership(texture.width, texture.height, rgb)
    }
    fun readPlane(egl: EglContexts, texture: GpuTexture, stop: () -> Boolean = { false }): FloatPlane {
        require(texture.format == GpuTexture.Format.R32F)
        val rgba = readRgba(egl, texture, stop)
        return FloatPlane.takeOwnership(texture.width, texture.height, FloatArray(texture.width * texture.height) { rgba.get(it * 4) })
    }
    private fun readRgba(egl: EglContexts, texture: GpuTexture, stop: () -> Boolean): java.nio.FloatBuffer {
        texture.requireContext(egl)
        GpuFence(egl).use { it.await(stop) }
        val bytes = Math.multiplyExact(Math.multiplyExact(texture.width, texture.height), 16)
        val rgba = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder()).asFloatBuffer()
        GLES32.glBindFramebuffer(GLES32.GL_FRAMEBUFFER, texture.framebufferId)
        GLES32.glReadBuffer(GLES32.GL_COLOR_ATTACHMENT0)
        GLES32.glBindBuffer(GLES32.GL_PIXEL_PACK_BUFFER, 0)
        GLES32.glPixelStorei(GLES32.GL_PACK_ALIGNMENT, 1)
        GLES32.glPixelStorei(GLES32.GL_PACK_ROW_LENGTH, 0)
        GLES32.glPixelStorei(GLES32.GL_PACK_SKIP_PIXELS, 0)
        GLES32.glPixelStorei(GLES32.GL_PACK_SKIP_ROWS, 0)
        GLES32.glReadPixels(0, 0, texture.width, texture.height, GLES32.GL_RGBA, GLES32.GL_FLOAT, rgba)
        egl.checkGl("read stage tap")
        return rgba
    }
}
