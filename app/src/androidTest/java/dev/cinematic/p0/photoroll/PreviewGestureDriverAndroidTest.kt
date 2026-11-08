package dev.cinematic.p0.photoroll

import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.CaptureTheme
import dev.cinematic.p0.photoedit.PhotoEditBar
import dev.cinematic.p0.photoedit.PhotoEditScale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** 合成コントロールの自動入力だけを確認する。処理速度・実提示50msの判定ではない。 */
class PreviewGestureDriverAndroidTest {
    @get:Rule val fixture = SyntheticFixtureRule()

    @Test fun injectDragWithoutPerMoveIdleWaitAndDeliverRelease() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("previewPresentationProbe") == "true" && args.getString("isolated") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val value = mutableStateOf(.5)
        val changed = AtomicInteger()
        val finished = CountDownLatch(1)
        fixture.content = { CaptureTheme {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(top = 48.dp)) {
                PhotoEditBar("Synthetic preview slider", value.value, { value.value },
                    PhotoEditScale(0.0, 1.0, .01), 1, true, { it.toString() },
                    { value.value = it; changed.incrementAndGet() },
                    onValueChangeFinished = { finished.countDown() }, baseline = .5, quantization = .01)
            }
        } }
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.contentDescription?.toString() == "Synthetic preview slider") return node
            for (index in 0 until node.childCount) find(node.getChild(index))?.let { return it }
            return null
        }
        ActivityScenario.launch(PhotoRollActivity::class.java).use {
            var bounds: Rect? = null
            // 準備完了までの待機は計測するドラッグの前だけ。
            repeat(100) {
                if (bounds == null) {
                    instrumentation.waitForIdleSync()
                    find(fixture.fixtureRoot())?.let { node -> bounds = Rect().also(node::getBoundsInScreen) }
                    if (bounds == null) SystemClock.sleep(20)
                }
            }
            val rect = requireNotNull(bounds) { "Synthetic slider unavailable" }
            val records = PreviewGestureDriver(instrumentation.uiAutomation).drag(1,
                rect.exactCenterX(), rect.exactCenterY(), -rect.width() * .25f)
            assertTrue("Release reaches the actual component", finished.await(5, TimeUnit.SECONDS))
            assertTrue("The real ruler receives changes", changed.get() > 0)
            assertEquals(32, records.size)
            assertTrue(records.zipWithNext().all { (a, b) -> a.eventUptimeMs <= b.eventUptimeMs })
            assertTrue(records.all { it.callStartNs <= it.callEndNs && it.plannedUptimeMs <= it.eventUptimeMs })
        }
    }
}
