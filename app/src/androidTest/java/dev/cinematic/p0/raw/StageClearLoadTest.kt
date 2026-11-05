package dev.cinematic.p0.raw

import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.MessageDigest
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in kernel load measurement, not a DNG/codec/Pixel benchmark or a capacity admission test. */
@RunWith(AndroidJUnit4::class)
class StageClearLoadTest {
    @Test fun measureSyntheticR7AndLook() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("stageClearLoad") == "true")
        check(args.getString("isolated") == "true" && Build.HARDWARE in setOf("goldfish", "ranchu"))
        val edge = requireNotNull(args.getString("edge")).toInt()
        require(edge in setOf(1024, 1536, 2048))
        val width = edge; val height = edge * 3 / 4
        val image = LinearSrgbImage(width, height, FloatArray(width * height * 3) { i ->
            // Repeatable HDR/negative texture, no image asset or device input.
            (((i * 37L + (i / 3 / width) * 19L) % 4096) / 2048f) - .05f
        })
        val roi = FloatPlane(width, height, FloatArray(width * height) { .2f })
        val faces = FaceMask(width, height, FloatArray(width * height))
        var expected: String? = null
        repeat(4) { iteration -> // First run is a reported warm-up, never discarded silently.
            val running = AtomicBoolean(true)
            val heap = AtomicLong(usedHeap())
            val pss = AtomicLong(Debug.getPss())
            val sampler = Thread({
                var samples = 0
                while (running.get()) {
                    heap.accumulateAndGet(usedHeap(), ::maxOf)
                    if (++samples % 10 == 0) pss.accumulateAndGet(Debug.getPss(), ::maxOf)
                    try { Thread.sleep(10) } catch (_: InterruptedException) { break }
                }
            }, "synthetic-load-sampler")
            val before = stats()
            val start = SystemClock.elapsedRealtimeNanos()
            sampler.start()
            val output = try { StageClearLook.renderFromBase(R7Base.render(image, roi, 0.0), faces).image }
            finally {
                running.set(false); sampler.interrupt(); sampler.join(5000)
                check(!sampler.isAlive)
            }
            val elapsed = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
            val after = stats()
            val checksum = digest(output)
            expected?.let { assertEquals(it, checksum) }; expected = checksum
            assertEquals(width, output.width); assertEquals(height, output.height)
            val report = JSONObject().put("edge", edge).put("iteration", iteration).put("warmup", iteration == 0)
                .put("elapsedMs", elapsed).put("heapLimitBytes", Runtime.getRuntime().maxMemory())
                .put("sampledHeapBytes", heap.get()).put("sampledPssKiB", pss.get()).put("sha256", checksum)
            for (key in before.keys) report.put(key, after.getValue(key) - before.getValue(key))
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nStageClearLoad $report\n") })
        }
    }

    private fun usedHeap() = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
    private fun stats() = listOf("art.gc.bytes-allocated", "art.gc.gc-count", "art.gc.gc-time", "art.gc.blocking-gc-count")
        .associateWith { requireNotNull(Debug.getRuntimeStat(it)).toLong() }
    private fun digest(image: LinearSrgbImage): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val row = ByteBuffer.allocate(image.width * 3 * 4)
        repeat(image.height) { y ->
            row.clear()
            repeat(image.width * 3) { x -> row.putFloat(image[y * image.width * 3 + x]) }
            digest.update(row.array())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
