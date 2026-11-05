package dev.cinematic.rawprobe

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Debug
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.testing.writeSyntheticTrialDng
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic native decode only: excludes Stage Clear, JPEG, production admission and camera QoS. */
@RunWith(AndroidJUnit4::class)
class FullResolutionProbeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private fun optIn() {
        val args = InstrumentationRegistry.getArguments()
        check(args.getString("rawProbe") == "true")
        check(Build.HARDWARE in setOf("goldfish", "ranchu") || args.getString("allowPhysical") == "true")
        check(context.packageName == "dev.cinematic.rawprobe")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(65536)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun fixture(file: File, width: Int, height: Int, orientation: Int) {
        file.outputStream().buffered().use { writeSyntheticTrialDng(it, width, height, orientation) }
    }

    private fun memory(): JSONObject {
        val runtime = Runtime.getRuntime()
        val process = Debug.MemoryInfo().also(Debug::getMemoryInfo)
        val system = ActivityManager.MemoryInfo().also {
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it)
        }
        val rss = File("/proc/self/status").useLines { lines ->
            lines.filter { it.startsWith("VmRSS:") || it.startsWith("VmHWM:") }.toList()
        }
        return JSONObject().put("elapsedMs", SystemClock.elapsedRealtime())
            .put("managedUsedBytes", runtime.totalMemory() - runtime.freeMemory())
            .put("nativeAllocatedBytes", Debug.getNativeHeapAllocatedSize()).put("pssKiB", process.totalPss)
            .put("availableMemBytes", system.availMem).put("lowMemory", system.lowMemory)
            .put("rssAndCumulativeHighWater", JSONArray(rss))
            .put("thermalStatus", (context.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus)
    }

