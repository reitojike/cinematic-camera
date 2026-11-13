package dev.cinematic.p0.photoroll

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.jpegrender.original.Original24Request
import dev.cinematic.p0.rawcapture.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*

/** Uses the ordinary Compose screen and service path, after host-proven process death.
 * No replacement renderer, owner, notification or UI is installed by this probe. */
internal object SaveRecoveryColdUiProbe {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    fun check(app: Context, old: Original24Request, name: String, boundary: String, publishExplicitly: Boolean) {
        require(boundary in setOf("intent", "b-cursor"))
        check(Build.HARDWARE in setOf("ranchu", "goldfish") && app.packageName == "dev.cinematic.p0")
        assertNull(AndroidManualOriginalTrialSaves.knownPrepared(old.source))
        cameraRollReadPermissions(Build.VERSION.SDK_INT).forEach {
            instrumentation.uiAutomation.grantRuntimePermission(app.packageName, it)
        }
        if (Build.VERSION.SDK_INT >= 33)
            instrumentation.uiAutomation.grantRuntimePermission(app.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        var camera: PhotoRollCameraOwners.Owner? = null
        instrumentation.runOnMainSync {
            camera = AndroidPhotoRollCameraObservations.register { PhotoRollCameraSnapshot(false, false, false, false) }
        }
        val sawForegroundOwner = AtomicBoolean(false)
        val observer = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val watch = observer.launch(start = CoroutineStart.UNDISPATCHED) {
            ForegroundExports.changes.collect {
                if (ForegroundExports.gate.active() != null) sawForegroundOwner.set(true)
            }
        }
        try {
            ActivityScenario.launch(PhotoRollActivity::class.java).use {
                click(name)
                val expected = if (boundary == "b-cursor") old.copy(generation = old.generation + 1) else old
                await("cold metadata and controls") {
                    AndroidManualOriginalTrialSaves.knownPrepared(old.source)?.request == expected &&
                        action(if (boundary == "b-cursor") "JPEGを保存" else "保存結果を確認・保存し直す") != null
                }
                val handle = requireNotNull(AndroidManualOriginalTrialSaves.knownPrepared(old.source))
                assertEquals(old.strength, handle.appliedStrength, 0.0) // draft is deliberately .35
                assertEquals(expected, handle.request)
                await("fixed strength visibly applied") { strengthDisplayed(old.strength) }
                val before = intent(app, expected)
                val idleUntil = SystemClock.elapsedRealtime() + 1500
                while (SystemClock.elapsedRealtime() < idleUntil) {
                    instrumentation.waitForIdleSync()
                    assertEquals(expected, AndroidManualOriginalTrialSaves.knownPrepared(old.source)?.request)
                    assertArrayEquals(before, intent(app, expected))
                    assertNull(publication(app, expected))
                    assertNull(ForegroundExports.gate.active())
                    assertTrue("UI must keep the fixed requested and displayed strength", strengthDisplayed(old.strength))
                    Thread.sleep(25)
                }
                assertFalse(sawForegroundOwner.get())
                if (boundary == "intent") {
                    click("保存結果を確認・保存し直す"); click("もう一度確認")
                    await("recheck returned without producer") { action("保存結果を確認・保存し直す") != null }
                    assertArrayEquals(before, intent(app, expected))
                    assertNull(publication(app, expected))
                    assertEquals(ManualOriginalTrialSavePhase.HOLD, handle.state.value.phase)
                    assertFalse(sawForegroundOwner.get())
                }
                if (publishExplicitly) {
                    if (boundary == "intent") {
                        click("保存結果を確認・保存し直す")
                        click("重複の可能性はあるが保存し直す")
                    } else click("JPEGを保存")
                    await("explicit FGS publication", 180_000) {
                        AndroidManualOriginalTrialSaves.knownPrepared(old.source)?.state?.value?.phase == ManualOriginalTrialSavePhase.PUBLISHED &&
                            ForegroundExports.gate.active() == null
                    }
                    assertTrue("Normal service path must be observed", sawForegroundOwner.get())
                    val next = requireNotNull(AndroidManualOriginalTrialSaves.knownPrepared(old.source))
                    assertEquals(old.generation + 1, next.request.generation)
                    assertEquals(old.recipe, next.request.recipe)
                    assertFalse(next.hasUnreleasedResources)
                    val result = requireNotNull(publication(app, next.request))
                    assertEquals(JpegPublicationPhase.PUBLISHED, result.phase)
                    assertEquals(next.request.source, result.render.request.source)
                    DngContentVerifier.verify(result.fingerprint,
                        { requireNotNull(app.contentResolver.openInputStream(android.net.Uri.parse(result.uri))) })
                }
            }
            GpuPhotoRollRetiredOwners.releaseBeforeOpen()
        } finally {
            watch.cancel(); observer.cancel()
            instrumentation.runOnMainSync { camera?.close() }
        }
    }
    private fun storage(app: Context, request: Original24Request, kind: CaptureJournalFile): AtomicJournalStorage {
        val root = RenderedJpegFiles(File(app.noBackupFilesDir, "raw-captures"), request.captureId, request.generation).generationRoot()
        return AndroidAtomicJournalStorage(requireNotNull(root.parentFile), request.generation.toString(), kind)
    }
    private fun intent(app: Context, request: Original24Request) = storage(app, request, CaptureJournalFile.MANUAL_REQUEST).read(ManualOriginalTrialRequestCodec.MAX_BYTES)
    private fun publication(app: Context, request: Original24Request) = storage(app, request, CaptureJournalFile.JPEG_PUBLICATION)
        .read(JpegPublicationCodec.MAX_BYTES)?.let(JpegPublicationCodec::decode)
    private fun strengthDisplayed(value: Double): Boolean {
        // Inspect the actual disabled/enabled strength control, not the immutable backend handle.
        fun texts(node: AccessibilityNodeInfo): List<String> = buildList {
            node.text?.toString()?.let { addAll(it.split('\n')) }
            for (i in 0 until node.childCount) node.getChild(i)?.let { addAll(texts(it)) }
        }
        fun find(node: AccessibilityNodeInfo?): Boolean {
            if (node == null || !node.refresh()) return false
            val own = node.text?.toString()?.split('\n').orEmpty()
            if ("STRENGTH" in own) {
                // Compose may merge the button's text; otherwise use the immediate text column.
                val labels = if (own.size > 1) own else node.parent?.let(::texts).orEmpty()
                return trialStrengthLabel(value) in labels && "表示中" in labels &&
                    labels.none { it.contains("未反映") || it.startsWith("表示中 ") }
            }
            for (i in 0 until node.childCount) if (find(node.getChild(i))) return true
            return false
        }
        instrumentation.uiAutomation.clearCache()
        return find(instrumentation.uiAutomation.rootInActiveWindow)
    }
    private fun action(label: String): AccessibilityNodeInfo? {
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null || !node.refresh()) return null
            if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
            for (i in 0 until node.childCount) find(node.getChild(i))?.let { return it }
            return null
        }
        instrumentation.uiAutomation.clearCache()
        var node = find(instrumentation.uiAutomation.rootInActiveWindow)
        while (node != null && !node.isClickable && node.isEnabled) node = node.parent
        return node?.takeIf { it.refresh() && it.isVisibleToUser && it.isClickable && it.isEnabled }
    }
    private fun click(label: String) {
        var node: AccessibilityNodeInfo? = null
        await(label) { action(label).also { node = it } != null }
        assertTrue(requireNotNull(node).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }
    private fun await(label: String, timeout: Long = 60_000, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeout
        do {
            instrumentation.waitForIdleSync()
            if (condition()) return
            Thread.sleep(25)
        } while (SystemClock.elapsedRealtime() < until)
        error("Recovery UI did not reach: $label")
    }
}
