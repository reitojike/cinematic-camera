package dev.cinematic.p0.gpu

import android.opengl.GLES32

internal object GlCapabilities {
    data class Result(val supported: Boolean, val missing: List<String>, val renderer: String,
        val major: Int, val minor: Int, val maxTextureSize: Int) {
        fun requireSupported() { if (!supported) throw GpuUnavailableException(missing.joinToString(", ")) }
    }

    fun inspect(egl: EglContexts): Result {
        egl.requireCurrent()
        fun integer(name: Int) = IntArray(1).also { GLES32.glGetIntegerv(name, it, 0) }[0]
        val major = integer(GLES32.GL_MAJOR_VERSION)
        val minor = integer(GLES32.GL_MINOR_VERSION)
        val maximum = integer(GLES32.GL_MAX_TEXTURE_SIZE)
        val extensions = (0 until integer(GLES32.GL_NUM_EXTENSIONS)).map { GLES32.glGetStringi(GLES32.GL_EXTENSIONS, it) }.toSet()
        val missing = buildList {
            if (major < 3 || (major == 3 && minor < 2)) add("OpenGL ES 3.2")
            if (major < 3 || (major == 3 && minor < 1)) add("Compute shaders")
            if ("GL_EXT_color_buffer_float" !in extensions) add("GL_EXT_color_buffer_float")
            if (maximum < 4096) add("GL_MAX_TEXTURE_SIZE >= 4096")
        }
        return Result(missing.isEmpty(), missing, GLES32.glGetString(GLES32.GL_RENDERER).orEmpty(), major, minor, maximum)
    }
}
