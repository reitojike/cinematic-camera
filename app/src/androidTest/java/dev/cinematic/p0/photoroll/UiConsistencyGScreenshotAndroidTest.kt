package dev.cinematic.p0.photoroll

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.*
import dev.cinematic.p0.jpegrender.original.Original24PreflightGeometry
import dev.cinematic.p0.photoedit.PhotoAdjustmentPolicy
import dev.cinematic.p0.rawcapture.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Test

/** PR-G before/after layout evidence. Synthetic emulator only, inert product callbacks.
 * Capture uses real shared chrome in a labelled synthetic scaffold, not CameraX.
 * Develop/roll use real passive views; initial-guide is the real caption component only.
 * Software View-tree PNGs are not GPU/handoff, saved-output or manual-regression evidence.
 * Baseline transplant changes only initialGuideCaption(frame) to the preceding caption expression.
 */
class UiConsistencyGScreenshotAndroidTest {
    @get:org.junit.Rule val fixture = SyntheticFixtureRule()
    private data class Layout(val name: String, val height: Int, val font: Float)
    private data class Scene(val name: String, val state: DevelopMessageState? = null,
        val saveEnabled: Boolean = false, val parameter: String? = null,
        val guide: InitialPreviewKind? = null)
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val args get() = InstrumentationRegistry.getArguments()
    private val viewport = AtomicReference<Rect>()
    private val density = AtomicReference(1f)
    private val ready = DevelopMessageState(PhotoRollPhase.DISPLAYED, PhotoRollSavePhase.READY)

