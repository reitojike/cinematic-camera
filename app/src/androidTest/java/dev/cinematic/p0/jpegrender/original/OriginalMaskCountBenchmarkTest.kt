package dev.cinematic.p0.jpegrender.original

import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.raw.original.OriginalMask
import dev.cinematic.p0.raw.original.OriginalRasterFile
import dev.cinematic.p0.raw.original.countOriginalMaskPixels
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated logical RGB-read elimination measurement, not a photo/DNG/Look or physical-device benchmark. */
@RunWith(AndroidJUnit4::class)
class OriginalMaskCountBenchmarkTest {
    @Test fun compareRgbReadAndMaskOnlyOnFixedSyntheticFloatRasters() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(app.packageName == "dev.cinematic.p0.original24probe")
        assumeTrue(args.getString("original24") == "true")
        assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val runId = requireNotNull(args.getString("runId")).also { require(it.matches(Regex("[a-f0-9]{32}"))) }
        val sizes = when (args.getString("maskCountSize") ?: "BOTH") {
            "12MP" -> listOf(4000 to 3000)
            "24MP" -> listOf(6000 to 4000)
            "BOTH" -> listOf(4000 to 3000, 6000 to 4000)
            else -> error("Unknown fixed mask-count size")
        }
        val masks = when (args.getString("maskCountMask") ?: "BOTH") {
            "NONE" -> listOf("NONE")
            "MIXED" -> listOf("MIXED")
            "BOTH" -> listOf("NONE", "MIXED")
            else -> error("Unknown fixed mask pattern")
        }
        val directory = File(app.noBackupFilesDir, "original-mask-count-$runId")
        check(!directory.exists() && directory.mkdir())
        val reportFile = File(app.filesDir, "original-mask-count-$runId.json")
        check(reportFile.createNewFile())
        val samples = JSONArray()
        val fixtures = JSONArray()
        val report = JSONObject().put("runId", runId).put("applicationId", app.packageName)
            .put("scope", "synthetic-float-raster-mask-count-only-no-photo-no-dng-no-look-no-device-speed-claim")
            .put("status", "STARTED").put("complete", false)
            .put("byteAccounting", "logical complete Float32 RGB rows consumed; physical disk/cache IO is not measured")
            .put("cachePolicy", "no page-cache eviction or cold-disk claim; alternate AB/BA in each pair")
            .put("fixtures", fixtures).put("samples", samples)
        reportFile.writeText(report.toString(2))
        try {
            for ((width, height) in sizes) {
                val file = File(directory, "synthetic-${width}x$height.f32")
                val fixtureStart = SystemClock.elapsedRealtimeNanos()
                writeSyntheticFloatRows(file, width, height)
                val expectedBytes = width.toLong() * height * 3 * 4
                assertEquals(expectedBytes, file.length())
                if (width == 6000) assertEquals(288_000_000L, expectedBytes)
                fixtures.put(JSONObject().put("width", width).put("height", height)
                    .put("dataFileBytes", file.length()).put("generationElapsedNs", SystemClock.elapsedRealtimeNanos() - fixtureStart)
                    .put("maximumGenerationBufferBytes", width * 3 * 4))
                val raster = OriginalRasterFile(file, width, height)
                try {
                    for (pattern in masks) {
                        val mask = if (pattern == "NONE") OriginalMask.NONE else OriginalMask { y, row ->
                            row.indices.forEach { x -> row[x] = when ((y.toLong() * width + x) % 4) {
                                0L -> 0f; 1L -> .5f; 2L -> Float.fromBits(.5f.toRawBits() + 1); else -> 1f
                            } }
                        }
                        val expectedCount = if (pattern == "NONE") 0 else width * height / 2
                        repeat(3) { pair ->
                            val routes = if (pair % 2 == 0) listOf("RGB_AND_MASK", "MASK_ONLY") else listOf("MASK_ONLY", "RGB_AND_MASK")
                            for ((order, route) in routes.withIndex()) {
                                val sample = JSONObject().put("width", width).put("height", height)
                                    .put("pattern", pattern).put("pair", pair).put("order", order).put("route", route)
                                    .put("dataFileBytes", expectedBytes).put("status", "STARTED")
                                samples.put(sample)
                                measure(app, raster, mask, expectedCount, route, sample)
                                reportFile.writeText(report.toString(2))
                            }
                        }
                    }
                } finally { raster.closePending() }
            }
            report.put("status", "success").put("complete", true)
        } catch (error: Throwable) {
            report.put("status", "failure").put("code", error.javaClass.simpleName)
            throw error
        } finally { reportFile.writeText(report.toString(2)) }
        // Synthetic create-only raster/report paths are retained for the owning test run, including failures.
    }

    private fun measure(context: Context, raster: OriginalRasterFile, mask: OriginalMask, expectedCount: Int,
        route: String, sample: JSONObject) {
        val metrics = OriginalProbeMetrics(context)
        var failure: Throwable? = null
        var maskRows = 0
        var rgbRows = 0
        var rgbBytes = 0L
        val observedMask = OriginalMask { y, row ->
            assertEquals(maskRows, y)
            maskRows++
            mask.row(y, row)
        }
        try {
            metrics.stage("before")
            val start = SystemClock.elapsedRealtimeNanos()
            val count = if (route == "RGB_AND_MASK") {
                val row = FloatArray(raster.width)
                var count = 0
                // Frozen former engine operation: RGB is decoded/validated by rows(), but unused here.
                raster.rows({ false }) { y, rgb ->
                    rgbRows++
                    rgbBytes += rgb.size.toLong() * 4
                    observedMask.row(y, row)
                    require(row.all { it.isFinite() && it in 0f..1f })
                    row.forEach { if (it > .5f) count++ }
                }
                count
            } else countOriginalMaskPixels(raster.width, raster.height, observedMask) { false }
            sample.put("kernelElapsedNs", SystemClock.elapsedRealtimeNanos() - start)
            assertEquals(expectedCount, count)
            assertEquals(raster.height, maskRows)
            assertEquals(if (route == "RGB_AND_MASK") raster.height else 0, rgbRows)
            assertEquals(if (route == "RGB_AND_MASK") raster.bytes else 0L, rgbBytes)
            assertFalse(raster.hasPendingOwners)
            sample.put("maskPixelCount", count).put("maskRows", maskRows).put("rgbRowsRead", rgbRows)
                .put("logicalRgbBytesRead", rgbBytes).put("logicalRgbBytesAvoided", raster.bytes - rgbBytes)
                .put("status", "success")
            metrics.stage("after")
        } catch (error: Throwable) {
            failure = error
            sample.put("status", "failure").put("code", error.javaClass.simpleName)
            throw error
        } finally {
            try { metrics.stop(); metrics.appendTo(sample) }
            catch (cleanup: Throwable) { if (failure == null) throw cleanup else if (failure !== cleanup) failure.addSuppressed(cleanup) }
        }
    }

    /** One row only; each file is fully materialized, never sparse or a full-frame FloatArray. */
    private fun writeSyntheticFloatRows(file: File, width: Int, height: Int) {
        val buffer = ByteBuffer.allocate(width * 3 * 4).order(ByteOrder.LITTLE_ENDIAN)
        FileChannel.open(file.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
            repeat(height) { y ->
                buffer.clear()
                repeat(width * 3) { x -> buffer.putFloat(((y * 37L + x * 17L) % 4096).toFloat() / 2048f - .05f) }
                buffer.flip()
                while (buffer.hasRemaining()) check(channel.write(buffer) > 0)
            }
            channel.force(true)
            assertEquals(width.toLong() * height * 12, channel.size())
        }
    }
}
