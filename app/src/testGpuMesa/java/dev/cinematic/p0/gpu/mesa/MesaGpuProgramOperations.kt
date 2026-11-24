package dev.cinematic.p0.gpu.mesa

import dev.cinematic.p0.gpu.GpuProgramOperations
import dev.cinematic.p0.gpu.GpuShaderStage
import org.lwjgl.opengles.GLES32.*

internal object MesaGpuProgramOperations : GpuProgramOperations {
    override fun createShader(stage: GpuShaderStage): Int = glCreateShader(
        when (stage) {
            GpuShaderStage.VERTEX -> GL_VERTEX_SHADER
            GpuShaderStage.FRAGMENT -> GL_FRAGMENT_SHADER
            GpuShaderStage.COMPUTE -> GL_COMPUTE_SHADER
        },
    )

    override fun compileShader(shader: Int, source: String) {
        glShaderSource(shader, source)
        glCompileShader(shader)
    }

    override fun shaderCompiled(shader: Int): Boolean = glGetShaderi(shader, GL_COMPILE_STATUS) != 0

    override fun shaderInfoLog(shader: Int): String = glGetShaderInfoLog(shader)

    override fun createProgram(): Int = glCreateProgram()

    override fun linkProgram(program: Int, shaders: List<Int>) {
        shaders.forEach { glAttachShader(program, it) }
        glLinkProgram(program)
    }

    override fun programLinked(program: Int): Boolean = glGetProgrami(program, GL_LINK_STATUS) != 0

    override fun programInfoLog(program: Int): String = glGetProgramInfoLog(program)

    override fun uniformLocation(program: Int, name: String): Int = glGetUniformLocation(program, name)

    override fun deleteShader(shader: Int) {
        glDeleteShader(shader)
    }

    override fun deleteProgram(program: Int) {
        glDeleteProgram(program)
    }
}
