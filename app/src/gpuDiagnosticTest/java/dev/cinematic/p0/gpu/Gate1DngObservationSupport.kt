package dev.cinematic.p0.gpu

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import dev.cinematic.p0.raw.LinearSrgbImage
import dev.cinematic.p0.rawcapture.DngContentVerifier
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.util.regex.Pattern

/** Test-only Files identity and separate PSS observations. No product hooks, native release,
 * permissions, GC, polling server or extra GPU render. Dump timeout is NOT an end-to-end IO deadline. */
internal class Gate1DngObservationSupport(private val folder: File, private val prefix: String) {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val target get() = instrumentation.targetContext
    private val device get() = UiDevice.getInstance(instrumentation)
    val pss = JSONArray()
    private var originalIdentity: String? = null
    val auxiliaryPicker = JSONObject().put("phase", "not_started").put("cancelStatus", "not_required")
        .put("evidenceScope", "picker navigation only; not direct selected result URI or absence of initial synthetic renders")
        .put("waitScope", "10s per navigation/cancel polling stage; not a hard end-to-end UI automation deadline")
    private var pickerOwner: WeakReference<GpuPreviewDiagnosticActivity>? = null
    private var pickerPackage: String? = null
    private var pickerPending = false
    private var cancelAttempted = false
    private var auxiliaryUiEvidenceEnabled = false
    private val uiTimeoutEvidence = JSONArray()

    fun verifyTarget(): JSONObject {
        val args = InstrumentationRegistry.getArguments()
        check(args.getString("gate1AuthorizedTarget") == Build.MODEL)
        val expected = checkNotNull(args.getString("gate1TargetApkSha256"))
        check(expected == "cf79d84ea2e225d390516ff8f7bcfe3c2d6e93c775f5118a0b97008cfd4285ac")
        val digest = MessageDigest.getInstance("SHA-256")
        File(target.applicationInfo.sourceDir).inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        check(hex(digest.digest()) == expected)
        return JSONObject().put("model", Build.MODEL).put("hardware", Build.HARDWARE)
            .put("targetApkSha256", expected).put("authorizationMeaning", "guard only; explicit host/user authorization is separately required before any physical-device access")
    }

