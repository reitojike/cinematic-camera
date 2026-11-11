package dev.cinematic.p0.gpu

import android.app.Activity
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Fixed CPU 1ce: public UI and Window PixelCopy only. No product types, reflection or render hook. */
@RunWith(AndroidJUnit4::class)
class CpuGate1VisualCaptureTest {
    @Test fun captureSelectedCpuDisplay() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("gate1VisualCapture") == "cpu")
        var support: Gate1VisualCaptureSupport? = null
        var failed = false
        try {
            val s = Gate1VisualCaptureSupport("cpu",
                "dev.cinematic.p0.gpu.CpuGate1VisualCaptureTest#captureSelectedCpuDisplay",
                Gate1VisualCaptureSupport.CPU_SHA).also { support = it }
            s.report.put("decodedFloatIdentity", "UNOBSERVED_FIXED_CPU")
                .put("internalFrameRecipe", "UNOBSERVED_FIXED_CPU")
                .put("cpuRenderHook", false).put("cpuTimingCollection", false)
                .put("captureMeaning", "latest queued Window buffer cropped to the public Image bounds; not offscreen rerender")
                .put("nativeCompletion", "UNOBSERVED").put("allWorkersIdle", "UNOBSERVED")
            s.startHub()
            var activity: Activity? = null
            var state: JSONObject? = null
            Gate1VisualCaptureSupport.await(180000) {
                s.observeExternalSetup()
                activity = s.bindExpectedActivity(Gate1VisualCaptureSupport.CPU_ACTIVITY)
                if (activity == null) {
                    saveReadiness(s, JSONObject().put("activityBound", false).put("ready", false)
                        .put("firstBlocker", "expected_activity_not_resumed_and_focused")
                        .put("uiPredicates", "NOT_OBSERVED"))
                    false
                } else { state = readUi(s); state != null }
            }
            val host = checkNotNull(activity)
            s.requireActive(host, Gate1VisualCaptureSupport.CPU_ACTIVITY)
            s.phase("verify_cpu_source_and_ui")
            s.report.put("sourceBefore", s.verifySource()).put("uiBefore", state)
            val before = checkNotNull(state)
            val imageBounds = fromJson(before.getJSONArray("imageBoundsScreen"))
            var first: android.graphics.Bitmap? = null
            var second: android.graphics.Bitmap? = null
            try {
                s.phase("copy_cpu_window_a")
                first = s.windowCopy(host, imageBounds, "cpu-window-a")
                s.report.put("originalA", s.saveOriginal(first, "cpu-window-a")); s.save()
                s.requireActive(host, Gate1VisualCaptureSupport.CPU_ACTIVITY)
                check(readUi(s)?.toString() == before.toString()) { "CPU UI changed during capture" }
                SystemClock.sleep(250)
                s.phase("copy_cpu_window_b")
                second = s.windowCopy(host, imageBounds, "cpu-window-b")
                s.report.put("originalB", s.saveOriginal(second, "cpu-window-b"))
                val after = checkNotNull(readUi(s))
                s.report.put("uiAfter", after).put("twoCopiesSamePixels", first.sameAs(second))
                    .put("stabilityMeaning", "two copies 250ms apart agree; not proof of correct or latest CPU internal frame")
                check(after.toString() == before.toString())
                check(first.sameAs(second)) { "CPU display changed; no recapture" }
            } finally { first?.recycle(); second?.recycle() }
            s.requireActive(host, Gate1VisualCaptureSupport.CPU_ACTIVITY)
            s.report.put("sourceAfter", s.verifySource()).put("acquisitionStatus", "ORIGINALS_CAPTURED")
                .put("formalVisualPairReady", false)
        } catch (error: Throwable) {
            failed = true
            support?.recordFailure(error)
        } finally {
            support?.let { s ->
                try { s.returnToHub() } catch (error: Throwable) {
                    failed = true
                    s.report.getJSONObject("cleanup").put("status", "UNPROVEN").put("failureType", error.javaClass.simpleName)
                }
                s.report.put("outcome", if (failed) "FAILED_OR_STOPPED" else "CAPTURE_CANDIDATE_ONLY")
                s.save()
            }
        }
        if (failed) throw AssertionError("CPU visual acquisition stopped; inspect the private report")
    }

    /** Image semantics, not the parent viewport. No corrective control input is performed. */
    private fun readUi(s: Gate1VisualCaptureSupport): JSONObject? {
        val nodes = s.nodes()
        val text = nodes.mapNotNull { it.text?.toString() }
        val predicates = linkedMapOf<String, Boolean>()
        val observed = JSONObject().put("nodeCount", nodes.size)
        predicates["target_nodes_present"] = nodes.isNotEmpty()
        // Files selection does not add an item to the CPU listing; its name can be unavailable.
        listOf("更新中", "未反映", "画像を準備中", "原本を確認中").zip(
            listOf("no_updating_text", "no_unapplied_text", "no_preparing_text", "no_checking_text")
        ).forEach { (label, key) -> predicates[key] = text.none { label in it } }
        val panels = nodes.count { it.contentDescription?.toString() == "現像調整パネル" }
        observed.put("adjustmentPanelCount", panels)
        predicates["no_adjustment_panel"] = panels == 0

        val images = nodes.filter { it.contentDescription?.toString() == "現像結果（表示用）" }
        val image = images.singleOrNull()
        observed.put("imageCount", images.size)
        predicates["one_after_image"] = image != null
        val state = image?.stateDescription?.toString()
        observed.put("imageStateKind", when {
            image == null -> "NOT_OBSERVED"
            state == null -> "ABSENT"
            state == "現像結果（表示用）" -> "EXPECTED_AFTER"
            else -> "OTHER"
        })
        predicates["after_image_state"] = image != null && (state == null || state == "現像結果（表示用）")
        val viewports = nodes.filter { it.contentDescription?.toString() == "現像画像の表示領域" }
        val viewport = viewports.singleOrNull()
        observed.put("viewportCount", viewports.size)
        predicates["one_viewport"] = viewport != null

        val labels = nodes.filter { it.contentDescription?.toString() == "診断プレビュー2並列" }
        val label = labels.singleOrNull()
        observed.put("parallelLabelCount", labels.size)
        predicates["one_parallel_label"] = label != null
        // Match the existing switchControl contract: the labelled descendant need not be checkable.
        // Stop at the nearest checkable node; never substitute an unrelated control or click it.
        var control = label
        var depth = 0
        while (control != null && !control.isCheckable && depth < 32) {
            control = control.parent
            depth++
        }
        val sameScope = label != null && control != null &&
            label.packageName?.toString() == Gate1VisualCaptureSupport.TARGET &&
            control.packageName?.toString() == Gate1VisualCaptureSupport.TARGET &&
            label.windowId == control.windowId && label.isVisibleToUser && control.isVisibleToUser
        val labelBounds = label?.let(s::bounds)
        val controlBounds = control?.let(s::bounds)
        val containsLabel = labelBounds != null && controlBounds != null &&
            !labelBounds.isEmpty && !controlBounds.isEmpty && controlBounds.contains(labelBounds)
        observed.put("parallelAncestorDepth", depth)
            .put("parallelLabelCheckable", label?.isCheckable ?: JSONObject.NULL)
            .put("parallelControlCheckable", control?.isCheckable ?: JSONObject.NULL)
            .put("parallelControlChecked", control?.isChecked ?: JSONObject.NULL)
            .put("parallelControlEnabled", control?.isEnabled ?: JSONObject.NULL)
            .put("parallelLabelBoundsScreen", labelBounds?.let(s::rect) ?: JSONObject.NULL)
            .put("parallelControlBoundsScreen", controlBounds?.let(s::rect) ?: JSONObject.NULL)
        predicates["parallel_control_found"] = control?.isCheckable == true
        predicates["parallel_same_scope_visible"] = sameScope
        predicates["parallel_control_contains_label"] = containsLabel
        predicates["parallel_off"] = control?.isCheckable == true && !control.isChecked

        fun button(heading: String, expected: String): Boolean {
            return nodes.filter { it.isClickable && it.isEnabled }.any { node ->
                val tokens = descendantText(node).split(Regex("[\\s,]+"))
                heading in tokens && expected in tokens && "未反映" !in tokens
            }
        }
        predicates["strength_footer_50"] = button("STRENGTH", "50")
        predicates["warmth_footer_plus_0"] = button("色温度", "+0")
        predicates["exposure_footer_plus_0_0"] = button("明るさ", "+0.0")
        val bounds = image?.let(s::bounds)
        val viewportBounds = viewport?.let(s::bounds)
        observed.put("imageBoundsScreen", bounds?.let(s::rect) ?: JSONObject.NULL)
            .put("viewportBoundsScreen", viewportBounds?.let(s::rect) ?: JSONObject.NULL)
        predicates["image_nonempty"] = bounds != null && !bounds.isEmpty
        predicates["viewport_contains_image"] = bounds != null && viewportBounds?.contains(bounds) == true
        val blocker = predicates.entries.firstOrNull { !it.value }?.key
        saveReadiness(s, JSONObject().put("activityBound", true).put("ready", blocker == null)
            .put("firstBlocker", blocker ?: "NONE").put("predicates", JSONObject(predicates))
            .put("observed", observed))
        if (blocker != null) return null
        checkNotNull(bounds); checkNotNull(viewportBounds)
        val scale = minOf(bounds.width() / 1024.0, bounds.height() / 768.0)
        val width = 1024 * scale; val height = 768 * scale
        // A continuous mathematical FIT rectangle, NOT an asserted integer raster crop.
        val fit = JSONArray(listOf(bounds.exactCenterX() - width / 2, bounds.exactCenterY() - height / 2,
            bounds.exactCenterX() + width / 2, bounds.exactCenterY() + height / 2))
        val overlaps = JSONArray()
        nodes.forEach { node ->
            val b = s.bounds(node)
            val isImage = node.contentDescription?.toString() == "現像結果（表示用）"
            val overlap = b.left < fit.getDouble(2) && b.right > fit.getDouble(0) &&
                b.top < fit.getDouble(3) && b.bottom > fit.getDouble(1)
            // Exclude enclosing containers. Preserve anonymous smaller UI regions, including permanent notices.
            if (!isImage && overlap && !b.contains(bounds) &&
                (node.text?.isNotBlank() == true || node.isClickable || node.isCheckable)) {
                overlaps.put(JSONObject().put("boundsScreen", s.rect(b)).put("textNode", node.text?.isNotBlank() == true)
                    .put("clickable", node.isClickable).put("checkable", node.isCheckable))
            }
        }
        return JSONObject().put("fixtureNameVisible", text.any { it == Gate1VisualCaptureSupport.FIXTURE })
            .put("selectionEvidence", "external Files selection record plus expected URI/read-grant/hash; result URI and decoded frame are not directly observed").put("strengthUi", .5).put("warmthUi", 0)
            .put("exposureEvUi", 0)
            .put("recipeObservation", "rounded public footer text, not internal Double values or per-frame metadata")
            .put("recipeDisplayHalfWidth", JSONObject().put("strength", .005).put("warmth", .5).put("exposureEv", .05))
            .put("parallelUi", false).put("showBeforeUi", false)
            .put("imageBoundsScreen", s.rect(bounds)).put("parentViewportBoundsScreen", s.rect(viewportBounds))
            .put("fitContentRectScreenContinuous", fit)
            .put("contentRectMeaning", "source-based 4:3 FIT estimate only; not decoded CPU dimensions or an automatic crop")
            .put("potentialOverlayRegions", overlaps).put("unoccluded", if (overlaps.length() == 0) "UNPROVEN" else "FALSE_OR_UNPROVEN")
            .put("imageSourceDimensions", "REGISTERED_1024x768_NOT_DIRECTLY_OBSERVED")
    }

    /** One bounded scalar snapshot only. Never records arbitrary UI text or provider filenames. */
    private fun saveReadiness(s: Gate1VisualCaptureSupport, value: JSONObject) {
        if (s.report.optJSONObject("cpuReadiness")?.toString() != value.toString()) {
            s.report.put("cpuReadiness", value)
            s.save()
        }
    }

    private fun descendantText(root: AccessibilityNodeInfo): String {
        val text = ArrayList<String>(); val queue = ArrayDeque<AccessibilityNodeInfo>(); queue.add(root)
        var count = 0
        while (queue.isNotEmpty()) {
            check(++count < 256)
            val node = queue.removeFirst(); node.text?.toString()?.let(text::add)
            repeat(node.childCount) { node.getChild(it)?.let(queue::addLast) }
        }
        return text.joinToString(" ")
    }

    private fun fromJson(value: JSONArray) = Rect(value.getInt(0), value.getInt(1), value.getInt(2), value.getInt(3))
}