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
                if (activity == null) false else { state = readUi(s); state != null }
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
        // Files selection does not add an item to the CPU listing; its name can be unavailable.
        if (text.any { value -> listOf("更新中", "未反映", "画像を準備中", "原本を確認中").any(value::contains) }) return null
        if (nodes.any { it.contentDescription?.toString() == "現像調整パネル" }) return null
        val image = nodes.singleOrNull { it.contentDescription?.toString() == "現像結果（表示用）" } ?: return null
        if (image.stateDescription != null && image.stateDescription.toString() != "現像結果（表示用）") return null
        val viewport = nodes.singleOrNull { it.contentDescription?.toString() == "現像画像の表示領域" } ?: return null
        val parallel = nodes.singleOrNull { it.contentDescription?.toString() == "診断プレビュー2並列" } ?: return null
        if (!parallel.isCheckable || parallel.isChecked) return null
        fun button(heading: String, expected: String): Boolean {
            return nodes.filter { it.isClickable && it.isEnabled }.any { node ->
                val tokens = descendantText(node).split(Regex("[\\s,]+"))
                heading in tokens && expected in tokens && "未反映" !in tokens
            }
        }
        if (!button("STRENGTH", "50") || !button("色温度", "+0") || !button("明るさ", "+0.0")) return null
        val bounds = s.bounds(image)
        val viewportBounds = s.bounds(viewport)
        if (bounds.isEmpty || !viewportBounds.contains(bounds)) return null
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