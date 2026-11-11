package dev.cinematic.p0.gpu

import android.app.Activity
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutInfo
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.util.fastRoundToInt
import kotlin.math.ceil
import kotlin.math.floor
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
                .put("cpuRenderHook", false).put("cpuTimingCollection", false).put("displayGeometryVerified", false)
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
            s.phase("observe_cpu_unclipped_layout")
            val layoutBefore = displayGeometry(s, host, before, imageBounds)
            s.report.put("displayGeometryBefore", layoutBefore.json); s.save()
            var first: android.graphics.Bitmap? = null
            var second: android.graphics.Bitmap? = null
            try {
                s.phase("copy_cpu_window_a")
                first = s.windowCopy(host, imageBounds, "cpu-window-a")
                s.report.put("originalA", s.saveOriginal(first, "cpu-window-a")); s.save()
                s.requireActive(host, Gate1VisualCaptureSupport.CPU_ACTIVITY)
                val between = checkNotNull(readUi(s))
                check(between.toString() == before.toString()) { "CPU UI changed during capture" }
                s.phase("observe_cpu_layout_between")
                val layoutBetween = displayGeometry(s, host, between, imageBounds, layoutBefore)
                s.report.put("displayGeometryBetween", layoutBetween.json); s.save()
                check(layoutBetween.json.toString() == layoutBefore.json.toString()) { "CPU layout changed; no recapture" }
                SystemClock.sleep(250)
                s.phase("copy_cpu_window_b")
                second = s.windowCopy(host, imageBounds, "cpu-window-b")
                s.report.put("originalB", s.saveOriginal(second, "cpu-window-b"))
                val after = checkNotNull(readUi(s))
                s.report.put("uiAfter", after).put("twoCopiesSamePixels", first.sameAs(second))
                    .put("stabilityMeaning", "two copies 250ms apart agree; not proof of correct or latest CPU internal frame")
                check(after.toString() == before.toString())
                check(first.sameAs(second)) { "CPU display changed; no recapture" }
                s.phase("observe_cpu_layout_after")
                val layoutAfter = displayGeometry(s, host, after, imageBounds, layoutBefore)
                s.report.put("displayGeometryAfter", layoutAfter.json); s.save()
                check(layoutAfter.json.toString() == layoutBefore.json.toString()) { "CPU layout changed; no recapture" }
                s.report.put("displayGeometryVerified", true)
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
        // Same UiAutomation connection, public APIs only; no second UI client or product hooks.
        // clearCache: developer.android.com/reference/android/app/UiAutomation#clearCache()
        // refresh: developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo#refresh()
        val automation = s.ins.uiAutomation
        val cleared = if (android.os.Build.VERSION.SDK_INT >= 34) automation.clearCache() else null
        val root = automation.rootInActiveWindow
        val window = root?.windowId
        val all = ArrayList<AccessibilityNodeInfo>()
        val parents = HashMap<AccessibilityNodeInfo, AccessibilityNodeInfo?>()
        val children = HashMap<AccessibilityNodeInfo, MutableList<AccessibilityNodeInfo>>()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, AccessibilityNodeInfo?>>()
        root?.let { queue.add(it to null) }
        var complete = root != null && cleared != false
        var refreshFailed = 0
        var scopeRejected = 0
        var missingChildren = 0
        while (complete && queue.isNotEmpty()) {
            check(all.size < 1023) { "Unexpected UI tree size" }
            val (node, parent) = queue.removeFirst()
            if (!node.refresh()) { refreshFailed++; complete = false; break }
            if (node.packageName?.toString() != Gate1VisualCaptureSupport.TARGET || node.windowId != window) {
                scopeRejected++; complete = false; break
            }
            check(!parents.containsKey(node)) { "Duplicate UI node" }
            all.add(node); parents[node] = parent; children[node] = ArrayList()
            parent?.let { children.getValue(it).add(node) }
            for (index in 0 until node.childCount) {
                val child = node.getChild(index)
                if (child == null) { missingChildren++; complete = false; break }
                queue.add(child to node)
            }
        }
        val endRoot = automation.rootInActiveWindow
        val sameWindow = endRoot?.refresh() == true &&
            endRoot.packageName?.toString() == Gate1VisualCaptureSupport.TARGET && endRoot.windowId == window
        val freshness = JSONObject().put("apiLevel", android.os.Build.VERSION.SDK_INT)
            .put("cacheClearSupported", cleared != null)
            .put("cacheCleared", cleared ?: JSONObject.NULL).put("refreshedNodeCount", all.size)
            .put("refreshFailedCount", refreshFailed).put("scopeRejectedCount", scopeRejected)
            .put("missingChildCount", missingChildren).put("sameActiveWindowAfter", sameWindow)
            .put("complete", complete && sameWindow)
            .put("meaning", "one refreshed traversal; not an atomic frame or proof that all CPU workers are idle")
        if (!complete || !sameWindow) {
            saveReadiness(s, JSONObject().put("activityBound", true).put("ready", false)
                .put("firstBlocker", "accessibility_snapshot_not_fresh")
                .put("uiPredicates", "NOT_OBSERVED").put("freshness", freshness))
            return null
        }
        val nodes = all.filter { it.isVisibleToUser }
        val visible = nodes.toHashSet()
        val text = nodes.mapNotNull { it.text?.toString() }
        val predicates = linkedMapOf<String, Boolean>()
        val observed = JSONObject().put("nodeCount", nodes.size)
        predicates["target_nodes_present"] = nodes.isNotEmpty()
        // Files selection does not add an item to the CPU listing; its name can be unavailable.
        listOf("更新中", "未反映", "画像を準備中", "原本を確認中").zip(
            listOf("no_updating_text", "no_unapplied_text", "no_preparing_text", "no_checking_text")
        ).forEach { (label, key) -> predicates[key] = text.none { label in it } }
        val unapplied = nodes.filter { it.text?.contains("未反映") == true }
        observed.put("unappliedNodeCount", unapplied.size)
            .put("unappliedEvidence", JSONArray(unapplied.take(4).map { node ->
                JSONObject().put("boundsScreen", s.rect(s.bounds(node))).put("kind", when (node.text?.toString()) {
                    "表示中 · 未反映の調整あり" -> "IMAGE_CAPTION"
                    "指定 · 未反映" -> "FOOTER_PENDING"
                    else -> "OTHER_MATCH"
                })
            }))
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
            control = parents[control]
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
            val candidates = nodes.filter { it.isClickable }.map { node ->
                node to descendantText(node, children, visible).split(Regex("[\\s,]+"))
            }.filter { (_, tokens) -> heading in tokens }
            if (heading == "明るさ") observed.put("exposureFooterCandidateCount", candidates.size)
                .put("exposureFooterEvidence", JSONArray(candidates.take(4).map { (node, tokens) ->
                    JSONObject().put("boundsScreen", s.rect(s.bounds(node))).put("enabled", node.isEnabled)
                        .put("expectedValuePresent", expected in tokens).put("unappliedTokenPresent", "未反映" in tokens)
                }))
            return candidates.any { (node, tokens) ->
                node.isEnabled && expected in tokens && "未反映" !in tokens
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
            .put("observed", observed).put("freshness", freshness))
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
            .put("imageSemanticsId", image?.extras?.takeIf { it.containsKey("androidx.compose.ui.semantics.id") }
                ?.getInt("androidx.compose.ui.semantics.id") ?: JSONObject.NULL)
            .put("imageAccessibilityWindowId", image?.windowId ?: JSONObject.NULL)
            .put("fitContentRectScreenContinuous", fit)
            .put("contentRectMeaning", "source-based 4:3 FIT estimate only; not decoded CPU dimensions or an automatic crop")
            .put("potentialOverlayRegions", overlaps).put("unoccluded", if (overlaps.length() == 0) "UNPROVEN" else "FALSE_OR_UNPROVEN")
            .put("imageSourceDimensions", "REGISTERED_1024x768_NOT_DIRECTLY_OBSERVED")
    }

    private data class LayoutObservation(val json: JSONObject, val root: ViewRootForTest, val layout: LayoutInfo)

    /** Read existing public test interfaces only. Never creates a root, sets a callback or measures layout. */
    private fun displayGeometry(s: Gate1VisualCaptureSupport, host: Activity, ui: JSONObject,
        rawBounds: Rect, previous: LayoutObservation? = null): LayoutObservation {
        var result: Result<LayoutObservation>? = null
        s.ins.runOnMainSync { result = runCatching {
            val decor = host.window.decorView
            val roots = ArrayList<ViewRootForTest>()
            val queue = ArrayDeque<View>(); queue.add(decor)
            var visited = 0
            while (queue.isNotEmpty()) {
                check(++visited <= 1024) { "Unexpected View tree size" }
                val view = queue.removeFirst()
                if (view is ViewRootForTest) roots.add(view)
                if (view is ViewGroup) repeat(view.childCount) { queue.add(view.getChildAt(it)) }
            }
            val id = ui.getInt("imageSemanticsId")
            val windowId = ui.getInt("imageAccessibilityWindowId")
            val matches = roots.flatMap { root ->
                root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false).also {
                    check(it.size <= 1024) { "Unexpected semantics tree size" }
                }.filter { it.id == id && it.config.getOrNull(SemanticsProperties.ContentDescription) ==
                    listOf("現像結果（表示用）") }.map { root to it }
            }
            val (root, node) = matches.single()
            val view = root.view
            val layout = node.layoutInfo
            check(node.root === root && layout.semanticsId == id)
            check(view.isAttachedToWindow && view.isShown && decor.windowToken != null && view.windowToken == decor.windowToken)
            check(view.createAccessibilityNodeInfo().windowId == windowId)
            check(root.isLifecycleInResumedState && !root.hasPendingMeasureOrLayout)
            check(layout.isAttached && layout.isPlaced && !layout.isDeactivated)
            if (previous != null) check(root === previous.root && layout === previous.layout) { "CPU layout identity changed" }
            val coordinates = layout.coordinates
            val size = coordinates.size
            check(coordinates.isAttached && size == node.size && size.width > 0 && size.height > 0)
            val local = listOf(Offset.Zero, Offset(size.width.toFloat(), 0f),
                Offset(size.width.toFloat(), size.height.toFloat()), Offset(0f, size.height.toFloat()))
            val screen = local.map(coordinates::localToScreen)
            val window = local.map(coordinates::localToWindow)
            check(screen.all { it.x.isFinite() && it.y.isFinite() } && window.all { it.x.isFinite() && it.y.isFinite() })
            // Exact unit translation only: no zoom, pan mismatch, rotation or perspective compensation.
            check(screen == local.map { it + screen.first() } && window == local.map { it + window.first() })
            check(screen.first() == node.positionOnScreen && window.first() == node.positionInWindow)
            val decorScreen = IntArray(2).also(decor::getLocationOnScreen)
            val decorWindow = IntArray(2).also(decor::getLocationInWindow)
            val screenToWindow = Offset((decorWindow[0] - decorScreen[0]).toFloat(), (decorWindow[1] - decorScreen[1]).toFloat())
            check(window == screen.map { it + screenToWindow })
            // Fixed 1ce PhotoRollComparisonImage: Fit, default Center, no draw transform. CPU frame remains unobserved.
            // PainterModifier aligns rounded scaled size; BitmapPainter draws that same rounded destination size.
            val scale = ContentScale.Fit.computeScaleFactor(Size(1024f, 768f), Size(size.width.toFloat(), size.height.toFloat()))
            val scaled = Size(1024f * scale.scaleX, 768f * scale.scaleY)
            val drawSize = IntSize(scaled.width.fastRoundToInt(), scaled.height.fastRoundToInt())
            val align = Alignment.Center.align(drawSize, size, layout.layoutDirection)
            check(drawSize.width > 0 && drawSize.height > 0)
            val left = screen.first().x.toDouble() + align.x - rawBounds.left
            val top = screen.first().y.toDouble() + align.y - rawBounds.top
            val content = listOf(left, top, left + drawSize.width, top + drawSize.height)
            check(content[0] >= 0 && content[1] >= 0 && content[2] <= rawBounds.width() && content[3] <= rawBounds.height())
            val crop = Rect(ceil(content[0]).toInt(), ceil(content[1]).toInt(), floor(content[2]).toInt(), floor(content[3]).toInt())
            check(!crop.isEmpty && Rect(0, 0, rawBounds.width(), rawBounds.height()).contains(crop))
            check(!root.hasPendingMeasureOrLayout)
            fun points(values: List<Offset>) = JSONArray(values.map { JSONArray(listOf(it.x, it.y)) })
            val json = JSONObject().put("schema", "layout-crop-v1")
                .put("observationKind", "PUBLIC_COMPOSE_UNCLIPPED_LAYOUT_FIXED_FIT_CENTER")
                .put("coordinateSpace", "raw_original_pixels").put("semanticsNodeId", id)
                .put("accessibilityWindowId", windowId).put("rootIdentityToken", System.identityHashCode(root))
                .put("layoutIdentityToken", System.identityHashCode(layout)).put("sameActivityWindow", true)
                .put("pendingMeasureOrLayout", false).put("attachedPlacedActive", true).put("unitTranslationOnly", true)
                .put("layoutSize", JSONArray(listOf(size.width, size.height)))
                .put("layoutCornersScreen", points(screen)).put("layoutCornersWindow", points(window))
                .put("screenToWindowOffset", JSONArray(listOf(screenToWindow.x, screenToWindow.y)))
                .put("rawBoundsScreen", s.rect(rawBounds)).put("rawSize", JSONArray(listOf(rawBounds.width(), rawBounds.height())))
                .put("contentScale", "Fit").put("alignment", "Center")
                .put("sourceAspectBasis", "REGISTERED_INPUT_AND_FIXED_CPU_PATH_NOT_FRAME_OBSERVED")
                .put("sourceSizeBasis", JSONArray(listOf(1024, 768)))
                .put("scaledSizeFloat", JSONArray(listOf(scaled.width, scaled.height)))
                .put("drawSizeRounded", JSONArray(listOf(drawSize.width, drawSize.height)))
                .put("alignmentOffset", JSONArray(listOf(align.x, align.y)))
                .put("painterRounding", "Compose fastRoundToInt scaled size; Alignment.Center.align; BitmapPainter integer dstSize")
                .put("contentRectRawContinuous", JSONArray(content)).put("cropRectRaw", s.rect(crop))
                .put("cropRounding", "inward_ceil_start_floor_end").put("unoccluded", "UNPROVEN")
                .put("meaning", "layout-derived crop metadata only; raw PNG unchanged; not atomic frame or CPU bitmap observation")
            LayoutObservation(json, root, layout)
        } }
        return checkNotNull(result).getOrThrow()
    }

    /** One bounded scalar snapshot only. Never records arbitrary UI text or provider filenames. */
    private fun saveReadiness(s: Gate1VisualCaptureSupport, value: JSONObject) {
        if (s.report.optJSONObject("cpuReadiness")?.toString() != value.toString()) {
            s.report.put("cpuReadiness", value)
            s.save()
        }
    }

    /** Reuse the same refreshed tree. Do not fetch children or parents again while evaluating UI. */
    private fun descendantText(root: AccessibilityNodeInfo,
        children: Map<AccessibilityNodeInfo, List<AccessibilityNodeInfo>>,
        visible: Set<AccessibilityNodeInfo>): String {
        val text = ArrayList<String>(); val queue = ArrayDeque<AccessibilityNodeInfo>(); queue.add(root)
        var count = 0
        while (queue.isNotEmpty()) {
            check(++count < 256)
            val node = queue.removeFirst()
            if (node in visible) node.text?.toString()?.let(text::add)
            children[node]?.forEach(queue::addLast)
        }
        return text.joinToString(" ")
    }

    private fun fromJson(value: JSONArray) = Rect(value.getInt(0), value.getInt(1), value.getInt(2), value.getInt(3))
}