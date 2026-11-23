package dev.cinematic.p0.gpu

internal enum class GpuShaderStage { VERTEX, FRAGMENT, COMPUTE }

/** Program operations on the current context. The shader library manages handle lifetimes. */
internal interface GpuProgramOperations {
    fun createShader(stage: GpuShaderStage): Int
    fun compileShader(shader: Int, source: String)
    fun shaderCompiled(shader: Int): Boolean
    fun shaderInfoLog(shader: Int): String
    fun createProgram(): Int
    fun linkProgram(program: Int, shaders: List<Int>)
    fun programLinked(program: Int): Boolean
    fun programInfoLog(program: Int): String
    fun uniformLocation(program: Int, name: String): Int
    fun deleteShader(shader: Int)
    fun deleteProgram(program: Int)
}
