package dev.cinematic.p0.gpu

import android.app.Activity
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** External UI controller only. Fixed CPU APK has no added observation code.
 * Functional candidate, not a benchmark or proof of per-image values. Host owns APK verification,
 * gfxinfo/PSS collection and isolation. No accessibility polling during the comparison gesture.
 */
@RunWith(AndroidJUnit4::class)
class CpuGate1ReferenceUiTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ui get() = inst.uiAutomation

    @Test fun axisHoldReferenceOnly() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("cpuReferenceUi") == "true" && args.getString("isolated") == "true")
        check(args.getString("class") == "dev.cinematic.p0.gpu.CpuGate1ReferenceUiTest#axisHoldReferenceOnly")
        check(Build.HARDWARE in setOf("ranchu", "goldfish") && Build.VERSION.SDK_INT >= 30)
        check(inst.targetContext.packageName == TARGET)
        // A host attestation, not an in-app hash measurement. Host must verify installed APK bytes.
        check(args.getString("verifiedCpuApkSha256") == CPU_SHA)
        val axis = Axis.from(checkNotNull(args.getString("cpuAxis")))
        val root = File(checkNotNull(inst.targetContext.getExternalFilesDir("cpu-reference-ui")),
            "functional-${SystemClock.elapsedRealtime()}")
        check(root.mkdirs())
        val events = JSONArray()
        val report = JSONObject().put("schema", 1).put("purpose", "UNREGISTERED_FUNCTIONAL_REFERENCE")
            .put("hostAttestedCpuApkSha256", CPU_SHA).put("axis", axis.id)
            .put("requestedStart", axis.start).put("requestedPeak", axis.peak).put("requestedReturn", axis.start)
            .put("valueUnit", axis.unit).put("rangeMinimum", axis.minimum).put("rangeMaximum", axis.maximum)
            .put("stateDescriptionConversion", axis.displayConversion)
            .put("riseMs", 200).put("holdMs", 400).put("returnMs", 200).put("plannedDurationMs", 800)
            .put("intervalMs", 20).put("plannedInjectedEvents", 42).put("tagSetupTapEvents", 0)
            .put("screenValuesAreImageFrameValues", false).put("peakScreenValue", JSONObject.NULL)
            .put("peakScreenStatus", "NOT_CAPTURED_USE_SEPARATE_AUXILIARY_RUN")
            .put("perImageValues", "UNAVAILABLE").put("formalGate1Measurement", false)
            .put("injectedEvents", events)
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
            gesture(x, y, k, axis, events)
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
        var selected: AccessibilityNodeInfo? = null
        await("UI click target", timeout) { selected = node(predicate); selected != null }
        var target = checkNotNull(selected)
        while (!target.isClickable) target = checkNotNull(target.parent)
        check(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
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