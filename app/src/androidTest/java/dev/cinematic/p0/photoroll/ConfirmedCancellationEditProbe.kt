package dev.cinematic.p0.photoroll

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.photoedit.AndroidPhotoEditRecipes
import dev.cinematic.p0.photoedit.PhotoEditValues
import dev.cinematic.p0.raw.PhotoAdjustments
import dev.cinematic.p0.rawcapture.AndroidManualOriginalTrialSaves
import dev.cinematic.p0.rawcapture.ManualOriginalTrialSavePhase
import dev.cinematic.p0.rawcapture.SharedOriginalReference
import dev.cinematic.p0.jpegrender.original.Original24Request
import java.util.concurrent.TimeUnit
import org.junit.Assert.*

/** Used only after the caller confirms an actual pre-JPEG synthetic cancellation. The source,
 * publication and cleanup assertions remain in Original24CancellationRetryInstrumentationTest. */
internal object ConfirmedCancellationEditProbe {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    fun publish(app: Context, source: SharedOriginalReference, name: String,
        cancelled: AndroidManualOriginalTrialSaves.ManualOriginalTrialHandle, isolatedUi: SyntheticFixtureRule? = null): AndroidManualOriginalTrialSaves.ManualOriginalTrialHandle =
        publishRequest(app, source, name, cancelled.request, cancelled, isolatedUi = isolatedUi)

    fun publishCold(app: Context, source: SharedOriginalReference, name: String,
        request: Original24Request): AndroidManualOriginalTrialSaves.ManualOriginalTrialHandle {
        assertNull(AndroidManualOriginalTrialSaves.knownPrepared(source))
        return publishRequest(app, source, name, request, null)
    }

    fun retryCold(app: Context, source: SharedOriginalReference, name: String,
        request: Original24Request): AndroidManualOriginalTrialSaves.ManualOriginalTrialHandle {
        assertNull(AndroidManualOriginalTrialSaves.knownPrepared(source))
        return publishRequest(app, source, name, request, null, changeRecipe = false)
    }

    private fun publishRequest(app: Context, source: SharedOriginalReference, name: String, request: Original24Request,
        live: AndroidManualOriginalTrialSaves.ManualOriginalTrialHandle?, changeRecipe: Boolean = true, isolatedUi: SyntheticFixtureRule? = null): AndroidManualOriginalTrialSaves.ManualOriginalTrialHandle {
        check(Build.HARDWARE in setOf("ranchu", "goldfish") && app.packageName == "dev.cinematic.p0")
        check(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        if (live != null) AndroidPhotoEditRecipes(app).save(source, PhotoEditValues(request.strength,
            request.adjustments.warmth, request.adjustments.exposureEv)).get(30, TimeUnit.SECONDS)
        cameraRollReadPermissions(Build.VERSION.SDK_INT).forEach {
            if (isolatedUi == null) instrumentation.uiAutomation.grantRuntimePermission(app.packageName, it)
            else check(app.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED)
        }
        val screen = isolatedUi?.let { IsolatedSavePhotoRollScreen(app, it) }
        isolatedFixture = isolatedUi
        var primary: Throwable? = null
        var camera: PhotoRollCameraOwners.Owner? = null
        var published: AndroidManualOriginalTrialSaves.ManualOriginalTrialHandle? = null
        try {
            instrumentation.runOnMainSync {
                camera = AndroidPhotoRollCameraObservations.register { PhotoRollCameraSnapshot(false, false, false, false) }
            }
            screen?.install()
            ActivityScenario.launch(PhotoRollActivity::class.java).use {
                click(name)
                await("confirmed cancellation remains retryable and editable") {
                    action("同じ設定で保存を再試行") != null && action("STRENGTH") != null
                }
                val cancelled = live ?: requireNotNull(AndroidManualOriginalTrialSaves.knownPrepared(source))
                assertEquals(request, cancelled.request)
                assertEquals(ManualOriginalTrialSavePhase.CANCELLED, cancelled.state.value.phase)
                if (!changeRecipe) {
                    click("同じ設定で保存を再試行")
                    await("restored same recipe publication", 180_000) {
                        cancelled.state.value.phase == ManualOriginalTrialSavePhase.PUBLISHED && ForegroundExports.gate.active() == null
                    }
                    assertEquals(request, cancelled.request)
                    published = cancelled
                    return@use
                }
                click("一覧へ戻る")
                click(name)
                click("STRENGTH")
                click("RESET")
                click("目盛りを閉じる")
                await("new recipe ready") { action("JPEGを保存") != null }
                val next = requireNotNull(AndroidManualOriginalTrialSaves.knownPrepared(source))
                assertEquals(request.generation + 1, next.request.generation)
                assertEquals(.5, next.request.strength, 0.0)
                assertEquals(PhotoAdjustments.NONE, next.request.adjustments)
                click("JPEGを保存")
                await("new recipe publication", 180_000) {
                    next.state.value.phase == ManualOriginalTrialSavePhase.PUBLISHED && ForegroundExports.gate.active() == null
                }
                assertEquals("Old cancelled recipe must never publish", ManualOriginalTrialSavePhase.CANCELLED, cancelled.state.value.phase)
                assertNull(cancelled.state.value.uri)
                published = next
            }
            GpuPhotoRollRetiredOwners.releaseBeforeOpen()
            return requireNotNull(published)
        } catch (error: Throwable) { primary = error; throw error }
        finally {
            try {
                dev.cinematic.p0.jpegrender.original.preserveSafetyCleanup(primary,
                    { screen?.close(primary) },
                    { instrumentation.runOnMainSync { camera?.close() } })
            } finally { isolatedFixture = null }
        }
    }

    private var isolatedFixture: SyntheticFixtureRule? = null
    private fun action(label: String): AccessibilityNodeInfo? {
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null || !node.refresh()) return null
            if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
            for (i in 0 until node.childCount) find(node.getChild(i))?.let { return it }
            return null
        }
        instrumentation.uiAutomation.clearCache()
        val fixture = isolatedFixture
        var node = find(if (fixture != null) fixture.fixtureRoot() else instrumentation.uiAutomation.rootInActiveWindow)
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
        val deadline = SystemClock.elapsedRealtime() + timeout
        do {
            instrumentation.waitForIdleSync()
            if (condition()) return
            Thread.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        error("Cancelled save editor did not reach: $label")
    }
}
