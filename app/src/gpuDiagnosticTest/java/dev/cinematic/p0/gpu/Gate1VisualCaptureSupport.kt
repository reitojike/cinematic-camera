package dev.cinematic.p0.gpu

import android.app.Activity
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.DocumentsContract
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.UiDevice
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Public Android/UI-only support. Contains no CPU product types or reflection.
 * https://developer.android.com/reference/android/view/PixelCopy
 * PixelCopy may convert format/color space; native dimensions prevent scaling, not conversion. */
internal class Gate1VisualCaptureSupport(private val kind: String, method: String, expectedApk: String) {
    val ins = InstrumentationRegistry.getInstrumentation()
    private val target get() = ins.targetContext
    val report = JSONObject().put("version", "visual-acquisition-v1").put("side", kind)
        .put("purpose", "private display originals for later human review, not Gate 1 acceptance")
        .put("criterion3", "NOT_ASSESSED").put("sameCaptureResolution", "NOT_ESTABLISHED")
        .put("sameLook", "USER_DECISION_REQUIRED").put("performanceMeasurement", false)
        .put("normalization", "NONE").put("viewOrBufferResize", false)
        .put("setupMode", "external standard UI selection; this test never navigates Files")
        .put("setupPollingLimitMs", 180000).put("hardEndToEndDeadline", false)
        .put("captureAttempts", JSONArray()).put("pickerObservation", JSONObject().put("status", "not_observed"))
        .put("cleanup", JSONObject().put("status", "not_started"))
    val folder: File
    private var hub: Activity? = null
    var capturedActivity: Activity? = null
    private var initialEnvironment: JSONObject? = null
    private var observedPickerPackage: String? = null

