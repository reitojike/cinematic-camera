package dev.cinematic.p0.jpegrender.original

import android.os.Build
import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.raw.original.OriginalField
import dev.cinematic.p0.raw.original.OriginalFilter
import dev.cinematic.p0.raw.original.originalWeights
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in emulator microbenchmark of a synthetic field only. No camera, decode or full-frame array. */
@RunWith(AndroidJUnit4::class)
class OriginalFieldRowBenchmarkTest {
    @Test fun originalToneFieldThreeWalksAlternateLegacyAndCachedRows() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(context.packageName == "dev.cinematic.p0.original24probe")
        assumeTrue(args.getString("originalFieldRowBenchmark") == "true")
        assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val runId = requireNotNull(args.getString("runId")).also { require(it.matches(Regex("[a-f0-9]{32}"))) }
        val reportFile = File(context.filesDir, "original-field-row-$runId.json")
        check(reportFile.createNewFile())
        val walks = JSONArray()
        val report = JSONObject().put("runId", runId).put("scope", "synthetic-original-field-row-microbenchmark-only")
            .put("baseline", "fad7d3ad OriginalField.row horizontal-first formula")
            .put("fieldWidth", 25).put("fieldHeight", 17).put("outputWidth", 6000).put("outputHeight", 4000)
            .put("channels", 1).put("filter", "CUBIC").put("walksPerRoute", 3).put("warmupRowsPerRoute", 128)
            .put("checksumKind", "fnv1a64-over-float-raw-bits-diagnostic")
            .put("fullOutputArrayAllocated", false).put("sharedOutputRowPayloadBytes", 24_000)
            .put("cachePayloadLimitBytes", 96_000).put("abi", Build.SUPPORTED_ABIS[0])
            .put("walks", walks).put("status", "STARTED").put("complete", false)
        reportFile.writeText(report.toString(2))
        try {
            val values = FloatArray(25 * 17) { i -> (((i * 73 + i / 7 * 19) % 257) - 41) / 19f }
            val cached = OriginalField(25, 17, 1, values, 6000, 4000, OriginalFilter.CUBIC)
            val legacy = LegacyRows(values)
            val output = FloatArray(6000) // Shared across both routes; never hold the 24MP output.
            var warmBits = 0
            repeat(128) { visit ->
                val y = visit * 31 % 4000
                legacy.row(y, output); warmBits = warmBits xor output[visit % output.size].toRawBits()
                cached.row(y, output); warmBits = warmBits xor output[visit % output.size].toRawBits()
            }
            report.put("warmupDiagnosticBits", warmBits)
            var expectedChecksum: Long? = null
            repeat(3) { round ->
                val order = if (round % 2 == 0) listOf("legacy", "cached") else listOf("cached", "legacy")
                for ((position, route) in order.withIndex()) {
                    val readRow: (Int, FloatArray) -> Unit = if (route == "legacy") legacy::row else cached::row
                    val walk = walk(output, readRow)
                    if (expectedChecksum == null) expectedChecksum = walk.checksum
                    assertEquals(expectedChecksum, walk.checksum)
                    assertTrue(cached.rowCachePayloadBytes <= 96_000L)
                    walks.put(JSONObject().put("round", round).put("position", position).put("route", route)
                        .put("elapsedNanos", walk.elapsedNanos).put("threadCpuNanos", walk.threadCpuNanos)
                        .put("checksum", walk.checksum.toULong().toString(16))
                        .put("cachePayloadBytes", if (route == "cached") cached.rowCachePayloadBytes else 0))
                    reportFile.writeText(report.toString(2))
                }
            }
            assertEquals(96_000L, cached.rowCachePayloadBytes)
            report.put("cachePayloadBytes", cached.rowCachePayloadBytes).put("allChecksumsEqual", true)
                .put("status", "success").put("complete", true)
        } catch (error: Throwable) {
            report.put("status", "failure").put("code", error.javaClass.simpleName)
            throw error
        } finally { reportFile.writeText(report.toString(2)) }
    }

    private data class Walk(val elapsedNanos: Long, val threadCpuNanos: Long, val checksum: Long)
    private fun walk(output: FloatArray, row: (Int, FloatArray) -> Unit): Walk {
        val start = SystemClock.elapsedRealtimeNanos()
        val cpuStart = Debug.threadCpuTimeNanos()
        var checksum = -3750763034362895579L
        repeat(4000) { y ->
            row(y, output)
            for (value in output) checksum = (checksum xor (value.toRawBits().toLong() and 0xffffffffL)) * 1099511628211L
        }
        val cpuElapsed = Debug.threadCpuTimeNanos() - cpuStart
        return Walk(SystemClock.elapsedRealtimeNanos() - start, cpuElapsed, checksum)
    }

    /** Test-only fad7d3ad/a73 old formula. Coefficients and Double/Float operation order unchanged. */
    private class LegacyRows(private val values: FloatArray) {
        private val columns = originalWeights(25, 6000, OriginalFilter.CUBIC)
        private val rows = originalWeights(17, 4000, OriginalFilter.CUBIC)
        fun row(y: Int, output: FloatArray) {
            val vertical = rows[y]
            repeat(6000) { x ->
                val horizontal = columns[x]
                var sum = 0.0
                vertical.weights.indices.forEach { dy ->
                    var value = 0.0
                    horizontal.weights.indices.forEach { dx ->
                        value += values[(vertical.first + dy) * 25 + horizontal.first + dx] * horizontal.weights[dx]
                    }
                    sum += value.toFloat() * vertical.weights[dy]
                }
                output[x] = sum.toFloat()
            }
        }
    }
}
