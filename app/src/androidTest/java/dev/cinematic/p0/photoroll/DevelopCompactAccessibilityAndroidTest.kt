package dev.cinematic.p0.photoroll

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.CaptureTheme
import dev.cinematic.p0.photoedit.PhotoAdjustmentPolicy
import dev.cinematic.p0.rawcapture.OriginalFingerprint
import dev.cinematic.p0.rawcapture.OriginalGrant
import dev.cinematic.p0.rawcapture.SharedOriginalReference
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** #451: real editor composable with synthetic images and callbacks, never an export owner. */
class DevelopCompactAccessibilityAndroidTest {
    @get:Rule val fixture = SyntheticFixtureRule()

    @Test fun shortViewportAtSystemDoubleFontReachesSaveAndComparison() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("developCompact451") == "true")
        check(arguments.getString("isolated") == "true")
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Use the Android Gradle Plugin's connected-test output channel, not a guessed shell path.
        val output = File(requireNotNull(arguments.getString("additionalTestOutputDir")) {
            "Run through connectedDebugAndroidTest with its managed additional-test-output channel"
        }, "develop-451")
        check(output.isDirectory || output.mkdirs())
        val saved = AtomicInteger()
        val configuration = AtomicReference<Triple<Float, Float, Float>>()
        val shown = requireNotNull(TrialStrengthEditor.empty(PhotoAdjustmentPolicy.defaults).bind(
            SharedOriginalReference("content://fixture/develop-451",
                OriginalFingerprint(100, "a".repeat(64)), OriginalGrant.USER_SELECTED)))
        val editor = shown.shown(shown.ticket, shown.requestedValues)
        fun runSettingsCommand(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)
        ).bufferedReader().use { it.readText().trim() }
        fun readFontScale(): String? = runSettingsCommand("settings get system font_scale")
            .takeUnless { it.isEmpty() || it == "null" }
            ?.also { check(it.toFloatOrNull() != null) { "Could not read system font_scale: $it" } }
        val originalFontScale = readFontScale()
        val current = Bitmap.createBitmap(480, 640, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val before = Bitmap.createBitmap(480, 640, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
        val state = DevelopMessageState(PhotoRollPhase.DISPLAYED, PhotoRollSavePhase.READY)
        fixture.content = {
            CaptureTheme {
                Surface(Modifier.fillMaxSize()) {
                    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
                        val available = maxWidth to maxHeight
                        check(available.first >= 360.dp && available.second >= 480.dp)
                        Box(Modifier.requiredSize(360.dp, 480.dp)) {
                            BoxWithConstraints(Modifier.fillMaxSize()) {
                                configuration.set(Triple(maxWidth.value, maxHeight.value, LocalDensity.current.fontScale))
                                PhotoRollDevelopView(
                                    name = "SYNTHETIC.dng", mode = PhotoRollPreviewMode.MANUAL_TRIAL,
                                    state = editor, readState = { editor }, phase = PhotoRollPhase.DISPLAYED,
                                    stage = null, message = null, preview = current, comparison = before,
                                    comparisonLabel = "合成比較", foreground = true,
                                    savePhase = PhotoRollSavePhase.READY, savePending = false,
                                    saveEnabled = true, beginEnabled = true, save = { saved.incrementAndGet() },
                                    begin = {}, back = {}, retry = {}, change = { _, _, _ -> },
                                    reset = {}, apply = {}, cancel = {}, close = {},
                                    modifier = Modifier.fillMaxSize(),
                                    productMessages = developMessages(state, emptyList()),
                                    productSave = developSavePresentation(state),
                                    info = {},
                                )
                            }
                        }
                    }
                }
            }
        }
        fun settle() {
            instrumentation.waitForIdleSync()
            fixture.automation.clearCache()
        }
        fun bounds(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)
        fun matching(label: String): List<AccessibilityNodeInfo> {
            val found = mutableListOf<AccessibilityNodeInfo>()
            fun visit(node: AccessibilityNodeInfo?) {
                if (node == null) return
                if (node.isVisibleToUser && !bounds(node).isEmpty &&
                    (node.text?.toString() == label || node.contentDescription?.toString() == label)) found += node
                for (i in 0 until node.childCount) visit(node.getChild(i))
            }
            visit(fixture.fixtureRoot())
            return found
        }
        fun awaitMatch(label: String, predicate: (AccessibilityNodeInfo) -> Boolean = { true }): AccessibilityNodeInfo {
            repeat(60) {
                settle()
                val matches = matching(label).filter(predicate)
                if (matches.size == 1 && matches.single().refresh()) return matches.single()
                SystemClock.sleep(100)
            }
            error("No unique visible fixture node: $label")
        }
        // ボタンにマージされる前の Text ノードから、現在の制約での文字レイアウトを取得する。
        fun awaitSwitchTextLayout(scenario: ActivityScenario<PhotoRollActivity>, label: String): TextLayoutResult {
            var matchCount = 0
            repeat(60) {
                settle()
                var textLayout: TextLayoutResult? = null
                scenario.onActivity { activity ->
                    matchCount = 0
                    fun visitSemantics(node: SemanticsNode) {
                        val hasLabel = node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true
                        if (hasLabel &&
                            generateSequence(node.parent) { it.parent }.take(4)
                                .any { SemanticsProperties.Selected in it.config }
                        ) {
                            matchCount++
                            val action = node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action
                            val results = mutableListOf<TextLayoutResult>()
                            if (action?.invoke(results) == true) textLayout = results.singleOrNull()
                        }
                        node.children.forEach(::visitSemantics)
                    }
                    fun visitView(view: View) {
                        if (view is ViewRootForTest) {
                            visitSemantics(view.semanticsOwner.unmergedRootSemanticsNode)
                        }
                        if (view is ViewGroup) {
                            for (index in 0 until view.childCount) visitView(view.getChildAt(index))
                        }
                    }
                    visitView(activity.window.decorView)
                }
                if (matchCount == 1) textLayout?.let { return it }
                SystemClock.sleep(100)
            }
            error("No unique switch Text layout: $label (last match count=$matchCount)")
        }
        fun clickNode(start: AccessibilityNodeInfo) {
            var node = start
            while (!node.isClickable) node = requireNotNull(node.parent)
            assertTrue(node.isVisibleToUser && node.isEnabled)
            assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            settle()
        }
        // Samples only the visible image viewport, mapped from screen to the captured decor.
        // Nine rows by nine columns; at least 12 interior hits and at most 2 opposite hits.
        fun verifyPreviewColor(scenario: ActivityScenario<PhotoRollActivity>, expected: Int, other: Int) {
            val viewport = bounds(awaitMatch("現像画像の表示領域"))
            val offset = IntArray(2)
            scenario.onActivity { it.window.decorView.getLocationOnScreen(offset) }
            viewport.offset(-offset[0], -offset[1])
            val shot = fixture.captureSoftware()
            try {
                check(viewport.intersect(0, 0, shot.width, shot.height))
                check(viewport.width() >= 36 && viewport.height() >= 36)
                var expectedHits = 0
                var otherHits = 0
                for (y in 1..9) for (x in 1..9) {
                    val color = shot.getPixel(viewport.left + x * viewport.width() / 10,
                        viewport.top + y * viewport.height() / 10)
                    if (color == expected) expectedHits++
                    if (color == other) otherHits++
                }
                assertTrue("Synthetic preview colors expected=$expectedHits opposite=$otherHits of 81",
                    expectedHits >= 12 && otherHits <= 2)
            } finally { shot.recycle() }
        }
        fun capturePng(name: String) {
            val shot = fixture.captureSoftware()
            try {
                File(output, "$name.png").outputStream().use {
                    check(shot.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally { shot.recycle() }
        }
        fun retainFailureImage(failure: Throwable) {
            // Still inside ActivityScenario.use; do not lose this frame if a selector disappears.
            // Capture only the known fixture's View tree, never the desktop or system display.
            if (configuration.get() != Triple(360f, 480f, 2f)) return
            try { capturePng("360x480-font2-failure") }
            catch (captureFailure: Exception) { failure.addSuppressed(captureFailure) }
            catch (captureFailure: AssertionError) { failure.addSuppressed(captureFailure) }
        }
        // システム文字サイズ2倍が前提の試験なので、端末の状態に依存しないよう起動前に設定し、終了後に元へ戻す。
        var testFailure: Throwable? = null
        try {
            runSettingsCommand("settings put system font_scale 2.0")
            check(readFontScale()?.toFloatOrNull() == 2f) { "Could not set system font_scale to 2.0" }
            ActivityScenario.launch(PhotoRollActivity::class.java).use { scenario ->
                try {
                    val fontScaleDeadline = SystemClock.uptimeMillis() + 10_000L
                    var fontScale = 0f
                    do {
                        scenario.onActivity { fontScale = it.resources.configuration.fontScale }
                        if (fontScale == 2f || SystemClock.uptimeMillis() >= fontScaleDeadline) break
                        SystemClock.sleep(100)
                    } while (true)
                    assertEquals(2f, fontScale, 0.001f)
                    val viewport = awaitMatch("現像画像の表示領域")
                    assertEquals(Triple(360f, 480f, 2f), configuration.get())
                    capturePng("360x480-font2-initial")
                    // Defer comparison color assertions until after independently exercising save.
                    var initialBlueFailure: Throwable? = null
                    try { verifyPreviewColor(scenario, Color.BLUE, Color.GRAY) }
                    catch (failure: AssertionError) { initialBlueFailure = failure }
                    catch (failure: IllegalStateException) { initialBlueFailure = failure }
                    var outer = requireNotNull(viewport.parent)
                    while (!outer.isScrollable) outer = requireNotNull(outer.parent)
                    val reachedStrength = { matching("強さ").isNotEmpty() }
                    var strengthSeen = reachedStrength()
                    for (attempt in 0 until 12) {
                        if (matching("JPEGを保存").isNotEmpty() && matching("表示").isNotEmpty()) break
                        // Refresh each scroll step; reacquire from the live viewport when it remains visible.
                        matching("現像画像の表示領域").singleOrNull()?.parent?.let { nearest ->
                            var parent = nearest
                            while (!parent.isScrollable) parent = requireNotNull(parent.parent)
                            outer = parent
                        }
                        check(outer.refresh() && outer.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
                        SystemClock.sleep(250)
                        settle()
                        strengthSeen = strengthSeen || reachedStrength()
                    }
                    capturePng("360x480-font2-after-scroll")
                    assertTrue("Strength row is reachable during real outer scrolling", strengthSeen)
                    clickNode(awaitMatch("JPEGを保存"))
                    assertEquals(1, saved.get())
                    val display = awaitMatch("表示")
                    val displayY = bounds(display).centerY()
                    val compare = awaitMatch("比較") { bounds(it).top <= displayY && displayY < bounds(it).bottom }
                    val switchLabelLayouts = listOf(
                        "表示" to awaitSwitchTextLayout(scenario, "表示"),
                        "比較" to awaitSwitchTextLayout(scenario, "比較"),
                    )
                    clickNode(compare)
                    // Recover the same top position even if the viewport scrolled out when reaching Save.
                    // Scroll to the start, not just until a partial preview becomes visible.
                    for (attempt in 0 until 12) {
                        check(outer.refresh())
                        if (!outer.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) break
                        SystemClock.sleep(250)
                        settle()
                    }
                    initialBlueFailure?.let { throw it }
                    val deadline = SystemClock.uptimeMillis() + 3_000L
                    while (true) {
                        try {
                            verifyPreviewColor(scenario, Color.GRAY, Color.BLUE)
                            break
                        } catch (failure: AssertionError) {
                            if (SystemClock.uptimeMillis() >= deadline) throw failure
                            SystemClock.sleep(100)
                        }
                    }
                    capturePng("360x480-font2-comparison")
                    // Update visualReview only after visually reviewing the PNGs.
                    File(output, "360x480-font2-checks.txt").writeText(
                        "viewportDp=360x480\nfontScale=2.0\nouterScrollReachedSave=true\ncomparisonPixels=blue-to-gray\nsaveCallbacks=1\nvisualReview=pending\n")
                    data class SwitchLabelCheck(
                        val label: String,
                        val layout: TextLayoutResult,
                        val oneLineWidth: Float,
                        val availableWidth: Int,
                    )

                    val switchLabelChecks = switchLabelLayouts.map { (label, layout) ->
                        val oneLineWidth = layout.multiParagraph.maxIntrinsicWidth
                        val availableWidth = minOf(layout.size.width, layout.layoutInput.constraints.maxWidth)
                        SwitchLabelCheck(label, layout, oneLineWidth, availableWidth)
                    }
                    // hasVisualOverflow だけが落ちた原因を次回実行で数値から確定するために書き出す。
                    File(output, "360x480-font2-checks.txt").appendText(buildString {
                        for ((label, layout, oneLineWidth, availableWidth) in switchLabelChecks) {
                            val paragraph = layout.multiParagraph
                            val input = layout.layoutInput
                            val constraints = input.constraints
                            val lastCharIndex = input.text.length - 1
                            val hasCharacterBounds = lastCharIndex >= 0 && layout.lineCount > 0
                            val firstChar = if (hasCharacterBounds) layout.getBoundingBox(0) else null
                            val lastChar = if (hasCharacterBounds) layout.getBoundingBox(lastCharIndex) else null
                            val lineWidth = if (layout.lineCount > 0) {
                                layout.getLineRight(0) - layout.getLineLeft(0)
                            } else {
                                -1f
                            }
                            appendLine("$label.lineCount=${layout.lineCount}")
                            appendLine("$label.sizeWidthPx=${layout.size.width}")
                            appendLine("$label.sizeHeightPx=${layout.size.height}")
                            appendLine("$label.multiParagraphWidthPx=${paragraph.width}")
                            appendLine("$label.multiParagraphHeightPx=${paragraph.height}")
                            appendLine("$label.maxIntrinsicWidthPx=${paragraph.maxIntrinsicWidth}")
                            appendLine("$label.minIntrinsicWidthPx=${paragraph.minIntrinsicWidth}")
                            appendLine("$label.constraintsMinWidthPx=${constraints.minWidth}")
                            appendLine("$label.constraintsMaxWidthPx=${constraints.maxWidth}")
                            appendLine("$label.constraintsMinHeightPx=${constraints.minHeight}")
                            appendLine("$label.constraintsMaxHeightPx=${constraints.maxHeight}")
                            appendLine("$label.assertOneLineWidthPx=$oneLineWidth")
                            appendLine("$label.assertAvailableWidthPx=$availableWidth")
                            appendLine("$label.lineWidthPx=$lineWidth")
                            appendLine("$label.firstCharLeftPx=${firstChar?.left ?: -1f}")
                            appendLine("$label.firstCharRightPx=${firstChar?.right ?: -1f}")
                            appendLine("$label.lastCharLeftPx=${lastChar?.left ?: -1f}")
                            appendLine("$label.lastCharRightPx=${lastChar?.right ?: -1f}")
                            appendLine("$label.didOverflowWidth=${layout.didOverflowWidth}")
                            appendLine("$label.didOverflowHeight=${layout.didOverflowHeight}")
                            appendLine("$label.hasVisualOverflow=${layout.hasVisualOverflow}")
                            appendLine("$label.softWrap=${input.softWrap}")
                            appendLine("$label.maxLines=${input.maxLines}")
                            appendLine("$label.overflow=${input.overflow}")
                            appendLine("$label.density=${input.density.density}")
                            appendLine("$label.fontScale=${input.density.fontScale}")
                            appendLine("$label.styleFontSizeSp=${input.style.fontSize}")
                            appendLine("$label.styleLetterSpacingSp=${input.style.letterSpacing}")
                        }
                    })
                    // boundsInRoot は制約内のノード矩形なので、字形が横に切れても幅・高さの閾値を通り得る。
                    // 行数で折り返しを検出し、横切れは同じ段落の全文の1行幅と実際の配置幅を比較する。
                    // 段落自体が幅制約に収まり overflow が false の場合も、1行に必要な幅の不足を検出できる。
                    for ((label, layout, oneLineWidth, availableWidth) in switchLabelChecks) {
                        assertEquals("$label wraps: expected one line", 1, layout.lineCount)
                        assertTrue(
                            "$label horizontal clipping: needs ${oneLineWidth}px for one line, has ${availableWidth}px",
                            oneLineWidth <= availableWidth,
                        )
                        assertFalse(
                            "$label layout overflow: width=${layout.didOverflowWidth}, height=${layout.didOverflowHeight}",
                            layout.hasVisualOverflow,
                        )
                    }
                } catch (failure: AssertionError) {
                    retainFailureImage(failure)
                    throw failure
                } catch (failure: Exception) {
                    retainFailureImage(failure)
                    throw failure
                }
            }
        } catch (failure: Throwable) {
            testFailure = failure
            throw failure
        } finally {
            try {
                if (originalFontScale == null) {
                    runSettingsCommand("settings delete system font_scale")
                } else {
                    runSettingsCommand("settings put system font_scale $originalFontScale")
                }
                check(readFontScale() == originalFontScale) { "Could not restore system font_scale" }
            } catch (restoreFailure: Throwable) {
                val failure = testFailure ?: throw restoreFailure
                failure.addSuppressed(restoreFailure)
            } finally {
                current.recycle()
                before.recycle()
            }
        }
    }
}
