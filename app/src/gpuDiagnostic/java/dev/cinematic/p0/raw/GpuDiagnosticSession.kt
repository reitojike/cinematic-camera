package dev.cinematic.p0.raw

import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES30
import java.util.concurrent.CancellationException

/** 合成写真1枚の診断だけで保持。同一owner threadで準備・使用・解放する。 */
internal class GpuDiagnosticSession(private val cancelled: () -> Boolean = { false }) : AutoCloseable {
    private val owner = Thread.currentThread()
    private var display = EGL14.EGL_NO_DISPLAY
    private var context = EGL14.EGL_NO_CONTEXT
    private var surface = EGL14.EGL_NO_SURFACE
    private var initialized = false
    private var closed = false
    var blurProgram = 0; private set
    var ortonProgram = 0; private set
    val renderer: String
    val preparationNs: Long

    init {
        val started = System.nanoTime()
        check(EGL14.eglGetCurrentContext() == EGL14.EGL_NO_CONTEXT) { "GPU diagnostic requires an unbound owner thread" }
        try {
            checkpoint()
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(display != EGL14.EGL_NO_DISPLAY)
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1))
            initialized = true
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
            val count = IntArray(1)
            check(EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR, EGL14.EGL_NONE), 0, configs, 0, 1, count, 0) && count[0] > 0)
            val config = checkNotNull(configs[0])
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
            check(context != EGL14.EGL_NO_CONTEXT)
            surface = EGL14.eglCreatePbufferSurface(display, config,
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            check(surface != EGL14.EGL_NO_SURFACE)
            check(EGL14.eglMakeCurrent(display, surface, surface, context))
            check(GLES30.glGetString(GLES30.GL_EXTENSIONS).orEmpty().contains("GL_EXT_color_buffer_float"))
            renderer = GLES30.glGetString(GLES30.GL_RENDERER).orEmpty()
            blurProgram = GpuBlurProbe.createProgram()
            ortonProgram = GpuDiagnosticOrton.createProgram()
            GLES30.glDisable(GLES30.GL_BLEND)
            GLES30.glDisable(GLES30.GL_DITHER)
            GLES30.glFinish()
            check(GLES30.glGetError() == GLES30.GL_NO_ERROR)
            checkpoint()
            preparationNs = System.nanoTime() - started
        } catch (failure: Throwable) {
            try { close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    fun checkpoint() {
        check(Thread.currentThread() === owner) { "GPU session used by a different thread" }
        check(!closed) { "GPU session is closed" }
        if (cancelled()) throw CancellationException("GPU診断を取り消しました")
    }

    fun requireCurrent() {
        checkpoint()
        check(context != EGL14.EGL_NO_CONTEXT && EGL14.eglGetCurrentContext() == context) { "GPU context was lost or replaced" }
    }

    override fun close() {
        check(Thread.currentThread() === owner) { "GPU session must close on its owner thread" }
        if (closed) return
        closed = true
        var released = true
        if (context != EGL14.EGL_NO_CONTEXT && EGL14.eglGetCurrentContext() == context) {
            if (blurProgram != 0) GLES30.glDeleteProgram(blurProgram)
            if (ortonProgram != 0) GLES30.glDeleteProgram(ortonProgram)
            released = EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT) && released
        }
        if (surface != EGL14.EGL_NO_SURFACE) released = EGL14.eglDestroySurface(display, surface) && released
        if (context != EGL14.EGL_NO_CONTEXT) released = EGL14.eglDestroyContext(display, context) && released
        if (initialized) released = EGL14.eglTerminate(display) && released
        released = EGL14.eglReleaseThread() && released
        check(released) { "GPU session cleanup failed" }
    }
}