    init {
        val args = InstrumentationRegistry.getArguments()
        check(args.getString("class") == method)
        check(args.getString("gate1Part2") == "visual-acquisition-v1")
        check(args.getString("gate1VisualCapture") == kind && args.getString("isolated") == "true")
        check(args.getString("gate1VisualSetup") == "external-files-selection")
        check(args.getString("gate1AuthorizedTarget") == Build.MODEL)
        check(target.packageName == TARGET && Build.VERSION.SDK_INT >= 30)
        val emulator = Build.HARDWARE in setOf("ranchu", "goldfish")
        if (!emulator) check(Build.MODEL == "Pixel 9a" && args.getString("gate1PixelProbe") == "true")
        check(args.getString("gate1TargetApkSha256") == expectedApk)
        check(hash(File(target.applicationInfo.sourceDir).inputStream()) == expectedApk)
        check(args.getString("gate1DngDocumentId") == DOCUMENT)
        val id = checkNotNull(args.getString("gate1VisualRun")).also {
            check(it.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,39}")))
        }
        val pairId = checkNotNull(args.getString("gate1VisualPair")).also { check(it.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,39}"))) }
        folder = File(checkNotNull(target.getExternalFilesDir("gate1-visual")), "$kind-$id")
        check(!folder.exists() && folder.mkdirs()) { "Do not overwrite any previous visual run" }
        report.put("runId", id).put("pairId", pairId).put("targetApkSha256", expectedApk).put("emulator", emulator)
            .put("authorizationMeaning", "guard only; separate permission is required before device access")
            .put("source", JSONObject().put("sha256", DNG_SHA).put("bytes", DNG_BYTES)
                .put("orientation", 1).put("registeredInputWidth", 1024).put("registeredInputHeight", 768))
            .put("requestedRecipe", JSONObject().put("strength", .5).put("warmth", 0).put("exposureEv", 0)
                .put("showBefore", false).put("rotationDegrees", 0))
        save()
    }

    fun startHub() {
        initialEnvironment = environment().also { current ->
            check(current.getInt("thermalStatus") in 0..PowerManager.THERMAL_STATUS_LIGHT)
            check(current.getBoolean("interactive"))
            report.put("environmentStart", current)
        }
        hub = ins.startActivitySync(Intent().setClassName(TARGET, HUB).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        await(30000) { resumed(hub, HUB) }
        if (kind == "gpu") {
            // Existing Gate 1 launch pattern: preserve the original Hub task, without CLEAR_TASK.
            ins.runOnMainSync {
                val owner = checkNotNull(hub)
                owner.startActivity(Intent().setClassName(TARGET, GPU_ACTIVITY))
            }
            report.put("launchPath", "same Hub Activity.startActivity to existing GPU preview; no Hub preview button exists")
        } else report.put("launchPath", "external public CPU Files Hub button")
        ins.sendStatus(0, Bundle().apply {
            putString("gate1VisualStatus", "waiting_for_external_${kind}_Files_selection")
            putString("gate1VisualSetupLimit", "180 seconds; use the registered synthetic DNG and baseline recipe")
        })
        report.put("phase", "waiting_for_external_files_selection"); save()
    }

    fun bindExpectedActivity(className: String): Activity? {
        var result: Activity? = null
        ins.runOnMainSync {
            val candidates = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
            result = candidates.singleOrNull()?.takeIf { it.javaClass.name == className && !it.isFinishing && it.hasWindowFocus() }
            result?.let {
                check(it.taskId == checkNotNull(hub).taskId) { "Target does not belong to this Hub task" }
                check(capturedActivity == null || capturedActivity === it) { "Activity changed during acquisition" }
                capturedActivity = it
            }
        }
        return result
    }

    /** Read-only evidence while the host performs standard UI operations; never clicks or dumps the tree. */
    fun observeExternalSetup() {
        val root = ins.uiAutomation.rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: return
        if (!pkg.endsWith(".documentsui")) return
        val row = report.getJSONObject("pickerObservation")
        row.put("status", "documents_ui_observed").put("package", pkg)
            .put("scope", "registered row/location visibility only, not a selected result URI")
        val owned = capturedActivity
        if (owned != null) ins.runOnMainSync {
            val stage = ActivityLifecycleMonitorRegistry.getInstance().getLifecycleStageOf(owned)
            if (owned.taskId == hub?.taskId && stage in setOf(Stage.PAUSED, Stage.STOPPED) && !owned.isFinishing) {
                observedPickerPackage = pkg
                row.put("expectedTargetPaused", true)
            }
        }
        val directory = root.findAccessibilityNodeInfosByViewId("$pkg:id/dir_list").singleOrNull()
        val toolbar = root.findAccessibilityNodeInfosByViewId("$pkg:id/toolbar").singleOrNull()
        fun containsExact(start: AccessibilityNodeInfo?, text: String): Boolean {
            val queue = ArrayDeque<AccessibilityNodeInfo>(); start?.let(queue::addLast); var count = 0
            while (queue.isNotEmpty()) {
                check(++count < 1024)
                val node = queue.removeFirst()
                if (node.isVisibleToUser && node.text?.toString() == text) return true
                repeat(node.childCount) { node.getChild(it)?.let(queue::addLast) }
            }
            return false
        }
        if (containsExact(directory, FIXTURE)) row.put("registeredRowObserved", true)
        if (containsExact(toolbar, "Download")) row.put("downloadToolbarObserved", true)
    }

    fun requireActive(activity: Activity, className: String) {
        check(resumed(activity, className))
        check(UiDevice.getInstance(ins).currentPackageName == TARGET)
        val start = checkNotNull(initialEnvironment)
        val current = environment()
        check(current.getBoolean("interactive") && current.getInt("thermalStatus") <= start.getInt("thermalStatus"))
        check(current.getInt("plugged") == start.getInt("plugged") && current.getInt("batteryStatus") == start.getInt("batteryStatus"))
    }

    fun verifySource(): JSONObject {
        val uri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", DOCUMENT)
        check(target.checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED)
        var bytes = 0L
        val digest = MessageDigest.getInstance("SHA-256")
        checkNotNull(target.contentResolver.openInputStream(uri)).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                bytes += count
                check(bytes <= DNG_BYTES)
                digest.update(buffer, 0, count)
            }
        }
        check(bytes == DNG_BYTES && hex(digest.digest()) == DNG_SHA)
        return JSONObject().put("expectedDocumentReadable", true).put("bytes", bytes).put("sha256", DNG_SHA)
            .put("selectedResultUriDirectlyObserved", false)
    }

    fun nodes(): List<AccessibilityNodeInfo> {
        val root = ins.uiAutomation.rootInActiveWindow ?: return emptyList()
        if (root.packageName?.toString() != TARGET) return emptyList()
        val result = ArrayList<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>(); queue.add(root)
        var visited = 0
        while (queue.isNotEmpty()) {
            check(++visited < 1024) { "Unexpected UI tree size" }
            val node = queue.removeFirst()
            if (node.packageName?.toString() == TARGET && node.isVisibleToUser) result.add(node)
            repeat(node.childCount) { node.getChild(it)?.let(queue::addLast) }
        }
        return result
    }

