package dev.cinematic.p0.photoroll

import android.graphics.Bitmap
import java.io.File
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.*
import dev.cinematic.p0.CaptureTheme
import org.junit.Assert.*
import org.junit.Test

/** Synthetic access and listing data only. Run with isolated=true on the shared API 37 ATD. */
class CameraRollBrowserAndroidTest {
    @get:org.junit.Rule val diagnostic = SyntheticFixtureRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun outputFile(name: String): File {
        val supplied = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        val root = supplied?.let(::File)
            ?: File(requireNotNull(instrumentation.targetContext.externalMediaDirs.firstOrNull()), "additional_test_output")
        return File(root, "ui-pr-e").apply { mkdirs() }.resolve(name)
    }
    private val full = CameraRollAccess(true, true, true, false)
    private val selected = CameraRollAccess(true, false, false, true)
    private val emptyText = "該当する項目がありません。アクセス許可・絞り込みを確認するか、FilesでDNGを開いてください。"
    private data class Fixture(val access: CameraRollAccess, val loading: Boolean = false,
        val items: List<PhotoRollItem> = emptyList(), val permission: Boolean = true,
        val files: Boolean = true, val width: Int = 412, val height: Int = 924, val fontScale: Float = 1f, val notice: String? = null, val save: List<UiMessage> = emptyList(), val saveAction: (MessageAction) -> Unit = {})

