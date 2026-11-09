package dev.cinematic.p0.gpu

import android.opengl.EGL14
import android.opengl.GLES32
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.gpu.diag.GpuStageTap
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class GpuFoundationAndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun floats(size: Int) = ByteBuffer.allocateDirect(size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private fun <T> gpu(block: (EglContexts) -> T): T = GlThread().use { thread ->
        thread.call { EglContexts(thread, instrumentation.context.assets).use { egl ->
            val capabilities = GlCapabilities.inspect(egl)
            Log.i("GpuFoundation", "capabilities=$capabilities")
            capabilities.requireSupported()
            block(egl)
        } }
    }

    @Test fun rgba32fRoundTripIsBitExactAndIncludeProgramIsCached() = gpu { egl ->
        val width = 1024; val height = 769
        val data = FloatArray(width * height * 4) { i -> (i % 4093 - 2046).toFloat() / 128f }
        val source = floats(data.size).apply { put(data); rewind() }
        val ledger = GpuMemoryLedger()
        ShaderLibrary(egl, instrumentation.context.assets).use { shaders ->
            PassGraph(egl).use { graph ->
                GpuTexture.create(egl, ledger, width, height, GpuTexture.Format.RGBA32F).use { input ->
                    GpuTexture.create(egl, ledger, width, height, GpuTexture.Format.RGBA32F).use { output ->
                        input.upload(source)
                        val program = shaders.fragment("contract/copy.frag")
                        assertSame(program, shaders.fragment("contract/copy.frag"))
                        graph.run(FragmentPass(program, listOf(TextureInput("s_input", input, 0)), output))
                        GpuFence(egl).use { it.await() }
                        val result = floats(data.size)
                        GLES32.glBindFramebuffer(GLES32.GL_FRAMEBUFFER, output.framebufferId)
                        GLES32.glReadPixels(0, 0, width, height, GLES32.GL_RGBA, GLES32.GL_FLOAT, result)
                        egl.checkGl("RGBA32F round trip")
                        for (i in data.indices) assertEquals("channel $i", data[i].toRawBits(), result.get(i).toRawBits())
                        val rgb = GpuStageTap.readRgb(egl, output)
                        assertEquals(data[4].toRawBits(), rgb[3].toRawBits())
                    }
                }
            }
        }
        assertEquals(0L, ledger.allocatedBytes)
    }

    @Test fun texturesReleaseIdempotentlyAndRejectUseAfterClose() = gpu { egl ->
        val ledger = GpuMemoryLedger()
        repeat(100) {
            val texture = GpuTexture.create(egl, ledger, 8, 8, GpuTexture.Format.R32F)
            texture.upload(floats(64).apply { repeat(64) { put(it.toFloat()) }; rewind() })
            assertEquals(63f, GpuStageTap.readPlane(egl, texture)[63], 0f)
            texture.close(); texture.close()
            assertThrows(IllegalStateException::class.java) { texture.textureId }
            assertThrows(IllegalStateException::class.java) { texture.framebufferId }
        }
        assertEquals(0L, ledger.allocatedBytes); assertEquals(0, ledger.textureCount)
        val small = GpuMemoryLedger(4)
        assertThrows(GpuMemoryBudgetException::class.java) { GpuTexture.create(egl, small, 2, 2, GpuTexture.Format.R32F) }
        assertEquals(0L, small.allocatedBytes)
    }

    @Test fun multipleOutputsUseOnePassAndPreserveTextureOwnership() = gpu { egl ->
        val ledger = GpuMemoryLedger()
        val shaders = egl.shaders(); val graph = egl.passes()
        assertSame(shaders, egl.shaders()); assertSame(graph, egl.passes())
        shaders.fragmentSource("two-outputs", """#version 320 es
precision highp float;
layout(location=0) out vec4 o_first;
layout(location=1) out vec4 o_second;
void main() { o_first=vec4(-0.5, 1.25, 0.25, 1.0); o_second=vec4(2.0, 0.5, -0.25, 1.0); }
""").use { program ->
            GpuTexture.create(egl, ledger, 8, 4, GpuTexture.Format.RGBA32F).use { first ->
                GpuTexture.create(egl, ledger, 8, 4, GpuTexture.Format.RGBA32F).use { second ->
                    assertSame(egl, first.context); assertSame(ledger, first.memoryLedger)
                    val pass = FragmentPass(program, emptyList(), first, additionalOutputs = listOf(second))
                    graph.run(pass)
                    assertEquals(-0.5f, GpuStageTap.readRgb(egl, first)[0], 0f)
                    assertEquals(2f, GpuStageTap.readRgb(egl, second)[0], 0f)
                    assertEquals(2, ledger.textureCount)
                    assertThrows(IllegalArgumentException::class.java) { graph.run(pass.copy(additionalOutputs = listOf(first))) }
                    assertThrows(IllegalArgumentException::class.java) { graph.run(pass.copy(inputs = listOf(TextureInput("s_input", second, 0)))) }
                    GpuTexture.create(egl, ledger, 2, 2, GpuTexture.Format.RGBA32F).use { wrongSize ->
                        assertThrows(IllegalArgumentException::class.java) { graph.run(pass.copy(additionalOutputs = listOf(wrongSize))) }
                    }
                }
            }
        }
        assertEquals(0L, ledger.allocatedBytes)
    }

    @Test fun contextResourcesCloseTogetherAndFailedTextureCloseKeepsAccounting() = gpu { egl ->
        val ledger = GpuMemoryLedger()
        val texture = GpuTexture.create(egl, ledger, 2, 2, GpuTexture.Format.R32F)
        val program = egl.shaders().fragment("contract/copy.frag")
        val graph = egl.passes()
        val display = EGL14.eglGetCurrentDisplay()
        assertTrue(EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT))
        assertThrows(IllegalStateException::class.java) { texture.close() }
        assertEquals(16L, ledger.allocatedBytes)
        egl.makeOffscreenCurrent()
        texture.close()
        assertEquals(0L, ledger.allocatedBytes)
        egl.close(); egl.close()
        assertThrows(IllegalStateException::class.java) { program.id }
        assertThrows(IllegalStateException::class.java) { egl.shaders() }
        assertThrows(IllegalStateException::class.java) { egl.passes() }
        graph.close() // Context destruction has already released GL resources.
    }

    @Test fun uiThreadCannotCallAndWorkerExceptionsReachCaller() {
        GlThread().use { thread ->
            instrumentation.runOnMainSync { assertThrows(IllegalStateException::class.java) { thread.call { 1 } } }
            assertEquals(42, thread.call { thread.requireCurrent(); 42 })
            assertThrows(IllegalArgumentException::class.java) { thread.call { throw IllegalArgumentException("expected") } }
            assertEquals(43, thread.call { 43 })
        }
    }

    @Test fun asynchronousFailuresReportOnceAndAcceptedPostsDrainOnClose() {
        val thread = GlThread()
        val ran = AtomicInteger()
        try {
            thread.post { throw IllegalArgumentException("async failure") }
            val failure = assertThrows(IllegalStateException::class.java) { thread.call { ran.incrementAndGet() } }
            assertEquals("async failure", failure.cause?.message)
            assertEquals(0, ran.get())
            assertEquals(1, thread.call { ran.incrementAndGet() })
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            thread.post { started.countDown(); check(release.await(5, TimeUnit.SECONDS)); ran.incrementAndGet() }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            thread.post { ran.incrementAndGet() }
            release.countDown()
            thread.close()
            assertEquals(3, ran.get())
            assertThrows(IllegalStateException::class.java) { thread.requireCurrent() }
        } finally { thread.close() }
    }

    @Test fun badShaderIncludesDriverLogAndFenceChecksCancellation() = gpu { egl ->
        val files = mapOf("main" to "#version 320 es\n#include \"child\"", "child" to "float example = SC_GLOW_THRESHOLD;")
        val expanded = ShaderSource({ files.getValue(it) }).expand("main")
        assertTrue(expanded.startsWith("#version 320 es\n#define "))
        assertTrue(expanded.contains("float example = SC_GLOW_THRESHOLD;"))
        assertThrows(IllegalStateException::class.java) { ShaderSource({ "#include \"cycle\"" }).expand("cycle") }
        ShaderLibrary(egl, instrumentation.context.assets).use { shaders ->
            val failure = assertThrows(IllegalStateException::class.java) {
                shaders.fragmentSource("deliberately-invalid", "#version 320 es\nthis is invalid GLSL")
            }
            assertTrue(failure.message.orEmpty().contains("deliberately-invalid"))
            assertTrue(failure.message.orEmpty().substringAfter('\n').isNotBlank())
        }
        GpuFence(egl).use { fence -> assertThrows(CancellationException::class.java) { fence.await { true } } }
    }

    @Test fun computePassWritesFloatImageAndBarrierMakesItReadable() = gpu { egl ->
        val ledger = GpuMemoryLedger()
        ShaderLibrary(egl, instrumentation.context.assets).use { shaders ->
            shaders.computeSource("constant-compute", """#version 320 es
precision highp float;
precision highp int;
layout(local_size_x=1) in;
layout(r32f, binding=0) writeonly uniform highp image2D u_destination;
void main() { imageStore(u_destination, ivec2(gl_GlobalInvocationID.xy), vec4(0.25)); }
""").use { program ->
                PassGraph(egl).use { graph ->
                    GpuTexture.create(egl, ledger, 8, 4, GpuTexture.Format.R32F).use { output ->
                        graph.run(ComputePass(program, emptyList(), 8, 4, bindResources = {
                            GLES32.glBindImageTexture(0, output.textureId, 0, false, 0, GLES32.GL_WRITE_ONLY, GLES32.GL_R32F)
                        }))
                        val plane = GpuStageTap.readPlane(egl, output)
                        repeat(32) { assertEquals(0.25f, plane[it], 0f) }
                    }
                }
            }
        }
        assertEquals(0L, ledger.allocatedBytes)
    }
}