    fun bounds(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)
    fun rect(rect: Rect) = JSONArray(listOf(rect.left, rect.top, rect.right, rect.bottom))

    /** Source and destination dimensions are identical: no PixelCopy scaling. */
    fun windowCopy(activity: Activity, screenRect: Rect, label: String): Bitmap {
        val source = Rect(screenRect)
        ins.runOnMainSync {
            val decor = activity.window.decorView
            val screen = IntArray(2).also(decor::getLocationOnScreen)
            val window = IntArray(2).also(decor::getLocationInWindow)
            source.offset(window[0] - screen[0], window[1] - screen[1])
            check(Rect(window[0], window[1], window[0] + decor.width, window[1] + decor.height).contains(source))
        }
        return copy(source.width(), source.height(), label, "Window", source) { bitmap, listener ->
            PixelCopy.request(activity.window, source, bitmap, listener, Handler(Looper.getMainLooper()))
        }
    }

    fun surfaceCopy(view: SurfaceView, label: String): Bitmap {
        var source = Rect()
        ins.runOnMainSync {
            check(view.holder.surface.isValid && view.isShown)
            source = Rect(view.holder.surfaceFrame)
        }
        return copy(source.width(), source.height(), label, "SurfaceView", source) { bitmap, listener ->
            PixelCopy.request(view, source, bitmap, listener, Handler(Looper.getMainLooper()))
        }
    }

    private fun copy(width: Int, height: Int, label: String, sourceKind: String, source: Rect,
        request: (Bitmap, PixelCopy.OnPixelCopyFinishedListener) -> Unit): Bitmap {
        check(width > 0 && height > 0 && width.toLong() * height <= 16_000_000L)
        val row = JSONObject().put("label", label).put("sourceKind", sourceKind).put("sourceRect", rect(source))
            .put("width", width).put("height", height).put("status", "requested")
        report.getJSONArray("captureAttempts").put(row); save()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val latch = CountDownLatch(1)
        val lock = Any()
        var completed = false; var abandoned = false; var result = -1
        try {
            ins.runOnMainSync {
                request(bitmap, PixelCopy.OnPixelCopyFinishedListener { code ->
                    synchronized(lock) {
                        completed = true; result = code
                        if (abandoned) bitmap.recycle()
                    }
                    latch.countDown()
                })
            }
        } catch (failure: Throwable) {
            // Unknown request outcome: keep the Bitmap alive for a possible late callback.
            row.put("status", "request_failed").put("failureType", failure.javaClass.simpleName)
            synchronized(lock) { if (completed) bitmap.recycle() else abandoned = true }
            throw failure
        }
        if (!latch.await(10000, TimeUnit.MILLISECONDS)) {
            synchronized(lock) { if (completed) bitmap.recycle() else abandoned = true }
            row.put("status", "callback_timeout"); save()
            error("PixelCopy callback unconfirmed; no retry")
        }
        row.put("resultCode", result)
        if (result != PixelCopy.SUCCESS) {
            bitmap.recycle(); row.put("status", "copy_failed"); save()
            error("PixelCopy failed; no retry")
        }
        row.put("status", "copied").put("colorSpace", bitmap.colorSpace?.name ?: "unknown")
        return bitmap
    }

    fun saveOriginal(bitmap: Bitmap, label: String): JSONObject {
        check(label.matches(Regex("[a-z0-9-]+")))
        val file = File(folder, "$label.png")
        check(file.createNewFile())
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        return JSONObject().put("file", file.name).put("bytes", file.length())
            .put("sha256", hash(file.inputStream())).put("width", bitmap.width).put("height", bitmap.height)
            .put("colorSpace", bitmap.colorSpace?.name ?: "unknown").put("rescaled", false)
    }