    @Test fun captureSpecifiedStatesAndSaveLayouts() {
        check(args.getString("isolated") == "true")
        check(Build.HARDWARE in listOf("ranchu", "goldfish") || Build.FINGERPRINT.contains("generic")) {
            "Synthetic emulator only; physical-device operation prohibited"
        }
        val revision = args.getString("uiGRevision") ?: "after"
        check(revision in listOf("before", "after"))
        val output = File(requireNotNull(args.getString("additionalTestOutputDir")), "ui-pr-g/$revision")
        check(output.isDirectory || output.mkdirs())
        val normal = Layout("normal", 760, 1f)
        val layouts = listOf(normal, Layout("short-500dp", 500, 1f),
            Layout("font-1.8", 760, 1.8f), Layout("short-500dp-font-1.8", 500, 1.8f))
        val states = listOf(
            Scene("develop-ready", ready, true),
            Scene("develop-edit-strength", ready.copy(editing = true)),
            Scene("develop-settings-saving", ready.copy(savingRecipe = true)),
            Scene("develop-preparing", DevelopMessageState(PhotoRollPhase.CHECKING)),
            Scene("develop-hold", ready.copy(savePhase = PhotoRollSavePhase.HOLD)),
            Scene("develop-saving", ready.copy(savePhase = PhotoRollSavePhase.SAVING, saving = true, exportPaused = true)),
            Scene("develop-rechecking", ready.copy(savePhase = PhotoRollSavePhase.HOLD, rechecking = true)),
            Scene("develop-published", ready.copy(savePhase = PhotoRollSavePhase.PUBLISHED), true),
            Scene("develop-cancelled", ready.copy(savePhase = PhotoRollSavePhase.CANCELLED), true),
            Scene("develop-failed", ready.copy(savePhase = PhotoRollSavePhase.GPU_FAILED), true),
            Scene("develop-unconfirmed", ready.copy(savePhase = PhotoRollSavePhase.UNCONFIRMED), true),
            Scene("develop-long-name", ready, true),
            Scene("develop-edit-temperature", ready.copy(editing = true), parameter = "色温度"),
            Scene("develop-edit-brightness", ready.copy(editing = true), parameter = "明るさ"))
        val extras = listOf(Scene("capture-rec"), Scene("capture-failure"), Scene("capture-sending"),
            Scene("capture-permission"),
            Scene("guide-saved", guide = InitialPreviewKind.SAVED_JPEG),
            Scene("guide-raw", guide = InitialPreviewKind.HALF_GUIDE),
            Scene("guide-embedded", guide = InitialPreviewKind.EMBEDDED_JPEG), Scene("roll-list"))
        val selected = args.getString("uiGCases")?.split(",")?.toSet()
        val selectedLayouts = args.getString("uiGLayouts")?.split(",")?.toSet()
        val cases = layouts.filter { selectedLayouts == null || it.name in selectedLayouts }.flatMap { layout ->
            (states + if (layout == normal) extras else emptyList()).filter { selected == null || it.name in selected }
                .map { layout to it }
        }
        check(cases.isNotEmpty())
        val manualHold = args.getString("uiGManualHoldSeconds")?.toInt() ?: 0
        require(manualHold in 0..180 && (manualHold == 0 || cases.size == 1))
        val panelHeights = mutableMapOf<String, Float>()
        val image = syntheticImage()
        val source = SharedOriginalReference("content://fixture/ui-pr-g", OriginalFingerprint(100, "a".repeat(64)), OriginalGrant.USER_SELECTED)
        val bound = requireNotNull(TrialStrengthEditor.empty(PhotoAdjustmentPolicy.defaults).bind(source))
        val editor = bound.shown(bound.ticket, bound.requestedValues)
        val identity = InitialPreviewIdentity(source, Original24PreflightGeometry(600, 800, 600, 800, 1, 600, 800), "synthetic")
        try {
            for ((layout, scene) in cases) {
                val name = "${layout.name}-${scene.name}"
                scene.guide?.let { kind ->
                    val frame = InitialPreviewFrame(image, kind, identity)
                    val caption = initialGuideCaption(frame)
                    assertEquals(frame.label, caption.body)
                    assertEquals(if (revision == "after") CaptureChromeTone.WARNING else CaptureChromeTone.NORMAL, caption.tone)
                    val expected = when {
                        kind.edited -> "保存済みJPEGの案内表示"
                        kind == InitialPreviewKind.HALF_GUIDE -> "RAW基準の案内画像"
                        else -> "撮影時の案内画像"
                    } + (if (revision == "after") " · " else "・") +
                        (if (kind.edited) "プレビュー準備中" else "調整は未反映")
                    assertEquals(expected, caption.body)
                }
                fixture.content = {
                    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().background(CaptureDesign.Ink)) {
                        // Preserve the exact logical viewport even on a smaller emulator surface.
                        val nativeDensity = LocalDensity.current.density
                        val scale = minOf(maxWidth.value / 360f, maxHeight.value / layout.height) * nativeDensity
                        CompositionLocalProvider(LocalDensity provides Density(scale, layout.font)) {
                            CaptureTheme {
                                Box(Modifier.size(360.dp, layout.height.dp).onGloballyPositioned {
                                    val r = it.boundsInWindow()
                                    viewport.set(Rect(r.left.roundToInt(), r.top.roundToInt(), r.right.roundToInt(), r.bottom.roundToInt()))
                                    density.set(scale)
                                }.background(CaptureDesign.Ink)) {
                                    when {
                                        scene.name.startsWith("capture-") -> capture(scene.name, image)
                                        scene.name == "roll-list" -> roll()
                                        scene.guide != null -> {
                                            val frame = InitialPreviewFrame(image, scene.guide, identity)
                                            Column(Modifier.fillMaxSize()) {
                                                DevelopCaption(initialGuideCaption(frame), Modifier.fillMaxWidth())
                                                Image(image.asImageBitmap(), frame.label, Modifier.fillMaxWidth().weight(1f), contentScale = ContentScale.Fit)
                                                Text("合成画像 · 案内キャプション部品のみ", style = CaptureDesign.Label, color = CaptureDesign.Muted)
                                            }
                                        }
                                        else -> develop(scene, editor, image)
                                    }
                                }
                            }
                        }
                    }
                }
                ActivityScenario.launch(PhotoRollActivity::class.java).use {
                    settle()
                    if (scene.parameter != null) {
                        val button = awaitParameter(scene.parameter)
                        assertTrue("Select parameter in the synthetic view", button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                        settle()
                    }
                    val bounds = requireNotNull(viewport.get())
                    assertEquals("Exact logical viewport height", layout.height.toFloat(), bounds.height() / density.get(), 1f)
                    if (scene.state?.editing == true) {
                        reveal(awaitNode("現像調整パネル"))
                        val panelRect = Rect().also(awaitNode("現像調整パネル")::getBoundsInScreen)
                        panelHeights[name] = panelRect.height() / density.get()
                        if (scene.parameter != null && revision == "after") {
                            assertFalse("No duplicate temperature explanation", nodes().any { it.text?.toString() == "相対調整です（K値ではありません）" })
                            panelHeights["${layout.name}-develop-edit-strength"]?.let { assertEquals("Parameter switching preserves panel height", it, panelRect.height() / density.get(), 1f) }
                        }
                    }
                    writeImage(output, "$name-top")
                    val evidence = StringBuilder("synthetic; revision=$revision; viewport=360x${layout.height}dp; fontScale=${layout.font}; density=${density.get()}\n")
                    if (scene.state != null) {
                        val presentation = developSavePresentation(scene.state)
                        val saveNode = awaitNode(presentation.description)
                        reveal(saveNode)
                        val visible = actionNode(awaitNode(presentation.description))
                        writeTree(output, name)
                        val rect = Rect().also(visible::getBoundsInScreen)
                        assertTrue("Save action visible: $name", visible.isVisibleToUser)
                        assertTrue("Complete save action inside captured viewport: $name", bounds.contains(rect))
                        assertEquals("Existing save eligibility: $name", scene.saveEnabled, visible.isEnabled)
                        assertEquals("Save state remains available to accessibility", presentation.label, visible.stateDescription?.toString())
                        assertTrue("Save hit width >=76dp: $name", rect.width() / density.get() >= 75.99f)
                        assertTrue("Save hit height >=76dp: $name", rect.height() / density.get() >= 75.99f)
                        assertTrue("Save action stays within 96dp row: $name", rect.height() / density.get() <= 96.01f)
                        // Bounds of every visible save child must stay inside the merged hit area.
                        descendants(visible).filter { it.isVisibleToUser }.forEach { child ->
                            val childBounds = Rect().also(child::getBoundsInScreen)
                            assertTrue("Save child is not clipped by action: $name", rect.contains(childBounds) || childBounds.isEmpty)
                        }
                        evidence.append("saveEnabled=${visible.isEnabled}; state=${presentation.label}; hit=${rect.width()/density.get()}x${rect.height()/density.get()}dp\n")
                        if (revision == "after") {
                            val action = when {
                                scene.state.rechecking -> "確認中"
                                scene.state.saving -> "保存中"
                                scene.state.editing || scene.state.savingRecipe -> "保存"
                                scene.state.savePhase == PhotoRollSavePhase.PUBLISHED -> "開く"
                                scene.state.savePhase in setOf(PhotoRollSavePhase.CANCELLED, PhotoRollSavePhase.GPU_FAILED) -> "再試行"
                                scene.state.savePhase == PhotoRollSavePhase.UNCONFIRMED -> "確認"
                                else -> "保存"
                            }
                            assertTrue("Visible action label: $name", descendants(visible).any { it.text?.toString()?.contains(action) == true } || visible.text?.toString()?.contains(action) == true)
                        }
                        writeImage(output, "$name-footer")
                    }
                    File(output, "$name-metrics.txt").writeText(evidence.toString())
                    // Optional, bounded manual-review window. No injected actions or real owner callbacks.
                    if (manualHold > 0) {
                        File(output, "manual-ready.txt").writeText(name)
                        repeat(manualHold) {
                            writeTree(output, "manual-latest", activeWindow = true)
                            if (fixture.fixtureRoot() != null) writeImage(output, "manual-latest-host")
                            SystemClock.sleep(1000)
                        }
                    }
                }
            }
        } finally { fixture.content = null; image.recycle() }
    }

