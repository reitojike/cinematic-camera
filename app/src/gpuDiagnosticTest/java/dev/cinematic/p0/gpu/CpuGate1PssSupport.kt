package dev.cinematic.p0.gpu

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import java.util.regex.Pattern
import java.io.File
import java.io.InputStream
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.util.ArrayDeque
import org.json.JSONArray
import org.json.JSONObject

/** Optional external UI PSS reference only. No product types, renderer observation or forced release. */
internal class CpuGate1PssSupport(private val folder: File, val started: Long) {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val monitor get() = ActivityLifecycleMonitorRegistry.getInstance()
    private var hub: WeakReference<Activity>? = null
    private var roll: WeakReference<Activity>? = null
    private var hubId = 0; private var hubTask = -1; private var rollId = 0
    private var destroyed = false
    // Scalars only: never keep a stopped/destroyed Hub alive to make a PSS boundary pass.
    private var hubStage: Stage? = null
    private var hubEventCount = 0
    private var hubEventElapsedMs: Long? = null
    private var hubDestroyed = false
    private var hubDestroyedElapsedMs: Long? = null
    private val hubTransitions = ArrayDeque<JSONObject>()
    private var hubTransitionsDropped = 0
    private var hubTransitionRecordFailures = 0
    private var lastLogcatWallMs = System.currentTimeMillis()
    private var uiTimeoutCount = 0
    val uiTimeoutDiagnostics = JSONArray()
    private var hubReferenceUnavailableObserved = false
    private var hubDestroyedChangingConfigurations: Boolean? = null
    private var differentHubId: Int? = null
    private var differentHubStage: Stage? = null
    private var differentHubElapsedMs: Long? = null
    private var originalIdentity: String? = null
    private val callback = ActivityLifecycleCallback { activity, stage ->
        if (activity === roll?.get() && stage == Stage.DESTROYED) destroyed = true
        if (activity.javaClass.name == "dev.cinematic.p0.raw.DiagnosticHubActivity") {
            // Only scalar lifecycle events, including startup before bindHub. Never Activity references.
            try {
                if (hubTransitions.size == 64) { hubTransitions.removeFirst(); hubTransitionsDropped++ }
                hubTransitions.addLast(JSONObject().put("elapsedMs", SystemClock.elapsedRealtime())
                    .put("identity", System.identityHashCode(activity)).put("task", activity.taskId)
                    .put("stage", stage.name).put("changingConfigurations", activity.isChangingConfigurations))
            } catch (_: Throwable) { hubTransitionRecordFailures++ }
        }
        if (activity === hub?.get()) {
            hubStage = stage; hubEventCount++; hubEventElapsedMs = SystemClock.elapsedRealtime()
            if (stage == Stage.DESTROYED) {
                hubDestroyed = true; hubDestroyedElapsedMs = hubEventElapsedMs
                hubDestroyedChangingConfigurations = activity.isChangingConfigurations
            }
        } else if (hub != null && activity.javaClass.name == "dev.cinematic.p0.raw.DiagnosticHubActivity" && activity.taskId == hubTask) {
            differentHubId = System.identityHashCode(activity); differentHubStage = stage
            differentHubElapsedMs = SystemClock.elapsedRealtime()
        }
    }
    init { inst.runOnMainSync { monitor.addLifecycleCallback(callback) } }
    fun dispose() = inst.runOnMainSync { monitor.removeLifecycleCallback(callback) }
    fun bindHub(activity: Activity) = inst.runOnMainSync {
        check(hub == null) { "Original Hub must never be rebound" }
        check(activity.javaClass.name == "dev.cinematic.p0.raw.DiagnosticHubActivity")
        hub = WeakReference(activity); hubId = System.identityHashCode(activity); hubTask = activity.taskId
        hubStage = monitor.getLifecycleStageOf(activity)
    }
    fun bindRoll(): Boolean {
        var found = false
        inst.runOnMainSync {
            val current = monitor.getActivitiesInStage(Stage.RESUMED)
                .filter { it.javaClass.name == "dev.cinematic.p0.photoroll.PhotoRollActivity" }
            check(current.size <= 1)
            current.singleOrNull()?.let {
                check(it.taskId == hubTask); roll = WeakReference(it); rollId = System.identityHashCode(it); found = true
            }
        }
        return found
    }
    fun withRoll(block: (Activity) -> Unit) = inst.runOnMainSync { block(checkNotNull(roll?.get())) }
    fun lifecycle(hubExpected: Boolean, requireDestroyed: Boolean = false): String {
        var result = ""
        inst.runOnMainSync {
            val observed = hub?.get()
            val failure = hubFailure(observed)
            check(failure == null) { checkNotNull(failure) }
            val original = checkNotNull(observed)
            val active = if (hubExpected) original else checkNotNull(roll?.get())
            check(active.taskId == hubTask && active.hasWindowFocus() &&
                monitor.getLifecycleStageOf(active) == Stage.RESUMED &&
                monitor.getActivitiesInStage(Stage.RESUMED).singleOrNull() === active)
            if (requireDestroyed) check(destroyed)
            result = "hub=$hubId/task=$hubTask/hubWeakAlive=true/hubResumed=${monitor.getLifecycleStageOf(original) == Stage.RESUMED}" +
                "/hubFocus=${original.hasWindowFocus()}/roll=$rollId/rollDestroyed=$destroyed/foreground=${if (hubExpected) "hub" else "roll"}"
        }
        return result
    }
    private fun hubFailure(original: Activity?): String? {
        if (hub == null) return "ORIGINAL_HUB_NOT_BOUND"
        if (original == null) hubReferenceUnavailableObserved = true
        return hubContinuityFailure(original != null,
            original != null && System.identityHashCode(original) == hubId && original.taskId == hubTask,
            hubDestroyed || original?.isDestroyed == true || original?.let { monitor.getLifecycleStageOf(it) } == Stage.DESTROYED,
            differentHubId != null)
    }

