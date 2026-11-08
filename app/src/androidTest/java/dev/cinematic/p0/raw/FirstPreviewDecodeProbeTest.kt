package dev.cinematic.p0.raw

import android.graphics.Bitmap
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.raw.original.NativeOriginalRaster
import dev.cinematic.p0.raw.original.OriginalFields
import dev.cinematic.p0.raw.original.OriginalRasterFile
import dev.cinematic.p0.testing.writeSyntheticTrialDng
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 製品非接続。明示起動した合成入力のデコード比較のみ。初回UI速度ではない。 */
@RunWith(AndroidJUnit4::class)
class FirstPreviewDecodeProbeTest {
    @Test fun compareExistingHalfDecodeWithOriginalReduction() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("firstPreviewProbe") == "true")
        val sizes = if (arguments.getString("firstPreview12Mp") == "true")
            listOf(2048 to 1536, 4000 to 3000) else listOf(2048 to 1536)
        for ((width, height) in sizes) for (orientation in listOf(1, 6)) for (smooth in listOf(true, false)) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val root = File.createTempFile("first-preview-", ".tmp", context.cacheDir)
            check(root.delete() && root.mkdir())
            val input = File(root, "synthetic.dng")
            val raster = File(root, "linear.rgb16")
            try {
                input.outputStream().use { output ->
                    writeSyntheticTrialDng(output, width, height, orientation) { x, y ->
                        if (smooth) 64 + (3500L * x / (width - 1)).toInt()
                        else 164 + (x * 65 + y * 25) % 3500
                    }
                }
                val fingerprint = digest(input)
                for (reverse in listOf(false, true)) {
                    lateinit var half: Timed
                    lateinit var full: Timed
                    if (reverse) { full = full(input, raster); half = half(input) }
                    else { half = half(input); full = full(input, raster) }
                    assertEquals(full.image.width, half.image.width)
                    assertEquals(full.image.height, half.image.height)
                    var maximum = 0
                    var changedPixels = 0
                    repeat(full.image.pixelCount) { pixel ->
                        var changed = false
                        repeat(3) { channel ->
                            val i = pixel * 3 + channel
                            val delta = abs(srgb(full.image[i]) - srgb(half.image[i]))
                            maximum = max(maximum, delta)
                            changed = changed || delta != 0
                        }
                        if (changed) changedPixels++
                    }
                    Log.i("FirstPreviewProbe", "synthetic=${width}x$height orientation=$orientation smooth=$smooth " +
                        "reverse=$reverse halfMs=${half.ms} fullReduceMs=${full.ms} " +
                        "max8=$maximum changedFraction=${changedPixels.toDouble() / full.image.pixelCount} " +
                        "outputBytes=${full.image.pixelCount.toLong() * 12} " +
                        "originalRasterBytes=${width.toLong() * height * 6}")
                    val name = "synthetic-${width}x$height-$orientation-$smooth-$reverse"
                    val dir = outputDirectory()
                    File(dir, "$name.json").writeText(JSONObject()
                        .put("halfMs", half.ms).put("fullReduceMs", full.ms)
                        .put("max8", maximum).put("changedFraction", changedPixels.toDouble() / full.image.pixelCount)
                        .put("outputBytes", full.image.pixelCount.toLong() * 12)
                        .put("originalRasterBytes", width.toLong() * height * 6).toString())
                    if (!reverse) { png(File(dir, "$name-half.png"), half.image); png(File(dir, "$name-full.png"), full.image) }
                    assertArrayEquals(fingerprint, digest(input))
                    assertFalse(raster.exists())
                }
                val cancelled = CancellationSignal().apply { cancel() }
                val cancellation = assertThrows(java.io.IOException::class.java) { half(input, cancelled) }
                assertEquals("DNG_CANCELLED", cancellation.message)
                assertTrue(half(input).image.pixelCount > 0)
                assertArrayEquals(fingerprint, digest(input))
            } finally {
                // このfixtureが作成した既知の2ファイル以外は削除しない。
                if (raster.exists()) check(raster.delete())
                if (input.exists()) check(input.delete())
                check(root.delete())
            }
        }
    }

    private data class Timed(val image: LinearSrgbImage, val ms: Double)
    private fun half(input: File, signal: CancellationSignal = CancellationSignal()): Timed {
        val start = SystemClock.elapsedRealtimeNanos()
        val decoded = ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY).use {
            NativeDngDecoder.decode(it.fd, 1024, signal)
        }
        return Timed(LinearSrgbImage(decoded.width, decoded.height, decoded.pixels),
            (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0)
    }

    private fun full(input: File, output: File): Timed {
        check(output.createNewFile())
        val start = SystemClock.elapsedRealtimeNanos()
        var rows: OriginalRasterFile? = null
        try {
            val metadata = ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY).use { source ->
                ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_READ_WRITE).use { sink ->
                    JSONObject(NativeOriginalRaster.decode24(source.fd, sink.fd, CancellationSignal()))
                }
            }
            val width = metadata.getInt("width"); val height = metadata.getInt("height")
            assertEquals(0, metadata.getInt("halfSize"))
            assertEquals("linear-sRGB-D65", metadata.getString("color"))
            assertEquals(width.toLong() * height * 6, output.length())
            rows = OriginalRasterFile(output, width, height, 2)
            val scale = min(1.0, 1024.0 / max(width, height))
            val image = OriginalFields.reduce(rows, floor(width * scale).toInt(),
                floor(height * scale).toInt(), 3, { false }) { rgb, out -> rgb.copyInto(out) }.reducedRgb()
            return Timed(image, (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0)
        } finally {
            rows?.closePending()
            check(output.delete())
        }
    }

    private fun srgb(linear: Float): Int {
        val x = linear.toDouble().coerceIn(0.0, 1.0)
        return ((if (x <= .0031308) 12.92 * x else 1.055 * x.pow(1.0 / 2.4) - .055) * 255).roundToInt()
    }
    private fun outputDirectory(): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val configured = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        val root = configured?.let(::File) ?: File(requireNotNull(context.externalMediaDirs.firstOrNull()), "additional_test_output")
        return File(root, "first-preview-probe").apply { check(isDirectory || mkdirs()) }
    }
    private fun png(file: File, image: LinearSrgbImage) {
        val pixels = IntArray(image.pixelCount) { i ->
            (255 shl 24) or (srgb(image[i * 3]) shl 16) or (srgb(image[i * 3 + 1]) shl 8) or srgb(image[i * 3 + 2])
        }
        val bitmap = Bitmap.createBitmap(pixels, image.width, image.height, Bitmap.Config.ARGB_8888)
        try { file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
    }
    private fun digest(file: File): ByteArray {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = stream.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
        }
        return hash.digest()
    }
}
