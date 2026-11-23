package dev.cinematic.p0.gpu

import android.opengl.GLES32

internal object AndroidGpuProgramOperations : GpuProgramOperations {
    override fun createShader(stage: GpuShaderStage): Int = GLES32.glCreateShader(
        when (stage) {
            GpuShaderStage.VERTEX -> GLES32.GL_VERTEX_SHADER
            GpuShaderStage.FRAGMENT -> GLES32.GL_FRAGMENT_SHADER
            GpuShaderStage.COMPUTE -> GLES32.GL_COMPUTE_SHADER
        },
    )

    override fun compileShader(shader: Int, source: String, label: String) {
        GLES32.glShaderSource(shader, source)
        GLES32.glCompileShader(shader)
        val status = IntArray(1)
        GLES32.glGetShaderiv(shader, GLES32.GL_COMPILE_STATUS, status, 0)
        check(status[0] != 0) { "Shader compile failed: $label\n${GLES32.glGetShaderInfoLog(shader)}" }
    }

    override fun createProgram(): Int = GLES32.glCreateProgram()

    override fun linkProgram(program: Int, shaders: List<Int>, label: String) {
        shaders.forEach { GLES32.glAttachShader(program, it) }
        GLES32.glLinkProgram(program)
        val status = IntArray(1)
        GLES32.glGetProgramiv(program, GLES32.GL_LINK_STATUS, status, 0)
        check(status[0] != 0) { "Shader link failed: $label\n${GLES32.glGetProgramInfoLog(program)}" }
    }

    override fun uniformLocation(program: Int, name: String): Int = GLES32.glGetUniformLocation(program, name)

    override fun deleteShader(shader: Int) {
        GLES32.glDeleteShader(shader)
    }

    override fun deleteProgram(program: Int) {
        GLES32.glDeleteProgram(program)
    }
}
