package dev.cinematic.p0.gpu

import android.opengl.GLES32
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.gpu.analysis.GpuAnalysis
import dev.cinematic.p0.gpu.export.GpuExportAnalysis
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException
import kotlin.math.ceil
import kotlin.math.floor

/** #286: 合成入力のみ。Android の帯累積と所有権を検証し、実機性能の合格とは扱わない。 */
@RunWith(AndroidJUnit4::class)
class GpuExportAnalysisAndroidTest {
    @Before fun syntheticEmulatorOnly() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish")) { "Synthetic emulator only" }
    }

    private fun gpu(block: (EglContexts, GpuMemoryLedger) -> Unit) = GlThread().use { thread ->
        thread.call {
            EglContexts(thread, InstrumentationRegistry.getInstrumentation().targetContext.assets).use { egl ->
                GlCapabilities.inspect(egl).requireSupported()
                val ledger = GpuMemoryLedger()
                block(egl, ledger)
                assertEquals(0L, ledger.allocatedBytes)
                assertEquals(0, ledger.textureCount)
                egl.checkGl("export analysis test cleanup")
            }
        }
    }

    private fun texture(egl: EglContexts, ledger: GpuMemoryLedger, width: Int, height: Int, values: FloatArray): GpuTexture {
        val result = GpuTexture.create(egl, ledger, width, height, GpuTexture.Format.R32F)
        try {
            result.upload(ByteBuffer.allocateDirect(values.size * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
                .asFloatBuffer().apply { put(values); flip() })
            return result
        } catch (failure: Throwable) { result.close(); throw failure }
    }

    private fun quantiles(egl: EglContexts, ledger: GpuMemoryLedger, width: Int, values: FloatArray,
        bandRows: Int): DoubleArray {
        val height = values.size / width
        var scans = 0
        val result = GpuExportAnalysis.quantiles(egl, width, height, { false }) { accept ->
            scans++
            var y = 0
            while (y < height) {
                val rows = minOf(bandRows, height - y)
                texture(egl, ledger, width, rows, values.copyOfRange(y * width, (y + rows) * width)).use(accept)
                y += rows
            }
        }
        assertEquals(2, scans)
        return result
    }

    @Test fun twoScansMatchFullFrameAndSortedReferenceExactly() = gpu { egl, ledger ->
        val cases = listOf(
            1 to floatArrayOf(1f),
            1 to floatArrayOf(-0f, 0f),
            17 to FloatArray(17 * 65) { -.25f },
            1 to floatArrayOf(-Float.MAX_VALUE, -1f, -Float.MIN_VALUE, -0f, 0f, Float.MIN_VALUE, 1f, Float.MAX_VALUE),
            257 to FloatArray(257 * 513) { p -> ((p * 15485863L % 32452843) / 32452843.0 * 40 - 20).toFloat() },
        )
        for ((width, values) in cases) {
            val height = values.size / width
            val sorted = values.sortedArray()
            val full = texture(egl, ledger, width, height, values).use {
                GpuAnalysis.localToneQuantiles(it, width, height)
            }
            for (rows in listOf(3, 64, 256)) {
                val actual = quantiles(egl, ledger, width, values, rows)
                for ((i, q) in listOf(.5, .92).withIndex()) {
                    val rank = (values.size - 1) * q
                    val lower = sorted[floor(rank).toInt()].toDouble()
                    val upper = sorted[ceil(rank).toInt()].toDouble()
                    val expected = lower + (upper - lower) * (rank - floor(rank))
                    assertEquals("sorted ${width}x$height rows=$rows q=$q", expected.toRawBits(), actual[i].toRawBits())
                    assertEquals("full-frame ${width}x$height rows=$rows q=$q", full[i].toRawBits(), actual[i].toRawBits())
                }
            }
        }
    }

    @Test fun nonFiniteAndIncompleteOrExtraBandsAreRejected() = gpu { egl, ledger ->
        for (invalid in floatArrayOf(Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) {
                quantiles(egl, ledger, 1, floatArrayOf(0f, invalid), 1)
            }
        }
        texture(egl, ledger, 1, 1, floatArrayOf(1f)).use { input ->
            assertThrows(IllegalStateException::class.java) {
                GpuExportAnalysis.quantiles(egl, 1, 2, { false }) { it(input) }
            }
            assertThrows(IllegalArgumentException::class.java) {
                GpuExportAnalysis.quantiles(egl, 1, 1, { false }) { it(input); it(input) }
            }
            assertThrows(IllegalArgumentException::class.java) {
                GpuExportAnalysis.quantiles(egl, 2, 1, { false }) { it(input) }
            }
            input.requireOpen()
        }
    }

    @Test fun cancellationAndProducerFailureReleaseBufferAndAllowRetry() = gpu { egl, ledger ->
        var enumerated = false
        assertThrows(CancellationException::class.java) {
            GpuExportAnalysis.quantiles(egl, 1, 1, { true }) { enumerated = true }
        }
        assertFalse(enumerated)
        texture(egl, ledger, 1, 1, floatArrayOf(2f)).use { input ->
            var stop = false
            assertThrows(CancellationException::class.java) {
                GpuExportAnalysis.quantiles(egl, 1, 2, { stop }) { accept ->
                    accept(input)
                    stop = true
                    accept(input)
                }
            }
            val failure = IllegalStateException("synthetic band producer failure")
            assertSame(failure, assertThrows(IllegalStateException::class.java) {
                GpuExportAnalysis.quantiles(egl, 1, 2, { false }) { accept -> accept(input); throw failure }
            })
            input.requireOpen()
            assertEquals(0, IntArray(1).also {
                GLES32.glGetIntegerv(GLES32.GL_SHADER_STORAGE_BUFFER_BINDING, it, 0)
            }[0])
            assertArrayEquals(doubleArrayOf(2.0, 2.0),
                GpuExportAnalysis.quantiles(egl, 1, 1, { false }) { it(input) }, 0.0)
        }
    }

    @Test fun changedTargetCountsBetweenScansAreRejected() = gpu { egl, ledger ->
        var scans = 0
        assertThrows(IllegalStateException::class.java) {
            GpuExportAnalysis.quantiles(egl, 1, 1, { false }) { accept ->
                val value = if (scans++ == 0) 1f else 2f
                texture(egl, ledger, 1, 1, floatArrayOf(value)).use(accept)
            }
        }
    }
}