    fun loadThroughFiles(withActivity: ((GpuPreviewDiagnosticActivity) -> Unit) -> Unit,
        auxiliaryStrict: Boolean = false, failAfterDownload: Boolean = false): JSONObject {
        check(!failAfterDownload || auxiliaryStrict)
        val args = InstrumentationRegistry.getArguments()
        val documentId = checkNotNull(args.getString("gate1DngDocumentId"))
        check(documentId.matches(Regex("primary:Download/[A-Za-z0-9_.-]+\\.dng")))
        val uri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", documentId)
        if (auxiliaryStrict) {
            withActivity {
                check(it.diagnosticState().resumed && it.hasWindowFocus())
                pickerOwner = WeakReference(it)
            }
            auxiliaryUiEvidenceEnabled = true
            auxiliaryPicker.put("phase", "launch_requested").put("uiTimeoutEvidence", uiTimeoutEvidence)
                .put("expectedAuthority", "com.android.externalstorage.documents")
                .put("expectedLocation", "primary:Download")
                .put("selectionScope", "registered exact name only, via the confirmed internal root and its Download row; no provider fallback; selected result URI not directly observed")
        }
        find("Files button", By.pkg(target.packageName).text("試験用DNGをFilesから選択"), Direction.DOWN).click()
        await("DocumentsUI foreground") {
            if (auxiliaryStrict) device.currentPackageName in setOf("com.android.documentsui", "com.google.android.documentsui")
            else device.currentPackageName?.endsWith(".documentsui") == true
        }
        val filesPackage = checkNotNull(device.currentPackageName)
        if (auxiliaryStrict) {
            pickerPackage = filesPackage; pickerPending = true
            auxiliaryPicker.put("documentsUiPackage", filesPackage).put("phase", "picker_foreground")
            openAuxiliaryDownload(filesPackage, documentId.substringAfterLast('/'))
            check(!failAfterDownload) { "intentional_auxiliary_picker_failure_after_download" }
            findAuxiliaryEntry(filesPackage, documentId.substringAfterLast('/'), "preregistered_dng").click()
            auxiliaryPicker.put("phase", "selection_clicked")
        } else {
            openInternalDownload(filesPackage)
            find("preregistered DNG", By.pkg(filesPackage).text(documentId.substringAfterLast('/')), Direction.DOWN).click()
        }
        await("diagnostic app returned") { device.currentPackageName == target.packageName }
        if (auxiliaryStrict) { pickerPending = false; auxiliaryPicker.put("phase", "app_returned") }
        await("native DNG load", 60_000) {
            var loaded = false
            withActivity {
                val state = it.diagnosticState()
                if (auxiliaryStrict) {
                    loaded = state.resumed && !state.gpuError && state.inputResult == "loaded" && !state.loading
                } else {
                    check(state.resumed && !state.gpuError)
                    check(state.inputResult !in setOf("load_error", "readiness_timeout"))
                    loaded = state.inputResult == "loaded" && !state.loading
                }
            }
            loaded
        }
        check(target.checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION)
            == PackageManager.PERMISSION_GRANTED)
        val fingerprint = DngContentVerifier.identify(1573250L, { checkNotNull(target.contentResolver.openInputStream(uri)) })
        check(fingerprint.bytes == 1573250L && fingerprint.sha256 == "796555f835a38a94162383dc9f037023a1fd6400037d7b5f3f0e287ccd64bf3a")
        return JSONObject().put("sourceSha256", fingerprint.sha256).put("sourceBytes", fingerprint.bytes)
            .put("filesSelection", "exact preregistered document through existing ACTION_OPEN_DOCUMENT")
            .put("loadingMeaning", "Activity loaded and loading=false; native pending/owner cleanup not observed or proven")
    }

    fun verifyOriginalUnchanged() {
        val documentId = checkNotNull(InstrumentationRegistry.getArguments().getString("gate1DngDocumentId"))
        val uri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", documentId)
        val fingerprint = DngContentVerifier.identify(1573250L, { checkNotNull(target.contentResolver.openInputStream(uri)) })
        check(fingerprint.bytes == 1573250L && fingerprint.sha256 == "796555f835a38a94162383dc9f037023a1fd6400037d7b5f3f0e287ccd64bf3a")
    }

    fun inputIdentity(input: LinearSrgbImage): JSONObject {
        check(input.width == 1024 && input.height == 768)
        val digest = MessageDigest.getInstance("SHA-256")
        val sample = ByteArray(4)
        repeat(input.pixelCount * 3) { index ->
            val bits = input[index].toRawBits()
            repeat(4) { sample[it] = (bits ushr (it * 8)).toByte() }
            digest.update(sample)
        }
        val hash = hex(digest.digest())
        check(hash == "db20dc7a341bd1c9c60e1087896380c20c5b130248ef1d6eb51cce1221882ae3")
        return JSONObject().put("width", input.width).put("height", input.height).put("asShotFloatSha256", hash)
            .put("encoding", "RGB float raw bits little endian row-major; no extra photo copy")
    }

    private fun identity(): String {
        val pid = Process.myPid()
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("pidof ${target.packageName}")).use {
            val bytes = ByteArray(4096)
            val n = it.read(bytes)
            check(n > 0 && n < bytes.size && it.read() == -1)
            check(String(bytes, 0, n, Charsets.UTF_8).trim() == pid.toString())
        }
        val base = File("/proc/$pid")
        val name = File(base, "cmdline").readText().substringBefore('\u0000')
        check(name == target.packageName)
        val uid = File(base, "status").useLines { lines -> lines.single { it.startsWith("Uid:") }.substringAfter(':').trim().split(Regex("\\s+")).first().toInt() }
        check(uid == Process.myUid())
        val start = File(base, "stat").readText().substringAfterLast(") ").split(' ')[19].toLong()
        return "$pid/$uid/$start/$name"
    }

    /** One dump, streamed to a unique file; never retain the full dump on heap or retry missing data.
     * dumpsys -t bounds service-output polling, not process launch/Binder lookup/filesystem IO. */
    fun capturePss(phase: String, boundary: () -> String) {
        val row = JSONObject().put("phase", phase).put("status", "incomplete")
        pss.put(row)
        try {
        val settledToken = boundary()
        row.put("boundaryBeforeSettle", settledToken)
        SystemClock.sleep(1000)
        val preToken = boundary()
        row.put("boundaryBeforeDump", preToken)
        check(settledToken == preToken) { "PSS boundary changed during settle" }
        val pre = identity()
        if (originalIdentity == null) originalIdentity = pre
        check(pre == originalIdentity)
        row.put("identityBefore", pre).put("beginUptimeMs", SystemClock.uptimeMillis())
        val file = File(folder, "$prefix-pss-${pss.length().toString().padStart(2, '0')}.txt")
        check(file.createNewFile())
        row.put("rawFileName", file.name).put("command", "dumpsys -t 10 meminfo -d ${Process.myPid()}")
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("dumpsys -t 10 meminfo -d ${Process.myPid()}")).use { input ->
            file.outputStream().use { output -> input.copyTo(output, 8192) }
        }
        val postToken = boundary()
        row.put("boundaryAfterDump", postToken)
        val post = identity()
        row.put("identityAfter", post).put("endUptimeMs", SystemClock.uptimeMillis()).put("rawBytes", file.length())
        check(postToken == preToken && post == pre) { "PSS boundary or process identity changed" }
        parseMeminfo(file, row)
        row.put("status", "verified_reference_sample")
        } catch (failure: Throwable) {
            row.put("status", "invalid_reference_sample").put("failureType", failure.javaClass.simpleName)
            throw failure
        }
    }

    /** Match both standard Android table layouts, then map the two header rows to numeric columns.
     * Unknown layouts, missing/duplicate rows and partial dumps fail closed; no zero substitution. */
    private fun parseMeminfo(file: File, row: JSONObject) {
        val split = Regex("\\s+")
        var processHeader = false
        var timeout = false
        var top: List<String>? = null
        var columns: List<String>? = null
        var summaryTotal: Long? = null
        val values = mutableMapOf<String, List<Long>>()
        file.forEachLine { line ->
            val text = line.trim()
            if (line.contains("MEMINFO in pid ${Process.myPid()} [${target.packageName}]")) processHeader = true
            if (line.contains("DUMP TIMEOUT") || line.contains("Error dumping service") || line.contains("Can't find service")) timeout = true
            val parts = text.split(split)
            if (parts.firstOrNull() == "Pss" && parts.lastOrNull() == "Heap") {
                check(top == null)
                val normalized = parts.map { if (it == "SwapPss") "Swap" else it }
                check(normalized == listOf("Pss", "Private", "Private", "Swap", "Rss", "Heap", "Heap", "Heap") ||
                    normalized == listOf("Pss", "Pss", "Shared", "Private", "Shared", "Private", "Swap", "Rss", "Heap", "Heap", "Heap"))
                top = parts
            } else if (top != null && columns == null && parts.firstOrNull() == "Total") {
                val expected = if (checkNotNull(top).size == 8) listOf("Total", "Dirty", "Clean", "Dirty", "Total", "Size", "Alloc", "Free")
                    else listOf("Total", "Clean", "Dirty", "Dirty", "Clean", "Clean", "Dirty", "Total", "Size", "Alloc", "Free")
                check(parts == expected)
                columns = checkNotNull(top).zip(parts).map { (a, b) -> "$a $b" }
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
        check(processHeader && !timeout && values.keys == setOf("Native Heap", "Dalvik Heap", "TOTAL"))
        val names = checkNotNull(columns)
        val pss = names.indexOf("Pss Total").also { check(it >= 0) }
        val allocated = names.indexOf("Heap Alloc").also { check(it >= 0) }
        val total = values.getValue("TOTAL")[pss]
        check(summaryTotal != null && summaryTotal == total)
        row.put("totalPssKb", total).put("nativePssKb", values.getValue("Native Heap")[pss])
            .put("dalvikPssKb", values.getValue("Dalvik Heap")[pss])
            .put("nativeHeapAllocKb", values.getValue("Native Heap")[allocated])
            .put("managedHeapAllocKb", values.getValue("Dalvik Heap")[allocated])
            .put("timeoutOrErrorMarker", timeout)
    }

    /** Auxiliary route only. Do not toggle storage visibility or search another provider. */
    private fun openAuxiliaryDownload(pkg: String, registeredName: String) {
        val roots = By.res(pkg, "roots_list")
        val labels = listOfNotNull(Build.MODEL, Settings.Global.getString(target.contentResolver, "device_name"),
            "Internal storage", "Internal shared storage", "内部ストレージ", "内部共有ストレージ")
        val label = Pattern.compile(labels.distinct().joinToString("|") { Pattern.quote(it) })
        auxiliaryPicker.put("phase", "open_roots")
        if (!device.hasObject(roots)) find("auxiliary Files drawer", By.pkg(pkg).clazz("android.widget.ImageButton")
            .desc(Pattern.compile("Show roots|Open navigation drawer|ルートを表示|ナビゲーション ドロワーを開く"))).click()
        clickFreshAuxiliaryRoot(pkg, label)
        auxiliaryPicker.put("phase", "await_internal_root")
        awaitAuxiliaryDirectory(pkg, label, label, "internalRootArrival", "auxiliary internal root arrived")
        auxiliaryPicker.put("internalRootConfirmed", true)
        rewindDirectory(pkg)
        val download = findAuxiliaryEntry(pkg, Environment.DIRECTORY_DOWNLOADS, "download_folder", label)
        auxiliaryPicker.put("downloadRowClickAttempts", 1).put("downloadRowClickReturned", false)
        download.click()
        auxiliaryPicker.put("downloadRowClickReturned", true).put("phase", "await_download")
        awaitAuxiliaryDirectory(pkg, label, label, "downloadArrival", "auxiliary Download arrived", registeredName)
        auxiliaryPicker.put("phase", "download_confirmed").put("downloadConfirmed", true)
            .put("directSelectedUriObservation", false)
    }

    /** Download arrival requires the registered DNG in dir_list, never a toolbar title.
     * Only called after the confirmed internal root and its exact Download row were clicked.
     * Reads are sequential; the route and visible name do not directly observe the result URI. */
    private fun awaitAuxiliaryDirectory(pkg: String, expected: Pattern, rootLabel: Pattern,
        recordName: String, waitLabel: String, registeredName: String? = null) {
        val record = JSONObject().put("pollCount", 0).put("waitOutcome", "pending")
            .put("observationScope", "sequential predicate reads; setup only; not atomic or direct document URI evidence")
        record.put("arrivalRule", if (registeredName == null) "internal_root_toolbar" else "unique_visible_registered_dng_in_directory")
        auxiliaryPicker.put(recordName, record)
        try {
            await(waitLabel) {
                val sample = JSONObject().put("status", "incomplete").put("beginUptimeMs", SystemClock.uptimeMillis())
                record.put("pollCount", record.getInt("pollCount") + 1).put("lastPredicateSample", sample)
                val foregroundMatches = (device.currentPackageName == pkg).also { sample.put("foregroundMatches", it) }
                val rootsAbsent = (!device.hasObject(By.res(pkg, "roots_list"))).also { sample.put("rootsAbsent", it) }
                val expectedContentPresent = if (registeredName == null) {
                    device.hasObject(By.pkg(pkg).text(expected).hasAncestor(By.res(pkg, "toolbar")))
                } else {
                    auxiliaryPicker.optBoolean("internalRootConfirmed") && auxiliaryPicker.optBoolean("downloadRowClickReturned") &&
                        visibleAuxiliaryEntry(pkg, registeredName) != null
                }
                sample.put(if (registeredName == null) "expectedToolbarPresent" else "registeredDngVisible", expectedContentPresent)
                val directoryPresent = device.hasObject(By.res(pkg, "dir_list")).also { sample.put("directoryPresent", it) }
                val arrived = foregroundMatches && rootsAbsent && expectedContentPresent && directoryPresent
                sample.put("arrived", arrived).put("endUptimeMs", SystemClock.uptimeMillis()).put("status", "complete")
                arrived
            }
            record.put("waitOutcome", "arrived")
        } catch (failure: Throwable) {
            record.put("waitOutcome", "failed").put("failureType", failure.javaClass.simpleName)
            throw failure
        } finally {
            // Later, independent diagnostic reads: never substitute them for the arrival predicate.
            val snapshot = JSONObject().put("status", "incomplete")
                .put("beginUptimeMs", SystemClock.uptimeMillis())
            record.put("knownToolbarExactMatches", snapshot)
            try {
                val toolbar = By.res(pkg, "toolbar")
                snapshot.put("internalRootLabel", device.hasObject(By.pkg(pkg).text(rootLabel).hasAncestor(toolbar)))
                    .put("downloadLabel", device.hasObject(By.pkg(pkg).text(Environment.DIRECTORY_DOWNLOADS).hasAncestor(toolbar)))
                    .put("endUptimeMs", SystemClock.uptimeMillis()).put("status", "recorded")
            } catch (failure: Throwable) {
                // Diagnostic failure must not replace the original navigation failure or allow a pass.
                snapshot.put("status", "unavailable").put("failureType", failure.javaClass.simpleName)
            }
        }
    }

    /** Root selection only: refresh/readiness may poll; ACTION_CLICK is issued once, never retried. */
    private fun clickFreshAuxiliaryRoot(pkg: String, label: Pattern) {
        val record = JSONObject().put("actionAttempts", 0)
        auxiliaryPicker.put("rootClick", record)
        var targetNode: AccessibilityNodeInfo? = null
        await("fresh auxiliary internal root") {
            targetNode = null
            record.put("ambiguous", false)
            val window = instrumentation.uiAutomation.rootInActiveWindow
            val roots = window?.findAccessibilityNodeInfosByViewId("$pkg:id/roots_list")?.singleOrNull()
            var selected: AccessibilityNodeInfo? = null
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            roots?.let(queue::addLast)
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                if (node.packageName?.toString() == pkg && label.matcher(node.text?.toString().orEmpty()).matches()) {
                    if (selected != null) {
                        record.put("ambiguous", true).put("ready", false)
                        return@await false
                    }
                    selected = node
                }
                repeat(node.childCount) { node.getChild(it)?.let(queue::addLast) }
            }
            val item = selected
            val labelFresh = item?.refresh() == true
            var action = item
            while (action != null && !action.isClickable && action.viewIdResourceName != "$pkg:id/roots_list") action = action.parent
            val actionFresh = action?.refresh() == true
            var ancestor = item?.parent
            var scoped = false
            while (ancestor != null) {
                if (ancestor.viewIdResourceName == "$pkg:id/roots_list" && ancestor.packageName?.toString() == pkg) { scoped = true; break }
                ancestor = ancestor.parent
            }
            val activeWindow = instrumentation.uiAutomation.rootInActiveWindow?.windowId
            val ready = labelFresh && actionFresh && scoped && item != null && action != null &&
                item.packageName?.toString() == pkg && label.matcher(item.text?.toString().orEmpty()).matches() &&
                item.isVisibleToUser && item.isEnabled && action.packageName?.toString() == pkg &&
                action.viewIdResourceName != "$pkg:id/roots_list" && action.isVisibleToUser && action.isEnabled && action.isClickable &&
                item.windowId == activeWindow && action.windowId == activeWindow &&
                action.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }
            record.put("labelFresh", labelFresh).put("actionFresh", actionFresh).put("scoped", scoped).put("ready", ready)
            if (ready) targetNode = action
            ready
        }
        record.put("actionAttempts", 1)
        try {
            val accepted = checkNotNull(targetNode).performAction(AccessibilityNodeInfo.ACTION_CLICK)
            record.put("accepted", accepted)
            check(accepted) { "Auxiliary root ACTION_CLICK returned false; no retry" }
        } catch (failure: Throwable) {
            record.put("failureType", failure.javaClass.simpleName)
            throw failure
        }
    }

    private fun visibleAuxiliaryEntry(pkg: String, name: String): UiObject2? = try {
        device.findObjects(By.pkg(pkg).text(name).enabled(true).hasAncestor(By.res(pkg, "dir_list")))
            .singleOrNull()?.takeIf { !it.visibleBounds.isEmpty }
    } catch (_: StaleObjectException) {
        null // Readiness read only; never repeat a click.
    }

    private fun findAuxiliaryEntry(pkg: String, name: String, phase: String, location: Pattern? = null): UiObject2 {
        auxiliaryPicker.put("phase", phase)
        val directory = By.res(pkg, "dir_list")

        var result: UiObject2? = null
        await("auxiliary $phase") {
            result = null
            val locationReady = if (location != null) {
                device.hasObject(By.pkg(pkg).text(location).hasAncestor(By.res(pkg, "toolbar")))
            } else auxiliaryPicker.optBoolean("downloadConfirmed")
            if (device.currentPackageName != pkg || device.hasObject(By.res(pkg, "roots_list")) || !locationReady) return@await false
            result = visibleAuxiliaryEntry(pkg, name)
            if (result == null && location != null) device.findObject(directory)?.let { if (it.isScrollable) it.scroll(Direction.DOWN, .5f) }
            result != null
        }
        return checkNotNull(result)
    }

    /** Called only by auxiliary failure cleanup, before the existing Activity finish. Back once,
     * never retry or claim cancellation merely because input injection returned. */
    fun cancelFailedAuxiliaryPicker() {
        if (!pickerPending) return
        auxiliaryPicker.put("cancelStatus", "checking_owned_picker").put("backReturned", false)
            .put("originalActivityResumed", false)
        var phase = "ownership_guard"
        try {
            check(!cancelAttempted) { "Picker cancellation already attempted" }
            val owner = checkNotNull(pickerOwner?.get()) { "Original picker owner unavailable" }
            val pkg = checkNotNull(pickerPackage) { "Owned picker package unavailable" }
            check(device.currentPackageName == pkg) { "Owned picker not foreground" }
            instrumentation.runOnMainSync {
                val stage = ActivityLifecycleMonitorRegistry.getInstance().getLifecycleStageOf(owner)
                check(stage != Stage.DESTROYED && stage != Stage.RESUMED && !owner.isFinishing) { "Original picker owner not eligible" }
            }
            cancelAttempted = true
            phase = "back_call"
            auxiliaryPicker.put("cancelStatus", "back_attempted").put("backAttempts", 1)
            val accepted = device.pressBack()
            auxiliaryPicker.put("backReturned", true).put("backAccepted", accepted)
            check(accepted) { "Picker Back returned false" }
            phase = "await_original_activity"
            await("original diagnostic Activity resumed after picker cancel") {
                var resumed = false
                instrumentation.runOnMainSync {
                    val monitor = ActivityLifecycleMonitorRegistry.getInstance()
                    resumed = monitor.getActivitiesInStage(Stage.RESUMED).singleOrNull() === owner &&
                        owner.diagnosticState().resumed && owner.hasWindowFocus() && !owner.isFinishing
                }
                device.currentPackageName == target.packageName && resumed
            }
            pickerPending = false
            auxiliaryPicker.put("cancelStatus", "original_activity_resumed").put("originalActivityResumed", true)
        } catch (failure: Throwable) {
            // Preserve our bounded diagnostic messages, not arbitrary framework/provider text.
            val knownMessages = setOf("Picker cancellation already attempted", "Original picker owner unavailable",
                "Owned picker package unavailable", "Owned picker not foreground", "Original picker owner not eligible",
                "Picker Back returned false", "Timed out: original diagnostic Activity resumed after picker cancel")
            auxiliaryPicker.put("cancelStatus", "unproven").put("cancelFailurePhase", phase)
                .put("cancelFailureType", failure.javaClass.simpleName)
                .put("cancelFailureMessage", failure.message?.let { if (it in knownMessages) it else "[non-contract message omitted]" } ?: JSONObject.NULL)
            throw failure
        }
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private fun openInternalDownload(filesPackage: String) {
        val labels = listOfNotNull(Build.MODEL, Settings.Global.getString(target.contentResolver, "device_name"),
            "Internal storage", "Internal shared storage", "内部ストレージ", "内部共有ストレージ")
        val rootsList = By.res(filesPackage, "roots_list")
        val internalRoot = By.pkg(filesPackage).text(Pattern.compile(labels.distinct().joinToString("|") { Pattern.quote(it) }))
            .hasAncestor(rootsList)
        fun openRoots() {
            if (!device.hasObject(rootsList)) find("Files drawer", By.pkg(filesPackage).clazz("android.widget.ImageButton")
                .desc(Pattern.compile("Show roots|Open navigation drawer|ルートを表示|ナビゲーション ドロワーを開く"))).click()
            find("Files roots", rootsList)
        }
        openRoots()
        if (!device.hasObject(internalRoot)) {
            device.pressBack()
            find("Files overflow", By.pkg(filesPackage).desc(Pattern.compile("More options|その他のオプション"))).click()
            find("show internal storage", By.pkg(filesPackage).text(Pattern.compile("Show internal storage|内部ストレージを表示"))).click()
            openRoots()
        }
        find("scoped internal root", internalRoot).click()
        await("drawer closed") { !device.hasObject(rootsList) }
        rewindDirectory(filesPackage)
        find("internal Download", By.pkg(filesPackage).text(Environment.DIRECTORY_DOWNLOADS), Direction.DOWN).click()
        rewindDirectory(filesPackage)
    }

    /** DocumentsUI retains scroll position. Scope scrolling to the actual file list, not its outer
     * ScrollView, and start at the top before searching. This never changes the picker result. */
    private fun rewindDirectory(filesPackage: String) {
        device.waitForIdle(1_000)
        await("Files directory at start") {
            val directory = device.findObject(By.res(filesPackage, "dir_list"))
            directory != null && (!directory.isScrollable || !directory.scroll(Direction.UP, 0.8f))
        }
    }

    private fun find(label: String, selector: BySelector, direction: Direction? = null): UiObject2 {
        var result: UiObject2? = null
        await(label) {
            result = device.findObject(selector)
            if (result != null) true else {
                if (direction != null) device.currentPackageName?.let { packageName ->
                    val directory = device.findObject(By.res(packageName, "dir_list").scrollable(true))
                    (directory ?: device.findObject(By.pkg(packageName).scrollable(true)))?.scroll(direction, 0.5f)
                }
                false
            }
        }
        return checkNotNull(result)
    }

    private fun await(label: String, timeoutMs: Long = 10_000, predicate: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) {
            if (predicate()) return
            SystemClock.sleep(25)
        }
        val failure = IllegalStateException("Timed out: $label")
        if (auxiliaryUiEvidenceEnabled) {
            try { captureUiTimeout(label) } catch (recordingFailure: Throwable) { failure.addSuppressed(recordingFailure) }
        }
        throw failure
    }

    /** One standard PNG and XML capture per timeout, before the caller continues. Files stay private beside
     * the run JSON; failed or partial captures are retained, never retried or used to pass a wait. */
    fun captureUiTimeout(label: String) {
        val suffix = (uiTimeoutEvidence.length() + 1).toString().padStart(2, '0')
        val record = JSONObject().put("waitLabel", label).put("beginUptimeMs", SystemClock.uptimeMillis())
            .put("scope", "private timeout evidence; sequential captures, not atomic; may contain file names; no publication")
            .put("timeoutScope", "capture and file IO are outside the expired polling budget; no hard end-to-end deadline")
        uiTimeoutEvidence.put(record)
        auxiliaryPicker.put("uiTimeoutEvidence", uiTimeoutEvidence)
        fun capture(kind: String, extension: String, action: (File) -> Boolean) {
            val file = File(folder, "$prefix-ui-timeout-$suffix.$extension")
            val artifact = JSONObject().put("fileName", file.name).put("attempts", 1)
                .put("status", "incomplete").put("beginUptimeMs", SystemClock.uptimeMillis())
            record.put(kind, artifact)
            try {
                check(file.createNewFile()) { "Do not overwrite timeout evidence" }
                check(action(file) && file.length() > 0) { "Timeout capture incomplete" }
                artifact.put("status", "saved")
            } catch (failure: Throwable) {
                artifact.put("status", "failed").put("failureType", failure.javaClass.simpleName)
            } finally {
                artifact.put("bytes", file.length()).put("endUptimeMs", SystemClock.uptimeMillis())
            }
        }
        capture("screenshot", "png") { device.takeScreenshot(it) }
        capture("hierarchy", "xml") { device.dumpWindowHierarchy(it); true }
        record.put("endUptimeMs", SystemClock.uptimeMillis())
    }
}
