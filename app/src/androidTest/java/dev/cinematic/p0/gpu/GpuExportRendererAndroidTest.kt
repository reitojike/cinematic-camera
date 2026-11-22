package dev.cinematic.p0.gpu

import android.graphics.Bitmap
import android.opengl.GLES32
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.gpu.diag.GpuStageTap
import dev.cinematic.p0.gpu.export.GpuExportBands
import dev.cinematic.p0.gpu.export.GpuExportRenderer
import dev.cinematic.p0.gpu.export.GpuExportRgb16
import dev.cinematic.p0.gpu.export.GpuExportTimings
import dev.cinematic.p0.raw.original.OriginalRaster
import dev.cinematic.p0.raw.original.OriginalRasterFile
import dev.cinematic.p0.raw.original.originalCheckpoint
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

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

    private class Raster(override val width: Int = 257, override val height: Int = 513) : OriginalRaster {
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

    private fun rgb16Sample(pixel: Int, channel: Int): Int =
        (pixel * 15485863 + channel * 32452843) and 65535

    private fun withRgb16Raster(rows: Int, width: Int = 257, block: (OriginalRasterFile) -> Unit) {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val file = File.createTempFile("synthetic-rgb16", ".bin", cache)
        try {
            val input = OriginalRasterFile(file, width, rows, 2)
            val row = ByteBuffer.allocate(width * 6).order(ByteOrder.LITTLE_ENDIAN)
            file.outputStream().use { stream ->
                repeat(rows) { y ->
                    row.clear()
                    repeat(width) { x ->
                        repeat(3) { channel -> row.putShort(rgb16Sample(y * width + x, channel).toShort()) }
                    }
                    stream.write(row.array())
                }
            }
            block(input)
        } finally { file.delete() }
    }

    @Test fun rgb16GpuDecodeMatchesEveryCpuBit() = gpu { egl ->
        val ledger = GpuMemoryLedger(GpuMemoryLedger.EXPORT_BUDGET)
        val side = GpuExportRgb16.LUT_SIDE
        GpuTexture.create(egl, ledger, side, side, GpuTexture.Format.R32UI).use { lut ->
            lut.upload(GpuExportRgb16.lutBits())
            for ((imageRows, bandRows) in listOf(257 to 64, 257 to 256, 256 to 256, 1 to 64)) {
                withRgb16Raster(imageRows) { input ->
                    val width = input.width
                    GpuExportBands.forEach(input, bandRows, { false }) { origin, rows, data ->
                        GpuTexture.create(egl, ledger, width, rows, GpuTexture.Format.RGB16UI).use { raw ->
                            GpuTexture.create(egl, ledger, width, rows, GpuTexture.Format.RGBA32F).use { output ->
                                raw.upload(data)
                                GpuExportRgb16.decode(egl, raw, lut, output)
                                val rgba = ByteBuffer.allocateDirect(width * rows * 16)
                                    .order(ByteOrder.nativeOrder()).asFloatBuffer()
                                GLES32.glBindFramebuffer(GLES32.GL_FRAMEBUFFER, output.framebufferId)
                                GLES32.glReadBuffer(GLES32.GL_COLOR_ATTACHMENT0)
                                GLES32.glBindBuffer(GLES32.GL_PIXEL_PACK_BUFFER, 0)
                                GLES32.glPixelStorei(GLES32.GL_PACK_ALIGNMENT, 1)
                                GLES32.glPixelStorei(GLES32.GL_PACK_ROW_LENGTH, 0)
                                GLES32.glPixelStorei(GLES32.GL_PACK_SKIP_PIXELS, 0)
                                GLES32.glPixelStorei(GLES32.GL_PACK_SKIP_ROWS, 0)
                                GLES32.glReadPixels(0, 0, width, rows, GLES32.GL_RGBA, GLES32.GL_FLOAT, rgba)
                                egl.checkGl("read RGB16 decoded RGBA32F")
                                for (y in 0 until rows) for (x in 0 until width) for (c in 0..3) {
                                    val sample = if (c < 3) rgb16Sample((origin + y) * width + x, c) else 65535
                                    val expected = (sample / 65535f).toRawBits()
                                    val actual = rgba.get().toRawBits()
                                    if (actual != expected) {
                                        fail("RGB16 decode band=$origin xy=($x,$y) channel=$c sample=$sample " +
                                            "expected=0x${expected.toString(16)} actual=0x${actual.toString(16)}")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        assertReleased(ledger)
    }

    @Test fun rgb16AndCpuFloatInputsProduceIdenticalFinalBitmaps() = gpu { egl ->
        withRgb16Raster(257) { rgb16 ->
            val cpu = object : OriginalRaster by rgb16 {}
            val ledger = GpuMemoryLedger(GpuMemoryLedger.EXPORT_BUDGET)
            fun bitmapPixels(input: OriginalRaster, bandRows: Int, strength: Double, byRow: Boolean): IntArray {
                val width = input.width
                val height = input.height
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.setHasAlpha(false)
                    val renderer = GpuExportRenderer(egl, ledger, bandRows)
                    val gains = doubleArrayOf(1.02, 1.0, .98)
                    if (byRow) {
                        renderer.render(input, .25, gains, strength) { y, argb ->
                            bitmap.setPixels(argb, 0, width, 0, y, width, 1)
                        }
                    } else {
                        renderer.renderBands(input, .25, gains, strength) { origin, rows, argb ->
                            bitmap.setPixels(argb, 0, width, 0, origin, width, rows)
                        }
                    }
                    assertReleased(ledger)
                    return IntArray(width * height).also { bitmap.getPixels(it, 0, width, 0, 0, width, height) }
                } finally { bitmap.recycle() }
            }
            for (bandRows in listOf(64, 256)) for (strength in listOf(0.0, .75)) {
                val reference = bitmapPixels(cpu, bandRows, strength, byRow = true)
                assertArrayEquals("Float bands: bandRows=$bandRows strength=$strength",
                    reference, bitmapPixels(cpu, bandRows, strength, byRow = false))
                assertArrayEquals("RGB16 bands: bandRows=$bandRows strength=$strength",
                    reference, bitmapPixels(rgb16, bandRows, strength, byRow = false))
            }
        }
    }

    @Test fun optIn12And24MpRgb16ExportsStayWithinTextureBudget() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("gpuLargeExport") == "true")
        gpu { egl ->
            for ((width, height) in listOf(4000 to 3000, 6000 to 4000)) {
                withRgb16Raster(height, width) { input ->
                    val budget = 192L * 1024 * 1024
                    assertEquals(budget, GpuMemoryLedger.EXPORT_BUDGET)
                    val ledger = GpuMemoryLedger(budget)
                    var nextRow = 0
                    val result = GpuExportRenderer(egl, ledger, 256)
                        .renderBands(input, 0.0, doubleArrayOf(1.0, 1.0, 1.0), 1.0) { origin, rows, _ ->
                            assertEquals(nextRow, origin)
                            assertEquals(minOf(256, height - origin), rows)
                            nextRow += rows
                        }
                    assertEquals(height, nextRow)
                    assertEquals(result.peakTextureBytes, ledger.peakAllocatedBytes)
                    assertTrue("${width}x$height peak=${ledger.peakAllocatedBytes}",
                        ledger.peakAllocatedBytes in 1L..budget)
                    assertReleased(ledger)
                    assertFalse(input.hasPendingOwners)
                    Log.i("GpuExportRendererTest", "Synthetic RGB16 ${width}x$height " +
                        "peak=${ledger.peakAllocatedBytes} budget=$budget outputRows=$nextRow")
                }
            }
        }
    }

    @Test fun rgb16SmallBudgetFailureReleasesLutAndPartialFields() = gpu { egl ->
        withRgb16Raster(257) { input ->
            val lutBytes = GpuExportRgb16.LUT_SIDE.toLong() * GpuExportRgb16.LUT_SIDE * Int.SIZE_BYTES
            val ledger = GpuMemoryLedger(lutBytes + 128L * 1024)
            val renderer = GpuExportRenderer(egl, ledger, 64)
            repeat(2) {
                assertThrows(GpuMemoryBudgetException::class.java) {
                    renderer.renderBands(input, 0.0, doubleArrayOf(1.0, 1.0, 1.0), .75) { _, _, _ -> fail() }
                }
                assertTrue(ledger.peakAllocatedBytes > lutBytes)
                assertTrue(ledger.peakAllocatedBytes <= ledger.budgetBytes)
                assertReleased(ledger)
                assertFalse(input.hasPendingOwners)
            }
        }
    }

    @Test fun rgb16FinalCancellationReleasesTexturesAndAllowsSameRendererRetry() = gpu { egl ->
        withRgb16Raster(257) { input ->
            val ledger = GpuMemoryLedger(GpuMemoryLedger.EXPORT_BUDGET)
            val renderer = GpuExportRenderer(egl, ledger, 64)
            val gains = doubleArrayOf(1.0, 1.0, 1.0)
            val stopped = AtomicBoolean()
            var deliveredRows = 0
            assertThrows(CancellationException::class.java) {
                renderer.renderBands(input, 0.0, gains, .75, stop = stopped::get) { origin, rows, _ ->
                    assertEquals(deliveredRows, origin)
                    assertEquals(64, rows)
                    deliveredRows += rows
                    stopped.set(true)
                }
            }
            assertEquals(64, deliveredRows)
            assertReleased(ledger)
            assertFalse(input.hasPendingOwners)

            var nextRow = 0
            renderer.renderBands(input, 0.0, gains, .75) { origin, rows, _ ->
                assertEquals(nextRow, origin)
                nextRow += rows
            }
            assertEquals(input.height, nextRow)
            assertReleased(ledger)
            assertFalse(input.hasPendingOwners)
        }
    }

    private fun assertReleased(ledger: GpuMemoryLedger) {
        assertEquals(0L, ledger.allocatedBytes)
        assertEquals(0, ledger.textureCount)
    }

    private data class Output(val pixels: IntArray, val result: GpuExportRenderer.Result)

    private fun render(egl: EglContexts, input: OriginalRaster, bandRows: Int, strength: Double, reuseTextures: Boolean = true): Output {
        val ledger = GpuMemoryLedger(GpuMemoryLedger.EXPORT_BUDGET)
        val output = IntArray(input.width * input.height)
        val bands = mutableListOf<Pair<Int, Int>>()
        var nextRow = 0
        val result = GpuExportRenderer(egl, ledger, bandRows, reuseTextures).render(input, .25, doubleArrayOf(1.02, 1.0, .98), strength,
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

    /** Opt-in diagnostic only: no performance threshold and no real-photo/device inputs. */
    @Test fun optInTimingCollectionOverheadSyntheticPairs() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("timingCost") == "true")
        gpu { egl ->
            val input = Raster(1600, 1200)
            val gains = doubleArrayOf(1.02, 1.0, .98)
            fun run(enabled: Boolean): Pair<Output, Long> {
                val ledger = GpuMemoryLedger(GpuMemoryLedger.EXPORT_BUDGET)
                val renderer = GpuExportRenderer(egl, ledger, 256)
                val pixels = IntArray(input.width * input.height)
                val timings = if (enabled) GpuExportTimings() else null
                var rows = 0
                // Renderer wall time includes synthetic input generation, GL work, readback,
                // row copy and renderer cleanup. Excludes setup, assertions, drain and logging.
                val start = System.nanoTime()
                val result = renderer.render(input, .25, gains, .75, timings = timings) { y, row ->
                    row.copyInto(pixels, y * input.width)
                    rows++
                }
                val nanos = System.nanoTime() - start
                assertEquals(input.height, rows)
                assertReleased(ledger)
                assertTrue(result.peakTextureBytes <= GpuMemoryLedger.EXPORT_BUDGET)
                return Output(pixels, result) to nanos
            }
            fun equal(a: Output, b: Output) {
                assertArrayEquals(a.pixels, b.pixels)
                assertArrayEquals(a.result.toneQuantiles.map { it.toRawBits() }.toLongArray(),
                    b.result.toneQuantiles.map { it.toRawBits() }.toLongArray())
                assertEquals(a.result.nearWhiteCount, b.result.nearWhiteCount)
                assertEquals(a.result.mistAutoStrength.toRawBits(), b.result.mistAutoStrength.toRawBits())
                assertEquals(a.result.peakTextureBytes, b.result.peakTextureBytes)
                assertEquals(a.result.boundaryNanos.keys, b.result.boundaryNanos.keys)
            }
            // One full warm-up per condition, same EGL/shader cache for all ten renders.
            val reference = run(false).first
            equal(reference, run(true).first)
            repeat(4) { pair ->
                val order = if (pair % 2 == 0) listOf(false, true) else listOf(true, false)
                for ((position, enabled) in order.withIndex()) {
                    val (output, nanos) = run(enabled)
                    equal(reference, output)
                    Log.i("GpuTimingOverhead", "synthetic_timing_overhead_v1 width=1600 height=1200 " +
                        "band_rows=256 strength=0.75 pair=$pair position=$position " +
                        "timings=${if (enabled) "on" else "off"} renderer_wall_nanos=$nanos")
                }
            }
        }
    }
    @Test fun pooledAndFreshStorageAreBitIdenticalIncludingTheShortLastBand() = gpu { egl ->
        for (bandRows in listOf(64, 256)) for (strength in listOf(0.0, .75)) {
            val fresh = render(egl, Raster(), bandRows, strength, reuseTextures = false)
            val pooled = render(egl, Raster(), bandRows, strength, reuseTextures = true)
            assertArrayEquals(fresh.pixels, pooled.pixels)
            assertArrayEquals(fresh.result.toneQuantiles.map { it.toRawBits() }.toLongArray(),
                pooled.result.toneQuantiles.map { it.toRawBits() }.toLongArray())
            assertEquals(fresh.result.nearWhiteCount, pooled.result.nearWhiteCount)
            assertEquals(fresh.result.mistAutoStrength.toRawBits(), pooled.result.mistAutoStrength.toRawBits())
        }
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
            Log.i("GpuExportRendererTest", "strength=$strength nearWhite64=${a.result.nearWhiteCount} " +
                "nearWhite256=${b.result.nearWhiteCount} delta=${b.result.nearWhiteCount - a.result.nearWhiteCount}")
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