    @Composable private fun develop(scene: Scene, editor: TrialStrengthEditor, image: Bitmap) {
        val state = requireNotNull(scene.state)
        val current = if (state.editing) editor.begin(.01, true) else editor
        PhotoRollDevelopView(if (scene.name == "develop-long-name") "SYNTHETIC_とても長いファイル名_ABCDEFGHIJKLMNOPQRSTUVWXYZ_1234567890.dng" else "SYNTHETIC.dng",
            PhotoRollPreviewMode.MANUAL_TRIAL, current, { current }, state.phase, null, null,
            image.takeUnless { state.exportPaused }, image.takeUnless { state.exportPaused }, "基準表示（撮影時WB・調整なし）", !state.savingRecipe,
            state.savePhase, state.saving || state.savingRecipe, scene.saveEnabled, !state.saving && !state.savingRecipe,
            {}, {}, {}, {}, { _, _, _ -> }, {}, {}, {}, {},
            modifier = Modifier.fillMaxSize(), gpuReady = !state.exportPaused,
            productMessages = developMessages(state), productSave = developSavePresentation(state),
            info = { Text("合成画像による表示確認です。", style = CaptureDesign.Body) })
    }

    @Composable private fun capture(name: String, image: Bitmap) {
        val input = CaptureChromeInput(if (name == "capture-rec") CaptureMode.CINE else CaptureMode.STILL,
            bound = true, manualExposure = false, photoFormat = "JPEG", captureReady = true, thermalSeverity = 0,
            exposureModePhase = if (name == "capture-failure") ControlPhase.UNKNOWN else ControlPhase.ACCEPTED,
            recordingStage = if (name == "capture-rec") RecordingStage.RECORDING else RecordingStage.IDLE,
            recordingDurationMs = 65000, photoStage = if (name == "capture-failure") PhotoStage.FAILED else PhotoStage.IDLE)
        val messages = when (name) {
            "capture-permission" -> captureMessages(cameraPermissionMissing = true)
            "capture-sending" -> captureMessages(phase = captureChangingMessage(listOf("ISO", "WB")))
            "capture-failure" -> captureMessages(captureFailed = true)
            else -> ScreenMessages()
        }
        Column(Modifier.fillMaxSize()) {
            CaptureStatusBar(input, true, false, {})
            Box(Modifier.fillMaxWidth().weight(1f)) {
                Image(image.asImageBitmap(), "合成カメラ画像", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                CaptureMessageOverlay(messages, false, false, {})
            }
            Text("合成撮影画面 · CameraXなし", Modifier.padding(8.dp), style = CaptureDesign.Label, color = CaptureDesign.Muted)
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("ISO", "SS", "EV", "FOCUS", "WB", "LOOK").forEach { Text(it, style = CaptureDesign.Label, color = CaptureDesign.Muted) }
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CaptureShutter(input, {}) }
        }
    }

