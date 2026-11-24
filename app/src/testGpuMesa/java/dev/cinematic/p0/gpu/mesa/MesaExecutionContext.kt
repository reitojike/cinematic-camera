package dev.cinematic.p0.gpu.mesa

import dev.cinematic.p0.gpu.GlCapabilities
import dev.cinematic.p0.gpu.GpuContextLostException
import dev.cinematic.p0.gpu.GpuExecutionContext
import dev.cinematic.p0.gpu.PassGraph
import dev.cinematic.p0.gpu.ShaderLibrary
import dev.cinematic.p0.gpu.ShaderSource
import java.io.File
import org.lwjgl.opengles.GLES32.*

internal class MesaExecutionContext : GpuExecutionContext, AutoCloseable {
    private val owner = Thread.currentThread()
    private val mesa = MesaContext()
    private var closed = false
    private var lost = false
    private var shaderLibrary: ShaderLibrary? = null
    private var passGraph: PassGraph? = null

    override val passOperations = MesaGpuPassOperations
    override val textureOperations = MesaGpuTextureOperations()
    override val pixelTransferOperations = MesaGpuPixelTransferOperations
    override val ssboOperations = MesaGpuSsboOperations
    override val syncOperations = MesaGpuSyncOperations

    override fun requireOwnerThread() {
        check(Thread.currentThread() === owner) { "GPU context belongs to another thread" }
    }

    override fun requireCurrent() {
        requireOwnerThread()
        check(!closed) { "GPU context is closed" }
        if (lost) throw GpuContextLostException("EGL context was lost")
        check(mesa.isCurrentContext()) { "EGL context is not current" }
    }

    override fun checkGl(operation: String) {
        val error = glGetError()
        if (error == 0x0507) {
            lost = true
            throw GpuContextLostException("$operation: GL_CONTEXT_LOST")
        }
        check(error == GL_NO_ERROR) { "$operation failed: GL 0x${error.toString(16)}" }
    }

    override fun releaseResource(block: () -> Unit) {
        requireOwnerThread()
        if (closed || lost) return
        requireCurrent()
        block()
    }

    override fun capabilities(): GlCapabilities.Result {
        requireCurrent()
        val major = glGetInteger(GL_MAJOR_VERSION)
        val minor = glGetInteger(GL_MINOR_VERSION)
        val maximum = glGetInteger(GL_MAX_TEXTURE_SIZE)
        val extensions = (0 until glGetInteger(GL_NUM_EXTENSIONS))
            .map { glGetStringi(GL_EXTENSIONS, it) }.toSet()
        val missing = buildList {
            if (major < 3 || (major == 3 && minor < 2)) add("OpenGL ES 3.2")
            if (major < 3 || (major == 3 && minor < 1)) add("Compute shaders")
            if ("GL_EXT_color_buffer_float" !in extensions) add("GL_EXT_color_buffer_float")
            if (maximum < 4096) add("GL_MAX_TEXTURE_SIZE >= 4096")
        }
        return GlCapabilities.Result(missing.isEmpty(), missing, glGetString(GL_RENDERER).orEmpty(), major, minor, maximum)
    }

    override fun shaders(): ShaderLibrary {
        requireCurrent()
        return shaderLibrary ?: run {
            val assetRoot = File(checkNotNull(System.getProperty("gpu.mesa.assetRoot")))
            val source = ShaderSource({ path -> File(assetRoot, path).readText() })
            ShaderLibrary(this, source, MesaGpuProgramOperations).also { shaderLibrary = it }
        }
    }

    override fun passes(): PassGraph {
        requireCurrent()
        return passGraph ?: PassGraph(this).also { passGraph = it }
    }

    override fun close() {
        requireOwnerThread()
        if (closed) return
        if (!lost) requireCurrent()

        var failure: Throwable? = null
        fun cleanup(block: () -> Unit) {
            try {
                block()
            } catch (error: Throwable) {
                val first = failure
                if (first == null) failure = error else first.addSuppressed(error)
            }
        }
        cleanup { passGraph?.close() }
        passGraph = null
        cleanup { shaderLibrary?.close() }
        shaderLibrary = null
        cleanup { mesa.close() }
        closed = true
        failure?.let { throw it }
    }
}
