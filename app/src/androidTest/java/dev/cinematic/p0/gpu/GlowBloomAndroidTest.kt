package dev.cinematic.p0.gpu

import android.opengl.GLES32
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.gpu.diag.GpuStageTap
import dev.cinematic.p0.gpu.look.StageClearGlowGpu
import dev.cinematic.p0.raw.StageClearGlowV1
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 共通 blur を待たずに、実際の bloom 呼び出し側と shader の契約を Android ES3.2 で検査。 */
@RunWith(AndroidJUnit4::class)
class GlowBloomAndroidTest {
    @Before fun syntheticEmulatorOnly() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish")) { "Synthetic emulator only" }
    }

    private fun gpu(block: (EglContexts) -> Unit): Unit = GlThread().use { thread ->
        thread.call {
            EglContexts(thread, InstrumentationRegistry.getInstrumentation().targetContext.assets).use { egl ->
                val capabilities = GlCapabilities.inspect(egl)
                Log.i("GlowBloomTest", "capabilities=$capabilities")
                capabilities.requireSupported()
                block(egl)
            }
        }
    }

    private fun texture(egl: EglContexts, ledger: GpuMemoryLedger, width: Int, height: Int = 1) =
        GpuTexture.create(egl, ledger, width, height, GpuTexture.Format.RGBA32F)

    private fun clear(egl: EglContexts, texture: GpuTexture) {
        GLES32.glBindFramebuffer(GLES32.GL_FRAMEBUFFER, texture.framebufferId)
        GLES32.glDisable(GLES32.GL_SCISSOR_TEST)
        GLES32.glColorMask(true, true, true, true)
        GLES32.glClearBufferfv(GLES32.GL_COLOR, 0, floatArrayOf(0f, 0f, 0f, 0f), 0)
        egl.checkGl("clear synthetic blur")
    }

    @Test fun everyKernelRunsInDeclarationOrderWithBitExactFloatWeights() = gpu { egl ->
        val kernels = StageClearGlowV1.BLOOM_KERNELS
        val ledger = GpuMemoryLedger()
        val radii = mutableListOf<Double>()
        val uniformBits = mutableListOf<Int>()
        var previousBlur: GpuTexture? = null
        var expected = 0f
        fun readWeight() {
            val program = egl.shaders().fragment("look/glow_bloom_accumulate.frag").id
            val value = FloatArray(1)
            GLES32.glGetUniformfv(program, GLES32.glGetUniformLocation(program, "u_kernelWeight"), value, 0)
            egl.checkGl("read bloom weight")
            uniformBits += value[0].toRawBits()
        }
        texture(egl, ledger, kernels.size + 1).use { source ->
            StageClearGlowGpu.accumulateBloom(source) { borrowed, radius ->
                assertSame(source, borrowed)
                previousBlur?.let { closed ->
                    assertThrows(IllegalStateException::class.java) { closed.textureId }
                    readWeight()
                }
                val index = radii.size
                radii += radius
                // The last pixel is cancellation-sensitive: reordering its additions changes
                // the result. Other pixels isolate each actual Kotlin Float weight exactly.
                val value = when (index) { 0 -> 33554432f; 1 -> -55924052f; 2 -> 5f; else -> .25f }
                expected += kernels[index].second * value
                val rgba = FloatArray(source.width * 4)
                repeat(3) { c -> rgba[index * 4 + c] = 1f; rgba[kernels.size * 4 + c] = value }
                texture(egl, ledger, source.width).also { blurred ->
                    val buffer = ByteBuffer.allocateDirect(rgba.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
                    buffer.put(rgba).rewind()
                    blurred.upload(buffer)
                    previousBlur = blurred
                }
            }.use { result ->
                readWeight()
                assertEquals(kernels.map { it.first.toRawBits() }, radii.map { it.toRawBits() })
                assertEquals(kernels.map { it.second.toRawBits() }, uniformBits)
                val rgb = GpuStageTap.readRgb(egl, result)
                kernels.forEachIndexed { index, kernel ->
                    repeat(3) { c -> assertEquals("kernel $index channel $c", kernel.second.toRawBits(), rgb[index * 3 + c].toRawBits()) }
                }
                repeat(3) { c -> assertEquals("ordered sum channel $c", expected.toRawBits(), rgb[kernels.size * 3 + c].toRawBits()) }
                previousBlur?.let { closed -> assertThrows(IllegalStateException::class.java) { closed.textureId } }
                assertEquals(2, ledger.textureCount) // Borrowed source + final sum, no retained per-kernel blur.
            }
            source.requireOpen()
        }
        assertEquals(0L, ledger.allocatedBytes)
    }

    @Test fun isolatedBloomPeakFits64MiBAndReleasesEachKernelBeforeTheNext() = gpu { egl ->
        val ledger = GpuMemoryLedger()
        val width = 1024; val height = 769
        var peak = 0L
        var calls = 0
        texture(egl, ledger, width, height).use { base ->
            texture(egl, ledger, width, height).use { source ->
                StageClearGlowGpu.accumulateBloom(source) { _, _ ->
                    assertEquals(4, ledger.textureCount) // base, source, two accumulators.
                    calls++
                    texture(egl, ledger, width, height).also {
                        clear(egl, it)
                        peak = maxOf(peak, ledger.allocatedBytes)
                    }
                }.use {
                    assertEquals(StageClearGlowV1.BLOOM_KERNELS.size, calls)
                    assertEquals(base.byteSize * 5, peak)
                    assertTrue(peak <= GpuMemoryLedger.PREVIEW_BUDGET)
                    assertEquals(3, ledger.textureCount)
                }
            }
        }
        assertEquals(0L, ledger.allocatedBytes)
        Log.i("GlowBloomTest", "isolatedBloomPeakBytes=$peak; full look/resample memory not measured")
    }

    @Test fun failedKernelReleasesAccumulatorsButNeverClosesBorrowedSource() = gpu { egl ->
        val ledger = GpuMemoryLedger()
        texture(egl, ledger, 8).use { source ->
            var calls = 0
            assertThrows(IllegalStateException::class.java) {
                StageClearGlowGpu.accumulateBloom(source) { _, _ ->
                    if (++calls == 2) error("synthetic second kernel failure")
                    texture(egl, ledger, source.width).also { clear(egl, it) }
                }
            }
            assertEquals(source.byteSize, ledger.allocatedBytes)
            assertThrows(IllegalArgumentException::class.java) {
                StageClearGlowGpu.accumulateBloom(source) { borrowed, _ -> borrowed }
            }
            source.requireOpen()
            assertEquals(1, ledger.textureCount)
        }
        assertEquals(0L, ledger.allocatedBytes)
    }
}
