package dev.cinematic.p0.gpu.mesa

import org.junit.Test
import org.lwjgl.opengles.GLES32.glDeleteProgram

class MesaShaderAssetsTest {
    @Test fun everyProductAssetCompilesAndLinksInGles32() = MesaContext().use {
        val shaders = MesaShaders()
        check(shaders.paths.isNotEmpty()) { "No GPU assets found" }
        check(shaders.paths.all { it.endsWith(".frag") || it.endsWith(".comp") || it.endsWith(".glsl") }) {
            "Unknown GPU asset type: ${shaders.paths}"
        }
        var programs = 0
        shaders.paths.filter { !it.endsWith(".glsl") }.forEach { path ->
            glDeleteProgram(shaders.compileAsset(path))
            programs++
        }
        shaders.paths.filter { it.endsWith(".glsl") && it !in shaders.readPaths }.forEach { path ->
            glDeleteProgram(shaders.compileInclude(path))
            programs++
        }
        check(shaders.readPaths.containsAll(shaders.paths)) { "Uncompiled assets: ${shaders.paths - shaders.readPaths}" }
        println("GPU_MESA_ASSETS assets=${shaders.paths.size} programs=$programs failed=0 skipped=0")
    }
}
