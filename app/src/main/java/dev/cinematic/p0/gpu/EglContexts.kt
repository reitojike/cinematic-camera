package dev.cinematic.p0.gpu

import android.content.res.AssetManager
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLSurface
import android.opengl.EGLExt
import android.opengl.GLES32
import android.view.Surface
import dev.cinematic.p0.gpu.preview.GpuSurfaceProbe

/** 1個の ES 3.2 context を pbuffer と表示面で共有する。全操作は GlThread 上。 */
internal class EglContexts(val glThread: GlThread, private val assets: AssetManager? = null) : AutoCloseable {
    private var display = EGL14.EGL_NO_DISPLAY
    private var context = EGL14.EGL_NO_CONTEXT
    private var pbuffer = EGL14.EGL_NO_SURFACE
    private var initialized = false
    private var closed = false
    private var lost = false
    private lateinit var config: EGLConfig
    private val targets = mutableSetOf<WindowTarget>()
    private var shaderLibrary: ShaderLibrary? = null
    private var passGraph: PassGraph? = null

    /** Context-owned resources: callers borrow these and must not close them individually. */
    fun shaders(): ShaderLibrary {
        requireCurrent()
        return shaderLibrary ?: ShaderLibrary(this, checkNotNull(assets) { "Shader assets were not provided" })
            .also { shaderLibrary = it }
    }
    fun passes(): PassGraph {
        requireCurrent()
        return passGraph ?: PassGraph(this).also { passGraph = it }
    }

    init {
        glThread.requireCurrent()
        check(EGL14.eglGetCurrentContext() == EGL14.EGL_NO_CONTEXT) { "GL thread already owns a context" }
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            eglCheck(display != EGL14.EGL_NO_DISPLAY, "eglGetDisplay")
            val version = IntArray(2)
            eglCheck(EGL14.eglInitialize(display, version, 0, version, 1), "eglInitialize")
            initialized = true
            eglCheck(EGL14.eglBindAPI(EGL14.EGL_OPENGL_ES_API), "eglBindAPI")
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            eglCheck(EGL14.eglChooseConfig(display, intArrayOf(
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT or EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE,
            ), 0, configs, 0, 1, count, 0), "eglChooseConfig")
            if (count[0] == 0) throw GpuUnavailableException("No EGL ES3 window/pbuffer config")
            config = checkNotNull(configs[0])
            // EGL_KHR_create_context: EGL_CONTEXT_MINOR_VERSION_KHR = 0x30FB.
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, 0x30FB, 2, EGL14.EGL_NONE), 0)
            eglCheck(context != EGL14.EGL_NO_CONTEXT, "eglCreateContext(ES 3.2)")
            pbuffer = EGL14.eglCreatePbufferSurface(display, config,
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            eglCheck(pbuffer != EGL14.EGL_NO_SURFACE, "eglCreatePbufferSurface")
            makeOffscreenCurrent()
        } catch (failure: Throwable) {
            runCatching { close() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    fun requireCurrent() {
        glThread.requireCurrent()
        check(!closed) { "EGL contexts are closed" }
        if (lost) throw GpuContextLostException("EGL context was lost")
        check(EGL14.eglGetCurrentContext() == context) { "EGL context is not current" }
    }

    fun makeOffscreenCurrent() {
        checkAlive()
        eglCheck(EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context), "eglMakeCurrent(pbuffer)")
    }

    fun createWindowTarget(surface: Surface): WindowTarget {
        checkAlive()
        val handle = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        eglCheck(handle != EGL14.EGL_NO_SURFACE, "eglCreateWindowSurface")
        return WindowTarget(handle).also { targets += it }
    }

    fun makeCurrent(target: WindowTarget) {
        checkAlive(); check(target.owner === this) { "Foreign window target" }; target.requireOpen()
        eglCheck(EGL14.eglMakeCurrent(display, target.handle, target.handle, context), "eglMakeCurrent(window)")
    }

    fun swapBuffers(target: WindowTarget) {
        requireCurrent(); check(target.owner === this) { "Foreign window target" }; target.requireOpen()
        check(EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW) == target.handle) { "Wrong window is current" }
        eglCheck(EGL14.eglSwapBuffers(display, target.handle), "eglSwapBuffers")
    }

    private fun checkAlive() {
        glThread.requireCurrent(); check(!closed) { "EGL contexts are closed" }
        if (lost) throw GpuContextLostException("EGL context was lost")
    }

    private fun eglCheck(ok: Boolean, operation: String) {
        if (ok) return
        val error = EGL14.eglGetError()
        GpuSurfaceProbe.event("egl.failure") { "code=0x${error.toString(16)}" }
        if (error == EGL14.EGL_CONTEXT_LOST) {
            lost = true
            throw GpuContextLostException("$operation: EGL_CONTEXT_LOST")
        }
        throw GpuUnavailableException("$operation failed: EGL 0x${error.toString(16)}")
    }

    internal fun checkGl(operation: String) {
        val error = GLES32.glGetError()
        if (error == 0x0507) { lost = true; throw GpuContextLostException("$operation: GL_CONTEXT_LOST") }
        check(error == GLES32.GL_NO_ERROR) { "$operation failed: GL 0x${error.toString(16)}" }
    }

    internal fun releaseResource(block: () -> Unit) {
        glThread.requireCurrent()
        // Context destruction/loss already frees its GL names. Always allow accounting cleanup.
        if (!closed && !lost) { requireCurrent(); block() }
    }

    /** Surface 自体は Compose/呼出側の所有であり、ここでは release しない。 */
    inner class WindowTarget internal constructor(internal val handle: EGLSurface) : GpuPreviewTarget, AutoCloseable {
        internal val owner: EglContexts get() = this@EglContexts
        private var released = false
        internal fun requireOpen() { check(this in targets && !released) { "EGL window target is closed or foreign" } }
        override fun close() {
            glThread.requireCurrent()
            if (released) return
            if (!closed && !lost && EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW) == handle) makeOffscreenCurrent()
            released = true; targets.remove(this)
            eglCheck(EGL14.eglDestroySurface(display, handle), "eglDestroySurface(window)")
        }
    }

    override fun close() {
        glThread.requireCurrent()
        if (closed) return
        var failure: Throwable? = null
        fun cleanup(block: () -> Unit) { try { block() } catch (e: Throwable) { if (failure == null) failure = e else failure!!.addSuppressed(e) } }
        // Delete context-owned GL names before detaching/destroying the context.
        if (!lost && context != EGL14.EGL_NO_CONTEXT && pbuffer != EGL14.EGL_NO_SURFACE) cleanup { makeOffscreenCurrent() }
        cleanup { passGraph?.close() }; passGraph = null
        cleanup { shaderLibrary?.close() }; shaderLibrary = null
        targets.toList().forEach { cleanup { it.close() } }
        cleanup { eglCheck(EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT), "eglMakeCurrent(release)") }
        if (pbuffer != EGL14.EGL_NO_SURFACE) cleanup { eglCheck(EGL14.eglDestroySurface(display, pbuffer), "eglDestroySurface(pbuffer)") }
        if (context != EGL14.EGL_NO_CONTEXT) cleanup { eglCheck(EGL14.eglDestroyContext(display, context), "eglDestroyContext") }
        if (initialized) cleanup { eglCheck(EGL14.eglTerminate(display), "eglTerminate") }
        cleanup { eglCheck(EGL14.eglReleaseThread(), "eglReleaseThread") }
        closed = true
        failure?.let { throw it }
    }
}