    private fun decode(file: File, cancelAt: String? = null, preCancelled: Boolean = false): JSONObject {
        val hash = sha256(file)
        val signal = CancellationSignal().also { if (preCancelled) it.cancel() }
        val stages = JSONArray()
        val running = AtomicBoolean(true)
        val managedMax = AtomicLong()
        val nativeMax = AtomicLong()
        val pssMax = AtomicLong()
        val samplerFailure = AtomicReference<Throwable?>()
        // Sampled maxima, not exact peaks. No forced GC or largeHeap.
        val sampler = thread(name = "raw-probe-sampler", isDaemon = true) {
            try {
            var ticks = 0
            while (running.get()) {
                val runtime = Runtime.getRuntime()
                managedMax.accumulateAndGet(runtime.totalMemory() - runtime.freeMemory(), ::maxOf)
                nativeMax.accumulateAndGet(Debug.getNativeHeapAllocatedSize(), ::maxOf)
                if (ticks++ % 10 == 0) pssMax.accumulateAndGet(Debug.getPss(), ::maxOf)
                try { Thread.sleep(10) } catch (_: InterruptedException) { break }
            }
            } catch (failure: Throwable) { samplerFailure.set(failure) }
        }
        val start = SystemClock.elapsedRealtime()
        val report = JSONObject().put("preCancelled", preCancelled).put("cancelAt", cancelAt ?: JSONObject.NULL)
        try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                Os.lseek(fd.fileDescriptor, 7, OsConstants.SEEK_SET)
                val result = NativeFullResolutionProbe.inspect(fd.fd, signal) { stage ->
                    stages.put(memory().put("stage", stage))
                    if (stage == cancelAt) signal.cancel()
                }
                assertEquals(7L, Os.lseek(fd.fileDescriptor, 0, OsConstants.SEEK_CUR))
                report.put("status", "success").put("output", JSONObject(result))
            }
        } catch (failure: IOException) {
            // Only stable native error codes; never exception paths or full stack traces in shared reports.
            check(failure.message in setOf("DNG_CANCELLED", "DNG_MEMORY", "DNG_LIMIT", "DNG_SOURCE", "DNG_UNSUPPORTED"))
            report.put("status", "failure").put("code", failure.message)
        } finally {
            running.set(false)
            sampler.interrupt()
            sampler.join(5000)
            check(!sampler.isAlive && samplerFailure.get() == null) { "Memory sampler did not finish normally" }
        }
        val elapsed = SystemClock.elapsedRealtime() - start
        assertEquals(hash, sha256(file))
        return report.put("sourceUnchanged", true).put("elapsedMs", elapsed)
            .put("stages", stages).put("after", memory())
            .put("sampleIntervalMs", 10).put("pssSampleIntervalMs", 100)
            .put("sampledManagedMaxBytes", managedMax.get()).put("sampledNativeMaxBytes", nativeMax.get())
            .put("sampledPssMaxKiB", pssMax.get())
    }

    private fun save(name: String, report: JSONObject) {
        report.put("runId", InstrumentationRegistry.getArguments().getString("runId") ?: "manual")
        File(context.filesDir, "$name.json").writeText(report.toString(2))
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nRawProbe $report\n") })
    }

    @Test fun fullResolution12And24Mp() {
        optIn()
        val reports = JSONArray()
        val file = File(context.cacheDir, "raw-probe-full.dng")
        try {
            for ((width, height) in listOf(4000 to 3000, 6000 to 4000)) {
                val available = memory()
                check(!available.getBoolean("lowMemory") && available.getLong("availableMemBytes") >= 1024L * 1024 * 1024)
                var unrotated: JSONObject? = null
                for (orientation in listOf(1, 6)) {
                    fixture(file, width, height, orientation)
                    val report = decode(file).put("inputWidth", width).put("inputHeight", height).put("orientation", orientation)
                    reports.put(report)
                    // Persist even a failed decode before asserting, so failures remain reviewable.
                    save("full-resolution", JSONObject().put("scope", "native-synthetic-only-not-full-render")
                        .put("maxRawPixels", 24000000).put("maxRawMemoryMiB", 512).put("cases", reports))
                    assertEquals("success", report.getString("status"))
                    val output = report.getJSONObject("output")
                    assertEquals(if (orientation == 1) width else height, output.getInt("width"))
                    assertEquals(if (orientation == 1) height else width, output.getInt("height"))
                    assertEquals(width.toLong() * height * 6, output.getLong("dataBytes"))
                    assertEquals(0, output.getInt("halfSize"))
                    if (orientation == 1) unrotated = output else assertClockwiseCorners(unrotated!!, output)
                }
            }
        } finally { check(!file.exists() || file.delete()) }
    }

    private fun assertClockwiseCorners(before: JSONObject, after: JSONObject) {
        val source = before.getJSONArray("samples")
        val target = after.getJSONArray("samples")
        for (index in listOf(0, 2, 6, 8)) {
            val value = source.getJSONArray(index)
            val x = before.getInt("height") - 1 - value.getInt(1)
            val y = value.getInt(0)
            val rotated = (0 until target.length()).map { target.getJSONArray(it) }
                .single { it.getInt(0) == x && it.getInt(1) == y }
            for (channel in 2..4) assertEquals(value.getInt(channel), rotated.getInt(channel))
        }
    }

    @Test fun cancellationLimitsAndRecovery() {
        optIn()
        val file = File(context.cacheDir, "raw-probe-boundaries.dng")
        val cases = JSONArray()
        fun record(report: JSONObject): JSONObject {
            cases.put(report)
            save("boundaries", JSONObject().put("scope", "isolated-native-safety-not-production-camera-priority").put("cases", cases))
            return report
        }
        try {
            fixture(file, 32, 24, 1)
            for ((stage, pre) in listOf(null to true, "process" to false)) {
                val report = record(decode(file, stage, pre))
                assertEquals("failure", report.getString("status"))
                assertEquals("DNG_CANCELLED", report.getString("code"))
            }
            record(decode(file)).also { assertEquals("success", it.getString("status")) }
            RandomAccessFile(file, "rw").use { it.setLength(64L * 1024 * 1024 + 1) }
            record(decode(file)).also { assertEquals("DNG_LIMIT", it.getString("code")) }
            fixture(file, 6250, 4000, 1)
            record(decode(file)).also { assertEquals("DNG_LIMIT", it.getString("code")) }
            file.writeBytes(byteArrayOf(0, 1, 2, 3))
            record(decode(file)).also { assertEquals("failure", it.getString("status")) }
            fixture(file, 32, 24, 1)
            record(decode(file)).also { assertEquals("success", it.getString("status")) }
            assertTrue(file.exists())
        } finally { check(!file.exists() || file.delete()) }
    }
}
