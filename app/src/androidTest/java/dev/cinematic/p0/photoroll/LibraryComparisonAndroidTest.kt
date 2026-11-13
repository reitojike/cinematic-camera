package dev.cinematic.p0.photoroll

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inspector.WindowInspector
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.*
import dev.cinematic.p0.rawcapture.*
import java.io.File
import org.junit.Test

/** Paired synthetic component evidence. Legacy drawing is pinned to main 53e68ffc.
 * No camera, SAF picker, import, save, notifications or real file permission is invoked. */
class LibraryComparisonAndroidTest {
    @get:org.junit.Rule val fixture = SyntheticFixtureRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val full = CameraRollAccess(true, true, true, false)
    private val partial = CameraRollAccess(true, false, false, true)
    private data class Scene(val name: String, val access: CameraRollAccess,
        val notice: String? = null, val tile: Boolean = false,
        val save: ForegroundSaveMessageState? = null, val import: DngImportScreenState? = null,
        val awaiting: Boolean = false)
    private fun output(name: String): File {
        val supplied = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        val root = supplied?.let(::File)
            ?: File(requireNotNull(instrumentation.targetContext.externalMediaDirs.firstOrNull()), "additional_test_output")
        return File(root, "ui-pr-e/comparison").apply { mkdirs() }.resolve(name)
    }
    private fun find(text: String): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.text?.toString() == text) return node
            for (i in 0 until node.childCount) visit(node.getChild(i))?.let { return it }
            return null
        }
        val root = instrumentation.uiAutomation.rootInActiveWindow
        return if (root?.packageName == instrumentation.targetContext.packageName) visit(root) else null
    }
    private fun await(text: String) {
        val end = SystemClock.uptimeMillis() + 8_000
        do {
            instrumentation.waitForIdleSync()
            if (find(text)?.isVisibleToUser == true) return
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < end)
        error("Missing synthetic comparison label: $text")
    }
    /** Public WindowInspector selects the focused app window, including Dialog.
     * This captures software View drawing, not GPU pixels or the system compositor.
     * https://developer.android.com/reference/android/view/inspector/WindowInspector */
    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        var image: Bitmap? = null
        instrumentation.runOnMainSync {
            val root = requireNotNull(WindowInspector.getGlobalWindowViews().lastOrNull { it.hasWindowFocus() })
            check(root.width > 0 && root.height > 0)
            image = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888).also { root.draw(Canvas(it)) }
        }
        requireNotNull(image).let { bitmap ->
            try { output(name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            finally { bitmap.recycle() }
        }
    }
    @Composable private fun Render(scene: Scene, before: Boolean) {
        CaptureTheme { Surface(Modifier.fillMaxSize()) {
            if (scene.import != null) {
                if (before) LegacyImportDialog(scene.import, awaitingSelection = scene.awaiting)
                else ImportDialogView(scene.import, null, scene.awaiting, false, true, {}, {}, {}, {}, {}, {})
            } else BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
                val nativeDensity = LocalDensity.current.density
                val density = minOf(maxWidth.value / 412, maxHeight.value / 924) * nativeDensity
                CompositionLocalProvider(LocalDensity provides Density(density, 1f)) {
                    Column(Modifier.size(412.dp, 924.dp)) {
                        val listing = PhotoRollListing(if (scene.tile) listOf(PhotoRollItem("content://synthetic/item", "SYNTHETIC.dng")) else emptyList())
                        if (before) {
                            scene.save?.let { LegacyForegroundSaveNotice(it.active, it.held, it.stopping, it.foreground, it.notificationsAllowed) }
                            TextButton(onClick = {}) { Text("外部DNGを取り込む") }
                            LegacyCameraRollBrowser(listing, scene.access, PhotoRollKindFilter.ALL,
                                null, false, false, scene.notice, PhotoRollPreviewMode.STAGE_CLEAR,
                                true, true, true, true, false, {}, {}, {}, {}, {}, {}, {}, {}, {}, Modifier.weight(1f), {})
                        } else CameraRollBrowser(listing, scene.access, PhotoRollKindFilter.ALL,
                            null, false, false, scene.notice, PhotoRollPreviewMode.STAGE_CLEAR,
                            true, true, true, true, false, {}, {}, {}, {}, {}, {}, {}, {}, {}, Modifier.weight(1f),
                            saveMessages = scene.save?.let { foregroundSaveMessages(it) } ?: emptyList(),
                            importEntry = { CaptureTextAction(MessageControl(MessageAction.CONFIRM, "取り込む"), {}) }, select = {})
                    }
                }
            }
        }
    }
    }
    @Test fun pairedMainAndPrDisplayStates() {
        check(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        val save = ForegroundSaveMessageState(true, true, false, false, true, false, true)
        val scenes = listOf(
            Scene("01-empty", full), Scene("02-partial", partial, tile = true),
            Scene("03-list-failure", partial, notice = "合成の項目を開けませんでした", tile = true),
            Scene("04-saving", full, save = save), Scene("05-stopping", full, save = save.copy(stopping = true)),
            Scene("06-held", full, save = save.copy(held = true)),
            Scene("07-partial-notifications", partial, tile = true, save = save.copy(active = false)),
            Scene("08-failure-saving", partial, notice = "合成の項目を開けませんでした", tile = true, save = save),
            Scene("09-import-explanation", full, import = DngImportScreenState()),
            Scene("10-import-awaiting", full, import = DngImportScreenState(), awaiting = true),
            Scene("11-import-progress", full, import = DngImportScreenState(busy = true, message = "取り込みを確認中…", messageKind = DngImportMessageKind.PROGRESS)),
            Scene("12-import-failure", full, import = DngImportScreenState(message = "取り込み先の状態を確認できません。追加コピーせず保留しています", messageKind = DngImportMessageKind.FAILURE)),
            Scene("13-import-complete", full, import = DngImportScreenState(message = "取り込みが完了しました", messageKind = DngImportMessageKind.ACCEPTED)),
            Scene("14-import-grant", full, import = DngImportScreenState(items = listOf(DngImportItem("synthetic", true, false, true)))),
        )
        val requested = InstrumentationRegistry.getArguments().getString("comparisonScenes")?.split(",")?.toSet()
        val selected = scenes.filter { requested == null || it.name in requested }
        check(selected.isNotEmpty())
        val current = mutableStateOf(selected.first())
        val before = mutableStateOf(true)
        fixture.content = { key(current.value.name, before.value) { Render(current.value, before.value) } }
        ActivityScenario.launch(PhotoRollActivity::class.java).use { scenario ->
            for (scene in selected) for (old in listOf(true, false)) {
                scenario.onActivity { current.value = scene; before.value = old }
                await(if (scene.import != null) {
                    if (old) "64MiB以下のDNGを共有フォルダへ変更せずコピーします。完了するまでSDを抜かないでください。選択元は変更しません。"
                    else "DNGを共有フォルダへコピーしてから開きます。"
                } else if (old) "外部DNGを取り込む" else "取り込む")
                SystemClock.sleep(150)
                capture("${scene.name}-${if (old) "before" else "after"}.png")
            }
        }
    }
}