    /** Diagnostic snapshot only. A missing weak reference never identifies its cause or proves GC. */
    fun lifecycleEvidence(): JSONObject {
        var result = JSONObject()
        inst.runOnMainSync {
            val original = hub?.get()
            val failure = hubFailure(original)
            result = JSONObject().put("observedElapsedMs", SystemClock.elapsedRealtime())
                .put("hubIdentity", hubId).put("hubTask", hubTask).put("hubWeakAlive", original != null)
                .put("hubCurrentStage", original?.let { monitor.getLifecycleStageOf(it).name } ?: JSONObject.NULL)
                .put("hubLastObservedStage", hubStage?.name ?: JSONObject.NULL).put("hubLifecycleCallbackCount", hubEventCount)
                .put("hubLastCallbackElapsedMs", hubEventElapsedMs ?: JSONObject.NULL)
                .put("hubDestroyedObserved", hubDestroyed).put("hubCurrentlyDestroyed", original?.isDestroyed ?: JSONObject.NULL)
                .put("hubDestroyedChangingConfigurations", hubDestroyedChangingConfigurations ?: JSONObject.NULL)
                .put("hubDestroyedElapsedMs", hubDestroyedElapsedMs ?: JSONObject.NULL)
                .put("hubTransitions", JSONArray(hubTransitions.toList())).put("hubTransitionsLimit", 64)
                .put("hubTransitionsDropped", hubTransitionsDropped).put("hubTransitionRecordFailures", hubTransitionRecordFailures)
                .put("differentHubSameTaskObserved", differentHubId != null).put("differentHubIdentity", differentHubId ?: JSONObject.NULL)
                .put("differentHubLastStage", differentHubStage?.name ?: JSONObject.NULL).put("differentHubLastElapsedMs", differentHubElapsedMs ?: JSONObject.NULL)
                .put("hubContinuityFailure", failure ?: JSONObject.NULL).put("weakReferenceLossCause", "UNKNOWN")
                .put("priorWeakReferenceLossOutcome", when {
                    hub == null -> "NOT_EVALUATED"
                    hubReferenceUnavailableObserved -> "OBSERVED_CAUSE_UNDETERMINED"
                    else -> "NOT_OBSERVED_CAUSE_UNDETERMINED"
                })
        }
        return result
    }
    fun deadline(capMs: Long, closing: Boolean = false) = minOf(SystemClock.elapsedRealtime() + capMs,
        started + 780_000 - if (closing) 0 else 45_000)
    fun remaining(deadline: Long) = remainingAt(deadline, SystemClock.elapsedRealtime()).also { check(it > 0) { "Soft elapsed budget exhausted" } }
    fun await(deadline: Long, predicate: () -> Boolean) {
        try {
            while (true) { remaining(deadline); if (predicate()) { remaining(deadline); return }; SystemClock.sleep(30) }
        } catch (failure: Throwable) {
            if (remainingAt(deadline, SystemClock.elapsedRealtime()) == 0L) recordUiTimeout("elapsed_ui_wait", failure)
            throw failure
        }
    }

