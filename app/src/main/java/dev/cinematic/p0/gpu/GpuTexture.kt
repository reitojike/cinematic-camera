package dev.cinematic.p0.gpu

import android.opengl.GLES32
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.ShortBuffer

/** テクスチャ/FBOの唯一の所有者。create/使用/closeは同じGlThreadとcontext上。 */
internal class GpuTexture private constructor(
    private val storage: Storage,
    internal val pool: GpuTexturePool? = null,
) : AutoCloseable {
    private val owner get() = storage.owner
    private val ledger get() = storage.ledger
    val width get() = storage.width
    val height get() = storage.height
    val format get() = storage.format
    private var closed = false
    enum class Format(val internalFormat: Int, val pixelFormat: Int, val pixelType: Int, val bytesPerPixel: Int) {
        RGBA32F(GLES32.GL_RGBA32F, GLES32.GL_RGBA, GLES32.GL_FLOAT, 16),
        R32F(GLES32.GL_R32F, GLES32.GL_RED, GLES32.GL_FLOAT, 4),
        RGBA8(GLES32.GL_RGBA8, GLES32.GL_RGBA, GLES32.GL_UNSIGNED_BYTE, 4),
        RGB16UI(GLES32.GL_RGB16UI, GLES32.GL_RGB_INTEGER, GLES32.GL_UNSIGNED_SHORT, 6),
        R32I(GLES32.GL_R32I, GLES32.GL_RED_INTEGER, GLES32.GL_INT, 4),
        R32UI(GLES32.GL_R32UI, GLES32.GL_RED_INTEGER, GLES32.GL_UNSIGNED_INT, 4),
    }
    /** Read-only owners used by fixed-signature analysis helpers; no global GL state. */
    val context: GpuExecutionContext get() { requireOpen(); return owner }
    val memoryLedger: GpuMemoryLedger get() { requireOpen(); return ledger }
    val byteSize: Long = width.toLong() * height * format.bytesPerPixel
    val textureId: Int get() { requireOpen(); return storage.textureId }
    val framebufferId: Int get() { requireOpen(); return storage.framebufferId }

    /** Creates a fresh lease; previous contents are undefined and must be fully overwritten. */
    fun createSibling(width: Int = this.width, height: Int = this.height, format: Format = this.format): GpuTexture {
        requireOpen()
        return create(owner, ledger, width, height, format, pool)
    }

    fun requireOpen() { owner.requireCurrent(); check(!closed) { "GPU texture is closed" } }
    internal fun requireContext(expected: GpuResourceContext) { check(owner === expected) { "Texture belongs to another context" }; requireOpen() }

    fun upload(data: Buffer) {
        requireOpen()
        require(data.isDirect) { "Texture upload requires a direct buffer" }
        val elementSize = when (data) { is ByteBuffer -> 1; is ShortBuffer -> 2; is IntBuffer, is FloatBuffer -> 4; else -> error("Unsupported texture buffer") }
        require(data.remaining().toLong() * elementSize >= byteSize) { "Texture upload buffer is too small" }
        storage.upload(data)
    }

    override fun close() {
        owner.requireOwnerThread()
        if (closed) return
        // Failed cleanup leaves this lease valid for an explicit retry.
        if (pool == null) storage.destroy() else pool.recycle(storage)
        closed = true
    }

    internal class Storage(
        val owner: GpuExecutionContext, val ledger: GpuMemoryLedger,
        val width: Int, val height: Int, val format: Format,
    ) {
        val byteSize = width.toLong() * height * format.bytesPerPixel
        private var texture = 0
        private var framebuffer = 0
        private var reserved = false
        val textureId: Int get() { owner.requireCurrent(); return texture }
        val framebufferId: Int get() {
            owner.requireCurrent()
            if (framebuffer == 0) {
                val ops = owner.textureOperations
                val candidate = ops.generateFramebuffer()
                try {
                    check(candidate != 0) { "glGenFramebuffers failed" }
                    ops.attachFramebufferTexture(candidate, texture)
                    check(ops.framebufferComplete()) { "$format cannot be attached to a complete FBO" }
                    owner.checkGl("create framebuffer")
                } catch (failure: Throwable) {
                    ops.deleteFramebuffer(candidate)
                    throw failure
                }
                framebuffer = candidate
            }
            return framebuffer
        }

        init {
            owner.requireCurrent()
            require(width > 0 && height > 0)
            val maximum = owner.textureOperations.maxTextureSize()
            require(width <= maximum && height <= maximum) { "Texture dimensions exceed GL_MAX_TEXTURE_SIZE" }
            ledger.reserve(byteSize); reserved = true
            try {
                texture = owner.textureOperations.generateTexture()
                check(texture != 0) { "glGenTextures failed" }
                owner.textureOperations.allocateTexture(texture, width, height, format)
                owner.checkGl("allocate $format texture")
            } catch (failure: Throwable) {
                runCatching { destroy() }.exceptionOrNull()?.let(failure::addSuppressed)
                throw failure
            }
        }


        fun upload(data: Buffer) {
            val ops = owner.textureOperations
            ops.prepareTextureUpload()
            owner.requireCurrent()
            ops.uploadTexture(texture, width, height, format, data)
            owner.checkGl("upload texture")
        }

        fun destroy() {
            owner.requireOwnerThread()
            if (!reserved) return
            // Lost contexts free names implicitly; foreign contexts preserve the owner for retry.
            owner.releaseResource {
                if (framebuffer != 0) owner.textureOperations.deleteFramebuffer(framebuffer)
                if (texture != 0) owner.textureOperations.deleteTexture(texture)
            }
            texture = 0; framebuffer = 0
            ledger.release(byteSize); reserved = false
        }
    }

    companion object {
        fun create(egl: GpuExecutionContext, ledger: GpuMemoryLedger, width: Int, height: Int, format: Format,
            pool: GpuTexturePool? = null): GpuTexture {
            if (pool != null) return pool.acquire(egl, ledger, width, height, format)
            return GpuTexture(Storage(egl, ledger, width, height, format))
        }
        internal fun lease(storage: Storage, pool: GpuTexturePool) = GpuTexture(storage, pool)
    }
}
