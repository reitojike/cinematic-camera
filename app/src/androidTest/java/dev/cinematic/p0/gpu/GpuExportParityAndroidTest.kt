package dev.cinematic.p0.gpu

import dev.cinematic.p0.gpu.constants.StageClearMistV1
import dev.cinematic.p0.gpu.constants.StageClearV1
import android.opengl.GLES32
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.gpu.analysis.GpuAnalysis
import dev.cinematic.p0.gpu.diag.GpuStageTap
import dev.cinematic.p0.gpu.export.GpuExportRenderer
import dev.cinematic.p0.gpu.r7.R7Gpu
import dev.cinematic.p0.gpu.reference.GpuReferenceImages
import dev.cinematic.p0.gpu.reference.ImageDiff
import dev.cinematic.p0.raw.*
import dev.cinematic.p0.raw.original.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.math.*

/** Actual GPU full-frame / export safety comparison. Synthetic emulator only. */
@RunWith(AndroidJUnit4::class)
class GpuExportParityAndroidTest {
    @Before fun isolatedEmulatorOnly() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish")) { "Synthetic emulator only" }
    }

    private fun gpu(action: (EglContexts) -> Unit) = GlThread().use { thread -> thread.call {
        EglContexts(thread, InstrumentationRegistry.getInstrumentation().targetContext.assets).use { egl ->
            GlCapabilities.inspect(egl).requireSupported(); action(egl)
        }
    } }

    private class ImageRaster(val image: LinearSrgbImage) : OriginalRaster {
        override val width = image.width; override val height = image.height
        override fun rows(stop: () -> Boolean, consume: (Int, FloatArray) -> Unit) {
            val row = FloatArray(width * 3)
            repeat(height) { y ->
                originalCheckpoint(stop)
                for (i in row.indices) row[i] = image[y * row.size + i]
                consume(y, row)
            }
        }
    }
    private data class Output(val pixels: IntArray, val quantiles: DoubleArray, val nearWhite: Long, val mistAuto: Double)
    private fun gains(adjustments: PhotoAdjustments): DoubleArray {
        val red = 2.0.pow(adjustments.warmth / 200.0); val blue = 2.0.pow(-adjustments.warmth / 200.0)
        val norm = .2126 * red + .7152 + .0722 * blue
        return doubleArrayOf(red / norm, 1.0 / norm, blue / norm)
    }
    private fun released(ledger: GpuMemoryLedger) {
        assertEquals(0L, ledger.allocatedBytes); assertEquals(0, ledger.textureCount)
    }

    @Test fun fullFrameAndBandsMatchAt1600x1200() = gpu { egl ->
        val input = GpuReferenceImages.create(GpuReferenceImages.Kind.MIXED, GpuReferenceImages.EXPORT_SIZE)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val log = File(checkNotNull(context.getExternalFilesDir("gpu-diagnostics")), "export-parity.log")
        log.writeText("Synthetic 1600x1200; fixed metered base EV=0; GPU-only full-frame and band comparison\n")
        for (adjustments in listOf(PhotoAdjustments.NONE, PhotoAdjustments(60.0, 1.0))) {
            for (strength in listOf(0.0, .5, 1.0)) {
                val params = GpuRenderParams(strength, adjustments)
                val label = "1600x1200 strength=$strength adjustments=$adjustments"
                val full = fullFrame(egl, input, params)
                for (rows in listOf(64, 256)) {
                    val actual = export(egl, input, params, rows)
                    assertGpuMatch(full, actual, "$label rows=$rows")
                    record(log, "$label rows=$rows GPU nearWhite full=${full.nearWhite} band=${actual.nearWhite} delta=${actual.nearWhite - full.nearWhite}")
                }
            }
        }
    }

    private fun assertGpuMatch(full: Output, band: Output, label: String) {
        assertArrayEquals("$label full/export DISPLAY8", full.pixels, band.pixels)
        assertArrayEquals("$label full/export tone", full.quantiles.map { it.toRawBits() }.toLongArray(),
            band.quantiles.map { it.toRawBits() }.toLongArray())
        assertEquals("$label full/export Mist factor", full.mistAuto.toRawBits(), band.mistAuto.toRawBits())
    }

    @Test fun gpuMismatchStillFails() {
        val reference = Output(intArrayOf(0xff000000.toInt()), doubleArrayOf(0.0, 0.0), 0, 1.0)
        val different = Output(intArrayOf(0xffffffff.toInt()), doubleArrayOf(5.0, 5.0), 1, .5)
        assertThrows(AssertionError::class.java) { assertGpuMatch(reference, different, "injected GPU mismatch") }
    }

    private fun record(log: File, line: String) {
        Log.i("GpuExportParity", line); log.appendText("$line\n")
    }

    private fun fullFrame(egl: EglContexts, input: LinearSrgbImage, params: GpuRenderParams): Output {
        // Test-only full-frame oracle needs more storage; it is never the export ledger or product path.
        val ledger = GpuMemoryLedger(512L * 1024 * 1024)
        var pixels: IntArray? = null; var quantiles: DoubleArray? = null; var count: Long? = null; var auto: Double? = null
        GpuTexture.create(egl, ledger, input.width, input.height, GpuTexture.Format.RGBA32F).use { source ->
            val rgba = ByteBuffer.allocateDirect(input.pixelCount * 16).order(ByteOrder.nativeOrder()).asFloatBuffer()
            repeat(input.pixelCount) { p -> repeat(3) { c -> rgba.put(input[p * 3 + c]) }; rgba.put(1f) }
            rgba.flip(); source.upload(rgba)
            R7Gpu(egl, ledger).use { r7 ->
                r7.render(source, params.adjustments.warmth, params.adjustments.exposureEv,
                    smoothLogTap = { _, values -> quantiles = values.copyOf() })
            }
            ConnectedStageClearGpuPipeline(source, GpuPhotoAnalysis(0.0, 0f), GpuFrameMode.FULL_RECOMPUTE).use { pipeline ->
                pipeline.render(params, { point, texture ->
                    when (point) {
                        GpuStageTap.Point.GLOW -> count = GpuAnalysis.nearWhiteCount(texture, texture.width, texture.height)
                        GpuStageTap.Point.DISPLAY8 -> pixels = readArgb(egl, texture)
                        else -> Unit
                    }
                }) { _, decisions -> auto = decisions.mistAutoStrength }
            }
        }
        released(ledger)
        return Output(checkNotNull(pixels), checkNotNull(quantiles), checkNotNull(count), checkNotNull(auto))
    }

    private fun readArgb(egl: EglContexts, texture: GpuTexture): IntArray {
        GpuFence(egl).use { it.await() }
        val bytes = ByteBuffer.allocateDirect(texture.width * texture.height * 4)
        GLES32.glBindFramebuffer(GLES32.GL_FRAMEBUFFER, texture.framebufferId)
        GLES32.glBindBuffer(GLES32.GL_PIXEL_PACK_BUFFER, 0)
        GLES32.glPixelStorei(GLES32.GL_PACK_ALIGNMENT, 1)
        GLES32.glPixelStorei(GLES32.GL_PACK_ROW_LENGTH, 0)
        GLES32.glPixelStorei(GLES32.GL_PACK_SKIP_PIXELS, 0)
        GLES32.glPixelStorei(GLES32.GL_PACK_SKIP_ROWS, 0)
        GLES32.glReadPixels(0, 0, texture.width, texture.height, GLES32.GL_RGBA, GLES32.GL_UNSIGNED_BYTE, bytes)
        egl.checkGl("read full-frame export oracle")
        return IntArray(texture.width * texture.height) { p ->
            (255 shl 24) or ((bytes.get(p * 4).toInt() and 255) shl 16) or
                ((bytes.get(p * 4 + 1).toInt() and 255) shl 8) or (bytes.get(p * 4 + 2).toInt() and 255)
        }
    }

    private fun export(egl: EglContexts, input: LinearSrgbImage, params: GpuRenderParams, rows: Int): Output {
        val ledger = GpuMemoryLedger(GpuMemoryLedger.EXPORT_BUDGET)
        val pixels = IntArray(input.pixelCount)
        var next = 0
        val result = GpuExportRenderer(egl, ledger, rows).render(ImageRaster(input), params.adjustments.exposureEv,
            gains(params.adjustments), params.strength) { y, row -> assertEquals(next++, y); row.copyInto(pixels, y * input.width) }
        assertEquals(input.height, next); released(ledger)
        assertTrue(result.peakTextureBytes <= GpuMemoryLedger.EXPORT_BUDGET)
        return Output(pixels, result.toneQuantiles, result.nearWhiteCount, result.mistAutoStrength)
    }

    @Test fun optIn12And24MpRemainBoundedAndPartitionIndependent() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("gpuLargeExport") == "true")
        gpu { egl ->
            for ((w, h) in listOf(4000 to 3000, 6000 to 4000)) {
                val input = object : OriginalRaster {
                    override val width = w; override val height = h
                    override fun rows(stop: () -> Boolean, consume: (Int, FloatArray) -> Unit) {
                        val row = FloatArray(w * 3)
                        repeat(h) { y ->
                            originalCheckpoint(stop)
                            for (x in 0 until w) {
                                row[x * 3] = ((x * 13 + y * 7) % 1024) / 256f
                                row[x * 3 + 1] = ((x * 5 + y * 17) % 1024) / 256f
                                row[x * 3 + 2] = ((x * 19 + y * 3) % 1024) / 256f
                            }
                            consume(y, row)
                        }
                    }
                }
                var firstHash: ByteArray? = null; var first: GpuExportRenderer.Result? = null
                for (rows in listOf(64, 256)) {
                    val ledger = GpuMemoryLedger(GpuMemoryLedger.EXPORT_BUDGET)
                    val digest = MessageDigest.getInstance("SHA-256")
                    val bytes = ByteBuffer.allocate(w * 4)
                    var next = 0
                    val result = GpuExportRenderer(egl, ledger, rows).render(input, 0.0, doubleArrayOf(1.0, 1.0, 1.0), 1.0) { y, row ->
                        assertEquals(next++, y); bytes.asIntBuffer().put(row); digest.update(bytes.array())
                    }
                    assertEquals(h, next); released(ledger)
                    assertTrue(result.peakTextureBytes <= GpuMemoryLedger.EXPORT_BUDGET)
                    val hash = digest.digest()
                    if (firstHash == null) { firstHash = hash; first = result } else {
                        assertArrayEquals(firstHash, hash)
                        assertArrayEquals(first!!.toneQuantiles.map { it.toRawBits() }.toLongArray(), result.toneQuantiles.map { it.toRawBits() }.toLongArray())
                        assertEquals(first!!.mistAutoStrength.toRawBits(), result.mistAutoStrength.toRawBits())
                        Log.i("GpuExportParity", "Synthetic ${w}x$h GPU nearWhite64=${first!!.nearWhiteCount} " +
                            "nearWhite256=${result.nearWhiteCount} delta=${result.nearWhiteCount - first!!.nearWhiteCount}")
                    }
                    Log.i("GpuExportParity", "Synthetic ${w}x$h rows=$rows peak=${result.peakTextureBytes} " +
                        "budget=${ledger.budgetBytes} outputRows=$next boundaries=${result.boundaryNanos}")
                }
            }
        }
    }
}