    /** One Back only for an owned picker. No repeated Back, kill, relaunch, GC or forced release. */
    fun returnToHub() {
        val cleanup = report.getJSONObject("cleanup")
        cleanup.put("status", "checking"); save()
        val originalHub = checkNotNull(hub)
        if (resumed(originalHub, HUB)) { cleanup.put("status", "same_hub_without_target"); return }
        var activity = capturedActivity
        if (activity == null) ins.runOnMainSync {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            activity = listOf(Stage.RESUMED, Stage.PAUSED, Stage.STOPPED).flatMap { monitor.getActivitiesInStage(it) }
                .distinct().singleOrNull { it.javaClass.name == if (kind == "cpu") CPU_ACTIVITY else GPU_ACTIVITY }
        }
        val owned = checkNotNull(activity) { "Expected target ownership unconfirmed" }
        check(owned.taskId == originalHub.taskId) { "Target does not belong to this Hub task" }
        capturedActivity = owned
        if (UiDevice.getInstance(ins).currentPackageName?.endsWith(".documentsui") == true) {
            check(observedPickerPackage != null && observedPickerPackage == UiDevice.getInstance(ins).currentPackageName) {
                "Picker ownership unconfirmed; no Back injected"
            }
            cleanup.put("pickerBackAttempts", 1); save()
            check(UiDevice.getInstance(ins).pressBack())
            await(10000) { resumed(owned, owned.javaClass.name) }
        }
        check(resumed(owned, owned.javaClass.name)) { "Owned target not resumed; no forced cleanup" }
        ins.runOnMainSync { owned.finish() }
        await(10000) {
            var destroyed = false
            ins.runOnMainSync { destroyed = ActivityLifecycleMonitorRegistry.getInstance().getLifecycleStageOf(owned) == Stage.DESTROYED }
            destroyed && resumed(originalHub, HUB)
        }
        cleanup.put("status", "target_destroyed_same_hub").put("sameHub", true).put("targetDestroyed", true)
            .put("activityGc", "UNOBSERVED").put("nativeOwnerRelease", "UNOBSERVED")
        report.put("environmentEnd", environment())
    }

    private fun resumed(activity: Activity?, className: String): Boolean {
        var ready = false
        ins.runOnMainSync {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            ready = activity != null && activity.javaClass.name == className &&
                monitor.getActivitiesInStage(Stage.RESUMED).singleOrNull() === activity &&
                activity.hasWindowFocus() && !activity.isFinishing
        }
        return ready
    }

    private fun environment(): JSONObject {
        val power = target.getSystemService(PowerManager::class.java)
        val battery = target.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return JSONObject().put("thermalStatus", power.currentThermalStatus).put("interactive", power.isInteractive)
            .put("plugged", battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1)
            .put("batteryStatus", battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1)
    }

    fun phase(value: String) { report.put("phase", value); save() }

    fun recordFailure(error: Throwable) {
        val safe = setOf("Visual acquisition polling deadline exceeded", "CPU UI changed during capture",
            "CPU display changed; no recapture", "GPU Surface changed; no recapture",
            "PixelCopy failed; no retry", "PixelCopy callback unconfirmed; no retry",
            "Activity changed during acquisition", "Target does not belong to this Hub task")
        report.put("acquisitionStatus", "FAILED_OR_STOPPED").put("failureType", error.javaClass.simpleName)
            .put("failurePhase", report.optString("phase", "guard_or_setup"))
            .put("failureReason", error.message?.takeIf { it in safe } ?: "non_contract_detail_omitted")
    }

    fun save() { File(folder, "report.json").writeText(report.toString(2)) }

    companion object {
        const val TARGET = "dev.cinematic.p0.gpudiagnostic"
        const val HUB = "dev.cinematic.p0.raw.DiagnosticHubActivity"
        const val CPU_ACTIVITY = "dev.cinematic.p0.photoroll.PhotoRollActivity"
        const val GPU_ACTIVITY = "dev.cinematic.p0.gpu.GpuPreviewDiagnosticActivity"
        const val FIXTURE = "gate1-common-synthetic-1024x768-v1.dng"
        const val DOCUMENT = "primary:Download/$FIXTURE"
        const val DNG_SHA = "796555f835a38a94162383dc9f037023a1fd6400037d7b5f3f0e287ccd64bf3a"
        const val DNG_BYTES = 1573250L
        const val CPU_SHA = "8cfc16cf5c60e29a3c67618402a9913be7a24a7f5367e5bd101c362e8b4bf745"
        const val GPU_SHA = "cf79d84ea2e225d390516ff8f7bcfe3c2d6e93c775f5118a0b97008cfd4285ac"
        fun await(timeoutMs: Long, ready: () -> Boolean) {
            val end = SystemClock.elapsedRealtime() + timeoutMs
            while (SystemClock.elapsedRealtime() < end) { if (ready()) return; SystemClock.sleep(100) }
            error("Visual acquisition polling deadline exceeded")
        }
        fun hash(input: InputStream): String = input.use {
            val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(8192)
            while (true) { val count = it.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            hex(digest.digest())
        }
        fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}