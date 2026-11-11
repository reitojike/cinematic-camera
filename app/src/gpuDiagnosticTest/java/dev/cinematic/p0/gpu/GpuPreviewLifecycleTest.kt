package dev.cinematic.p0.gpu

import android.content.ComponentCallbacks2
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.Surface
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.gpu.preview.GpuPreviewRenderer
import dev.cinematic.p0.gpu.preview.GpuPreviewSnapshot
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Opt-in diagnostic variant only; exercises the real Compose surface/lifecycle observer. */
@RunWith(AndroidJUnit4::class)
class GpuPreviewLifecycleTest {
    @Before fun syntheticEmulatorOnly() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish")) { "Synthetic emulator only" }
    }

    @Test fun oldSurfaceDetachCannotClearNewAttachmentNotification() {
        val renderer = GpuPreviewRenderer(InstrumentationRegistry.getInstrumentation().targetContext.assets)
        val firstTexture = SurfaceTexture(false)
        val secondTexture = SurfaceTexture(false)
        val first = Surface(firstTexture)
        val second = Surface(secondTexture)
        try {
            assertFalse(renderer.surfaceAttached.value)
            renderer.attachSurface(first, 16, 16)
            assertTrue(renderer.surfaceAttached.value)
            renderer.attachSurface(second, 16, 16)
            renderer.detachSurface(first)
            assertTrue(renderer.hasSurface)
            assertTrue("Old detach must not publish false for the new lease", renderer.surfaceAttached.value)
            renderer.resizeSurface(first, 32, 32)
            assertTrue(renderer.surfaceAttached.value)
            renderer.detachSurface(second)
            assertFalse(renderer.hasSurface)
            assertFalse(renderer.surfaceAttached.value)
            renderer.attachSurface(first, 16, 16)
            renderer.close()
            assertFalse(renderer.surfaceAttached.value)
            renderer.detachSurface(first) // Bounded existing shutdown barrier, before Surface release.
        } finally {
            renderer.close()
            first.release(); second.release()
            firstTexture.release(); secondTexture.release()
        }
    }

    @Suppress("DEPRECATION")
    @Test fun readinessTimeoutReplacementPauseAndDestroyKeepOnlyCurrentInput() {
        val scenario = ActivityScenario.launch(GpuPreviewDiagnosticActivity::class.java)
        val activity = AtomicReference<GpuPreviewDiagnosticActivity>()
        try {
            val currentRenderer = renderer(scenario)
            awaitSnapshot(currentRenderer, "initial real surface") { it.surfaceAttached && it.drawCount > 0 }
            // Remove the real Compose surface, not its readiness flag or shared CPU observations.
            scenario.onActivity { activity.set(it); it.setContent { } }
            awaitSnapshot(currentRenderer, "actual surface absent while resumed") { !it.surfaceAttached && it.windowCount == 0 }
            scenario.onActivity { it.acceptDiagnosticDocument(Uri.EMPTY) }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            val first = inputState(scenario)
            assertTrue(first.resumed && first.loading && !first.surfaceAttached && !first.pending)

            scenario.onActivity { it.acceptDiagnosticDocument(Uri.EMPTY) }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            val second = inputState(scenario)
            assertTrue(second.epoch > first.epoch && second.loading)
            assertEquals("Old cancelled callback cannot complete or clear the new wait", first.completedEpoch, second.completedEpoch)
            val deadline = SystemClock.elapsedRealtime() + 15_000
            var timedOut = inputState(scenario)
            while (timedOut.loading && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(25) // Test observation only; production uses StateFlow notification.
                timedOut = inputState(scenario)
            }
            assertEquals("Preparation timeout must be explicit, not cancellation/read failure", "readiness_timeout", timedOut.inputResult)
            assertEquals(second.epoch, timedOut.completedEpoch)
            assertFalse(timedOut.loading || timedOut.pending || timedOut.gpuError)

            scenario.onActivity { it.acceptDiagnosticDocument(Uri.EMPTY) }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertTrue(inputState(scenario).loading)
            scenario.moveToState(Lifecycle.State.CREATED)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            val paused = inputState(scenario)
            assertFalse(paused.resumed || paused.loading || paused.pending)
            assertEquals("Pause cancellation does not complete the newer input", second.epoch, paused.completedEpoch)
            assertEquals("idle_or_interrupted", paused.inputResult)

            scenario.onActivity { it.acceptDiagnosticDocument(Uri.EMPTY) }
            val pending = inputState(scenario)
            assertTrue(pending.pending)
            assertFalse(pending.loading)
            assertEquals("Already-paused delivery does not consume a request epoch", paused.epoch, pending.epoch)
            scenario.onActivity { it.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) }
            val afterBackgroundTrim = inputState(scenario)
            assertTrue("Background cleanup must retain a NEW already-paused picker result", afterBackgroundTrim.pending)
            assertFalse(afterBackgroundTrim.loading)
        } finally { scenario.close() }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val destroyed = checkNotNull(activity.get()).diagnosticState()
            assertFalse("Destroy discards any pending URI and wait", destroyed.pending || destroyed.loading || destroyed.resumed)
        }
    }

    @Suppress("DEPRECATION")
    @Test fun composeStopStartTrimAndTenActivityRecreationsRenderAgain() {
        ActivityScenario.launch(GpuPreviewDiagnosticActivity::class.java).use { scenario ->
            repeat(10) { cycle ->
                val currentRenderer = renderer(scenario)
                val active = awaitSnapshot(currentRenderer, "cycle $cycle initial render") {
                    it.drawCount > 0 && it.windowCount == 1 && it.allocatedBytes > 0
                }
                scenario.moveToState(Lifecycle.State.CREATED)
                val stopped = awaitSnapshot(currentRenderer, "cycle $cycle stop releases EGL window") { it.windowCount == 0 }
                scenario.onActivity { it.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) }
                val trimmed = awaitSnapshot(currentRenderer, "cycle $cycle trim releases GPU input") { it.allocatedBytes == 0L }
                assertEquals(stopped.drawCount, trimmed.drawCount)
                assertEquals(0, trimmed.windowCount)
                scenario.moveToState(Lifecycle.State.RESUMED)
                val resumed = awaitSnapshot(currentRenderer, "cycle $cycle start reloads retained input") {
                    it.drawCount > trimmed.drawCount && it.windowCount == 1 && it.allocatedBytes > 0
                }
                assertEquals(active.allocatedBytes, resumed.allocatedBytes)
                scenario.recreate()
                val replacement = renderer(scenario)
                assertNotSame("cycle $cycle owns a fresh renderer", currentRenderer, replacement)
                awaitSnapshot(replacement, "cycle $cycle recreated Compose surface renders") {
                    it.drawCount > 0 && it.windowCount == 1 && it.allocatedBytes > 0
                }
            }
        }
    }

    @Test fun diagnosticSlidersBeforeAndRotationReachRendererThroughAccessibilityActions() {
        // Connect accessibility before Compose publishes the initial control tree.
        InstrumentationRegistry.getInstrumentation().uiAutomation
        ActivityScenario.launch(GpuPreviewDiagnosticActivity::class.java).use { scenario ->
            val currentRenderer = renderer(scenario)
            awaitSnapshot(currentRenderer, "diagnostic initial frame") {
                it.drawCount > 0 && it.windowCount == 1 && it.allocatedBytes > 0
            }
            awaitControl("diagnostic slider tree ready") { it.rangeInfo != null }
            // Exercise the actual Compose controls, not direct renderer.submit calls. These are
            // accessibility interactions, not touch-drag latency or look/quality acceptance.
            for ((minimum, maximum, target) in listOf(
                Triple(0f, 1f, 0.25f), Triple(-100f, 100f, 25f), Triple(-2f, 2f, 0.5f),
            )) {
                val matchesRange: (AccessibilityNodeInfo) -> Boolean = { node ->
                    node.rangeInfo?.let { it.min == minimum && it.max == maximum } == true
                }
                val slider = revealControl("slider $minimum..$maximum", matchesRange)
                val baseline = settledSnapshot(currentRenderer)
                assertTrue("Slider node became stale", slider.refresh())
                assertTrue("Slider must accept ACTION_SET_PROGRESS", slider.performAction(
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
                    Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, target) },
                ))
                awaitControl("slider value $target") {
                    matchesRange(it) && kotlin.math.abs(checkNotNull(it.rangeInfo).current - target) < 0.001f
                }
                awaitSnapshot(currentRenderer, "slider update must draw") { it.drawCount > baseline.drawCount }
            }

            repeat(2) { toggle ->
                val switch = revealControl("Before switch") { it.isCheckable && it.isClickable }
                val wasChecked = switch.isChecked
                val baseline = settledSnapshot(currentRenderer)
                assertTrue("Before switch became stale", switch.refresh())
                assertTrue("Before must accept click", switch.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                awaitControl("Before toggle $toggle") { it.isCheckable && it.isChecked != wasChecked }
                awaitSnapshot(currentRenderer, "Before update must draw") { it.drawCount > baseline.drawCount }
            }

            for (rotation in listOf(0, 90, 180, 270)) {
                val button = revealControl("rotation $rotation") {
                    it.text?.toString() == "回転 $rotation°"
                }
                val baseline = settledSnapshot(currentRenderer)
                assertTrue("Rotation button became stale", button.refresh())
                var clickable = button
                while (!clickable.isClickable) clickable = checkNotNull(clickable.parent) { "Rotation has no clickable ancestor" }
                assertTrue("Rotation must accept click", clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                val next = (rotation + 90) % 360
                awaitControl("rotation label $next") { it.text?.toString() == "回転 $next°" }
                awaitSnapshot(currentRenderer, "rotation update must draw") { it.drawCount > baseline.drawCount }
            }
        }
    }

    private fun accessibilityNodes(): List<AccessibilityNodeInfo> {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        // Read current virtual-node text after Compose actions, not a cached pre-action label.
        automation.clearCache()
        val root = automation.rootInActiveWindow ?: return emptyList()
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            nodes.add(node)
            repeat(node.childCount) { node.getChild(it)?.let(::visit) }
        }
        visit(root)
        // minSdk 33: AccessibilityNodeInfo pooling/recycle is deprecated and no longer needed.
        return nodes
    }

    private fun revealControl(label: String, matches: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        do {
            val nodes = accessibilityNodes()
            nodes.firstOrNull(matches)?.let { node ->
                node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                return awaitControl(label, matches)
            }
            nodes.firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(150)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AssertionError("Accessibility control not found: $label")
    }

    private fun awaitControl(label: String, matches: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        do {
            accessibilityNodes().firstOrNull(matches)?.let { return it }
            SystemClock.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AssertionError("Accessibility state not observed: $label")
    }

    private fun settledSnapshot(renderer: GpuPreviewRenderer): GpuPreviewSnapshot {
        // Exclude initial upload/surface-scroll redraws from the following control's assertion.
        val deadline = SystemClock.elapsedRealtime() + 5_000
        var previous = awaitSnapshot(renderer, "before control") { it.windowCount == 1 && it.drawCount > 0 }
        var stable = 0
        do {
            SystemClock.sleep(100)
            val current = awaitSnapshot(renderer, "settle control") { it.windowCount == 1 }
            stable = if (current.drawCount == previous.drawCount) stable + 1 else 0
            if (stable >= 3) return current
            previous = current
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AssertionError("Preview did not settle before control interaction")
    }

    private fun renderer(scenario: ActivityScenario<GpuPreviewDiagnosticActivity>): GpuPreviewRenderer {
        val value = AtomicReference<GpuPreviewRenderer>()
        scenario.onActivity { value.set(it.renderer) }
        return checkNotNull(value.get())
    }

    private fun inputState(scenario: ActivityScenario<GpuPreviewDiagnosticActivity>): GpuPreviewDiagnosticActivity.DiagnosticState {
        val value = AtomicReference<GpuPreviewDiagnosticActivity.DiagnosticState>()
        scenario.onActivity { value.set(it.diagnosticState()) }
        return checkNotNull(value.get())
    }

    private fun awaitSnapshot(renderer: GpuPreviewRenderer, label: String,
        predicate: (GpuPreviewSnapshot) -> Boolean): GpuPreviewSnapshot {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        var latest: GpuPreviewSnapshot? = null
        do {
            val ready = CountDownLatch(1)
            val value = AtomicReference<GpuPreviewSnapshot>()
            renderer.snapshot { value.set(it); ready.countDown() }
            assertTrue("$label: snapshot callback timed out", ready.await(5, TimeUnit.SECONDS))
            latest = checkNotNull(value.get())
            if (predicate(latest)) return latest
            SystemClock.sleep(25)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AssertionError("$label: condition timed out; last=$latest")
    }
}
