package dev.cinematic.p0.gpu

import android.opengl.GLES32
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.ShortBuffer

/** テクスチャ/FBOの唯一の所有者。create/使用/closeは同じGlThreadとcontext上。 */
internal class GpuTexture private constructor(
    private val egl: EglContexts, private val ledger: GpuMemoryLedger,
    val width: Int, val height: Int, val format: Format,
) : AutoCloseable {
    enum class Format(val internalFormat: Int, val pixelFormat: Int, val pixelType: Int, val bytesPerPixel: Int) {
        RGBA32F(GLES32.GL_RGBA32F, GLES32.GL_RGBA, GLES32.GL_FLOAT, 16),
        R32F(GLES32.GL_R32F, GLES32.GL_RED, GLES32.GL_FLOAT, 4),
        RGBA8(GLES32.GL_RGBA8, GLES32.GL_RGBA, GLES32.GL_UNSIGNED_BYTE, 4),
        RGB16UI(GLES32.GL_RGB16UI, GLES32.GL_RGB_INTEGER, GLES32.GL_UNSIGNED_SHORT, 6),
        R32I(GLES32.GL_R32I, GLES32.GL_RED_INTEGER, GLES32.GL_INT, 4),
        R32UI(GLES32.GL_R32UI, GLES32.GL_RED_INTEGER, GLES32.GL_UNSIGNED_INT, 4),
    }
    val byteSize: Long = width.toLong() * height * format.bytesPerPixel
    private var texture = 0
    private var framebuffer = 0
    private var closed = false
    private var reserved = false
    val textureId: Int get() { requireOpen(); return texture }
    val framebufferId: Int get() {
        requireOpen()
        if (framebuffer == 0) {
            val names = IntArray(1)
            GLES32.glGenFramebuffers(1, names, 0)
            val candidate = names[0]
            try {
                check(candidate != 0) { "glGenFramebuffers failed" }
                GLES32.glBindFramebuffer(GLES32.GL_FRAMEBUFFER, candidate)
                GLES32.glFramebufferTexture2D(GLES32.GL_FRAMEBUFFER, GLES32.GL_COLOR_ATTACHMENT0, GLES32.GL_TEXTURE_2D, texture, 0)
                check(GLES32.glCheckFramebufferStatus(GLES32.GL_FRAMEBUFFER) == GLES32.GL_FRAMEBUFFER_COMPLETE) { "$format cannot be attached to a complete FBO" }
                egl.checkGl("create framebuffer")
                framebuffer = candidate
            } catch (failure: Throwable) {
                GLES32.glDeleteFramebuffers(1, intArrayOf(candidate), 0)
                throw failure
            }
        }
        return framebuffer
    }

    init {
        egl.requireCurrent()
        require(width > 0 && height > 0)
        val maximum = IntArray(1).also { GLES32.glGetIntegerv(GLES32.GL_MAX_TEXTURE_SIZE, it, 0) }[0]
        require(width <= maximum && height <= maximum) { "Texture dimensions exceed GL_MAX_TEXTURE_SIZE" }
        ledger.reserve(byteSize); reserved = true
        try {
            val names = IntArray(1)
            GLES32.glGenTextures(1, names, 0); texture = names[0]
            check(texture != 0) { "glGenTextures failed" }
            GLES32.glBindTexture(GLES32.GL_TEXTURE_2D, texture)
            GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_MIN_FILTER, GLES32.GL_NEAREST)
            GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_MAG_FILTER, GLES32.GL_NEAREST)
            GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_WRAP_S, GLES32.GL_CLAMP_TO_EDGE)
            GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_WRAP_T, GLES32.GL_CLAMP_TO_EDGE)
            GLES32.glTexStorage2D(GLES32.GL_TEXTURE_2D, 1, format.internalFormat, width, height)
            egl.checkGl("allocate $format texture")
        } catch (failure: Throwable) {
            runCatching { close() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    fun requireOpen() { egl.requireCurrent(); check(!closed) { "GPU texture is closed" } }
    internal fun requireContext(expected: EglContexts) { check(egl === expected) { "Texture belongs to another context" }; requireOpen() }

    fun upload(data: Buffer) {
        requireOpen()
        require(data.isDirect) { "Texture upload requires a direct buffer" }
        val elementSize = when (data) { is ByteBuffer -> 1; is ShortBuffer -> 2; is IntBuffer, is FloatBuffer -> 4; else -> error("Unsupported texture buffer") }
        require(data.remaining().toLong() * elementSize >= byteSize) { "Texture upload buffer is too small" }
        GLES32.glBindBuffer(GLES32.GL_PIXEL_UNPACK_BUFFER, 0)
        GLES32.glPixelStorei(GLES32.GL_UNPACK_ALIGNMENT, 1)
        GLES32.glPixelStorei(GLES32.GL_UNPACK_ROW_LENGTH, 0)
        GLES32.glPixelStorei(GLES32.GL_UNPACK_SKIP_PIXELS, 0)
        GLES32.glPixelStorei(GLES32.GL_UNPACK_SKIP_ROWS, 0)
        GLES32.glBindTexture(GLES32.GL_TEXTURE_2D, texture)
        GLES32.glTexSubImage2D(GLES32.GL_TEXTURE_2D, 0, 0, 0, width, height, format.pixelFormat, format.pixelType, data)
        egl.checkGl("upload texture")
    }

    override fun close() {
        egl.glThread.requireCurrent()
        if (closed) return
        // A foreign current context is a caller error: leave ownership/accounting intact for retry.
        egl.releaseResource {
            if (framebuffer != 0) GLES32.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
            if (texture != 0) GLES32.glDeleteTextures(1, intArrayOf(texture), 0)
        }
        texture = 0; framebuffer = 0; closed = true
        if (reserved) { ledger.release(byteSize); reserved = false }
    }
    companion object {
        fun create(egl: EglContexts, ledger: GpuMemoryLedger, width: Int, height: Int, format: Format): GpuTexture =
            GpuTexture(egl, ledger, width, height, format)
    }
}
