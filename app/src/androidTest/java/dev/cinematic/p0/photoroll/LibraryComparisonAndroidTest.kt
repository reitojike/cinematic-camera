package dev.cinematic.p0.photoroll

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.WindowManager
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
    /** Software reference reconstruction of the measured host/dialog position and background.
     * Not an OS-compositor screenshot or proof of actual window stacking. UiAutomation screenshots
     * are black on this ATD. Draw the real host decor and focused dialog at their measured screen
     * offsets, applying only the dialog's actual FLAG_DIM_BEHIND / dimAmount. No guessed centering.
     * The sidecar records both screen/window coordinates and LayoutParams for audit. */
    private fun capture(name: String, host: View, dialogExpected: Boolean) {
        instrumentation.waitForIdleSync()
        var image: Bitmap? = null
        var geometry = ""
        instrumentation.runOnMainSync {
            val root = requireNotNull(WindowInspector.getGlobalWindowViews().lastOrNull { it.hasWindowFocus() })
            check(root.context.packageName == instrumentation.targetContext.packageName) { "Focused window is not the target app" }
            check(root.width > 0 && root.height > 0)
            if (!dialogExpected) {
                // Preserve the original 01–08 whole-roll capture byte-for-byte in drawing method.
                image = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888).also { root.draw(Canvas(it)) }
            } else {
                check(root !== host) { "Expected focused dialog, got host decor" }
                check(host.width == 1080 && host.height == 2424) { "Unexpected ATD host size: ${host.width}x${host.height}" }
                val hostScreen = IntArray(2).also(host::getLocationOnScreen)
                val hostWindow = IntArray(2).also(host::getLocationInWindow)
                val dialogScreen = IntArray(2).also(root::getLocationOnScreen)
                val dialogWindow = IntArray(2).also(root::getLocationInWindow)
                val params = root.layoutParams as? WindowManager.LayoutParams
                    ?: error("Dialog WindowManager.LayoutParams unavailable")
                val dim = if (params.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0) params.dimAmount else 0f
                check(dim in 0f..1f)
                geometry = "software reference, not compositor evidence\n" +
                    "host=${host.width}x${host.height} screen=${hostScreen.toList()} window=${hostWindow.toList()}\n" +
                    "dialog=${root.width}x${root.height} screen=${dialogScreen.toList()} window=${dialogWindow.toList()}\n" +
                    "layout width=${params.width} height=${params.height} x=${params.x} y=${params.y} gravity=${params.gravity} flags=${params.flags} dimAmount=${params.dimAmount} appliedDim=$dim\n"
                android.util.Log.i("LibraryComparison", "$name\n$geometry")
                image = Bitmap.createBitmap(host.width, host.height, Bitmap.Config.ARGB_8888).also {
                    val canvas = Canvas(it)
                    host.draw(canvas)
                    canvas.drawColor(Color.argb((dim * 255).toInt(), 0, 0, 0))
                    val checkpoint = canvas.save()
                    canvas.translate((dialogScreen[0] - hostScreen[0]).toFloat(), (dialogScreen[1] - hostScreen[1]).toFloat())
                    root.draw(canvas)
                    canvas.restoreToCount(checkpoint)
                }
            }
        }
        requireNotNull(image).let { bitmap ->
            try { output(name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            finally { bitmap.recycle() }
        }
        if (dialogExpected) output(name.removeSuffix(".png") + "-geometry.txt").writeText(geometry)
    }
    @Composable private fun Render(scene: Scene, before: Boolean) {
        CaptureTheme { Surface(Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
                val nativeDensity = LocalDensity.current.density
                val density = minOf(maxWidth.value / 412, maxHeight.value / 924) * nativeDensity
                CompositionLocalProvider(LocalDensity provides Density(density, 1f)) {
                    Column(Modifier.size(412.dp, 924.dp)) {
                        val listing = PhotoRollListing(if (scene.tile) listOf(PhotoRollItem("content://synthetic/item", "SYNTHETIC.dng")) else emptyList())
                        // PhotoRollScreen predicates, with foreground=true, no picker/viewer/permission
                        // pending or loading. DngImportPanel reports busy || selected != null.
                        val importBusy = scene.import?.busy == true || scene.awaiting
                        val libraryEnabled = !importBusy
                        if (before) {
                            scene.save?.let { LegacyForegroundSaveNotice(it.active, it.held, it.stopping, it.foreground, it.notificationsAllowed) }
                            TextButton(onClick = {}, enabled = true) { Text(if (scene.import?.busy == true) "DNG取り込み中…" else "外部DNGを取り込む") }
                            LegacyCameraRollBrowser(listing, scene.access, PhotoRollKindFilter.ALL,
                                null, false, false, scene.notice, PhotoRollPreviewMode.STAGE_CLEAR,
                                libraryEnabled, libraryEnabled, libraryEnabled && scene.access.supported, libraryEnabled, false, {}, {}, {}, {}, {}, {}, {}, {}, {}, Modifier.weight(1f), {})
                        } else CameraRollBrowser(listing, scene.access, PhotoRollKindFilter.ALL,
                            null, false, false, scene.notice, PhotoRollPreviewMode.STAGE_CLEAR,
                            libraryEnabled, libraryEnabled, libraryEnabled && scene.access.supported, libraryEnabled, false, {}, {}, {}, {}, {}, {}, {}, {}, {}, Modifier.weight(1f),
                            saveMessages = scene.save?.let { foregroundSaveMessages(it) } ?: emptyList(),
                            importEntry = { CaptureTextAction(MessageControl(MessageAction.CONFIRM, "取り込む"), {}) }, select = {})
                    }
                }
            }
            // Keep the original native dialog density for both versions; only the background list
            // uses the same fit-to-window density as scenes 01–08.
            if (scene.import != null) {
                if (before) LegacyImportDialog(scene.import, awaitingSelection = scene.awaiting)
                else ImportDialogView(scene.import, null, scene.awaiting, false, true, {}, {}, {}, {}, {}, {})
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
                var host: View? = null
                scenario.onActivity { host = it.window.decorView }
                capture("${scene.name}-${if (old) "before" else "after"}.png", requireNotNull(host), scene.import != null)
            }
        }
    }
}
