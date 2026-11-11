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
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import dev.cinematic.p0.raw.LinearSrgbImage
import dev.cinematic.p0.rawcapture.DngContentVerifier
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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

    fun loadThroughFiles(withActivity: ((GpuPreviewDiagnosticActivity) -> Unit) -> Unit): JSONObject {
        val args = InstrumentationRegistry.getArguments()
        val documentId = checkNotNull(args.getString("gate1DngDocumentId"))
        check(documentId.matches(Regex("primary:Download/[A-Za-z0-9_.-]+\\.dng")))
        val uri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", documentId)
        find("Files button", By.pkg(target.packageName).text("試験用DNGをFilesから選択"), Direction.DOWN).click()
        await("DocumentsUI foreground") { device.currentPackageName?.endsWith(".documentsui") == true }
        val filesPackage = checkNotNull(device.currentPackageName)
        openInternalDownload(filesPackage)
        find("preregistered DNG", By.pkg(filesPackage).text(documentId.substringAfterLast('/')), Direction.DOWN).click()
        await("diagnostic app returned") { device.currentPackageName == target.packageName }
        await("native DNG load", 60_000) {
            var loaded = false
            withActivity {
                val state = it.diagnosticState()
                check(state.resumed && !state.gpuError)
                check(state.inputResult !in setOf("load_error", "readiness_timeout"))
                loaded = state.inputResult == "loaded" && !state.loading
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
        error("Timed out: $label")
    }
}
