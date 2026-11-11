package dev.cinematic.p0.gpu

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.gpu.diag.GpuStageTap
import dev.cinematic.p0.gpu.export.GpuExportRenderer
import dev.cinematic.p0.raw.original.OriginalRaster
import dev.cinematic.p0.raw.original.originalCheckpoint
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CancellationException

/** #286: 合成入力限定。帯分割、最終短帯、例外・取消時の所有権を実 renderer で確認する。 */
@RunWith(AndroidJUnit4::class)
class GpuExportRendererAndroidTest {
    @Before fun syntheticEmulatorOnly() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish")) { "Synthetic emulator only" }
    }

    private fun gpu(block: (EglContexts) -> Unit) = GlThread().use { thread ->
        thread.call {
            EglContexts(thread, InstrumentationRegistry.getInstrumentation().targetContext.assets).use { egl ->
                GlCapabilities.inspect(egl).requireSupported()
                block(egl)
                egl.checkGl("export renderer test cleanup")
            }
        }
    }

    private class Raster : OriginalRaster {
        override val width = 257
        override val height = 513
        override fun rows(stop: () -> Boolean, consume: (Int, FloatArray) -> Unit) {
            val row = FloatArray(width * 3)
            repeat(height) { y ->
                originalCheckpoint(stop)
                for (x in 0 until width) for (c in 0..2) {
                    // 再走査で同じ値を返す RGB16 相当の合成入力。実ファイル・写真は扱わない。
                    val sample = ((y * width + x) * 15485863L + c * 32452843L) and 65535L
                    row[x * 3 + c] = sample.toInt() / 65535f
                }
                consume(y, row)
            }
        }
    }

    private fun assertReleased(ledger: GpuMemoryLedger) {
        assertEquals(0L, ledger.allocatedBytes)
        assertEquals(0, ledger.textureCount)
    }

    private data class Output(val pixels: IntArray, val result: GpuExportRenderer.Result)

    private fun render(egl: EglContexts, input: OriginalRaster, bandRows: Int, strength: Double): Output {
        val ledger = GpuMemoryLedger(GpuMemoryLedger.EXPORT_BUDGET)
        val output = IntArray(input.width * input.height)
        val bands = mutableListOf<Pair<Int, Int>>()
        var nextRow = 0
        val result = GpuExportRenderer(egl, ledger, bandRows).render(input, .25, doubleArrayOf(1.02, 1.0, .98), strength,
            tap = { point, origin, texture ->
                assertEquals(GpuStageTap.Point.MIST, point)
                bands += origin to texture.height
            }) { y, row ->
            assertEquals(nextRow++, y)
            assertEquals(input.width, row.size)
            assertTrue(row.all { it ushr 24 == 255 })
            row.copyInto(output, y * input.width)
        }
        assertEquals(input.height, nextRow)
        assertEquals((0 until input.height step bandRows).map { it to minOf(bandRows, input.height - it) }, bands)
        assertEquals(1, bands.last().second)
        assertReleased(ledger)
        assertTrue(result.peakTextureBytes > 0 && result.peakTextureBytes <= GpuMemoryLedger.EXPORT_BUDGET)
        assertTrue(result.boundaryNanos.keys.containsAll(listOf("B1", "B1_QUANTILES", "B4", "FINAL")))
        return Output(output, result)
    }

    @Test fun finalArgbAndGlobalScalarsDoNotDependOn64Or256RowBands() = gpu { egl ->
        val input = Raster()
        for (strength in listOf(0.0, .75)) {
            val a = render(egl, input, 64, strength)
            val b = render(egl, input, 256, strength)
            assertArrayEquals("DISPLAY8 strength=$strength", a.pixels, b.pixels)
            assertTrue("Synthetic output must not be constant", a.pixels.toSet().size > 1)
            assertArrayEquals(a.result.toneQuantiles.map { it.toRawBits() }.toLongArray(),
                b.result.toneQuantiles.map { it.toRawBits() }.toLongArray())
            assertEquals(a.result.nearWhiteCount, b.result.nearWhiteCount)
            assertEquals(a.result.mistAutoStrength.toRawBits(), b.result.mistAutoStrength.toRawBits())
        }
    }

    @Test fun consumerFailureAndCancellationReleaseAllTexturesAndPermitSameRendererRetry() = gpu { egl ->
        val input = Raster()
        val ledger = GpuMemoryLedger(GpuMemoryLedger.EXPORT_BUDGET)
        val renderer = GpuExportRenderer(egl, ledger, 64)
        val failure = IllegalStateException("synthetic row consumer failure")
        var delivered = 0
        assertSame(failure, assertThrows(IllegalStateException::class.java) {
            renderer.render(input, .25, doubleArrayOf(1.02, 1.0, .98), .75) { y, _ ->
                delivered++
                if (y == 70) throw failure
            }
        })
        assertEquals(71, delivered)
        assertReleased(ledger)

        fun retry(): IntArray {
            val output = IntArray(input.width * input.height)
            var nextRow = 0
            renderer.render(input, .25, doubleArrayOf(1.02, 1.0, .98), .75) { y, row ->
                assertEquals(nextRow++, y)
                row.copyInto(output, y * input.width)
            }
            assertEquals(input.height, nextRow)
            assertReleased(ledger)
            return output
        }
        val afterFailure = retry()
        var stopped = false
        delivered = 0
        assertThrows(CancellationException::class.java) {
            renderer.render(input, .25, doubleArrayOf(1.02, 1.0, .98), .75, stop = { stopped }) { y, _ ->
                delivered++
                if (y == 70) stopped = true
            }
        }
        assertEquals(71, delivered)
        assertReleased(ledger)
        assertArrayEquals(afterFailure, retry())
    }

    @Test fun explicitSmallBudgetFailsWithoutLeakingPartialFields() = gpu { egl ->
        // B1 の小さな場は確保できるが、最初の 257x64 RGBA32F 帯は確保できない予算。
        val ledger = GpuMemoryLedger(128L * 1024)
        val renderer = GpuExportRenderer(egl, ledger, 64)
        repeat(2) {
            var delivered = 0
            assertThrows(GpuMemoryBudgetException::class.java) {
                renderer.render(Raster(), 0.0, doubleArrayOf(1.0, 1.0, 1.0), .75) { _, _ -> delivered++ }
            }
            assertEquals(0, delivered)
            assertTrue("Exercise cleanup after a successful partial allocation", ledger.peakAllocatedBytes > 0)
            assertTrue(ledger.peakAllocatedBytes <= ledger.budgetBytes)
            assertReleased(ledger)
        }
    }

    @Test fun cancellationAtEveryBoundaryReleasesTexturesAndPermitsFinalRetry() = gpu { egl ->
        val input = Raster()
        val ledger = GpuMemoryLedger(GpuMemoryLedger.EXPORT_BUDGET)
        val renderer = GpuExportRenderer(egl, ledger, 64)
        for (target in listOf("B1", "B1_QUANTILES", "B2", "B3", "B4", "B5", "FINAL")) {
            var stopped = false
            var targetBands = 0
            var delivered = 0
            var completed = false
            assertThrows("Cancel at $target", CancellationException::class.java) {
                renderer.render(input, .25, doubleArrayOf(1.02, 1.0, .98), .75,
                    stop = { stopped },
                    onBandComplete = { boundary, origin, rows ->
                        if (boundary == target) {
                            assertEquals(targetBands * 64, origin)
                            assertEquals(64, rows)
                            targetBands++
                            if (origin == 64) stopped = true
                        }
                    }) { y, row ->
                    assertEquals(delivered++, y)
                    assertEquals(input.width, row.size)
                }
                completed = true
            }
            assertTrue("Reached the second band of $target", stopped)
            assertEquals("No later band is submitted in $target", 2, targetBands)
            assertFalse("Cancelled $target must not return a result", completed)
            assertEquals("No complete output after cancellation at $target", if (target == "FINAL") 128 else 0, delivered)
            assertReleased(ledger)
            egl.checkGl("cancel export boundary $target")
        }
        var delivered = 0
        val result = renderer.render(input, .25, doubleArrayOf(1.02, 1.0, .98), .75) { y, row ->
            assertEquals(delivered++, y)
            assertEquals(input.width, row.size)
            assertTrue(row.all { it ushr 24 == 255 })
        }
        assertEquals(input.height, delivered)
        assertEquals(setOf("B1", "B1_QUANTILES", "B2", "B3", "B4", "B5", "FINAL"), result.boundaryNanos.keys)
        assertReleased(ledger)
    }
}
