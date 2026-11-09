package dev.cinematic.p0.rawdiagnostic

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import dev.cinematic.p0.jpegrender.original.Original24Attempt
import dev.cinematic.p0.jpegrender.original.Original24Request
import dev.cinematic.p0.rawcapture.*
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

/** Private foreground trial. No save coordinator, journal, MediaStore writes or persisted URI grant. */
internal class RawDiagnosticRunner(private val context: Context) {
    private val stopped = AtomicBoolean()
    private val harnessIo = DiagnosticIoOwner()
    @Volatile private var attemptQuiescent = true
    @Volatile private var cleanupHeld = false
    @Volatile private var samplerQuiescent = true
    val resourcesQuiescent get() = attemptQuiescent && samplerQuiescent && !harnessIo.hasOpenResources && !cleanupHeld
    @Volatile private var activeAttempt: Original24Attempt? = null
    fun cancel() { stopped.set(true); activeAttempt?.requestStop() }
    private fun checkpoint() { if (stopped.get()) throw java.util.concurrent.CancellationException() }
    private fun environment(): JSONObject {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return JSONObject().put("thermalStatus", if (Build.VERSION.SDK_INT >= 29)
            (context.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus else -1)
            .put("batteryTemperatureTenthsC", battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1)
            .put("batteryStatus", status)
            .put("charging", when (status) {
                BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_STATUS_FULL -> true
                BatteryManager.BATTERY_STATUS_DISCHARGING, BatteryManager.BATTERY_STATUS_NOT_CHARGING -> false
                else -> JSONObject.NULL
            })
    }
    private fun memory(): JSONObject {
        val info = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        val runtime = Runtime.getRuntime()
        return JSONObject().put("managedUsedBytes", runtime.totalMemory() - runtime.freeMemory())
            .put("nativeAllocatedBytes", Debug.getNativeHeapAllocatedSize()).put("pssKiB", info.totalPss)
    }
    private fun memorySummary(s: DiagnosticMemorySampler.Summary) = JSONObject()
        .put("quiescent", s.quiescent).put("failed", s.failed).put("sampleCount", s.sampleCount)
        .put("observedMaxManagedBytes", s.observedMaxManagedBytes ?: JSONObject.NULL)
        .put("observedMaxNativeBytes", s.observedMaxNativeBytes ?: JSONObject.NULL)
        .put("requestedIntervalNanos", s.requestedIntervalNanos).put("meanIntervalNanos", s.meanIntervalNanos ?: JSONObject.NULL)
        .put("maxGapNanos", s.maxGapNanos).put("elapsedNanos", s.elapsedNanos)
        .put("collectionTotalNanos", s.collectionTotalNanos).put("collectionMaxNanos", s.collectionMaxNanos)
        .put("skippedIntervals", s.skippedIntervals).put("failedSamples", s.failedSamples)
    private fun requireResources() {
        checkpoint()
        val info = ActivityManager.MemoryInfo().also {
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it)
        }
        check(!info.lowMemory)
        if (Build.VERSION.SDK_INT >= 29) check(
            (context.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus < 2)
    }

    fun run(uri: Uri, observeMemory: Boolean, reverse: Boolean, comparison: DiagnosticComparison, update: (String) -> Unit): String {
        require(comparison != DiagnosticComparison.SAVE_SAFETY || observeMemory)
        return runSession(uri, observeMemory, reverse, comparison, false, update)
    }

    fun runCancellation(uri: Uri, comparison: DiagnosticComparison = DiagnosticComparison.RAW4,
        update: (String) -> Unit): String {
        require(comparison == DiagnosticComparison.RAW4 || comparison == DiagnosticComparison.SAVE_SAFETY)
        return runSession(uri, false, false, comparison, true, update)
    }

    private fun runSession(uri: Uri, observeMemory: Boolean, reverse: Boolean, comparison: DiagnosticComparison,
        cancelProbe: Boolean, update: (String) -> Unit): String {
        // UUID paths stay private and are never serialized into the report.
        val root = File(context.noBackupFilesDir, "raw-diagnostic-${UUID.randomUUID()}")
        check(root.mkdir())
        val reportFile = File(root, "report.json")
        val runs = JSONArray()
        val report = JSONObject().put("schema", 2).put("scope", "private-original24-no-publication")
            .put("mode", if (cancelProbe) "CANCELLATION_BOUNDARIES_NOT_TIMING" else if (observeMemory) "MEMORY_PERIODIC_NOT_TIMING" else "TIMING")
            .put("comparison", comparison.name)
            .put("strength", .5).put("warmth", 0).put("exposureAdjustmentEv", 0)
            .put("ortonWorkers", 4).put("characterWorkers", 2)
            .put("order", if (cancelProbe) "BB" else DiagnosticPlan.order(reverse, observeMemory))
            .put("performanceThresholdPercent", 10).put("performanceDecision", if (cancelProbe || observeMemory) "NOT_TIMING" else "REQUIRES_BOTH_ORDERS")
            .put("runs", runs).put("complete", false)
        if (cancelProbe) report.put("cancellationScope", "AFTER_NATIVE_DECODE_AND_FOUNDATION_NOT_WORKER_IN_FLIGHT")
        if (observeMemory) report.put("sampling", "managed/native 100ms target; PSS before/after only; observed maxima, not peak guarantees; not comparable timing")
        fun persist() {
            harnessIo.output(reportFile.outputStream()).use { it.write(report.toString(2).toByteArray(Charsets.UTF_8)) }
        }
        var source: File? = null
        try {
            requireResources()
            // Bounded read-only import is outside all measured runs. No filename or EXIF queries.
            val input = File(root, "input.dng").also { source = it }
            require(uri.scheme == "content")
            harnessIo.input(requireNotNull(context.contentResolver.openInputStream(uri))).use { stream ->
                harnessIo.output(Files.newOutputStream(input.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)).use { out ->
                    val buffer = ByteArray(64 * 1024); var bytes = 0L
                    while (true) {
                        checkpoint(); val count = stream.read(buffer)
                        if (count < 0) break
                        if (count == 0 || bytes + count > 64L * 1024 * 1024) throw IOException()
                        out.write(buffer, 0, count); bytes += count
                    }
                }
            }
            val fingerprint = DngContentVerifier.identify(input.length(), { harnessIo.input(input.inputStream()) }, stopped::get)
            // Internal synthetic reference avoids retaining the provider URI in any request/journal.
            val reference = SharedOriginalReference("content://diagnostic.local/input", fingerprint, OriginalGrant.USER_SELECTED)
            var baseline: OriginalFingerprint? = null
            val successes = intArrayOf(0, 0)
            val plannedArms = if (cancelProbe) listOf(2, 2) else DiagnosticPlan.workers(reverse, observeMemory)
            for ((index, arm) in plannedArms.withIndex()) {
                val profile = comparison.profile(arm)
                val workers = profile.foundation
                val glowWorkers = profile.glow
                requireResources()
                val item = JSONObject().put("sequence", index).put("arm", arm).put("foundationWorkers", workers).put("glowWorkers", glowWorkers)
                    .put("toneWorkers", profile.tone).put("mistWorkers", profile.mist).put("fuseR7ColorRows", profile.fuseColor)
                    .put("excludedWarmup", (!cancelProbe && DiagnosticPlan.excluded(index, observeMemory))).put("status", "STARTED")
                    .put("environmentBefore", environment())
                runs.put(item); persist(); update("Attempt ${index + 1}/${plannedArms.size}; arm $arm")
                val attemptRoot = File(root, "attempt-$index"); check(attemptRoot.mkdir())
                val output = File(attemptRoot, "result.jpg")
                val io = object : AutoRenderIo {
                    override fun openOriginal() = harnessIo.input(input.inputStream())
                    override fun openOriginalDescriptor(): AutoReadOnlyDescriptor = error("Snapshot required")
                    override fun openOutput() = harnessIo.output(Files.newOutputStream(output.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))
                    override fun openOutputRead() = harnessIo.input(output.inputStream())
                }
                val timing = DiagnosticTimings()
                // PSS is deliberately outside the periodic sampler and render interval.
                if (observeMemory) item.put("memoryBefore", memory())
                val sampler = if (observeMemory) DiagnosticMemorySampler(readMemory = {
                    val runtime = Runtime.getRuntime()
                    DiagnosticMemorySampler.Reading(runtime.totalMemory() - runtime.freeMemory(), Debug.getNativeHeapAllocatedSize())
                }) else null
                val probeStop = AtomicBoolean()
                val cancelBoundary = if (index == 0) dev.cinematic.p0.raw.original.OriginalStage.NATIVE_DECODE
                    else dev.cinematic.p0.raw.original.OriginalStage.R7_FOUNDATION
                val trace = if (cancelProbe) dev.cinematic.p0.raw.original.OriginalStageTrace { stage, _, completed ->
                    if (stage == cancelBoundary && completed) {
                        probeStop.set(true)
                        activeAttempt?.requestStop()
                    }
                } else if (observeMemory) null else timing
                val attempt = Original24Attempt(context, Original24Request("diagnostic", index + 1L, reference), io,
                    File(attemptRoot, "scratch"), trace, parallelFoundationWorkers = workers, parallelGlowWorkers = glowWorkers,
                    parallelToneWorkers = profile.tone, parallelMistWorkers = profile.mist, fuseR7ColorRows = profile.fuseColor)
                attemptQuiescent = false
                activeAttempt = attempt
                if (stopped.get()) attempt.requestStop()
                val start = System.nanoTime()
                var success = false
                var memoryValid = true
                var probeCancelled = false
                var elapsed = 0L
                try {
                    val result = attempt.use {
                        if (sampler != null) {
                            samplerQuiescent = false
                            check(sampler.start())
                        }
                        it.render { stopped.get() || probeStop.get() }
                    }
                    check(!cancelProbe) // A cancellation probe must not return a completed result.
                    elapsed = System.nanoTime() - start
                    check(!attempt.hasUnreleasedResources)
                    val matches = baseline?.let { it == result.fingerprint } ?: true
                    if (baseline == null) baseline = result.fingerprint
                    item.put("status", "SUCCESS").put("jpegMatchesSessionBaseline", matches)
                        .put("sourceUnchanged", true).put("sameJpegReadback", true)
                    if (!matches) throw IOException()
                    success = true
                } catch (failure: Exception) {
                    probeCancelled = cancelProbe && probeStop.get() && !stopped.get() && failure is java.util.concurrent.CancellationException
                    item.put("status", if (probeCancelled) "CANCELLED_EXPECTED" else if (stopped.get()) "STOPPED" else "FAILED")
                    // No class/message/stack: providers and codecs can embed private data.
                    if (!probeCancelled) throw failure
                } finally {
                    if (elapsed == 0L) elapsed = System.nanoTime() - start
                    if (sampler != null) {
                        val observed = sampler.stopAndJoin()
                        samplerQuiescent = observed.quiescent
                        memoryValid = observed.quiescent && !observed.failed && observed.sampleCount > 0
                        item.put("memoryObserved", memorySummary(observed))
                        if (!memoryValid) item.put("status", "FAILED_MEMORY_OBSERVATION")
                    }
                    attemptQuiescent = !attempt.hasUnreleasedResources
                    if (attemptQuiescent) activeAttempt = null
                    if (!observeMemory && !cancelProbe) {
                        val spans = JSONArray()
                        timing.spans.forEach { span -> spans.put(JSONObject().put("stage", span.stage.name)
                            .put("elapsedNanos", span.nanos).put("completed", span.completed)) }
                        item.put("renderAndCloseNanos", elapsed).put("stages", spans)
                            .put("unattributedResidualNanos", timing.residualNanos(elapsed))
                    }
                    item.put("environmentAfter", environment())
                    if (observeMemory) item.put("memoryAfter", memory())
                    persist()
                    // Failed attempts remain private for owner cleanup; successful scratch is already disposed.
                    if (success) check(output.delete())
                }
                check(memoryValid)
                if (cancelProbe) {
                    check(probeCancelled && attemptQuiescent && !output.exists())
                    DngContentVerifier.verify(fingerprint, { harnessIo.input(input.inputStream()) }, stopped::get)
                    item.put("cancelBoundary", cancelBoundary.name).put("ownerClosed", true)
                        .put("noCompletedResult", true).put("partialJpegAbsent", true).put("sourceUnchanged", true)
                        .put("privateFailedScratchRetained", File(attemptRoot, "scratch").exists())
                    persist()
                    successes[arm - 1]++
                } else if (!DiagnosticPlan.excluded(index, observeMemory)) successes[arm - 1]++
            }
            // Verify provider bytes again outside timing; no writes to original, ever.
            DngContentVerifier.verify(fingerprint, { harnessIo.input(requireNotNull(context.contentResolver.openInputStream(uri))) }, stopped::get)
            report.put("selectedOriginalUnchanged", true).put("complete", if (cancelProbe) successes[1] == 2 else successes.all { it == (if (observeMemory) 1 else DiagnosticPlan.MEASURED_PER_ARM) })
                .put("measuredSuccessesA", if (cancelProbe) 0 else successes[0]).put("measuredSuccessesB", if (cancelProbe) 0 else successes[1])
                .put("expectedCancellationChecks", if (cancelProbe) successes[1] else 0).put("status", "SUCCESS")
        } catch (_: Exception) {
            report.put("status", if (stopped.get()) "STOPPED" else "FAILED")
        } finally {
            // Imported source is a disposable private copy; failed scratch is retained, not shared or uploaded.
            val cleanup = harnessIo.finish(attemptQuiescent && samplerQuiescent) {
                source?.let { !it.exists() || it.delete() } ?: true
            }
            cleanupHeld = !cleanup.quiescent || !cleanup.sourceRemoved || cleanup.closeFailed
            report.put("privateInputRemoved", cleanup.sourceRemoved).put("harnessCloseFailed", cleanup.closeFailed)
            if (cleanupHeld) report.put("complete", false).put("status", "HOLD_PRIVATE_CLEANUP")
            report.put("resourcesQuiescent", resourcesQuiescent)
            if (!cleanup.quiescent) report.put("status", "HOLD_OWNER_NOT_CLOSED")
            persist()
        }
        return report.toString(2)
    }
}