    /** Private failure evidence only; no navigation/retry. At most primary + cleanup timeout artifacts. */
    fun recordUiTimeout(label: String, originalFailure: Throwable) {
        try {
            val row = JSONObject().put("label", label).put("startedElapsedMs", SystemClock.elapsedRealtime())
                .put("privateOnly", true).put("containsUnredactedUi", true).put("hardIoDeadline", false)
            uiTimeoutDiagnostics.put(row)
            if (uiTimeoutCount >= 2) { row.put("status", "SKIPPED_ARTIFACT_LIMIT"); return }
            val prefix = "private-ui-timeout-${++uiTimeoutCount}"
            val device = UiDevice.getInstance(inst)
            fun save(extension: String, action: (File) -> Unit) {
                val file = File(folder, "$prefix.$extension")
                val artifact = JSONObject().put("file", file.name).put("startedElapsedMs", SystemClock.elapsedRealtime())
                row.put(extension, artifact)
                try { check(file.createNewFile()); action(file); check(file.length() > 0); artifact.put("status", "SAVED") }
                catch (diagnostic: Throwable) { artifact.put("status", "FAILED_OR_PARTIAL").put("failureType", diagnostic.javaClass.simpleName) }
                finally { artifact.put("bytes", file.length()).put("endedElapsedMs", SystemClock.elapsedRealtime()) }
            }
            save("xml") { device.dumpWindowHierarchy(it) }
            save("png") { check(device.takeScreenshot(it)) }
            row.put("endedElapsedMs", SystemClock.elapsedRealtime())
                .put("costMs", SystemClock.elapsedRealtime() - row.getLong("startedElapsedMs"))
        } catch (diagnostic: Throwable) { originalFailure.addSuppressed(diagnostic) }
    }

