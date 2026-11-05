package dev.cinematic.rawprobe

import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Fixed generated metadata, raw rows streamed; never holds a 12/24MP fixture ByteArray. */
private fun writeLargeBaseline(output: OutputStream, w: Int, h: Int, orientation: Int) {
    require(w.toLong() * h in 1..24_000_000)
    val small = smallGeometryDng(2, 2, orientation, intArrayOf(0, 0, 2, 2), intArrayOf(0, 0), intArrayOf(2, 2))
    val header = ByteBuffer.wrap(small.copyOf(small.size - 8)).order(ByteOrder.LITTLE_ENDIAN)
    repeat(header.getShort(8).toInt() and 65535) { i ->
        val p = 10 + i * 12
        when (header.getShort(p).toInt() and 65535) {
            256 -> header.putInt(p + 8, w)
            257, 278 -> header.putInt(p + 8, h)
            279 -> header.putInt(p + 8, w * h * 2)
            50829 -> { val offset = header.getInt(p + 8); header.putInt(offset + 8, h); header.putInt(offset + 12, w) }
            50720 -> { val offset = header.getInt(p + 8); header.putInt(offset, w); header.putInt(offset + 4, h) }
        }
    }
    output.write(header.array())
    val row = ByteBuffer.allocate(w * 2).order(ByteOrder.LITTLE_ENDIAN)
    repeat(h) { y ->
        row.clear()
        repeat(w) { x -> row.putShort((164 + (x * 65 + y * 25) % 3500).toShort()) }
        output.write(row.array())
    }
}

private fun probeDirectory(): File {
    val args = InstrumentationRegistry.getArguments()
    check(args.getString("rawProbe") == "true")
    check(Build.HARDWARE in setOf("ranchu", "goldfish")) // No physical-device override in this experiment.
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    check(context.packageName in setOf("dev.cinematic.rawprobe", "dev.cinematic.rawprobe.largeheap"))
    return Files.createTempDirectory(context.cacheDir.canonicalFile.toPath(), "baseline-large-").toFile()
}

@RunWith(AndroidJUnit4::class)
class BaselineLargeSmokeTest {
    @Test fun streamingTransportAndGeometryRejectCorruption() {
        val dir = probeDirectory()
        val source = File(dir, "generated.dng").apply { writeBytes(smallGeometryDng()) }
        for (mode in listOf("success", "drop", "mutate", "wrong-size")) {
            val attempt = BaselineJpegAttempt(source, File(dir, "$mode.jpg"), CancellationSignal(), wrap = { base ->
                object : FilterOutputStream(base) {
                    var position = 0
                    override fun write(b: ByteArray, off: Int, len: Int) {
                        if (mode == "drop") return
                        if (mode == "mutate") {
                            val copy = b.copyOfRange(off, off + len)
                            if (14 in position until position + len) copy[14 - position] = (copy[14 - position].toInt() xor 1).toByte()
                            out.write(copy)
                        } else out.write(b, off, len)
                        position += len
                    }
                }
            }, encode = { bitmap, sink ->
                if (mode != "wrong-size") bitmap.compress(Bitmap.CompressFormat.JPEG, 95, sink) else {
                    val other = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
                    try { other.compress(Bitmap.CompressFormat.JPEG, 95, sink) } finally { other.recycle() }
                }
            }, large = true)
            try { attempt.execute(); assertEquals("success", mode) }
            catch (failure: IOException) { assertNotEquals("success", mode) }
            assertTrue(attempt.released)
            assertEquals(mode == "success", attempt.receipt != null)
        }
    }
}

