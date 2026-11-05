package dev.cinematic.rawprobe

import android.os.Build
import android.os.Bundle
import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.raw.FaceMask
import dev.cinematic.p0.raw.FloatPlane
import dev.cinematic.p0.raw.LinearSrgbImage
import dev.cinematic.p0.raw.R7Base
import dev.cinematic.p0.raw.StageClearLook
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Same current R7/Stage Clear kernels, isolated from DNG/JPEG, camera/UI and publication. */
@RunWith(AndroidJUnit4::class)
class RenderMemoryTest {
    @Test fun measureCurrentKernels() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        check(args.getString("rawProbe") == "true")
        check(Build.HARDWARE in setOf("goldfish", "ranchu") || args.getString("allowPhysical") == "true")
        val context = instrumentation.targetContext
        check(context.packageName in setOf("dev.cinematic.rawprobe", "dev.cinematic.rawprobe.largeheap"))
        val edge = (args.getString("renderEdge") ?: "1024").toInt()
        require(edge in setOf(1024, 1536, 2048)) // Fixed diagnostic cases, no automatic escalation to 12/24MP.
        val cases = JSONArray()
        var expected: String? = null
        repeat(3) { iteration ->
            val metrics = ProbeMetrics(context)
            val beforeGc = requireNotNull(Debug.getRuntimeStat("art.gc.blocking-gc-count")).toLong()
            val report = JSONObject().put("iteration", iteration).put("warmup", iteration == 0)
                .put("edge", edge).put("heapLimitBytes", Runtime.getRuntime().maxMemory())
            try {
                val digest = render(edge, metrics)
                expected?.let { assertEquals(it, digest) }
                expected = digest
                metrics.stage("kernel_returned")
                report.put("status", "success").put("sha256", digest)
            } catch (_: OutOfMemoryError) {
                // Preserve a failed attempt; do not retry or silently lower the requested size.
                report.put("status", "failure").put("code", "MANAGED_MEMORY")
            } finally { metrics.stop() }
            metrics.appendTo(report).put("blockingGcDelta", requireNotNull(Debug.getRuntimeStat("art.gc.blocking-gc-count")).toLong() - beforeGc)
            cases.put(report)
            val all = JSONObject().put("runId", args.getString("runId") ?: "manual")
                .put("scope", "synthetic-current-kernels-input-and-checksum-included-not-dng-jpeg")
                .put("applicationId", context.packageName).put("faceDetection", "NOT_RUN").put("cases", cases)
            File(context.filesDir, "render-memory.json").writeText(all.toString(2))
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nRawProbeRender $all\n") })
            assertEquals("success", report.getString("status"))
        }
    }

    private fun render(edge: Int, metrics: ProbeMetrics): String {
        val width = edge; val height = edge * 3 / 4
        metrics.stage("synthetic_input")
        val image = LinearSrgbImage(width, height, FloatArray(width * height * 3) { i ->
            (((i * 37L + (i / 3 / width) * 19L) % 4096) / 2048f) - .05f
        })
        val roi = FloatPlane(width, height, FloatArray(width * height) { .2f })
        val faces = FaceMask(width, height, FloatArray(width * height))
        metrics.stage("r7")
        val base = R7Base.render(image, roi, 0.0)
        metrics.stage("stage_clear")
        val output = StageClearLook.renderFromBase(base, faces).image
        assertEquals(width, output.width); assertEquals(height, output.height)
        metrics.stage("checksum")
        val digest = MessageDigest.getInstance("SHA-256")
        val row = ByteBuffer.allocate(width * 3 * 4)
        repeat(height) { y ->
            row.clear()
            repeat(width * 3) { x -> row.putFloat(output[y * width * 3 + x]) }
            digest.update(row.array())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
