package dev.cinematic.p0.photoroll

import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.CaptureTheme
import dev.cinematic.p0.ExposureSelectionScale
import dev.cinematic.p0.photoedit.*
import org.junit.Assert.*
import org.junit.Test

/** Synthetic controls only: no camera or photo access, publication, or real-device haptic claims. */
class EditorRulerParityAndroidTest {
    @get:org.junit.Rule val fixture = SyntheticFixtureRule()
    @Test fun directionOneStepIntegerAccessibilityAndInterruptedGestureRemainConsistent() {
        check(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val value = mutableStateOf(.5)
        val key = mutableStateOf(0)
        val enabled = mutableStateOf(true)
        val capture = mutableStateOf(50)
        var finishes = 0
        val observed = mutableListOf<Double>()
        fixture.content = { CaptureTheme {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(top = 32.dp)) {
                ExposureSelectionScale("Capture", (0..100).map(Int::toString), capture.value, true, { capture.value = it })
                Spacer(Modifier.height(32.dp))
                PhotoEditBar("Editor", value.value, { value.value }, PhotoEditScale(0.0, 1.0, .01), key.value, enabled.value,
                    { trialStrengthLabel(it) }, { value.value = it; observed += it },
                    onValueChangeFinished = { finishes++ }, baseline = .5, quantization = .01)
            }
        } }
        fun node(): AccessibilityNodeInfo {
            fun find(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if (n == null) return null
                if (n.contentDescription?.toString() == "Editor") return n
                for (i in 0 until n.childCount) find(n.getChild(i))?.let { return it }
                return null
            }
            repeat(80) {
                instrumentation.waitForIdleSync()
                find(fixture.fixtureRoot())?.let { return it }
                SystemClock.sleep(50)
            }
            error("Missing editor slider")
        }
        var downTime = 0L
        fun touch(action: Int, x: Float, y: Float) {
            val now = SystemClock.uptimeMillis()
            if (action == MotionEvent.ACTION_DOWN) downTime = now
            val event = MotionEvent.obtain(downTime, now, action, x, y, 0)
            try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
            instrumentation.waitForIdleSync()
        }
        ActivityScenario.launch(PhotoRollActivity::class.java).use { scenario ->
            val rect = Rect().also { node().getBoundsInScreen(it) }
            val y = rect.exactCenterY()
            val x = rect.exactCenterX()
            val distance = 60 * instrumentation.targetContext.resources.displayMetrics.density
            touch(MotionEvent.ACTION_DOWN, x, y)
            touch(MotionEvent.ACTION_MOVE, x - distance, y)
            touch(MotionEvent.ACTION_UP, x - distance, y)
            assertTrue("Left drag increases like capture", value.value > .5)
            assertEquals(1, finishes)
            assertTrue(observed.all { kotlin.math.abs(it * 100 - kotlin.math.round(it * 100)) < 1e-9 })
            scenario.onActivity { value.value = .5 }
            instrumentation.waitForIdleSync()
            touch(MotionEvent.ACTION_DOWN, rect.right - 8f, y)
            touch(MotionEvent.ACTION_UP, rect.right - 8f, y)
            assertEquals(.51, value.value, 1e-12)
            val args = Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, .337f) }
            assertTrue(node().performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, args))
            instrumentation.waitForIdleSync()
            assertEquals(.34, value.value, 1e-12)
            val before = finishes
            touch(MotionEvent.ACTION_DOWN, x, y)
            touch(MotionEvent.ACTION_MOVE, x - distance, y)
            scenario.onActivity { key.value++; value.value = .5 }
            instrumentation.waitForIdleSync()
            touch(MotionEvent.ACTION_UP, x - distance, y)
            assertEquals("Old gesture cannot apply after replacement", before, finishes)
            assertEquals(.5, value.value, 0.0)
            scenario.onActivity { enabled.value = false }
            instrumentation.waitForIdleSync()
            touch(MotionEvent.ACTION_DOWN, rect.right - 8f, y)
            touch(MotionEvent.ACTION_UP, rect.right - 8f, y)
            assertEquals(.5, value.value, 0.0)
        }
    }
}
