package dev.cinematic.p0.gpu

import android.content.res.AssetManager
import android.opengl.GLES32

/** ローダーを分離し、同じinclude規則をJVM試験でも検証する。パスはgpu/からの相対。 */
internal class ShaderSource(private val read: (String) -> String, private val defines: () -> String = ShaderConstants::defines) {
    fun expand(path: String): String {
        val expanded = include(path, linkedSetOf())
        val lines = expanded.lines()
        require(lines.firstOrNull()?.trim() == "#version 320 es") { "Shader must start with #version 320 es: $path" }
        require(lines.drop(1).none { it.trimStart().startsWith("#version") }) { "Duplicate #version: $path" }
        return lines.first() + "\n" + defines() + lines.drop(1).joinToString("\n")
    }
    private fun include(path: String, stack: MutableSet<String>): String {
        require(path.isNotBlank() && !path.startsWith('/') && '\\' !in path && path.split('/').none { it == ".." || it == "." || it.isEmpty() }) { "Invalid shader path: $path" }
        check(stack.add(path)) { "Cyclic shader include: ${stack.joinToString(" -> ")} -> $path" }
        try {
            return read(path).lineSequence().joinToString("\n") { line ->
                val match = INCLUDE.matchEntire(line)
                if (match != null) include(match.groupValues[1], stack) else line
            }
        } finally { stack.remove(path) }
    }
    companion object { private val INCLUDE = Regex("\\s*#include\\s+\"([^\"]+)\"\\s*(?://.*)?") }
}

internal class GpuProgram internal constructor(private val egl: EglContexts, private var handle: Int, val compute: Boolean,
    private val onClose: () -> Unit = {}) : AutoCloseable {
    private val uniformLocations = mutableMapOf<String, Int>()
    internal val cachedUniformCount: Int get() { id; return uniformLocations.size }
    val id: Int get() { egl.requireCurrent(); check(handle != 0) { "GPU program is closed" }; return handle }
    fun uniformLocation(name: String): Int {
        val program = id // Validate ownership/lifetime even on a cache hit.
        return uniformLocations.getOrPut(name) { GLES32.glGetUniformLocation(program, name) }
    }
    internal fun requireContext(expected: EglContexts) { check(egl === expected) { "Program belongs to another context" }; id }
    override fun close() {
        egl.glThread.requireCurrent()
        if (handle == 0) return
        // A live but non-current context may reject cleanup. Preserve ownership for retry.
        egl.releaseResource { GLES32.glDeleteProgram(handle) }
        handle = 0; uniformLocations.clear(); onClose()
    }
}

internal class ShaderLibrary(private val egl: EglContexts, assets: AssetManager) : AutoCloseable {
    private val source = ShaderSource({ path -> assets.open("gpu/$path").bufferedReader().use { it.readText() } })
    private val programs = mutableMapOf<Pair<Boolean, String>, GpuProgram>()
    private val linkedPrograms = mutableMapOf<Int, GpuProgram>()
    private var closed = false
    fun fragment(path: String): GpuProgram = cached(false, path) { fragmentSource(path, source.expand(path)) }
    fun compute(path: String): GpuProgram = cached(true, path) { computeSource(path, source.expand(path)) }
    fun uniformLocation(program: Int, name: String): Int {
        requireOpen()
        return checkNotNull(linkedPrograms[program]) { "Program is not owned by this shader library" }.uniformLocation(name)
    }
    private fun cached(compute: Boolean, path: String, build: () -> GpuProgram): GpuProgram {
        requireOpen()
        return programs.getOrPut(compute to path, build).also { it.id }
    }
    /** Inline entry points are for synthetic tests; the caller owns the returned program. */
    fun fragmentSource(label: String, text: String): GpuProgram = link(label, listOf(GLES32.GL_VERTEX_SHADER to VERTEX, GLES32.GL_FRAGMENT_SHADER to text), false)
    fun computeSource(label: String, text: String): GpuProgram = link(label, listOf(GLES32.GL_COMPUTE_SHADER to text), true)
    private fun requireOpen() { egl.requireCurrent(); check(!closed) { "Shader library is closed" } }
    private fun link(label: String, stages: List<Pair<Int, String>>, compute: Boolean): GpuProgram {
        requireOpen()
        val shaders = mutableListOf<Int>()
        var program = 0
        try {
            for ((kind, text) in stages) {
                val shader = GLES32.glCreateShader(kind)
                check(shader != 0) { "glCreateShader failed: $label" }
                shaders += shader
                GLES32.glShaderSource(shader, text); GLES32.glCompileShader(shader)
                val status = IntArray(1); GLES32.glGetShaderiv(shader, GLES32.GL_COMPILE_STATUS, status, 0)
                check(status[0] != 0) { "Shader compile failed: $label\n${GLES32.glGetShaderInfoLog(shader)}" }
            }
            program = GLES32.glCreateProgram(); check(program != 0) { "glCreateProgram failed: $label" }
            shaders.forEach { GLES32.glAttachShader(program, it) }
            GLES32.glLinkProgram(program)
            val status = IntArray(1); GLES32.glGetProgramiv(program, GLES32.GL_LINK_STATUS, status, 0)
            check(status[0] != 0) { "Shader link failed: $label\n${GLES32.glGetProgramInfoLog(program)}" }
            egl.checkGl("compile/link $label")
            val linked = program
            return GpuProgram(egl, linked, compute) { linkedPrograms.remove(linked) }
                .also { linkedPrograms[linked] = it; program = 0 }
        } finally {
            shaders.forEach(GLES32::glDeleteShader)
            if (program != 0) GLES32.glDeleteProgram(program)
        }
    }
    override fun close() {
        egl.glThread.requireCurrent()
        if (closed) return
        programs.values.forEach { it.close() }; programs.clear(); linkedPrograms.clear(); closed = true
    }
    companion object {
        private val VERTEX = """#version 320 es
precision highp float;
precision highp int;
void main() {
    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
"""
    }
}
