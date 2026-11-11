package dev.cinematic.p0.gpu.mesa

import org.lwjgl.egl.EGL
import org.lwjgl.egl.EGL10.*
import org.lwjgl.egl.EGL14.*
import org.lwjgl.egl.EGL15.*
import org.lwjgl.opengles.GLES
import org.lwjgl.opengles.GLES32.*
import org.lwjgl.system.MemoryStack.stackPush
import org.lwjgl.system.JNI.invokePP
import org.lwjgl.system.MemoryUtil.memUTF8Safe

/** Test-only native bindings. Android's EGL/GLES implementation is tested separately. */
internal class MesaContext : AutoCloseable {
    private var display = EGL_NO_DISPLAY
    private var context = EGL_NO_CONTEXT
    private var surface = EGL_NO_SURFACE
    private var initialized = false
    private var glCurrent = false

    init {
        try {
            stackPush().use { stack ->
                display = eglGetDisplay(EGL_DEFAULT_DISPLAY)
                eglCheck(display != EGL_NO_DISPLAY, "eglGetDisplay")
                val major = stack.mallocInt(1)
                val minor = stack.mallocInt(1)
                eglCheck(eglInitialize(display, major, minor), "eglInitialize")
                initialized = true
                EGL.createDisplayCapabilities(display, major[0], minor[0])
                eglCheck(eglBindAPI(EGL_OPENGL_ES_API), "eglBindAPI(GLES)")
                val configs = stack.mallocPointer(1)
                val count = stack.mallocInt(1)
                eglCheck(eglChooseConfig(display, stack.ints(
                    EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
                    EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_NONE,
                ), configs, count), "eglChooseConfig")
                check(count[0] > 0) { "No EGL GLES3 pbuffer configuration" }
                context = eglCreateContext(display, configs[0], EGL_NO_CONTEXT,
                    stack.ints(EGL_CONTEXT_MAJOR_VERSION, 3, EGL_CONTEXT_MINOR_VERSION, 2, EGL_NONE))
                eglCheck(context != EGL_NO_CONTEXT, "eglCreateContext(GLES 3.2)")
                surface = eglCreatePbufferSurface(display, configs[0], stack.ints(EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE))
                eglCheck(surface != EGL_NO_SURFACE, "eglCreatePbufferSurface")
                eglCheck(eglMakeCurrent(display, surface, surface, context), "eglMakeCurrent")
                GLES.createCapabilities()
                glCurrent = true
                val version = checkNotNull(glGetString(GL_VERSION))
                val renderer = checkNotNull(glGetString(GL_RENDERER))
                val vendor = checkNotNull(glGetString(GL_VENDOR))
                // LWJGL 3.4.3 has no generated MESA_query_driver binding. Resolve the
                // official EGL extension entry point through LWJGL's function provider.
                val driverFunction = EGL.getFunctionProvider().getFunctionAddress("eglGetDisplayDriverName")
                check("EGL_MESA_query_driver" in eglQueryString(display, EGL_EXTENSIONS).orEmpty().split(' '))
                check(driverFunction != 0L) { "eglGetDisplayDriverName is unavailable" }
                val driver = memUTF8Safe(invokePP(display, driverFunction))
                println("GPU_MESA_CONTEXT egl=${major[0]}.${minor[0]} vendor=$vendor renderer=$renderer driver=$driver version=$version")
                check(version.startsWith("OpenGL ES ")) { "Not an OpenGL ES context: $version" }
                check("Mesa" in version) { "Mesa is required, got $version" }
                check(!driver.isNullOrBlank()) { "EGL_MESA_query_driver must report a driver name" }
                val glMajor = glGetInteger(GL_MAJOR_VERSION)
                val glMinor = glGetInteger(GL_MINOR_VERSION)
                check(glMajor > 3 || glMajor == 3 && glMinor >= 2) { "OpenGL ES 3.2 required: $version" }
                val extensions = (0 until glGetInteger(GL_NUM_EXTENSIONS)).map { glGetStringi(GL_EXTENSIONS, it) }
                check("GL_EXT_color_buffer_float" in extensions) { "GL_EXT_color_buffer_float is required" }
                check(glGetInteger(GL_MAX_TEXTURE_SIZE) >= 4096) { "GL_MAX_TEXTURE_SIZE must be >= 4096" }
                checkGl("capabilities")
            }
        } catch (failure: Throwable) {
            runCatching { close() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    private fun eglCheck(ok: Boolean, operation: String) {
        check(ok) { "$operation failed: EGL 0x${eglGetError().toString(16)}" }
    }

    override fun close() {
        if (glCurrent) { GLES.setCapabilities(null); glCurrent = false }
        if (initialized) {
            eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT)
            if (surface != EGL_NO_SURFACE) eglDestroySurface(display, surface)
            if (context != EGL_NO_CONTEXT) eglDestroyContext(display, context)
            eglTerminate(display)
            initialized = false
        }
        surface = EGL_NO_SURFACE
        context = EGL_NO_CONTEXT
        display = EGL_NO_DISPLAY
    }
}

internal fun checkGl(operation: String) {
    val error = glGetError()
    check(error == GL_NO_ERROR) { "$operation failed: GL 0x${error.toString(16)}" }
}
