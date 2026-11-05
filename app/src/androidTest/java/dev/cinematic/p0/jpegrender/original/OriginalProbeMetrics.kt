package dev.cinematic.p0.jpegrender.original

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject

internal fun memorySnapshot(context: Context): JSONObject {
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

/** Observed maxima only. Sampling costs are included; no forced GC or automatic retries. */
internal class OriginalProbeMetrics(private val context: Context) {
    private val running = AtomicBoolean(true)
    private val managedMax = AtomicLong()
    private val nativeMax = AtomicLong()
    private val pssMax = AtomicLong()
    private val failure = AtomicReference<Throwable?>()
    private val stages = JSONArray()
    private val start = SystemClock.elapsedRealtime()
    private var elapsed = 0L
    private val sampler = thread(name = "raw-probe-sampler", isDaemon = true) {
        try {
            var ticks = 0
            while (running.get()) {
                val runtime = Runtime.getRuntime()
                managedMax.accumulateAndGet(runtime.totalMemory() - runtime.freeMemory(), ::maxOf)
                nativeMax.accumulateAndGet(Debug.getNativeHeapAllocatedSize(), ::maxOf)
                if (ticks++ % 10 == 0) pssMax.accumulateAndGet(Debug.getPss(), ::maxOf)
                try { Thread.sleep(10) } catch (_: InterruptedException) { break }
            }
        } catch (error: Throwable) { failure.set(error) }
    }
    fun stage(name: String) { stages.put(memorySnapshot(context).put("stage", name)) }
    fun stop() {
        running.set(false)
        sampler.interrupt()
        sampler.join(5000)
        check(!sampler.isAlive && failure.get() == null) { "Memory sampler did not finish normally" }
        elapsed = SystemClock.elapsedRealtime() - start
    }
    fun appendTo(report: JSONObject): JSONObject = report.put("elapsedMs", elapsed)
        .put("stages", stages).put("after", memorySnapshot(context))
        .put("sampleIntervalMs", 10).put("pssSampleIntervalMs", 100)
        .put("sampledManagedMaxBytes", managedMax.get()).put("sampledNativeMaxBytes", nativeMax.get())
        .put("sampledPssMaxKiB", pssMax.get())
}
