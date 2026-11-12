package dev.cinematic.p0.gpu

import android.opengl.GLES32
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.gpu.look.StageClearFinishGpu
import dev.cinematic.p0.gpu.preview.present
import dev.cinematic.p0.photoroll.TrialDisplayPixels
import dev.cinematic.p0.raw.LinearSrgbImage
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/** #284: both actual display programs, including presentation's row inversion and rounding. */
@RunWith(AndroidJUnit4::class)
class GpuDisplayEncodingAndroidTest {
    @Before fun isolatedEmulatorOnly() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
    }

    @Test fun presentAndEncodeUseIdenticalBytesAcross4096UnitSamples() = gpu { egl, ledger ->
        compare(egl, ledger, LinearSrgbImage(64, 64, FloatArray(4096 * 3) {
            ((it / 3 + (it % 3) * 1024) % 4096) / 4095f
        }))
    }

    @Test fun presentAndEncodeAgreeAtClipAndTransferBoundaries() = gpu { egl, ledger ->
        for (value in floatArrayOf(-Float.MAX_VALUE, -1f, -0f, 0f, Float.MIN_VALUE,
            Math.nextDown(0.0031308f), 0.0031308f, Math.nextUp(0.0031308f),
            Math.nextDown(1f), 1f, Math.nextUp(1f), Float.MAX_VALUE)) {
            compare(egl, ledger, LinearSrgbImage(4, 4, FloatArray(4 * 4 * 3) { value }))
        }
    }

    private fun compare(egl: EglContexts, ledger: GpuMemoryLedger, image: LinearSrgbImage) {
        var cpuMaximum = 0
        val expected = TrialDisplayPixels.encode(image, maxOf(image.width, image.height)) { false }
        val rgba = ByteBuffer.allocateDirect(image.pixelCount * 16).order(ByteOrder.nativeOrder()).asFloatBuffer()
        repeat(image.pixelCount) { p -> repeat(3) { rgba.put(image[p * 3 + it]) }; rgba.put(1f) }; rgba.flip()
        GpuTexture.create(egl, ledger, image.width, image.height, GpuTexture.Format.RGBA32F).use { input ->
            input.upload(rgba)
            StageClearFinishGpu(egl, ledger).use { finish ->
                finish.encode(input).use { encoded ->
                    val bytes = read(egl, encoded)
                    GpuTexture.create(egl, ledger, image.width, image.height, GpuTexture.Format.RGBA8).use { output ->
                        val extensions = IntArray(1).also { GLES32.glGetIntegerv(GLES32.GL_NUM_EXTENSIONS, it, 0) }[0]
                        val linearSupported = (0 until extensions).any { GLES32.glGetStringi(GLES32.GL_EXTENSIONS, it) == "GL_OES_texture_float_linear" }
                        for (linear in if (linearSupported) listOf(false, true) else listOf(false)) {
                            present(egl, input, image.width, image.height, 0, output, linearFiltering = linear)
                            val displayed = read(egl, output)
                            repeat(image.height) { y -> repeat(image.width) { x ->
                                val p = y * image.width + x
                                val flipped = (image.height - 1 - y) * image.width + x
                                repeat(4) { channel ->
                                    val actual = bytes[p * 4 + channel].toInt() and 255
                                    assertEquals("display/encode at $x,$y/$channel linear=$linear", actual,
                                        displayed[flipped * 4 + channel].toInt() and 255)
                                    val cpu = if (channel == 3) 255 else (expected[p] ushr (16 - 8 * channel)) and 255
                                    if (channel == 3) assertEquals("opaque alpha", 255, actual)
                                    cpuMaximum = maxOf(cpuMaximum, abs(cpu - actual))
                                }
                            } }
                        }
                    }
                }
            }
        }
        Log.i("GpuDisplayEncoding", "CPU_REFERENCE_ONLY max8=$cpuMaximum")
        assertEquals(0L, ledger.allocatedBytes)
    }

    private fun read(egl: EglContexts, texture: GpuTexture): ByteArray {
        GpuFence(egl).use { it.await() }
        GLES32.glBindFramebuffer(GLES32.GL_FRAMEBUFFER, texture.framebufferId)
        GLES32.glReadBuffer(GLES32.GL_COLOR_ATTACHMENT0)
        GLES32.glBindBuffer(GLES32.GL_PIXEL_PACK_BUFFER, 0)
        GLES32.glPixelStorei(GLES32.GL_PACK_ALIGNMENT, 1)
        GLES32.glPixelStorei(GLES32.GL_PACK_ROW_LENGTH, 0)
        GLES32.glPixelStorei(GLES32.GL_PACK_SKIP_PIXELS, 0)
        GLES32.glPixelStorei(GLES32.GL_PACK_SKIP_ROWS, 0)
        val buffer = ByteBuffer.allocateDirect(texture.width * texture.height * 4)
        GLES32.glReadPixels(0, 0, texture.width, texture.height, GLES32.GL_RGBA, GLES32.GL_UNSIGNED_BYTE, buffer)
        egl.checkGl("compare presentation and encode bytes")
        return ByteArray(buffer.capacity()).also { buffer.get(it) }
    }

    private fun gpu(block: (EglContexts, GpuMemoryLedger) -> Unit) = GlThread().use { thread ->
        thread.call {
            EglContexts(thread, InstrumentationRegistry.getInstrumentation().targetContext.assets).use { egl ->
                GlCapabilities.inspect(egl).requireSupported()
                val ledger = GpuMemoryLedger()
                block(egl, ledger)
                assertEquals(0L, ledger.allocatedBytes)
                assertEquals(0, ledger.textureCount)
            }
        }
    }
}
