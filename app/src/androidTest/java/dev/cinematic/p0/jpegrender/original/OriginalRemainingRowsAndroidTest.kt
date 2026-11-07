package dev.cinematic.p0.jpegrender.original

import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.raw.StageClearColor
import dev.cinematic.p0.raw.original.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

/** Synthetic kernel comparison only, not DNG decoding, production save or physical-device speed. */
@RunWith(AndroidJUnit4::class)
class OriginalRemainingRowsAndroidTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val width = 256
    private val height = 192
    private data class Arm(val tone: Int, val mist: Int)
    private val arms = listOf(Arm(1, 1), Arm(2, 1), Arm(1, 2), Arm(2, 2))

    @Test fun sameApkParityTimingMemoryAndInterruptedCleanup() {
        val context = inst.targetContext
        assumeTrue(context.packageName == "dev.cinematic.p0.original24probe")
        assumeTrue(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val root = File(context.noBackupFilesDir, "remaining-rows-" + UUID.randomUUID())
        check(root.mkdir())
        val source = File(root, "input.f32")
        source.outputStream().use { out ->
            repeat(height) { y ->
                val buffer = ByteBuffer.allocate(width * 12).order(ByteOrder.LITTLE_ENDIAN)
                repeat(width * 3) { x -> buffer.putFloat(((x * 37L + y * 19) % 4096) / 2048f - .05f) }
                out.write(buffer.array())
            }
        }
        fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        fun sourceHash() = hash(source.readBytes())
        val originalHash = sourceHash()
        var baseline: Pair<String, String>? = null
        var attempt = 0
        val samples = JSONArray()
        fun run(arm: Arm, mode: String, phase: String? = null, fault: Boolean = false) {
            val work = OriginalRasterWorkspace(File(root, "attempt-" + attempt++))
            val input = OriginalRasterFile(source, width, height)
            val stop = AtomicBoolean()
            val injected = IOException("synthetic remaining row failure")
            val stageTimes = JSONObject()
            val metrics = if (mode == "MEMORY") OriginalProbeMetrics(context) else null
            try {
                metrics?.stage("before")
                val start = System.nanoTime()
                val result = OriginalStageClear24(work,
                    trace = OriginalStageTrace { stage, nanos, completed ->
                        assertTrue(completed)
                        stageTimes.put(stage.name, nanos)
                    }.takeIf { phase == null },
                    parallelOrtonWorkers = 4, parallelCharacterWorkers = 2, parallelFoundationWorkers = 2,
                    parallelToneWorkers = arm.tone, parallelMistWorkers = arm.mist,
                    remainingRowProbe = { name, y ->
                        if (name == phase && y == 2) {
                            if (fault) throw injected else stop.set(true)
                        }
                    }).render(input, .23, .5, stop = { stop.get() })
                val renderNanos = System.nanoTime() - start
                check(phase == null) { "Expected interruption" }
                metrics?.stage("after")
                metrics?.stop()
                val memoryReport = metrics?.let { collector -> JSONObject().also { collector.appendTo(it) } }
                val rawDigest = MessageDigest.getInstance("SHA-256")
                val pixels = IntArray(width * height)
                result.raster.rows({ false }) { y, row ->
                    val buffer = ByteBuffer.allocate(row.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                    row.forEach { buffer.putInt(it.toRawBits()) }
                    rawDigest.update(buffer.array())
                    repeat(width) { x ->
                        fun channel(c: Int) = (StageClearColor.linearToUnit(row[x * 3 + c]).coerceIn(0f, 1f) * 255).roundToInt()
                        pixels[y * width + x] = (255 shl 24) or (channel(0) shl 16) or (channel(1) shl 8) or channel(2)
                    }
                }
                val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
                val jpeg = try {
                    ByteArrayOutputStream().use { out ->
                        assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out))
                        hash(out.toByteArray())
                    }
                } finally { bitmap.recycle() }
                val signatures = rawDigest.digest().joinToString("") { "%02x".format(it) } to jpeg
                if (baseline == null) baseline = signatures else assertEquals(baseline, signatures)
                val sample = JSONObject().put("mode", mode).put("toneWorkers", arm.tone).put("mistWorkers", arm.mist)
                    .put("rawBitsEqual", true).put("testJpegEqual", true)
                if (mode != "MEMORY") sample.put("renderNanos", renderNanos).put("stageNanos", stageTimes)
                if (memoryReport != null) sample.put("memory", memoryReport)
                samples.put(sample)
                // GMD preserves logcat, but may discard instrumentation stream status bundles.
                val observed = JSONObject().put("mode", mode).put("toneWorkers", arm.tone).put("mistWorkers", arm.mist)
                    .put("rawBitsEqual", true).put("testJpegEqual", true)
                if (mode != "MEMORY") observed.put("renderNanos", renderNanos)
                    .put("toneNanos", stageTimes.getLong("R7_TONE_APPLY"))
                    .put("mistNanos", stageTimes.getLong("MIST"))
                if (memoryReport != null) for (key in listOf("sampledManagedMaxBytes", "sampledNativeMaxBytes", "sampledPssMaxKiB"))
                    observed.put(key, memoryReport.getLong(key))
                android.util.Log.i("RemainingRowsProbe", observed.toString())
            } catch (error: Throwable) {
                if (phase == null) throw error
                if (fault) assertSame(injected, error) else assertTrue(error is CancellationException)
                samples.put(JSONObject().put("mode", "SAFETY").put("phase", phase)
                    .put("fault", fault).put("expectedFailure", true))
            } finally {
                metrics?.stop()
                val pendingBeforeCleanup = work.hasPendingOwners
                work.closePending()
                assertFalse(work.hasPendingOwners)
                work.disposeConfirmed()
                assertFalse("workers and I/O must already be closed on return", pendingBeforeCleanup)
                assertFalse(input.hasPendingOwners)
                assertEquals(originalHash, sourceHash())
            }
        }
        try {
            // Dedicated warm-up for all arms, then forward/reverse/forward measured order.
            arms.forEach { run(it, "WARMUP") }
            listOf(arms, arms.reversed(), arms).forEach { order -> order.forEach { run(it, "TIMING") } }
            // Separate sampler-enabled memory observations are never timing evidence.
            arms.forEach { run(it, "MEMORY") }
            for (phase in listOf("tone", "mist")) for (fault in listOf(false, true)) run(Arm(2, 2), "SAFETY", phase, fault)
            run(Arm(2, 2), "RETRY")
            val report = JSONObject().put("width", width).put("height", height).put("samples", samples)
                .put("scope", "synthetic-float-kernel-and-test-JPEG; no-DNG/no-production-save/no-device-speed")
                .put("sourceUnchanged", true).put("status", "SUCCESS")
            android.util.Log.i("RemainingRowsProbe", "SUCCESS sourceUnchanged=true safetyCases=4 retryExact=true")
            inst.sendStatus(0, Bundle().apply { putString("stream", "\nRemainingRowsReport " + report + "\n") })
        } finally {
            assertTrue(source.delete())
            assertTrue(root.delete())
        }
    }
}
