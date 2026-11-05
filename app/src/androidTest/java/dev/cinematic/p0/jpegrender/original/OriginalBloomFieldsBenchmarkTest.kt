package dev.cinematic.p0.jpegrender.original

import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.raw.StageClearGlowV1
import dev.cinematic.p0.raw.StageClearV1
import dev.cinematic.p0.raw.original.OriginalField
import dev.cinematic.p0.raw.original.OriginalFields
import dev.cinematic.p0.raw.original.OriginalRaster
import dev.cinematic.p0.raw.original.OriginalRasterFile
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.round
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Same-APK bloom-field kernels only: synthetic Float rows, never DNG/whole Look/JPEG/device-speed evidence. */
@RunWith(AndroidJUnit4::class)
class OriginalBloomFieldsBenchmarkTest {
    @Test fun sequentialAndBatchedBloomFieldsAlternateOnIdenticalStreamingFixture() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(context.packageName == "dev.cinematic.p0.original24probe")
        assumeTrue(args.getString("originalBloomFieldsBenchmark") == "true")
        assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val runId = requireNotNull(args.getString("runId")).also { require(it.matches(Regex("[a-f0-9]{32}"))) }
        val (width, height) = when (args.getString("bloomFieldsSize") ?: "SMALL") {
            "SMALL" -> 512 to 384
            "24MP" -> 6000 to 4000
            else -> error("Unknown fixed bloom-field fixture size")
        }
        val directory = File(context.noBackupFilesDir, "original-bloom-fields-$runId")
        check(!directory.exists() && directory.mkdir())
        val reportFile = File(context.filesDir, "original-bloom-fields-$runId.json")
        check(reportFile.createNewFile())
        val samples = JSONArray()
        val kernels = StageClearGlowV1.BLOOM_KERNELS.toList()
        val radii = kernels.map { it.first }
        val report = JSONObject().put("runId", runId).put("applicationId", context.packageName)
            .put("scope", "same-apk-synthetic-bloom-fields-kernel-only-no-photo-no-dng-no-whole-look")
            .put("width", width).put("height", height).put("channels", 3)
            .put("sourcePattern", "256-level-ramp-with-negative-signed-zero-and-HDR-2-16-256")
            .put("bloomThreshold", StageClearGlowV1.THRESHOLD)
            .put("radii", JSONArray(radii)).put("weights", JSONArray(kernels.map { it.second.toDouble() }))
            .put("pairs", 3).put("warmupRoutes", 0)
            .put("cachePolicy", "fixture generation primes OS caches; no eviction/cold-disk claim; alternate AB/BA")
            .put("byteAccounting", "logical complete Float32 RGB rows consumed; not physical disk traffic")
            .put("kernelTimingScope", "field generation only; excludes fixture creation, checksum and report writes")
            .put("memorySamplingScope", "field generation plus boundary snapshots; checksums run after sampler stop")
            .put("memoryPolicy", "one route's fields reachable at a time; no forced GC; sampled process totals can include preceding garbage")
            .put("checksum", "SHA-256 of all reduced row-major interleaved Float raw bits, little-endian")
            .put("additionalDoublePayloadLimitBytes", 512 * 1024)
            .put("fullOutputArrayAllocated", false).put("samples", samples).put("status", "STARTED").put("complete", false)
        reportFile.writeText(report.toString(2))
        try {
            val data = File(directory, "synthetic-${width}x$height.f32")
            val fixtureStart = SystemClock.elapsedRealtimeNanos()
            writeFixture(data, width, height)
            val dataBytes = width.toLong() * height * 12
            assertEquals(dataBytes, data.length())
            report.put("dataFileBytes", dataBytes).put("fixtureGenerationNanos", SystemClock.elapsedRealtimeNanos() - fixtureStart)
                .put("fixtureRowBufferBytes", width * 12)
            var expected: List<FieldSignature>? = null
            repeat(3) { pair ->
                val routes = if (pair % 2 == 0) listOf("SEQUENTIAL", "BATCHED") else listOf("BATCHED", "SEQUENTIAL")
                for ((position, route) in routes.withIndex()) {
                    val sample = JSONObject().put("pair", pair).put("position", position).put("route", route).put("status", "STARTED")
                    samples.put(sample)
                    // Only small immutable signatures escape measure; no prior route's fields are retained.
                    val actual = measure(context, data, width, height, radii, route, sample)
                    if (expected == null) expected = actual
                    assertEquals("All reduced fields, order and raw-bit checksums must agree", expected, actual)
                    sample.put("allFieldSignaturesEqual", true)
                    reportFile.writeText(report.toString(2))
                }
            }
            report.put("allFieldSignaturesEqual", true).put("status", "success").put("complete", true)
        } catch (error: Throwable) {
            report.put("status", "failure").put("code", error.javaClass.simpleName)
            throw error
        } finally { reportFile.writeText(report.toString(2)) }
        // Retain only this run's create-only synthetic raster/report; never enumerate/delete other files.
    }

    private data class FieldSignature(val index: Int, val radiusBits: Long, val width: Int,
        val height: Int, val channels: Int, val floatCount: Int, val sha256: String)

    private fun measure(context: Context, data: File, width: Int, height: Int, radii: List<Double>,
        route: String, sample: JSONObject): List<FieldSignature> {
        val raster = OriginalRasterFile(data, width, height)
        val counted = CountingRaster(raster)
        var projectCalls = 0
        var projectedPixels = 0L
        val luma = StageClearV1.LUMA
        val project: (FloatArray, FloatArray) -> Unit = { rgb, out ->
            projectCalls++
            projectedPixels += width
            // OriginalStageClear24.glow's project, with the same Float expressions/order/constants.
            repeat(width) { p ->
                val i = p * 3
                val yy = max(0f, rgb[i] * luma[0] + rgb[i + 1] * luma[1] + rgb[i + 2] * luma[2])
                val excess = max(0f, (yy - StageClearGlowV1.THRESHOLD.toFloat()) /
                    (1 - StageClearGlowV1.THRESHOLD).toFloat()).pow(1.5f)
                repeat(3) { c -> out[i + c] = rgb[i + c] * (excess / max(yy, 1e-6f)) }
            }
        }
        val metrics = OriginalProbeMetrics(context)
        var failure: Throwable? = null
        var metricsStopped = false
        try {
            metrics.stage("before_kernel")
            val cpuStart = Debug.threadCpuTimeNanos()
            val wallStart = SystemClock.elapsedRealtimeNanos()
            val fields = if (route == "SEQUENTIAL") radii.map { OriginalFields.blur(counted, it, 3, { false }, project) }
                else OriginalFields.blurMany(counted, radii, 3, { false }, project)
            val wallNanos = SystemClock.elapsedRealtimeNanos() - wallStart
            val cpuNanos = Debug.threadCpuTimeNanos() - cpuStart
            metrics.stage("after_kernel")
            metricsStopped = true
            metrics.stop()
            metrics.appendTo(sample) // No checksum copy/allocation has occurred yet.
            val passes = if (route == "SEQUENTIAL") radii.size else 1
            assertEquals(passes, counted.passes)
            assertEquals(passes * height, counted.rowCallbacks)
            assertEquals(passes * height, projectCalls)
            assertEquals(width.toLong() * height * passes, projectedPixels)
            assertEquals(raster.bytes * passes, counted.logicalBytes)
            assertFalse(raster.hasPendingOwners)
            assertEquals(radii.size, fields.size)
            sample.put("kernelWallNanos", wallNanos).put("kernelThreadCpuNanos", cpuNanos)
                .put("inputPasses", counted.passes).put("inputRowCallbacks", counted.rowCallbacks)
                .put("projectCallbacks", projectCalls).put("projectedPixels", projectedPixels)
                .put("logicalRgbBytesRead", counted.logicalBytes).put("sourceFileBytes", raster.bytes)
            // A bounded reducedRgb copy only; never reconstruct a 24MP field output for verification.
            val verificationStart = SystemClock.elapsedRealtimeNanos()
            val signatures = fields.mapIndexed { index, field -> signature(index, radii[index], width, height, field) }
            sample.put("verificationNanos", SystemClock.elapsedRealtimeNanos() - verificationStart)
                .put("fields", JSONArray(signatures.map { s -> JSONObject().put("index", s.index)
                    .put("radiusHex", java.lang.Double.toHexString(Double.fromBits(s.radiusBits)))
                    .put("width", s.width).put("height", s.height).put("channels", s.channels)
                    .put("floatCount", s.floatCount).put("sha256", s.sha256) }))
                .put("status", "success")
            return signatures
        } catch (error: Throwable) {
            failure = error
            sample.put("status", "failure").put("code", error.javaClass.simpleName)
            throw error
        } finally {
            var cleanupFailure: Throwable? = null
            try { if (!metricsStopped) { metrics.stop(); metrics.appendTo(sample) } }
            catch (cleanup: Throwable) { cleanupFailure = cleanup }
            try { raster.closePending() }
            catch (cleanup: Throwable) {
                val previous = cleanupFailure
                if (previous == null) cleanupFailure = cleanup else if (previous !== cleanup) previous.addSuppressed(cleanup)
            }
            cleanupFailure?.let { cleanup ->
                if (failure == null) throw cleanup else if (failure !== cleanup) failure.addSuppressed(cleanup)
            }
        }
    }

    private fun signature(index: Int, radius: Double, outputWidth: Int, outputHeight: Int, field: OriginalField): FieldSignature {
        val cell = max(radius * max(outputWidth, outputHeight), 1.0)
        assertEquals(max(2, round(outputWidth / cell).toInt()), field.width)
        assertEquals(max(2, round(outputHeight / cell).toInt()), field.height)
        assertEquals(3, field.channels)
        check(max(field.width, field.height) <= 1024)
        val reduced = field.reducedRgb()
        val count = field.width * field.height * field.channels
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)
        repeat(count) { i ->
            if (buffer.remaining() < 4) { digest.update(buffer.array(), 0, buffer.position()); buffer.clear() }
            buffer.putInt(reduced[i].toRawBits())
        }
        digest.update(buffer.array(), 0, buffer.position())
        val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        return FieldSignature(index, radius.toRawBits(), field.width, field.height, field.channels, count, hash)
    }

    private class CountingRaster(private val source: OriginalRasterFile) : OriginalRaster {
        override val width get() = source.width
        override val height get() = source.height
        var passes = 0
        var rowCallbacks = 0
        var logicalBytes = 0L
        override fun rows(stop: () -> Boolean, consume: (Int, FloatArray) -> Unit) {
            passes++
            var nextRow = 0
            source.rows(stop) { y, row ->
                assertEquals(nextRow++, y)
                rowCallbacks++
                logicalBytes += row.size.toLong() * 4
                consume(y, row)
            }
            assertEquals(height, nextRow)
        }
    }

    private fun writeFixture(file: File, width: Int, height: Int) {
        val row = ByteBuffer.allocate(width * 12).order(ByteOrder.LITTLE_ENDIAN)
        FileChannel.open(file.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
            repeat(height) { y ->
                row.clear()
                repeat(width * 3) { x ->
                    val position = y.toLong() * width * 3 + x
                    val value = when (position % 1024) {
                        0L -> -.125f; 1L -> -0f; 2L -> 0f; 3L -> .7f; 4L -> .71f
                        5L -> 1f; 6L -> 2f; 7L -> 16f; 8L -> 256f
                        else -> ((y * 37L + x * 17L) % 256).toFloat() / 128f - .125f
                    }
                    row.putFloat(value)
                }
                row.flip()
                while (row.hasRemaining()) check(channel.write(row) > 0)
            }
            channel.force(true)
            assertEquals(width.toLong() * height * 12, channel.size())
        }
    }
}