    @Composable private fun roll() {
        CameraRollBrowser(PhotoRollListing((1..6).map { PhotoRollItem("content://synthetic/ui-g/$it", "SYNTHETIC_$it.dng") }), CameraRollAccess(true, true, true, false), PhotoRollKindFilter.ALL,
            null, false, false, null, PhotoRollPreviewMode.MANUAL_TRIAL, true, true, true, true,
            false, {}, {}, {}, {}, {}, {}, {}, {}, {}, modifier = Modifier.fillMaxSize(), select = {})
    }

    private fun descendants(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = buildList {
        for (i in 0 until node.childCount) node.getChild(i)?.let { add(it); addAll(descendants(it)) }
    }
    private fun nodes(): List<AccessibilityNodeInfo> {
        fixture.automation.clearCache()
        return fixture.fixtureRoot()?.let { listOf(it) + descendants(it) } ?: emptyList()
    }
    // Android exposes the description as a child; state/enabled/hit area belong to its action parent.
    // Observed identically on the baseline and PR-G; retain strict assertions on that owner.
    private fun actionNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var current = node
        while (!current.isClickable && current.stateDescription == null) current = current.parent ?: return current
        return current
    }
    private fun awaitParameter(label: String): AccessibilityNodeInfo {
        repeat(15) {
            settle()
            val button = nodes().firstOrNull { it.isClickable && it.isVisibleToUser &&
                (it.text?.toString()?.contains(label) == true || descendants(it).any { n -> n.text?.toString() == label }) }
            if (button != null) return button
            nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        }
        error("Missing visible parameter: $label")
    }
    private fun awaitNode(description: String): AccessibilityNodeInfo {
        repeat(30) {
            settle()
            nodes().firstOrNull { it.contentDescription?.toString() == description }?.let { return it }
            nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        }
        error("Missing save description: $description")
    }
    private fun reveal(node: AccessibilityNodeInfo) {
        node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
        settle()
        repeat(8) {
            val match = nodes().firstOrNull { it.contentDescription == node.contentDescription && it.isVisibleToUser }
            if (match != null) {
                val r = Rect().also(match::getBoundsInScreen)
                // Keep a margin for the complete action rather than its first visible pixel.
                val root = Rect().also(requireNotNull(fixture.fixtureRoot())::getBoundsInScreen)
                if (root.contains(r) && r.height() / density.get() >= 75.99f) return
            }
            nodes().firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            settle()
        }
    }
    private fun settle() {
        instrumentation.waitForIdleSync()
        val latch = CountDownLatch(1)
        instrumentation.runOnMainSync {
            val frames = android.view.Choreographer.getInstance()
            frames.postFrameCallback { frames.postFrameCallback { latch.countDown() } }
        }
        check(latch.await(5, TimeUnit.SECONDS)) { "Fixture layout timeout" }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(80)
    }
    private fun writeTree(output: File, name: String, activeWindow: Boolean = false) {
        fixture.automation.clearCache()
        val tree = if (activeWindow) fixture.automation.rootInActiveWindow?.takeIf {
            it.packageName == instrumentation.targetContext.packageName
        }?.let { listOf(it) + descendants(it) } ?: emptyList() else nodes()
        File(output, "$name-tree.txt").writeText("uptimeMs=${SystemClock.uptimeMillis()}\n" + tree.joinToString("\n") { node ->
            val r = Rect().also(node::getBoundsInScreen)
            "class=${node.className}; text=${node.text}; description=${node.contentDescription}; state=${node.stateDescription}; clickable=${node.isClickable}; enabled=${node.isEnabled}; visible=${node.isVisibleToUser}; bounds=$r; parentState=${node.parent?.stateDescription}"
        })
    }
    private fun writeImage(output: File, name: String) {
        val full = fixture.captureSoftware()
        try {
            val r = requireNotNull(viewport.get())
            check(r.left >= 0 && r.top >= 0 && r.right <= full.width && r.bottom <= full.height)
            val crop = Bitmap.createBitmap(full, r.left, r.top, r.width(), r.height())
            try { File(output, "$name.png").outputStream().use { check(crop.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
            finally { if (crop !== full) crop.recycle() }
        } finally { full.recycle() }
    }
    private fun syntheticImage() = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888).apply {
        val canvas = Canvas(this)
        canvas.drawColor(android.graphics.Color.rgb(40, 65, 85))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.rgb(180, 170, 140) }
        canvas.drawRect(80f, 200f, 520f, 650f, paint)
        paint.color = android.graphics.Color.rgb(225, 210, 160); canvas.drawCircle(410f, 200f, 65f, paint)
        paint.color = android.graphics.Color.WHITE; paint.textSize = 26f
        canvas.drawText("SYNTHETIC / NO PHOTO", 25f, 55f, paint)
    }
}