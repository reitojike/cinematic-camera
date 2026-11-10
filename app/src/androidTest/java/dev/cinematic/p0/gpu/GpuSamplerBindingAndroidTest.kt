package dev.cinematic.p0.gpu

import android.opengl.GLES32
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.gpu.diag.GpuStageTap
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class GpuSamplerBindingAndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Before fun syntheticEmulatorOnly() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish")) { "Synthetic emulator only" }
    }

    @Test fun fragmentRejectsMissingAndInactiveSamplersAndRecovers() = verifySamplerBindings(compute = false)

    @Test fun computeRejectsMissingAndInactiveSamplersAndRecovers() = verifySamplerBindings(compute = true)

    private fun verifySamplerBindings(compute: Boolean): Unit = GlThread().use { thread ->
        thread.call {
            EglContexts(thread, instrumentation.context.assets).use { egl ->
                val capabilities = GlCapabilities.inspect(egl)
                Log.i("GpuSamplerBinding", "compute=$compute capabilities=$capabilities")
                capabilities.requireSupported()
                val ledger = GpuMemoryLedger()
                val shaders = egl.shaders()
                val graph = egl.passes()
                val program = if (compute) shaders.computeSource("sampler-compute", COMPUTE)
                    else shaders.fragmentSource("sampler-fragment", FRAGMENT)
                program.use {
                    assertEquals(0, GLES32.glGetUniformLocation(program.id, "s_input"))
                    assertEquals(-1, GLES32.glGetUniformLocation(program.id, "s_unused"))
                    GpuTexture.create(egl, ledger, 2, 2, GpuTexture.Format.R32F).use { input ->
                        GpuTexture.create(egl, ledger, 2, 2, GpuTexture.Format.R32F).use { decoy ->
                            GpuTexture.create(egl, ledger, 2, 2, GpuTexture.Format.R32F).use { output ->
                                val pixels = floatArrayOf(0.25f, 0.5f, 1f, 2f)
                                input.upload(floats(pixels))
                                decoy.upload(floats(FloatArray(4) { -0.5f }))
                                var uniformCalls = 0
                                var resourceCalls = 0
                                fun run(inputs: List<TextureInput>) {
                                    if (compute) {
                                        graph.run(ComputePass(program, inputs, 2, 2, uniforms = { uniformCalls++ }, bindResources = {
                                            resourceCalls++
                                            GLES32.glBindImageTexture(0, output.textureId, 0, false, 0,
                                                GLES32.GL_WRITE_ONLY, GLES32.GL_R32F)
                                        }))
                                    } else {
                                        graph.run(FragmentPass(program, inputs, output, uniforms = { uniformCalls++ }))
                                    }
                                }
                                val valid = TextureInput("s_input", input, 1)
                                for (name in listOf("s_typo", "s_unused")) {
                                    output.upload(floats(FloatArray(4) { 0.75f }))
                                    GLES32.glActiveTexture(GLES32.GL_TEXTURE0)
                                    GLES32.glBindTexture(GLES32.GL_TEXTURE_2D, decoy.textureId)
                                    uniformCalls = 0; resourceCalls = 0
                                    val failure = assertThrows(IllegalArgumentException::class.java) {
                                        run(listOf(valid, TextureInput(name, input, 2)))
                                    }
                                    assertTrue(failure.message.orEmpty().contains(name))
                                    assertEquals(0, uniformCalls); assertEquals(0, resourceCalls)
                                    val unchanged = GpuStageTap.readPlane(egl, output)
                                    repeat(4) { assertEquals(0.75f, unchanged[it], 0f) }
                                    assertEquals(3, ledger.textureCount)

                                    // A valid location 0 must bind unit 1, not silently sample the decoy on unit 0.
                                    run(listOf(valid))
                                    assertEquals(1, uniformCalls)
                                    assertEquals(if (compute) 1 else 0, resourceCalls)
                                    val actual = GpuStageTap.readPlane(egl, output)
                                    pixels.indices.forEach { assertEquals(pixels[it], actual[it], 0f) }
                                }
                            }
                        }
                    }
                }
                assertEquals(0, ledger.textureCount)
                assertEquals(0L, ledger.allocatedBytes)
            }
        }
    }

    private fun floats(values: FloatArray) = ByteBuffer.allocateDirect(values.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(values); rewind() }

    companion object {
        private const val SAMPLERS = """#version 320 es
precision highp float;
precision highp int;
precision highp sampler2D;
layout(location=0) uniform sampler2D s_input;
uniform sampler2D s_unused;
"""
        private const val FRAGMENT = SAMPLERS + """
layout(location=0) out vec4 o_color;
void main() { o_color = texelFetch(s_input, ivec2(gl_FragCoord.xy), 0); }
"""
        private const val COMPUTE = SAMPLERS + """
layout(local_size_x=1, local_size_y=1) in;
layout(r32f, binding=0) writeonly uniform highp image2D u_destination;
void main() {
    ivec2 pixel = ivec2(gl_GlobalInvocationID.xy);
    imageStore(u_destination, pixel, texelFetch(s_input, pixel, 0));
}
"""
    }
}
