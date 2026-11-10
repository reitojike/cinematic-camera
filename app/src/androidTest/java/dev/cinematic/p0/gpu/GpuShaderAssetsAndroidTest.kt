package dev.cinematic.p0.gpu

import android.content.res.AssetManager
import android.opengl.GLES32
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 各工程の画素一致試験より先に実行する、製品 asset 全件の構文・リンク検査。 */
@RunWith(AndroidJUnit4::class)
class GpuShaderAssetsAndroidTest {
    @Test fun allGpuAssetsExpandCompileAndLink() {
        // instrumentation.context はテストAPKなので、必ず製品APKの asset を列挙する。
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        val paths = listAssets(assets, "gpu").map { it.removePrefix("gpu/") }
            .filterNot { it.substringAfterLast('/') == ".gitkeep" }.sorted()
        assertTrue("No shader assets found under gpu/", paths.isNotEmpty())
        val unsupported = paths.filter { it.substringAfterLast('.') !in setOf("frag", "comp", "glsl") }
        assertTrue("Unsupported GPU assets: $unsupported", unsupported.isEmpty())

        GlThread().use { thread ->
            thread.call {
                EglContexts(thread).use { egl ->
                    val capabilities = GlCapabilities.inspect(egl)
                    Log.i(TAG, "capabilities=$capabilities; assets=${paths.size}")
                    capabilities.requireSupported()
                    ShaderLibrary(egl, assets).use { library ->
                        val verified = linkedSetOf<String>()
                        var programs = 0
                        fun verify(path: String, includeOnly: Boolean = false) {
                            val included = linkedSetOf<String>()
                            // 実際のShaderSourceで再帰includeとV1定数注入を行う。
                            val source = ShaderSource({ requested ->
                                if (includeOnly && requested == WRAPPER_PATH) {
                                    includeFragment(path)
                                } else {
                                    assets.open("gpu/$requested").bufferedReader().use { it.readText() }
                                        .also { included += requested }
                                }
                            })
                            val expanded = source.expand(if (includeOnly) WRAPPER_PATH else path)
                            val program = if (path.endsWith(".comp")) {
                                library.computeSource(path, expanded)
                            } else {
                                library.fragmentSource(path, expanded)
                            }
                            program.use {
                                val linked = IntArray(1)
                                GLES32.glGetProgramiv(it.id, GLES32.GL_LINK_STATUS, linked, 0)
                                assertEquals("Not linked: $path", GLES32.GL_TRUE, linked[0])
                                egl.checkGl("asset compile/link $path")
                            }
                            verified += included
                            programs++
                            Log.i(TAG, "PASS gpu/$path; includes=${included.sorted()}")
                        }

                        // stageを持つincludeは、本来の親が提供する宣言・stageで検査する。
                        paths.filter { it.endsWith(".frag") || it.endsWith(".comp") }.forEach { verify(it) }
                        // 未使用の共通関数も漏らさない。mainのないincludeには最小fragmentを添える。
                        paths.filter { it.endsWith(".glsl") }.forEach {
                            if (it !in verified) verify(it, includeOnly = true)
                        }
                        assertEquals("Every GPU asset must compile and link", paths.toSet(), verified)
                        Log.i(TAG, "PASS all ${verified.size} GPU assets in $programs programs")
                    }
                }
            }
        }
    }

    private fun listAssets(assets: AssetManager, directory: String): List<String> =
        checkNotNull(assets.list(directory)) { "Cannot list assets: $directory" }.flatMap { name ->
            val path = "$directory/$name"
            if (checkNotNull(assets.list(path)).isNotEmpty()) listAssets(assets, path) else listOf(path)
        }

    private fun includeFragment(path: String) = """#version 320 es
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp isampler2D;
#include "$path"
layout(location = 0) out vec4 o_color;
void main() { o_color = vec4(0.0); }
"""

    private companion object {
        const val TAG = "GpuShaderAssets"
        const val WRAPPER_PATH = "__asset_compile_test__.frag"
    }
}