    private fun find(text: String): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.text?.toString() == text || node.contentDescription?.toString() == text) return node
            for (index in 0 until node.childCount) visit(node.getChild(index))?.let { return it }
            return null
        }
        return visit(diagnostic.fixtureRoot())
    }
    private fun actionable(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var current = node
        while (!current.isClickable) current = current.parent ?: return node
        return current
    }
    private fun scroll(forward: Boolean) {
        fun visit(node: AccessibilityNodeInfo?): Boolean {
            if (node == null) return false
            if (node.className == "android.widget.ScrollView" && node.performAction(
                    if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) return true
            for (index in 0 until node.childCount) if (visit(node.getChild(index))) return true
            return false
        }
        visit(diagnostic.fixtureRoot())
        SystemClock.sleep(180)
    }
    private fun await(text: String, enabled: Boolean? = null): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 7_000
        var attempts = 0
        do {
            instrumentation.waitForIdleSync()
            find(text)?.let { label ->
                val node = if (enabled == null) label else actionable(label)
                if (label.isVisibleToUser && (enabled == null || node.isEnabled == enabled)) return node
            }
            if (++attempts % 4 == 0) scroll((attempts / 20) % 2 == 0)
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        error("Missing reachable $text (enabled=$enabled)")
    }
    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        diagnostic.captureSoftware().let { bitmap ->
            try {
                outputFile("roll-design-$name.png")
                    .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally { bitmap.recycle() }
        }
    }
    private fun runFixture(initial: Fixture, block: (ActivityScenario<PhotoRollActivity>,
        (Fixture) -> Unit, () -> Pair<Int, Int>) -> Unit) {
        check(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        val state = mutableStateOf(initial)
        var permissions = 0
        var files = 0
        // Existing activity starts on an empty ATD; lifecycle callbacks install synthetic content.
        // Initial repository reads may occur; fixtures do not request permissions or launch viewers.
        diagnostic.content = {
                val fixture = state.value
                BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
                    val nativeDensity = LocalDensity.current.density
                    val density = minOf(maxWidth.value / fixture.width, maxHeight.value / fixture.height) * nativeDensity
                    CompositionLocalProvider(LocalDensity provides Density(density, fixture.fontScale)) {
                        CaptureTheme {
                            CameraRollBrowser(PhotoRollListing(fixture.items), fixture.access, PhotoRollKindFilter.ALL,
                                null, fixture.loading, false, fixture.notice, PhotoRollPreviewMode.MANUAL_TRIAL,
                                false, false, fixture.permission, fixture.files, false, {}, {}, {}, {}, {},
                                { permissions++ }, {}, { files++ }, {},
                                Modifier.size(fixture.width.dp, fixture.height.dp), saveMessages = fixture.save, saveAction = fixture.saveAction, select = {})
                        }
                    }
                }
        }
        ActivityScenario.launch(PhotoRollActivity::class.java).use { scenario ->
            scenario.recreate()
            block(scenario, { fixture -> scenario.onActivity { state.value = fixture }; instrumentation.waitForIdleSync() },
                { permissions to files })
        }
    }

    @Test fun accessGuidancePreservesFullSelectedMixedNoneAndUnsupportedMeanings() = runFixture(Fixture(full)) { _, update, _ ->
        await(emptyText)
        assertNull(find("アクセス許可・選び直す"))
        assertNull(find(full.description))
        capture("full-empty")
        val cases = listOf(selected,
            CameraRollAccess(true, true, false, true), CameraRollAccess(true, false, true, true),
            CameraRollAccess(true, true, false, false), CameraRollAccess(true, false, true, false),
            CameraRollAccess(true, false, false, false), CameraRollAccess(false, false, false, false))
        for (access in cases) {
            update(Fixture(access))
            await(if (access == selected) "選択した写真・動画だけにアクセスできます" else access.description)
            await(if (access.selected) "選び直す" else "アクセス許可・選び直す")
            await("設定")
            capture("access-${access.supported}-${access.images}-${access.videos}-${access.selected}")
        }
    }

    @Test fun emptyActionsDispatchExactlyOnceAndDisabledActionsNeverDispatch() = runFixture(Fixture(selected)) { _, update, counts ->
        assertTrue(await("アクセスを許可", true).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        assertTrue(await("Filesで開く", true).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
        assertEquals(1 to 1, counts())
        update(Fixture(selected, permission = false, files = false))
        await("アクセスを許可", false).performAction(AccessibilityNodeInfo.ACTION_CLICK)
        await("Filesで開く", false).performAction(AccessibilityNodeInfo.ACTION_CLICK)
        instrumentation.waitForIdleSync()
        assertEquals(1 to 1, counts())
        capture("disabled-empty-actions")
    }

    @Test fun loadingNeverAnnouncesEmptyAndRetainsOldSyntheticTiles() = runFixture(Fixture(full, loading = true)) { _, update, _ ->
        await("カメラロール")
        assertNull(find(emptyText))
        val item = PhotoRollItem("content://synthetic/item", "SYNTHETIC.dng")
        update(Fixture(full, loading = true, items = listOf(item)))
        await(item.name)
        capture("loading-retained-tile")
        assertNull(find(emptyText))
        update(Fixture(CameraRollAccess(true, false, false, false), items = listOf(item)))
        await(item.name)
        await(CameraRollAccess(true, false, false, false).description)
        assertNull(find(emptyText)) // App-owned visible items do not require a broad grant.
    }

    @Test fun emptyAccessAndFooterRemainReachableAcrossViewports() {
        for (fixture in listOf(Fixture(selected), Fixture(selected, width = 320, height = 480),
                Fixture(selected, width = 924, height = 412), Fixture(selected, fontScale = 1.3f))) {
            runFixture(fixture) { _, _, _ ->
                await("選択した写真・動画だけにアクセスできます")
                capture("viewport-${fixture.width}-${fixture.height}-${fixture.fontScale}")
                await("アクセスを許可", true)
                await("Filesで開く", true)
                capture("actions-${fixture.width}-${fixture.height}-${fixture.fontScale}")
                await("Filesで開く", true)
                capture("footer-${fixture.width}-${fixture.height}-${fixture.fontScale}")
                await("試験品質 · 手動保存 · 原本は変更しません ▾")
            }
        }
    }
    @Test fun saveStatesAndFailureUseOnePriorityBanner() = runFixture(Fixture(full)) { _, update, _ ->
        val base = ForegroundSaveMessageState(true, true, false, false, true, false, true)
        val cases = listOf("saving" to base, "stopping" to base.copy(stopping = true),
            "held" to base.copy(held = true), "notifications" to base.copy(active = false))
        for ((name, save) in cases) {
            val candidates = foregroundSaveMessages(save)
            update(Fixture(full, save = candidates))
            await(screenMessages(candidates).banner!!.body)
            capture("message-$name")
        }
        var cancels = 0
        update(Fixture(selected, notice = "合成の項目を開けませんでした", save = foregroundSaveMessages(base),
            saveAction = { if (it == MessageAction.CANCEL_SAVE) cancels++ }))
        await("合成の項目を開けませんでした")
        assertTrue(await("保存を取り消す", true).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
        assertEquals(1, cancels)
        await("通知を許可", true)
        assertNull(find("JPEGを保存中です。アプリを離れても続きます。"))
        assertNull(find("選択した写真・動画だけにアクセスできます"))
        capture("message-failure-priority")
    }

}
