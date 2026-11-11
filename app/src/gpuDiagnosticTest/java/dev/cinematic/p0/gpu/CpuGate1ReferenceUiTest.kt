package dev.cinematic.p0.gpu

import android.app.Activity
import android.content.Intent
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** External UI controller only. Fixed CPU APK has no added observation code.
 * Functional candidate, not a benchmark or proof of per-image values. Host owns APK verification,
 * PSS collection and isolation; gfxinfo is streamed outside the input window. No accessibility polling during the comparison gesture.
 */
@RunWith(AndroidJUnit4::class)
class CpuGate1ReferenceUiTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ui get() = inst.uiAutomation
    private val preparationClicks = JSONArray()

    @Test fun axisHoldReferenceOnly() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("cpuReferenceUi") == "true" && args.getString("isolated") == "true")
        check(args.getString("class") == "dev.cinematic.p0.gpu.CpuGate1ReferenceUiTest#axisHoldReferenceOnly")
        check(Build.VERSION.SDK_INT >= 30)
        val emulator = Build.HARDWARE in setOf("ranchu", "goldfish")
        if (!emulator) {
            // Consistent with gate1Part2OptIn. Flags are guards, never user authorization.
            // Separate explicit authorization is required before any physical-device access.
            check(args.getString("gate1Part2") == "cpu-reference-ui-v1")
            check(args.getString("gate1PixelProbe") == "true" && Build.MODEL == "Pixel 9a")
        }
        check(inst.targetContext.packageName == TARGET)
        // Require both host attestation and public ApplicationInfo.sourceDir byte identity.
        check(args.getString("verifiedCpuApkSha256") == CPU_SHA)
        val installedCpuSha = File(inst.targetContext.applicationInfo.sourceDir).inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8192)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
        check(installedCpuSha == CPU_SHA) { "Installed CPU APK bytes do not match the fixed baseline" }
        val runId = checkNotNull(args.getString("cpuRunId"))
        check(runId.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,79}"))) { "Safe unique cpuRunId required" }
        val axis = Axis.from(checkNotNull(args.getString("cpuAxis")))
        val root = File(checkNotNull(inst.targetContext.getExternalFilesDir("cpu-reference-ui")),
            runId)
        check(!root.exists() && root.mkdirs()) { "cpuRunId already exists or cannot be reserved; do not overwrite" }
        val events = JSONArray()
        val report = JSONObject().put("schema", 1).put("purpose", "UNREGISTERED_FUNCTIONAL_REFERENCE")
            .put("hostAttestedCpuApkSha256", CPU_SHA).put("verifiedInstalledCpuApkSha256", installedCpuSha)
            .put("runId", runId).put("emulator", emulator)
            .put("authorizationMeaning", "flags are guards only; physical-device access requires separate explicit authorization").put("axis", axis.id)
            .put("requestedStart", axis.start).put("requestedPeak", axis.peak).put("requestedReturn", axis.start)
            .put("valueUnit", axis.unit).put("rangeMinimum", axis.minimum).put("rangeMaximum", axis.maximum)
            .put("stateDescriptionConversion", axis.displayConversion)
            .put("riseMs", 200).put("holdMs", 400).put("returnMs", 200).put("plannedDurationMs", 800)
            .put("intervalMs", 20).put("plannedInjectedEvents", 42).put("tagSetupTapEvents", 0)
            .put("screenValuesAreImageFrameValues", false).put("peakScreenValue", JSONObject.NULL)
            .put("peakScreenStatus", "NOT_CAPTURED_USE_SEPARATE_AUXILIARY_RUN")
            .put("perImageValues", "UNAVAILABLE").put("formalGate1Measurement", false)
            .put("injectedEvents", events).put("preparationClicks", preparationClicks)
        File(root, "contract.json").writeText(report.toString(2))
        var activity: Activity? = null
        try {
            activity = inst.startActivitySync(Intent().setClassName(TARGET, "dev.cinematic.p0.photoroll.PhotoRollActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            click { it.text?.toString()?.startsWith("Files") == true && it.isEnabled }
            click(90_000) { it.text?.toString() == FIXTURE }
            click(120_000) { it.text?.toString() == axis.button && it.isEnabled }
            await("editable ruler", 120_000) { ready() && ruler(axis) != null }
            val initial = checkNotNull(ruler(axis))
            val start = axis.value(checkNotNull(initial.rangeInfo).current)
            check(kotlin.math.abs(start - axis.start) < .00001) { "Unexpected baseline; do not alter it silently" }
            report.put("screenStartValue", start).put("screenStartNormalizedRange", checkNotNull(initial.rangeInfo).current)
                .put("screenStartStateDescription", initial.stateDescription?.toString() ?: JSONObject.NULL).put("screenStartReadUptimeMs", SystemClock.uptimeMillis())
            val bounds = Rect().also(initial::getBoundsInScreen)
            val density = inst.targetContext.resources.displayMetrics.density
            // CPU1ce: D=24dp*span/tapStep, K=D/span, h=tapStep/3. Strength rounds to .01.
            val d = (24f * density * axis.span / axis.step).toFloat()
            val k = d.toDouble() / axis.span
            val x = bounds.exactCenterX(); val y = bounds.exactCenterY()
            val peakTravel = displacement(200, k, axis)
            val host = checkNotNull(activity)
            inst.runOnMainSync {
                val decor = host.window.decorView
                val origin = IntArray(2).also(decor::getLocationOnScreen)
                val insets = checkNotNull(decor.rootWindowInsets).getInsets(WindowInsets.Type.systemGestures())
                val safe = Rect(maxOf(bounds.left, origin[0] + insets.left), maxOf(bounds.top, origin[1] + insets.top),
                    minOf(bounds.right, origin[0] + decor.width - insets.right),
                    minOf(bounds.bottom, origin[1] + decor.height - insets.bottom))
                val slop = ViewConfiguration.get(host).scaledTouchSlop
                check(displacement(20, k, axis) > slop)
                check(x - peakTravel > safe.left && x < safe.right && y > safe.top && y < safe.bottom)
                report.put("geometry", JSONObject().put("coordinateSpace", "SCREEN")
                    .put("rulerBounds", rect(bounds)).put("safeBounds", rect(safe))
                    .put("gestureInsets", JSONArray(listOf(insets.left, insets.top, insets.right, insets.bottom)))
                    .put("density", density).put("touchSlopPx", slop).put("dragWidthPxFloat", d)
                    .put("pixelsPerValue", k).put("valuePerPx", 1.0 / k).put("tapStep", axis.step).put("quantizationStep", if (axis.id == "strength") .01 else JSONObject.NULL)
                    .put("baselineHoldValue", axis.step / 3).put("baselineHoldPx", k * axis.step / 3)
                    .put("startX", x).put("peakX", x - peakTravel).put("returnX", x).put("y", y))
            }
            report.put("gfxinfoReset", captureGfxinfo(root, "reset"))
            gesture(x, y, k, axis, events)
            report.put("gfxinfoFramestats", captureGfxinfo(root, "framestats"))
            report.put("actualDownToUpMs", events.getJSONObject(41).getLong("eventUptimeMs") - events.getJSONObject(0).getLong("eventUptimeMs"))
            // Outside input window. This is UI semantics readiness, not a renderer completion observation.
            await("ruler ready after release", 120_000) { ready() && ruler(axis) != null }
            val returned = checkNotNull(ruler(axis))
            report.put("screenReturnValue", axis.value(checkNotNull(returned.rangeInfo).current))
                .put("screenReturnNormalizedRange", checkNotNull(returned.rangeInfo).current)
                .put("screenReturnStateDescription", returned.stateDescription?.toString() ?: JSONObject.NULL)
                .put("screenReturnReadUptimeMs", SystemClock.uptimeMillis()).put("outcome", "UI_CONTROL_COMPLETED")
        } catch (error: Throwable) {
            report.put("outcome", "FAILED").put("errorType", error.javaClass.name).put("error", error.message)
            throw error
        } finally {
            try { activity?.let { host -> inst.runOnMainSync { host.finish() } } }
            finally { File(root, "report.json").writeText(report.toString(2)) }
        }
    }

    /** Separate optional PSS run: one Hub, one Files selection, 15 gestures, normal Back. */
    @Test fun selectedDngPssReferenceOnly() {
        val started = SystemClock.elapsedRealtime()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("cpuReferencePss") == "true")
        check(args.getString("class") == "dev.cinematic.p0.gpu.CpuGate1ReferenceUiTest#selectedDngPssReferenceOnly")
        check(args.getString("gate1Part2") == "cpu-reference-pss-v1" && args.getString("isolated") == "true")
        check(Build.VERSION.SDK_INT >= 30 && inst.targetContext.packageName == TARGET)
        val emulator = Build.HARDWARE in setOf("ranchu", "goldfish")
        check(args.getString("gate1AuthorizedTarget") == Build.MODEL)
        if (!emulator) check(args.getString("gate1PixelProbe") == "true" && Build.MODEL == "Pixel 9a")
        check(args.getString("verifiedCpuApkSha256") == CPU_SHA)
        val installed = File(inst.targetContext.applicationInfo.sourceDir).inputStream().use { CpuGate1PssSupport.fingerprint(it) }.second
        check(installed == CPU_SHA)
        check(args.getString("gate1DngDocumentId") == "primary:Download/$FIXTURE")
        val runId = checkNotNull(args.getString("cpuRunId"))
        check(runId.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,79}")))
        val root = File(checkNotNull(inst.targetContext.getExternalFilesDir("cpu-reference-pss")), runId)
        check(!root.exists() && root.mkdirs()) { "Safe unique cpuRunId required; never overwrite" }
        val phases = listOf("hub_before_input", "initial_ui") + listOf("strength", "warmth", "EV").flatMap { axis ->
            (0..4).map { "$axis-${if (it == 0) "warmup" else "recorded-$it"}" }
        } + "normal_close_same_hub"
        val slots = JSONArray(phases.mapIndexed { i, phase -> JSONObject().put("slot", i + 1).put("phase", phase).put("status", "not_started") })
        val report = JSONObject().put("schema", 1).put("runId", runId).put("purpose", "OPTIONAL_UI_EXTERNAL_PSS_REFERENCE")
            .put("formalGate1Measurement", false).put("plannedCpuSlots", 18).put("plannedCpuAndGpuSlots", 36).put("slots", slots)
            .put("verifiedInstalledCpuApkSha256", installed).put("emulator", emulator).put("preparationClicks", preparationClicks)
            .put("authorizationMeaning", "flags are guards, never physical-device permission")
            .put("nativeCompletion", "UNKNOWN").put("allWorkersIdle", "UNKNOWN").put("activityGc", "UNKNOWN").put("fullRelease", "UNKNOWN").put("gcState", "UNKNOWN")
            .put("latestImageFrame", "UNKNOWN: start equals return; unchanged old UI cannot prove the latest image frame")
            .put("selectedUriIdentityLimit", "Expected URI/read-grant/hash plus picker-location evidence, not direct selected result URI or decoded frame observation")
            .put("decodedFloatIdentity", "UNOBSERVED_FIXED_CPU").put("peakCollection", false).put("gfxinfoCollection", false)
            .put("softElapsedBudgetMs", 780000).put("normalExitReserveMs", 45000).put("hardIoCancellation", false)
            .put("eighteenCompletedWithinBudgetGuaranteed", false).put("outcome", "started")
        fun save() = File(root, "report.json").writeText(report.toString(2))
        save()
        val support = CpuGate1PssSupport(root, started)
        var hubBound = false; var rollBound = false; var closed = false; var closeAttempted = false; var activeSlot: JSONObject? = null
        fun accessible(predicate: (AccessibilityNodeInfo) -> Boolean) = node { it.packageName?.toString() == TARGET && predicate(it) }
        fun filesVisible() = accessible { it.text?.toString()?.startsWith("Files") == true && it.isEnabled } != null
        fun uiToken(axis: Axis): String {
            val state = support.lifecycle(false)
            val ruler = checkNotNull(ruler(axis)); check(ruler.refresh() && ruler.isEnabled && ruler.isVisibleToUser && ready())
            check(ruler.packageName?.toString() == TARGET)
            val range = checkNotNull(ruler.rangeInfo); val value = axis.value(range.current)
            check(kotlin.math.abs(value - axis.start) < .00001)
            return "$state/axis=${axis.id}/value=$value/normalized=${range.current}/enabled=${ruler.isEnabled}/closeEnabled=true" +
                "/window=${ruler.windowId}/description=${checkNotNull(ruler.stateDescription)}"
        }
        fun capture(index: Int, closing: Boolean = false, boundary: () -> String) {
            activeSlot = slots.getJSONObject(index)
            support.capture(checkNotNull(activeSlot), support.deadline(30_000, closing), boundary); save(); activeSlot = null
        }
        fun normalClose() {
            check(!closeAttempted); closeAttempted = true // No retry after uncertain Back outcome.
            val end = support.deadline(30_000, closing = true)
            fun back() { support.remaining(end); inst.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK) }
            if (runCatching { support.lifecycle(true, rollBound) }.isSuccess) { closed = true; return }
            if (ui.rootInActiveWindow?.packageName?.toString()?.endsWith(".documentsui") == true) {
                back(); support.await(end) { runCatching { support.lifecycle(false) }.isSuccess }
            }
            check(rollBound); support.lifecycle(false)
            if (listOf("strength", "warmth", "EV").any { ruler(Axis.from(it)) != null }) {
                back(); support.await(end) { !ready() && listOf("strength", "warmth", "EV").all { ruler(Axis.from(it)) == null } }
            }
            if (!filesVisible()) { // viewer -> list; never click the recipe-saving close control
                support.lifecycle(false); back(); support.await(end, ::filesVisible)
            }
            support.lifecycle(false); back()
            support.await(end) { runCatching { support.lifecycle(true, true) }.isSuccess }
            closed = true
        }
        try {
            val hubEnd = support.deadline(30_000)
            support.bindHub(inst.startActivitySync(Intent().setClassName(TARGET, "dev.cinematic.p0.raw.DiagnosticHubActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))); hubBound = true
            support.await(hubEnd) { runCatching { support.lifecycle(true) }.isSuccess }
            capture(0) { support.lifecycle(true) }
            val openEnd = support.deadline(30_000)
            click(support.remaining(openEnd)) { support.remaining(openEnd); it.packageName?.toString() == TARGET && it.text?.toString() == "CPUプレビューの確認（Filesから選択）" && it.isEnabled }
            support.await(openEnd) { support.bindRoll().also { rollBound = it } }
            val filesEnd = support.deadline(90_000)
            click(support.remaining(filesEnd)) { support.remaining(filesEnd); it.packageName?.toString() == TARGET && it.text?.toString()?.startsWith("Files") == true && it.isEnabled }
            val location = support.openDownload(filesEnd); report.put("pickerLocation", location)
            val picker = UiDevice.getInstance(inst)
            val filesPackage = location.getString("documentsUiPackage")
            val directory = By.res(filesPackage, "dir_list")
            fun requireDownload() {
                support.remaining(filesEnd)
                check(picker.currentPackageName == filesPackage && !picker.hasObject(By.res(filesPackage, "roots_list")) &&
                    picker.hasObject(By.pkg(filesPackage).text("Download").hasAncestor(By.res(filesPackage, "toolbar"))))
            }
            // Search only the confirmed directory; never scroll a broader parent or another provider.
            support.await(filesEnd) {
                requireDownload()
                if (picker.hasObject(By.pkg(filesPackage).text(FIXTURE).hasAncestor(directory))) true
                else {
                    val list = checkNotNull(picker.findObject(directory))
                    if (list.isScrollable) list.scroll(Direction.DOWN, .5f)
                    false
                }
            }
            click(support.remaining(filesEnd)) {
                support.remaining(filesEnd)
                var ancestor = it.parent
                while (ancestor != null && ancestor.viewIdResourceName != "$filesPackage:id/dir_list") ancestor = ancestor.parent
                val matches = it.packageName?.toString() == filesPackage && it.text?.toString() == FIXTURE && ancestor != null
                if (matches) requireDownload() // Recheck location immediately before the single fresh ACTION_CLICK.
                matches
            }
            val initialEnd = support.deadline(120_000)
            click(support.remaining(initialEnd)) { support.remaining(initialEnd); it.packageName?.toString() == TARGET && it.text?.toString() == "STRENGTH" && it.isEnabled }
            val strength = Axis.from("strength")
            support.await(initialEnd) { runCatching { uiToken(strength) }.isSuccess }
            report.put("sourceBeforeOperations", support.verifyFixture()); support.remaining(initialEnd)
            capture(1) { uiToken(strength) }
            var index = 2
            for (id in listOf("strength", "warmth", "EV")) {
                val axis = Axis.from(id)
                for (trial in 0..4) {
                    val row = slots.getJSONObject(index); activeSlot = row; row.put("status", "operation_started")
                    val end = support.deadline(30_000)
                    if (trial == 0 && id != "strength") click(support.remaining(end)) {
                        support.remaining(end); it.packageName?.toString() == TARGET && it.text?.toString() == axis.button && it.isEnabled
                    }
                    support.await(end) { runCatching { uiToken(axis) }.isSuccess }
                    row.put("uiBeforeOperation", uiToken(axis))
                    val bounds = Rect().also(checkNotNull(ruler(axis))::getBoundsInScreen)
                    val density = inst.targetContext.resources.displayMetrics.density
                    val d = (24f * density * axis.span / axis.step).toFloat(); val k = d.toDouble() / axis.span
                    val x = bounds.exactCenterX(); val y = bounds.exactCenterY(); val peakTravel = displacement(200, k, axis)
                    support.withRoll { host ->
                        val decor = host.window.decorView; val origin = IntArray(2).also(decor::getLocationOnScreen)
                        val insets = checkNotNull(decor.rootWindowInsets).getInsets(WindowInsets.Type.systemGestures())
                        val safe = Rect(maxOf(bounds.left, origin[0] + insets.left), maxOf(bounds.top, origin[1] + insets.top),
                            minOf(bounds.right, origin[0] + decor.width - insets.right), minOf(bounds.bottom, origin[1] + decor.height - insets.bottom))
                        val slop = ViewConfiguration.get(host).scaledTouchSlop
                        check(displacement(20, k, axis) > slop && x - peakTravel > safe.left && x < safe.right && y > safe.top && y < safe.bottom)
                        row.put("geometry", JSONObject().put("rulerBounds", rect(bounds)).put("safeBounds", rect(safe)).put("density", density)
                            .put("dragWidthPxFloat", d).put("pixelsPerValue", k).put("touchSlopPx", slop).put("startX", x).put("peakX", x - peakTravel).put("returnX", x).put("y", y))
                    }
                    check(support.remaining(end) > 800)
                    val events = JSONArray(); row.put("injectedEvents", events).put("plannedEvents", 42)
                        .put("riseMs", 200).put("holdMs", 400).put("returnMs", 200).put("requestedStart", axis.start).put("requestedPeak", axis.peak).put("requestedReturn", axis.start)
                    gesture(x, y, k, axis, events) // Existing geometry/trajectory. No UI reads during input.
                    support.await(end) { runCatching { uiToken(axis) }.isSuccess }
                    row.put("uiAfterOperation", uiToken(axis)).put("actualDownToUpMs", events.getJSONObject(41).getLong("eventUptimeMs") - events.getJSONObject(0).getLong("eventUptimeMs"))
                    capture(index++) { uiToken(axis) }
                }
            }
            report.put("sourceAfterOperations", support.verifyFixture())
            activeSlot = slots.getJSONObject(17).put("status", "close_started")
            normalClose(); capture(17, closing = true) { support.lifecycle(true, true) }
            report.put("outcome", "18_UI_EXTERNAL_REFERENCE_SAMPLES")
        } catch (failure: Throwable) {
            activeSlot?.let { if (it.optString("status") != "invalid_reference_sample") it.put("status", "operation_failed").put("failureType", failure.javaClass.simpleName) }
            report.put("outcome", "STOPPED_INCOMPLETE").put("failureType", failure.javaClass.simpleName)
            throw failure
        } finally {
            try {
                if (hubBound && !closeAttempted) try { normalClose() } catch (failure: Throwable) { report.put("normalCloseFailureType", failure.javaClass.simpleName) }
                report.put("normalCloseObserved", closed).put("endedElapsedMs", SystemClock.elapsedRealtime())
            } finally { try { support.dispose() } finally { save() } }
        }
    }

    @Test fun pssParserAndBudgetPureChecks() = CpuGate1PssSupport.checkPureContracts()

    /** Outside gesture window, while the target Activity/process still exists.
     * -t 10 is a dumpsys service timeout, NOT a hard deadline on executeShellCommand or pipe IO.
     * Raw output is reference UI-frame information, not image-render values or formal Gate1 data.
     * EOF is transport completion only: host must inspect raw output for timeout/no-process/errors.
     */
    private fun captureGfxinfo(root: File, operation: String): JSONObject {
        check(operation == "reset" || operation == "framestats")
        val file = File(root, "gfxinfo-$operation.txt")
        val result = JSONObject().put("operation", operation).put("file", file.name)
            .put("startedUptimeMs", SystemClock.uptimeMillis()).put("serviceTimeoutSeconds", 10)
            .put("hardIoDeadline", false).put("timeoutStatus", "UNKNOWN_REQUIRES_RAW_OUTPUT_AUDIT")
            .put("measurementStatus", "UNVALIDATED_REFERENCE_ONLY").put("exitCode", JSONObject.NULL)
            .put("bytes", JSONObject.NULL).put("imageFrameMapping", false).put("formalGate1Comparable", false)
        var bytes = 0L
        try {
            val descriptor = ui.executeShellCommand("dumpsys -t 10 gfxinfo $TARGET $operation")
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        bytes += count
                    }
                }
            }
            result.put("captureStatus", if (bytes == 0L) "EMPTY_OUTPUT_MISSING" else "EOF_RAW_SAVED")
        } catch (error: Exception) {
            result.put("captureStatus", "CAPTURE_FAILED_OR_PARTIAL")
                .put("errorType", error.javaClass.name).put("error", error.message)
        } finally {
            result.put("endedUptimeMs", SystemClock.uptimeMillis()).put("bytes", bytes)
            // Byte count is transport metadata; never substitute zero for missing frame measurements.
            try { File(root, "gfxinfo-$operation-status.json").writeText(result.toString(2)) }
            catch (error: Exception) { result.put("statusFileErrorType", error.javaClass.name) }
        }
        return result
    }
    private fun displacement(ms: Long, k: Double, axis: Axis): Float {
        val fraction = when { ms <= 200 -> ms / 200.0; ms <= 600 -> 1.0; else -> (800 - ms) / 200.0 }
        return if (fraction == 0.0) 0f else (k * (axis.step / 3 + (axis.peak - axis.start) * fraction)).toFloat()
    }
    private fun gesture(x: Float, y: Float, k: Double, axis: Axis, records: JSONArray) {
        val down = SystemClock.uptimeMillis()
        var current = x; var released = false; var primaryFailure: Throwable? = null
        fun send(action: Int, planned: Long) {
            val now = SystemClock.uptimeMillis()
            val event = MotionEvent.obtain(down, now, action, current, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try {
                val accepted = ui.injectInputEvent(event, false)
                records.put(JSONObject().put("action", action).put("plannedUptimeMs", planned)
                    .put("eventUptimeMs", now).put("injectionReturnedUptimeMs", SystemClock.uptimeMillis())
                    .put("screenX", current).put("screenY", y).put("accepted", accepted))
                check(accepted) { "Input injection rejected" }
            } finally { event.recycle() }
        }
        try {
            send(MotionEvent.ACTION_DOWN, down)
            for (i in 1..40) {
                val elapsed = i * 20L
                SystemClock.sleep((down + elapsed - SystemClock.uptimeMillis()).coerceAtLeast(0))
                current = x - displacement(elapsed, k, axis)
                send(MotionEvent.ACTION_MOVE, down + elapsed)
            }
            send(MotionEvent.ACTION_UP, down + 800)
            released = true
        } catch (error: Throwable) {
            primaryFailure = error; throw error
        } finally {
            if (!released) {
                // Failure cleanup is explicitly a 43rd/extra event, not hidden in a valid 42-event trial.
                try { send(MotionEvent.ACTION_CANCEL, SystemClock.uptimeMillis()) }
                catch (cleanup: Throwable) { primaryFailure?.addSuppressed(cleanup) ?: throw cleanup }
            }
        }
    }
    private fun ruler(axis: Axis): AccessibilityNodeInfo? = node { it.contentDescription?.toString() == axis.label && it.rangeInfo != null }
    private fun ready(): Boolean = node { it.contentDescription?.toString() == "\u76ee\u76db\u308a\u3092\u9589\u3058\u308b" && it.isEnabled } != null
    private fun node(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun find(current: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (predicate(current)) return current
            for (i in 0 until current.childCount) current.getChild(i)?.let { find(it)?.let { found -> return found } }
            return null
        }
        return ui.rootInActiveWindow?.let(::find)
    }
    private fun click(timeout: Long = 15_000, predicate: (AccessibilityNodeInfo) -> Boolean) {
        val record = JSONObject().put("startedUptimeMs", SystemClock.uptimeMillis()).put("actionAttempts", 0)
        preparationClicks.put(record) // Three setup clicks only; overwrite last readiness snapshot, not an unbounded log.
        var target: AccessibilityNodeInfo? = null
        try {
            await("fresh enabled actionable ancestor", timeout) {
                target = null
                val selected = node(predicate)
                if (selected == null) { record.put("label", JSONObject.NULL); record.put("ancestor", JSONObject.NULL); false }
                else {
                    val labelFresh = selected.refresh()
                    record.put("label", clickNode(selected).put("refreshSucceeded", labelFresh))
                    var ancestor: AccessibilityNodeInfo? = selected
                    while (ancestor != null && !ancestor.isClickable) ancestor = ancestor.parent
                    if (ancestor == null) { record.put("ancestor", JSONObject.NULL); false }
                    else {
                        val fresh = ancestor.refresh()
                        val activeWindow = ui.rootInActiveWindow?.windowId
                        val sameWindow = activeWindow != null && ancestor.windowId == activeWindow
                        val actionable = ancestor.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }
                        record.put("ancestor", clickNode(ancestor).put("refreshSucceeded", fresh)
                            .put("sameActiveWindow", sameWindow).put("visibleToUser", ancestor.isVisibleToUser))
                        // Public attachment evidence is refresh success + membership in the current active window.
                        val ready = labelFresh && predicate(selected) && fresh && sameWindow &&
                            ancestor.isVisibleToUser && ancestor.isEnabled && ancestor.isClickable && actionable
                        if (ready) target = ancestor
                        ready
                    }
                }
            }
            record.put("actionAttempts", 1).put("attemptUptimeMs", SystemClock.uptimeMillis())
            val accepted = checkNotNull(target).performAction(AccessibilityNodeInfo.ACTION_CLICK)
            record.put("accepted", accepted)
            check(accepted) { "ACTION_CLICK returned false; no retry or fallback" }
        } catch (error: Throwable) {
            record.put("failureType", error.javaClass.name).put("failure", error.message)
            throw error
        }
    }
    private fun clickNode(value: AccessibilityNodeInfo): JSONObject = JSONObject()
        .put("class", value.className?.toString() ?: JSONObject.NULL)
        .put("text", safeSetupText(value.text)).put("contentDescription", safeSetupText(value.contentDescription))
        .put("isEnabled", value.isEnabled).put("isClickable", value.isClickable).put("windowId", value.windowId)
        .put("actionIds", JSONArray(value.actionList.map { it.id }))
    private fun safeSetupText(value: CharSequence?): Any {
        val text = value?.toString() ?: return JSONObject.NULL
        // Do not save arbitrary Files provider contents or other personal UI text.
        return if (text in setOf("Files", FIXTURE, "STRENGTH", "色温度", "明るさ",
                "色温度 · 相対（K値ではありません）", "明るさ · EV")) text else "[REDACTED_NON_FIXTURE_TEXT]"
    }
    private fun await(label: String, timeout: Long, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < deadline) { if (predicate()) return; SystemClock.sleep(30) }
        error("Timed out: $label")
    }
    private fun rect(r: Rect) = JSONArray(listOf(r.left, r.top, r.right, r.bottom))
    /** CPU1ce PhotoAdjustmentPolicy + TrialStrengthEditorPane + PhotoEditBar semantics.
     * RangeInfo is normalized 0..1 for ALL axes; stateDescription is formatted UI text.
     * Auxiliary peak capture plan (not executed here): repeat the SAME axis/profile in a separate
     * non-timing run, read stateDescription/rangeInfo during 200..600ms with timestamp, optionally
     * take a screenshot. If read finishes outside the hold, record missing; never extend this run.
     * Strength text 50/56 means .50/.56; warmth text +6 is relative, never Kelvin; EV text +0.6.
     * Such UI reads do not prove the values of rendered image frames. No concurrent UI client.
     */
    private data class Axis(val id: String, val button: String, val label: String,
        val minimum: Double, val maximum: Double, val step: Double, val start: Double,
        val peak: Double, val unit: String, val displayConversion: String) {
        val span get() = maximum - minimum
        fun value(normalized: Float) = minimum + normalized.toDouble() * span
        companion object {
            fun from(id: String): Axis = when (id) {
                "strength" -> Axis(id, "STRENGTH", "STRENGTH", 0.0, 1.0, .01, .50, .56,
                    "strength_0_to_1", "displayed integer / 100; 50 means .50")
                "warmth" -> Axis(id, "色温度", "色温度 · 相対（K値ではありません）", -100.0, 100.0, 1.0, 0.0, 6.0,
                    "relative_warmth_not_Kelvin", "signed integer, rounded display; +6 means 6")
                "EV" -> Axis(id, "明るさ", "明るさ · EV", -2.0, 2.0, .1, 0.0, .6,
                    "exposure_EV", "signed one decimal; +0.6 means .6 EV")
                else -> error("cpuAxis must be exactly strength, warmth or EV")
            }
        }
    }
    companion object {
        private const val TARGET = "dev.cinematic.p0.gpudiagnostic"
        private const val CPU_SHA = "8cfc16cf5c60e29a3c67618402a9913be7a24a7f5367e5bd101c362e8b4bf745"
        private const val FIXTURE = "gate1-common-synthetic-1024x768-v1.dng"
    }
}