/** One explicit size/case per instrumentation process; no implicit large-image CI runs or retries. */
@RunWith(AndroidJUnit4::class)
class LargeBaselineMeasureTest {
    @Test fun oneOriginalSizeAttempt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runId = requireNotNull(InstrumentationRegistry.getArguments().getString("runId"))
        require(runId.matches(Regex("[a-f0-9]{32}")))
        val reportFile = File(context.filesDir, "baseline-$runId.json")
        val report = JSONObject().put("runId", runId).put("status", "STARTED").put("complete", false)
        Files.write(reportFile.toPath(), report.toString(2).toByteArray(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        try { measuredAttempt(report, reportFile) }
        catch (error: Throwable) {
            if (!report.optBoolean("complete")) {
                report.put("status", "SETUP_OR_CLEANUP_FAILURE").put("errorType", error.javaClass.simpleName)
                reportFile.writeText(report.toString(2))
            }
            throw error
        }
    }

    private fun measuredAttempt(report: JSONObject, reportFile: File) {
        val dir = probeDirectory()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val args = InstrumentationRegistry.getArguments()
        val mp = args.getString("megapixels")?.toInt()
        require(mp in listOf(12, 24))
        val mode = args.getString("case") ?: "success"
        require(mode in setOf("success", "write", "close", "cancel"))
        val (w, h, orientation) = if (mp == 12) Triple(4000, 3000, 6) else Triple(6000, 4000, 1)
        val state = memorySnapshot(context)
        check(!state.getBoolean("lowMemory") && state.getLong("availableMemBytes") >= 1024L * 1024 * 1024)
        check(dir.usableSpace >= 256L * 1024 * 1024)
        val source = File(dir, "generated.dng")
        val fixtureStart = SystemClock.elapsedRealtime()
        source.outputStream().buffered(65536).use { writeLargeBaseline(it, w, h, orientation) }
        val fixtureMs = SystemClock.elapsedRealtime() - fixtureStart
        val before = BaselineJpegAttempt.digest(source, 64L * 1024 * 1024)
        val output = File(dir, "baseline.jpg")
        val signal = CancellationSignal()
        lateinit var metrics: ProbeMetrics
        var packRows = 0
        var cancelHit = false
        var closes = 0
        val attempt = BaselineJpegAttempt(source, output, signal, stage = { name ->
            if (name != "pack_row") metrics.stage(name) else {
                packRows++
                if (packRows == 1) metrics.stage("pack_begin")
                if (mode == "cancel" && packRows == 3) { cancelHit = true; signal.cancel() }
            }
        }, wrap = { base -> object : FilterOutputStream(base) {
            override fun write(b: ByteArray, off: Int, len: Int) {
                if (mode == "write") { out.write(b, off, minOf(len, 5)); throw IOException("INJECTED_WRITE") }
                out.write(b, off, len)
            }
            override fun close() {
                closes++
                if (mode == "close" && closes == 1) throw IOException("INJECTED_CLOSE")
                out.close()
            }
        } }, large = true)
        report.put("scope", "DIAGNOSTIC_BASELINE_NO_R7_NO_STAGE_CLEAR")
            .put("megapixels", mp).put("case", mode).put("inputWidth", w).put("inputHeight", h).put("inputOrientation", orientation)
            .put("fixtureMsExcluded", fixtureMs).put("sourceBytes", source.length()).put("before", state)
            .put("maxHeapBytes", Runtime.getRuntime().maxMemory()).put("package", context.packageName)
        var failure: Throwable? = null
        metrics = ProbeMetrics(context)
        try {
            report.put("output", attempt.execute()).put("status", "success")
        } catch (error: Throwable) {
            failure = error
            val known = setOf("INJECTED_WRITE", "INJECTED_CLOSE", "DNG_CANCELLED", "DNG_MEMORY", "DNG_LIMIT", "DNG_UNSUPPORTED", "BASELINE_GEOMETRY")
            report.put("status", "failure").put("errorType", error.javaClass.simpleName)
                .put("code", if (error.message in known) error.message else "OTHER")
        } finally {
            report.put("releasedAtReturn", attempt.released).put("receiptPresent", attempt.receipt != null)
            if (mode == "close" && !attempt.released) {
                try { attempt.closePendingWriter() } catch (error: Throwable) { failure = error; report.put("retryCloseFailed", true) }
            }
            metrics.stop()
            report.put("releasedAfterExplicitClose", attempt.released).put("closeCalls", closes).put("packRows", packRows)
                .put("cancelHit", cancelHit).put("sourceUnchanged", before == BaselineJpegAttempt.digest(source, 64L * 1024 * 1024))
                .put("partialOutputPreserved", output.exists()).put("outputBytesAfter", if (output.exists()) output.length() else 0)
            metrics.appendTo(report)
            report.put("complete", true)
            reportFile.writeText(report.toString(2))
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("stream", "\nBaselineReport ${reportFile.name} $report\n") })
        }
        assertTrue("Original unchanged", report.getBoolean("sourceUnchanged"))
        assertTrue("All owners acknowledged", attempt.released)
        if (mode == "success") {
            assertNull("Unexpected failure: ${report.optString("code")}", failure)
            assertNotNull(attempt.receipt)
            assertEquals(if (orientation == 6) h else w, attempt.receipt!!.getInt("width"))
            assertEquals(if (orientation == 6) w else h, attempt.receipt!!.getInt("height"))
            assertEquals(w.toLong() * h, attempt.receipt!!.getJSONObject("geometry").getLong("packedPixels"))
            assertEquals("NONE_LARGE_DIAGNOSTIC", attempt.receipt!!.getJSONObject("geometry").getString("oracle"))
        } else {
            assertNotNull(failure); assertNull(attempt.receipt)
            assertEquals(when (mode) { "write" -> "INJECTED_WRITE"; "close" -> "INJECTED_CLOSE"; else -> "DNG_CANCELLED" }, failure!!.message)
            if (mode == "cancel") assertTrue(cancelHit) else assertTrue(output.exists())
            if (mode == "close") { assertFalse(report.getBoolean("releasedAtReturn")); assertEquals(2, closes) }
        }
    }
}
