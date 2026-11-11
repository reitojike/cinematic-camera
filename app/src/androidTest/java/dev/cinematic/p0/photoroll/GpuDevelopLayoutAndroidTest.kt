package dev.cinematic.p0.photoroll

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
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
                val replacement = GpuPhotoRollSession(instrumentation.targetContext.assets)
                scenario.onActivity { gpuSession.value = replacement; gpu.close() }
                repeat(100) {
                    instrumentation.waitForIdleSync()
                    if (!replacement.renderer.surfaceAttached.value) SystemClock.sleep(50)
                }
                assertTrue("same-photo retry attaches a fresh renderer surface", replacement.renderer.surfaceAttached.value)
                assertFalse(gpu.renderer.surfaceAttached.value)
                scenario.onActivity { failed.value = true }
                val retry = await("再試行")
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
