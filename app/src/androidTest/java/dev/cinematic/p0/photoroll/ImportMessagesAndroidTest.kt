package dev.cinematic.p0.photoroll

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.mutableStateOf
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.CaptureTheme
import dev.cinematic.p0.rawcapture.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Synthetic display only: never opens SAF, starts copying or reads a real photo. */
class ImportMessagesAndroidTest {
    @get:org.junit.Rule val fixture = SyntheticFixtureRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun outputFile(name: String): File {
        val supplied = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        val root = supplied?.let(::File)
            ?: File(requireNotNull(instrumentation.targetContext.externalMediaDirs.firstOrNull()), "additional_test_output")
        return File(root, "ui-pr-e").apply { mkdirs() }.resolve(name)
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
        val deadline = SystemClock.uptimeMillis() + 7_000
        do {
            instrumentation.waitForIdleSync()
            if (find(text)?.isVisibleToUser == true) return
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        error("Missing synthetic dialog label: $text")
    }
    @Test fun importStatesRenderWithoutInvokingImportActions() {
        check(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        val state = mutableStateOf(DngImportScreenState())
        var calls = 0
        fixture.content = {
            CaptureTheme {
                ImportDialogView(state.value, null, false, false, true,
                    { calls++ }, { calls++ }, { calls++ }, { calls++ }, { calls++ }, { calls++ })
            }
        }
        ActivityScenario.launch(PhotoRollActivity::class.java).use { scenario ->
            val cases = listOf(
                "explanation" to DngImportScreenState(),
                "progress" to DngImportScreenState(busy = true, message = "取り込みを確認中…", messageKind = DngImportMessageKind.PROGRESS),
                "failure" to DngImportScreenState(message = "取り込み先の状態を確認できません。追加コピーせず保留しています", messageKind = DngImportMessageKind.FAILURE),
                "complete" to DngImportScreenState(message = "取り込みが完了しました", messageKind = DngImportMessageKind.ACCEPTED),
                "grant-pending" to DngImportScreenState(items = listOf(DngImportItem("synthetic", true, false, true))),
                "cancelled" to DngImportScreenState(items = listOf(DngImportItem("synthetic", false, true, true))),
            )
            for ((name, value) in cases) {
                scenario.onActivity { state.value = value }
                await(value.message ?: if (value.items.isNotEmpty()) "取り込み 1 · 権限の解放待ち" else "DNGを共有フォルダへコピーしてから開きます。")
                instrumentation.waitForIdleSync()
                val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                try {
                    outputFile("import-message-$name.png")
                        .outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } finally { screenshot.recycle() }
            }
            assertEquals(0, calls)
        }
    }
}
