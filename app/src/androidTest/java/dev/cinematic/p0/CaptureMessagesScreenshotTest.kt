package dev.cinematic.p0

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.photoroll.PhotoRollActivity
import org.junit.Test
import java.io.File

/** Synthetic component captures only. No camera, MediaStore, personal photos, or physical device. */
class CaptureMessagesScreenshotTest {
    @Test fun captureMajorStates() {
        check(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val cases = linkedMapOf(
            "01-connecting" to captureMessages(waitingForConnection = true),
            "02-control-recovery" to captureMessages(controlRecovery = true, canReconnect = true),
            "03-capture-recovery" to captureMessages(captureRecovery = true),
            "04-thermal" to captureMessages(thermalWarning = true),
            "05-microphone" to captureMessages(microphoneWarning = true),
            "06-failure" to captureMessages(captureFailed = true),
            "07-progress" to captureMessages(phase = "ISO・WB変更中"),
            "08-saved" to captureMessages(notice = "保存しました"),
            "09-connecting-thermal" to captureMessages(waitingForConnection = true, thermalWarning = true),
            "10-reset" to ScreenMessages(),
            "13-display-fallback" to ScreenMessages(center = captureFailureFallback()),
            "14-display-fallback-busy" to ScreenMessages(center = captureFailureFallback(false)),
            "11-settings-busy" to captureSettingsMessages(busy = true),
            "12-settings-applying" to captureSettingsMessages(applying = "適用中：保存形式 JPEG → DNG · 再接続中（プレビュー一時停止・ロック解除）"),
        )
        val shown = mutableStateOf(cases.values.first())
        val horizontal = mutableStateOf(false)
        val reset = mutableStateOf(false)
        ActivityScenario.launch(PhotoRollActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent { CaptureTheme {
                    Box(Modifier.fillMaxSize().background(CaptureDesign.Ink)) {
                        CaptureMessageOverlay(shown.value, horizontal.value,
                            shown.value.center?.fact == "capture.recovery", {},
                            Modifier.graphicsLayer { rotationZ = if (horizontal.value) -90f else 0f })
                    }
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        shown.value.inline.forEach { CaptureInlineNote(it, {}) }
                    }
                    if (reset.value) CaptureResetDialog(dismiss = {}, reset = {})
                } }
            }
            for (landscape in listOf(false, true)) for ((name, messages) in cases) {
                scenario.onActivity {
                    horizontal.value = landscape
                    shown.value = messages
                    reset.value = name == "10-reset"
                }
                instrumentation.waitForIdleSync()
                android.os.SystemClock.sleep(200)
                scenario.onActivity { activity ->
                    val view = if (reset.value) android.view.inspector.WindowInspector.getGlobalWindowViews().last()
                        else activity.window.decorView
                    check(view.width > 0 && view.height > 0)
                    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                    try {
                        view.draw(Canvas(bitmap))
                        val directory = File(requireNotNull(InstrumentationRegistry.getArguments()
                            .getString("additionalTestOutputDir")), "ui-pr-b").apply { mkdirs() }
                        File(directory, "$name-${if (landscape) "horizontal" else "vertical"}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    } finally { bitmap.recycle() }
                }
            }
        }
    }
}
