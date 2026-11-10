package dev.cinematic.p0.gpu.mesa

import dev.cinematic.p0.gpu.ShaderSource
import java.io.File
import org.lwjgl.opengles.GLES32.*

/** Reads the real assets; no generated copies or Mesa-only edits of shader bodies. */
internal class MesaShaders {
    private val root = File(checkNotNull(System.getProperty("gpu.mesa.assetRoot")))
    private val testRoot = File(checkNotNull(System.getProperty("gpu.mesa.testAssetRoot")))
    // Android's asset packager omits dotfiles; ignore only the repository's directory markers.
    val paths = root.walkTopDown().filter { it.isFile && it.name != ".gitkeep" }
        .map { it.relativeTo(root).invariantSeparatorsPath }.sorted().toList()
    val readPaths = linkedSetOf<String>()
    private val source = ShaderSource({ path ->
        readPaths += path
        File(root, path).readText()
    }) // Default defines are the production ShaderConstants.defines(), not a duplicate table.

    fun compileAsset(path: String): Int {
        require(path in paths)
        return compile(path, source.expand(path), path.endsWith(".comp"))
    }

    fun compileInclude(path: String): Int {
        require(path in paths && path.endsWith(".glsl"))
        // Only a stage wrapper, not a replacement implementation of the included functions.
        val wrapperPath = "mesa_wrapper.frag"
        val wrapper = """#version 320 es
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp isampler2D;
#include "$path"
layout(location = 0) out vec4 o_color;
void main() { o_color = vec4(0.0); }
"""
        val expanded = ShaderSource({ requested ->
            if (requested == wrapperPath) wrapper else {
                readPaths += requested
                File(root, requested).readText()
            }
        }).expand(wrapperPath)
        return compile(path, expanded, false)
    }

    fun compileContractCopy(): Int = compile("contract/copy.frag", ShaderSource({ path ->
        File(testRoot, path).readText()
    }).expand("contract/copy.frag"), false)

    fun compileColorProbe(): Int {
        val path = "mesa_color_probe.frag"
        val wrapper = """#version 320 es
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp isampler2D;
#include "common/color.glsl"
uniform sampler2D s_input;
uniform int u_operation;
layout(location = 0) out vec4 o_color;
void main() {
    vec3 rgb = texelFetch(s_input, ivec2(gl_FragCoord.xy), 0).rgb;
    vec3 result;
    if (u_operation == 0) result = cs_linearToLab(rgb);
    else if (u_operation == 1) result = cs_labToLinear(rgb);
    else result = cs_labToLinear(cs_linearToLab(rgb));
    o_color = vec4(result, 1.0);
}
"""
        // The wrapper only invokes the real asset functions; it contains no copied formula.
        return compile(path, ShaderSource({ requested ->
            if (requested == path) wrapper else File(root, requested).readText()
        }).expand(path), false)
    }

    private fun compile(label: String, fragmentOrCompute: String, compute: Boolean): Int {
        val stages = if (compute) listOf(GL_COMPUTE_SHADER to fragmentOrCompute)
            else listOf(GL_VERTEX_SHADER to VERTEX, GL_FRAGMENT_SHADER to fragmentOrCompute)
        val shaders = mutableListOf<Int>()
        var program = 0
        try {
            for ((type, text) in stages) {
                val shader = glCreateShader(type)
                check(shader != 0) { "glCreateShader: $label" }
                shaders += shader
                glShaderSource(shader, text)
                glCompileShader(shader)
                check(glGetShaderi(shader, GL_COMPILE_STATUS) != 0) { "$label compile failed:\n${glGetShaderInfoLog(shader)}" }
            }
            program = glCreateProgram()
            check(program != 0) { "glCreateProgram: $label" }
            shaders.forEach { glAttachShader(program, it) }
            glLinkProgram(program)
            check(glGetProgrami(program, GL_LINK_STATUS) != 0) { "$label link failed:\n${glGetProgramInfoLog(program)}" }
            checkGl("compile/link $label")
            println("GPU_MESA_SHADER PASS path=$label stage=${if (compute) "compute" else "fragment"}")
            return program.also { program = 0 }
        } finally {
            shaders.forEach(::glDeleteShader)
            if (program != 0) glDeleteProgram(program)
        }
    }

    private companion object {
        const val VERTEX = """#version 320 es
precision highp float;
precision highp int;
void main() {
    vec2 corner = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    gl_Position = vec4(corner * 2.0 - 1.0, 0.0, 1.0);
}
"""
    }
}
