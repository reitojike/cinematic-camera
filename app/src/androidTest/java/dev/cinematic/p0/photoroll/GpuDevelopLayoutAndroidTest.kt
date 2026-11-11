package dev.cinematic.p0.photoroll

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import dev.cinematic.p0.gpu.preview.GpuPreviewSurface
import dev.cinematic.p0.gpu.preview.GpuPreviewRenderer
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.CaptureTheme
import dev.cinematic.p0.gpu.GpuSurfaceTeardownException
import dev.cinematic.p0.gpu.gpuFailurePolicy
import dev.cinematic.p0.photoedit.PhotoAdjustmentPolicy
import dev.cinematic.p0.rawcapture.OriginalFingerprint
import dev.cinematic.p0.rawcapture.OriginalGrant
import dev.cinematic.p0.rawcapture.SharedOriginalReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Layout/accessibility contract only. Surface is deliberately empty, so this is not pixel/GPU QA. */
class GpuDevelopLayoutAndroidTest {
    @get:Rule val fixture = SyntheticFixtureRule()
    @Test fun gpuAndCpuKeepIdenticalViewportAndImageBoundsAndFailureStaysInTheEditor() {
        check(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = Bitmap.createBitmap(256, 192, Bitmap.Config.ARGB_8888)
        val gpu = GpuPhotoRollSession(instrumentation.targetContext.assets)
        val gpuSession = mutableStateOf(gpu)
        val useGpu = mutableStateOf(false)
        val failed = mutableStateOf(false)
        var retries = 0
        val original = SharedOriginalReference("content://synthetic/layout", OriginalFingerprint(100, "a".repeat(64)), OriginalGrant.USER_SELECTED)
        val bound = checkNotNull(TrialStrengthEditor.empty(PhotoAdjustmentPolicy.defaults).bind(original))
        val editor = mutableStateOf(bound.shown(bound.ticket, bound.requestedValues))
        fixture.content = {
            CaptureTheme {
                PhotoRollDevelopView("SYNTHETIC", PhotoRollPreviewMode.MANUAL_TRIAL, editor.value, { editor.value },
                    if (failed.value) PhotoRollPhase.BLOCKED else PhotoRollPhase.DISPLAYED,
                    null, null, bitmap.takeUnless { useGpu.value }, null, null, true, null, false, false, false,
                    {}, {}, {}, { retries++ }, { _, _, _ -> }, {}, {}, {}, {}, modifier = Modifier.fillMaxSize(),
                    gpuSession = gpuSession.value.takeIf { useGpu.value }, gpuReady = useGpu.value && !failed.value,
                    gpuFailure = if (failed.value) gpuFailurePolicy(GpuSurfaceTeardownException("synthetic")) else null,
                    info = { Text("SYNTHETIC") })
            }
        }
        try {
            ActivityScenario.launch(PhotoRollActivity::class.java).use { scenario ->
                val cpuViewport = bounds("現像画像の表示領域")
                val cpuImage = bounds("現像結果（表示用）")
                scenario.onActivity { useGpu.value = true }
                instrumentation.waitForIdleSync()
                assertEquals(cpuViewport, bounds("現像画像の表示領域"))
                assertEquals(cpuImage, bounds("現像結果（表示用）"))
                // Require an established Surface before retry. This was the failing path:
                // replacing before its first creation could pass while hiding View reuse.
                awaitSurface(gpu.renderer)
                val replacement = GpuPhotoRollSession(instrumentation.targetContext.assets)
                scenario.onActivity { gpuSession.value = replacement; gpu.close() }
                awaitSurface(replacement.renderer)
                assertFalse(gpu.renderer.surfaceAttached.value)
                scenario.onActivity { failed.value = true }
                val label = await("再試行")
                val retry = checkNotNull(generateSequence(label) { it.parent }.firstOrNull { it.isClickable })
                assertTrue("retry action is enabled", retry.isEnabled)
                val failureBounds = Rect().also { retry.getBoundsInScreen(it) }
                assertTrue("retry remains inside selected image region", cpuViewport.contains(failureBounds))
                assertTrue(retry.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                instrumentation.waitForIdleSync()
                assertEquals(1, retries)
                assertEquals(cpuViewport, bounds("現像画像の表示領域"))
            }
        } finally {
            gpu.close(); gpuSession.value.close()
            assertTrue(gpu.renderer.awaitClosed(5_000))
            assertTrue(gpuSession.value.renderer.awaitClosed(5_000))
            GpuPhotoRollRetiredOwners.releaseBeforeOpen()
            bitmap.recycle()
        }
    }

    @Test fun replacingBeforeFirstSurfaceCreationStillAttachesTheNewRenderer() {
        check(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val original = GpuPreviewRenderer(instrumentation.targetContext.assets)
        val replacement = GpuPreviewRenderer(instrumentation.targetContext.assets)
        val current = mutableStateOf(original)
        fixture.content = {
            GpuPreviewSurface(current.value, Modifier.fillMaxSize())
            // After the original View is composed, before its first layout/Surface creation.
            SideEffect {
                if (current.value === original) {
                    check(!original.surfaceAttached.value)
                    current.value = replacement
                    original.close()
                }
            }
        }
        try {
            ActivityScenario.launch(PhotoRollActivity::class.java).use {
                awaitSurface(replacement)
                assertFalse(original.surfaceAttached.value)
            }
        } finally {
            original.close(); replacement.close()
            assertTrue(original.awaitClosed(5_000))
            assertTrue(replacement.awaitClosed(5_000))
        }
    }

    private fun awaitSurface(renderer: GpuPreviewRenderer) {
        repeat(100) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            if (renderer.surfaceAttached.value) return
            SystemClock.sleep(50)
        }
        fail("replacement renderer must receive its own Surface creation")
    }

    private fun bounds(label: String) = Rect().also { await(label).getBoundsInScreen(it) }
    private fun await(label: String): AccessibilityNodeInfo {
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
            for (index in 0 until node.childCount) find(node.getChild(index))?.let { return it }
            return null
        }
        repeat(100) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            find(fixture.fixtureRoot())?.let { return it }
            SystemClock.sleep(50)
        }
        error("Missing synthetic GPU editor node: $label")
    }
}