    /** After the PSS outcome, outside its settle/dump boundaries; still adds load before later samples. */
    private fun recordPointDiagnostics(row: JSONObject, deadline: Long) {
        val started = SystemClock.elapsedRealtime()
        val diagnostic = JSONObject().put("privateOnly", true).put("timing", "AFTER_PSS_OUTCOME")
            .put("startedElapsedMs", started).put("observedWallMs", System.currentTimeMillis())
            .put("heapPressureCause", "UNKNOWN").put("hardIoDeadline", false)
        row.put("privateDiagnostics", diagnostic)
        try {
            val runtime = Runtime.getRuntime()
            val heapAt = SystemClock.elapsedRealtime(); val allocated = runtime.totalMemory(); val free = runtime.freeMemory(); val maximum = runtime.maxMemory()
            diagnostic.put("javaHeap", JSONObject().put("observedElapsedMs", heapAt).put("usedBytes", allocated - free)
                .put("maxBytes", maximum).put("allocatedBytes", allocated).put("freeBytes", free).put("nonAtomicEstimate", true)
                .put("consistentSnapshot", free in 0..allocated && allocated <= maximum)
                .put("meaning", "Runtime process heap estimate after PSS outcome, not a memory-pressure or destruction-cause proof"))
            try { diagnostic.put("lifecycle", lifecycleEvidence()) }
            catch (failure: Throwable) { diagnostic.put("lifecycleFailureType", failure.javaClass.simpleName) }
            val log = JSONObject().put("status", "NOT_STARTED").put("maxBytes", 65536).put("maxMatchingRecords", 80)
                .put("shellTimeoutSeconds", 2).put("buffers", "system,events").put("maxWindowMs", 120000)
                .put("tags", "ActivityManager:I ActivityTaskManager:I am_destroy_activity:I wm_destroy_activity:I am_kill:I")
                .put("scope", "target package or Hub name only; absent/truncated logs do not prove no destruction")
            diagnostic.put("activityManagerLogcat", log)
            if (remainingAt(deadline, SystemClock.elapsedRealtime()) <= 2500) {
                log.put("status", "SKIPPED_REMAINING_SOFT_BUDGET"); return
            }
            val queryAt = System.currentTimeMillis(); val from = maxOf(lastLogcatWallMs, queryAt - 120000)
            val timestamp = "${from / 1000}.${(from % 1000).toString().padStart(3, '0')}"
            val command = "timeout 2s logcat -d -b system -b events -v epoch -T '$timestamp' -m 80 " +
                "-e 'dev[.]cinematic[.]p0[.]gpudiagnostic|DiagnosticHubActivity' " +
                "ActivityManager:I ActivityTaskManager:I am_destroy_activity:I wm_destroy_activity:I am_kill:I '*:S' 2>&1"
            val file = File(folder, "private-am-slot-${row.getInt("slot").toString().padStart(2, '0')}.txt")
            log.put("fromWallMs", from).put("queryStartedWallMs", queryAt).put("command", command).put("file", file.name)
                .put("windowClipped", from != lastLogcatWallMs).put("wallClockMovedBackwards", queryAt < lastLogcatWallMs)
                .put("status", "STARTED")
            check(file.createNewFile())
            var copied = 0
            ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand(command)).use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(4096)
                    while (copied < 65536) {
                        val n = input.read(buffer, 0, minOf(buffer.size, 65536 - copied)); if (n < 0) break
                        output.write(buffer, 0, n); copied += n
                    }
                }
            }
            // Start the next query at this query's start to avoid a gap. Overlap is intentional, not merged evidence.
            lastLogcatWallMs = queryAt
            log.put("bytes", file.length()).put("byteCapReached", copied == 65536)
                .put("status", "RAW_SAVED_REQUIRES_HOST_AUDIT").put("stderrIncluded", true).put("exitCode", JSONObject.NULL)
                .put("queryEndedWallMs", System.currentTimeMillis())
        } catch (failure: Throwable) {
            diagnostic.put("failureType", failure.javaClass.simpleName)
            diagnostic.optJSONObject("activityManagerLogcat")?.put("status", "FAILED_OR_PARTIAL")
        }
        finally { diagnostic.put("endedElapsedMs", SystemClock.elapsedRealtime()).put("costMs", SystemClock.elapsedRealtime() - started) }
    }
    /** Standard DocumentsUI root -> directory only; never search other providers for a filename. */
    fun openDownload(deadline: Long): JSONObject {
        val device = UiDevice.getInstance(inst)
        await(deadline) { device.currentPackageName?.endsWith(".documentsui") == true }
        val pkg = checkNotNull(device.currentPackageName)
        fun find(selector: BySelector): UiObject2 {
            var result: UiObject2? = null
            await(deadline) { result = device.findObject(selector); result != null }
            return checkNotNull(result)
        }
        val roots = By.res(pkg, "roots_list")
        val labels = listOf(android.os.Build.MODEL, "Internal storage", "Internal shared storage", "内部ストレージ", "内部共有ストレージ")
        val internal = By.pkg(pkg).text(Pattern.compile(labels.distinct().joinToString("|") { Pattern.quote(it) })).hasAncestor(roots)
        fun openRoots() {
            if (!device.hasObject(roots)) find(By.pkg(pkg).clazz("android.widget.ImageButton")
                .desc(Pattern.compile("Show roots|Open navigation drawer|ルートを表示|ナビゲーション ドロワーを開く"))).click()
            find(roots)
        }
        openRoots()
        if (!device.hasObject(internal)) {
            remaining(deadline); device.pressBack()
            find(By.pkg(pkg).desc(Pattern.compile("More options|その他のオプション"))).click()
            find(By.pkg(pkg).text(Pattern.compile("Show internal storage|内部ストレージを表示"))).click()
            openRoots()
        }
        find(internal).click(); await(deadline) { !device.hasObject(roots) }
        val directory = By.res(pkg, "dir_list")
        fun rewind() {
            await(deadline) { val list = device.findObject(directory); list != null && (!list.isScrollable || !list.scroll(Direction.UP, .8f)) }
        }
        rewind(); find(By.pkg(pkg).text("Download").hasAncestor(directory)).click()
        find(By.pkg(pkg).text("Download").hasAncestor(By.res(pkg, "toolbar"))); rewind()
        // This is picker-location evidence, not an intercepted ACTION_OPEN_DOCUMENT result URI.
        return JSONObject().put("documentsUiPackage", pkg).put("rootEvidence", "exact internal-storage label inside roots_list")
            .put("directoryEvidence", "Download child inside internal dir_list clicked; Download toolbar confirmed; drawer closed; directory rewound")
            .put("expectedProvider", "com.android.externalstorage.documents").put("directSelectedUriObservation", false)
    }
    fun verifyFixture(): JSONObject {
        val id = InstrumentationRegistry.getArguments().getString("gate1DngDocumentId")
        check(id == "primary:Download/gate1-common-synthetic-1024x768-v1.dng")
        val uri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", checkNotNull(id))
        val target = inst.targetContext
        check(target.checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED)
        val (bytes, sha) = checkNotNull(target.contentResolver.openInputStream(uri)).use(::fingerprint)
        check(bytes == 1573250L && sha == "796555f835a38a94162383dc9f037023a1fd6400037d7b5f3f0e287ccd64bf3a")
        return JSONObject().put("documentId", id).put("bytes", bytes).put("sha256", sha)
    }
    private fun identity(): String {
        val pid = Process.myPid(); val target = inst.targetContext.packageName
        ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand("pidof $target")).use {
            val bytes = ByteArray(4096); val n = it.read(bytes)
            check(n > 0 && n < bytes.size && it.read() == -1 && String(bytes, 0, n, Charsets.UTF_8).trim() == pid.toString())
        }
        val base = File("/proc/$pid")
        val name = File(base, "cmdline").readText().substringBefore('\u0000')
        val uid = File(base, "status").useLines { lines -> lines.single { it.startsWith("Uid:") }.substringAfter(':').trim().split(Regex("\\s+")).first().toInt() }
        val start = File(base, "stat").readText().substringAfterLast(") ").split(' ')[19].toLong()
        check(name == target && uid == Process.myUid() && uid == inst.targetContext.applicationInfo.uid && start > 0)
        return "$pid/$uid/$start/$name"
    }
    /** -t 10 is not a hard deadline on shell/Binder/pipe/file IO. No retries or missing-value substitution. */
    fun capture(row: JSONObject, deadline: Long, boundary: () -> String) {
        row.put("status", "started").put("boundaryMeaning", "UI_EXTERNAL_REFERENCE")
        var phase = "before_settle"
        try {
            remaining(deadline)
            val settledIdentity = identity(); val settled = boundary()
            row.put("boundaryBeforeSettle", settled).put("identityBeforeSettle", settledIdentity).put("beforeSettleElapsedMs", SystemClock.elapsedRealtime())
            check(remaining(deadline) > 1000); SystemClock.sleep(1000); remaining(deadline)
            phase = "before_dump"
            val pre = identity(); val before = boundary()
            row.put("boundaryBeforeDump", before).put("identityBeforeDump", pre).put("beforeDumpElapsedMs", SystemClock.elapsedRealtime())
            if (originalIdentity == null) originalIdentity = pre
            check(settled == before && settledIdentity == pre && pre == originalIdentity)
            remaining(deadline)
            phase = "dump"
            val file = File(folder, "pss-${row.getInt("slot").toString().padStart(2, '0')}.txt")
            check(file.createNewFile())
            val begin = SystemClock.elapsedRealtime()
            row.put("rawFileName", file.name).put("beginElapsedMs", begin).put("unit", "KiB")
                .put("command", "dumpsys -t 10 meminfo -d ${Process.myPid()}")
            try {
                ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand("dumpsys -t 10 meminfo -d ${Process.myPid()}")).use { input ->
                    file.outputStream().use { output -> input.copyTo(output, 8192) }
                }
            } finally { row.put("endElapsedMs", SystemClock.elapsedRealtime()).put("dumpCostMs", SystemClock.elapsedRealtime() - begin).put("rawBytes", file.length()) }
            phase = "after_dump"
            val after = boundary(); val post = identity()
            row.put("boundaryAfterDump", after).put("identityAfterDump", post).put("afterDumpElapsedMs", SystemClock.elapsedRealtime())
            check(after == before && post == pre); remaining(deadline)
            phase = "parse"
            file.useLines { parseMeminfo(it, row, Process.myPid(), inst.targetContext.packageName) }
            remaining(deadline); row.put("status", "verified_ui_external_reference")
        } catch (failure: Throwable) {
            row.put("status", "invalid_reference_sample").put("failureType", failure.javaClass.simpleName).put("failurePhase", phase)
            try { row.put("lifecycleAfterFailure", lifecycleEvidence()) }
            catch (diagnostic: Throwable) { failure.addSuppressed(diagnostic) }
            throw failure
        } finally {
            // Diagnostics must not replace the original PSS/lifecycle failure, including allocation/IO failures.
            try { recordPointDiagnostics(row, deadline) }
            catch (diagnostic: Throwable) { runCatching { row.put("privateDiagnosticsFailureType", diagnostic.javaClass.simpleName) } }
        }
    }
    companion object {
        fun hubContinuityFailure(weakAlive: Boolean, sameIdentity: Boolean, destroyed: Boolean, differentHub: Boolean): String? = when {
            destroyed -> "ORIGINAL_HUB_DESTROYED"
            differentHub -> "DIFFERENT_HUB_SAME_TASK_OBSERVED"
            !weakAlive -> "ORIGINAL_HUB_REFERENCE_UNAVAILABLE_CAUSE_UNKNOWN"
            !sameIdentity -> "ORIGINAL_HUB_IDENTITY_MISMATCH"
            else -> null
        }
        fun checkHubContinuityContracts() {
            check(hubContinuityFailure(true, true, false, false) == null)
            check(hubContinuityFailure(true, true, true, false) == "ORIGINAL_HUB_DESTROYED")
            check(hubContinuityFailure(false, false, true, false) == "ORIGINAL_HUB_DESTROYED")
            check(hubContinuityFailure(true, true, false, true) == "DIFFERENT_HUB_SAME_TASK_OBSERVED")
            check(hubContinuityFailure(true, false, false, false) == "ORIGINAL_HUB_IDENTITY_MISMATCH")
            val reference = WeakReference(Any()).also { it.clear() } // Deterministic reference loss, not forced GC.
            check(hubContinuityFailure(reference.get() != null, false, false, false) == "ORIGINAL_HUB_REFERENCE_UNAVAILABLE_CAUSE_UNKNOWN")
        }
        fun remainingAt(deadline: Long, now: Long) = (deadline - now).coerceAtLeast(0)
        fun fingerprint(input: InputStream): Pair<Long, String> {
            val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(8192); var bytes = 0L
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n); bytes += n }
            return bytes to digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
        // Minimal copy of Gate1DngObservationSupport's 8/11-column parser; no GPU helper change.
        fun parseMeminfo(lines: Sequence<String>, row: JSONObject, pid: Int, target: String) {
            val split = Regex("\\s+")
            var headers = 0; var timeout = false; var top: List<String>? = null
            var columns: List<String>? = null; var summaryTotal: Long? = null
            val values = mutableMapOf<String, List<Long>>()
            lines.forEach { line ->
                val text = line.trim(); val parts = text.split(split)
                if (line.contains("MEMINFO in pid $pid [$target]")) headers++
                if (line.contains("DUMP TIMEOUT") || line.contains("Error dumping service") || line.contains("Can't find service")) timeout = true
                if (parts.firstOrNull() == "Pss" && parts.lastOrNull() == "Heap") {
                    check(top == null)
                    val normalized = parts.map { if (it == "SwapPss") "Swap" else it }
                    check(normalized == listOf("Pss", "Private", "Private", "Swap", "Rss", "Heap", "Heap", "Heap") ||
                        normalized == listOf("Pss", "Pss", "Shared", "Private", "Shared", "Private", "Swap", "Rss", "Heap", "Heap", "Heap"))
                    top = parts
                } else if (top != null && columns == null && parts.firstOrNull() == "Total") {
                    val expected = if (checkNotNull(top).size == 8) listOf("Total", "Dirty", "Clean", "Dirty", "Total", "Size", "Alloc", "Free")
                        else listOf("Total", "Clean", "Dirty", "Dirty", "Clean", "Clean", "Dirty", "Total", "Size", "Alloc", "Free")
                    check(parts == expected); columns = checkNotNull(top).zip(parts).map { (a, b) -> "$a $b" }
                    row.put("meminfoColumns", JSONArray(columns))
                }
                val label = when {
                    text.startsWith("Native Heap ") -> "Native Heap"
                    text.startsWith("Dalvik Heap ") -> "Dalvik Heap"
                    text.startsWith("TOTAL ") && !text.startsWith("TOTAL PSS:") && !text.startsWith("TOTAL RSS:") -> "TOTAL"
                    else -> null
                }
                if (label != null) {
                    val fields = text.removePrefix(label).trim().split(split)
                    check(fields.size == checkNotNull(columns).size && fields.all { it.matches(Regex("[0-9]+")) })
                    check(values.put(label, fields.map(String::toLong)) == null)
                }
                Regex("^TOTAL PSS:\\s*(\\d+)").find(text)?.let {
                    check(summaryTotal == null); summaryTotal = it.groupValues[1].toLong()
                }
            }
            check(headers == 1 && !timeout && values.keys == setOf("Native Heap", "Dalvik Heap", "TOTAL"))
            val names = checkNotNull(columns)
            val pss = names.indexOf("Pss Total").also { check(it >= 0) }
            val alloc = names.indexOf("Heap Alloc").also { check(it >= 0) }
            val total = values.getValue("TOTAL")[pss]; check(summaryTotal != null && summaryTotal == total)
            row.put("totalPssKb", total).put("nativePssKb", values.getValue("Native Heap")[pss]).put("dalvikPssKb", values.getValue("Dalvik Heap")[pss])
                .put("nativeHeapAllocKb", values.getValue("Native Heap")[alloc]).put("managedHeapAllocKb", values.getValue("Dalvik Heap")[alloc])
        }
        /** Pure fixture checks: no app launch, shell, file writes, dependencies or product hooks. */
        fun checkPureContracts() {
            check(remainingAt(1000, 999) == 1L && remainingAt(1000, 1000) == 0L && remainingAt(1000, 1001) == 0L)
            for (wide in listOf(false, true)) {
                val top = if (wide) "Pss Pss Shared Private Shared Private SwapPss Rss Heap Heap Heap" else "Pss Private Private Swap Rss Heap Heap Heap"
                val bottom = if (wide) "Total Clean Dirty Dirty Clean Clean Dirty Total Size Alloc Free" else "Total Dirty Clean Dirty Total Size Alloc Free"
                val numbers = List(if (wide) 11 else 8) { "1" }.joinToString(" ")
                val lines = listOf("** MEMINFO in pid 42 [test] **", top, bottom, "Native Heap $numbers", "Dalvik Heap $numbers", "TOTAL $numbers", "TOTAL PSS: 1")
                check(JSONObject().also { parseMeminfo(lines.asSequence(), it, 42, "test") }.getLong("totalPssKb") == 1L)
                for (bad in listOf(lines.dropLast(1), lines + "DUMP TIMEOUT", lines + lines[3], lines.map { if (it == bottom) "Total Unknown" else it }, lines.drop(1), lines.dropLast(1) + "TOTAL PSS: 2", lines + lines[0], lines.map { it.replace("Native Heap $numbers", "Native Heap 1") }, lines.map { it.replace("Dalvik Heap $numbers", "Dalvik Heap -1") })) {
                    check(runCatching { parseMeminfo(bad.asSequence(), JSONObject(), 42, "test") }.isFailure)
                }
            }
        }
    }
}
