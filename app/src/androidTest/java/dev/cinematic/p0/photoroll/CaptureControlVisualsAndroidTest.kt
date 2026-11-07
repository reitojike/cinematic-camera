package dev.cinematic.p0.photoroll

import android.graphics.Rect
import android.graphics.Bitmap
import java.io.File
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.CaptureControlKey
import dev.cinematic.p0.CaptureModeSwitchSurface
import dev.cinematic.p0.CaptureTheme
import org.junit.Assert.*
import org.junit.Test

/** Shared presentation fixture only: no camera, media access or capture is started. */
class CaptureControlVisualsAndroidTest {
    @get:org.junit.Rule val diagnostic = SyntheticFixtureRule()
    @Test fun shootingKeyRotationAndCallerOwnedToggleRemainAvailable() {
        check(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val horizontal = mutableStateOf(false)
        val selected = mutableStateOf(true)
        val second = mutableStateOf(false)
        var toggles = 0
        fun find(description: String): AccessibilityNodeInfo? {
            fun walk(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if (node == null) return null
                if (node.contentDescription?.toString() == description) {
                    var action: AccessibilityNodeInfo = node
                    while (!action.isClickable) action = action.parent ?: return node
                    return action
                }
                for (i in 0 until node.childCount) walk(node.getChild(i))?.let { return it }
                return null
            }
            return walk(diagnostic.fixtureRoot())
        }
        fun await(description: String, expectedSelected: Boolean? = null, expectedChecked: Boolean? = null): AccessibilityNodeInfo {
            repeat(40) {
                instrumentation.waitForIdleSync()
                find(description)?.takeIf { it.isVisibleToUser && (expectedSelected == null || (it.isSelected || (it.isCheckable && it.isChecked)) == expectedSelected) &&
                    (expectedChecked == null || it.isChecked == expectedChecked) }?.let { return it }
                Thread.sleep(50)
            }
            diagnostic.failure(description)
            error("Missing $description")
        }
        diagnostic.content = { CaptureTheme {
                Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                    CaptureControlKey("ISO", "100", "AUTO", selected.value, true, {},
                        Modifier.width(120.dp), horizontal = horizontal.value,
                        rotation = if (horizontal.value) 90f else 0f)
                    CaptureModeSwitchSurface(second.value, Modifier.toggleable(second.value, role = Role.Switch,
                        onValueChange = { second.value = it; toggles++ }).semantics { contentDescription = "合成撮影モード" }) {
                        Text("STILL", Modifier.weight(1f)); Text("CINE", Modifier.weight(1f))
                    }
                }
        } }
        ActivityScenario.launch(PhotoRollActivity::class.java).use { scenario ->
            scenario.recreate() // Verify fixture installation and capture after host recreation.
            for (landscape in listOf(false, true)) {
                scenario.onActivity { horizontal.value = landscape; selected.value = true }
                val observed = await("ISO 100  AUTO")
                File(instrumentation.targetContext.getExternalFilesDir(null), "design-shooting-accessibility.txt").appendText(
                    "horizontal=$landscape description=${observed.contentDescription} selected=${observed.isSelected} checkable=${observed.isCheckable} checked=${observed.isChecked}\n")
                val key = await("ISO 100  AUTO", expectedSelected = true)
                assertTrue("selected button must expose checked or selected", key.isSelected || (key.isCheckable && key.isChecked))
                instrumentation.waitForIdleSync()
                diagnostic.captureSoftware().let { bitmap ->
                    try { File(instrumentation.targetContext.getExternalFilesDir(null),
                        "design-shooting-control-${if (landscape) "horizontal" else "vertical"}.png").outputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    } } finally { bitmap.recycle() }
                }
                val bounds = Rect().also { key.getBoundsInScreen(it) }
                assertTrue(bounds.width() > 0 && bounds.height() > 0)
                assertTrue(await("合成撮影モード").performAction(AccessibilityNodeInfo.ACTION_CLICK))
                instrumentation.waitForIdleSync()
                assertTrue(await("合成撮影モード", expectedChecked = second.value).isChecked == second.value)
                scenario.onActivity { selected.value = false }
                instrumentation.waitForIdleSync()
                assertFalse(await("ISO 100  AUTO", expectedSelected = false).let { it.isSelected || (it.isCheckable && it.isChecked) })
            }
            assertEquals(2, toggles)
        }
    }
}
