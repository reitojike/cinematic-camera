package dev.cinematic.p0.gpu

import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import dev.cinematic.p0.gpu.preview.GpuPreviewRenderer
import dev.cinematic.p0.gpu.preview.GpuPreviewSnapshot
import dev.cinematic.p0.rawcapture.DngContentVerifier
import dev.cinematic.p0.rawcapture.OriginalFingerprint
import dev.cinematic.p0.testing.writeSyntheticTrialDng
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.regex.Pattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real DocumentsUI selection, separate from fake SAF delivery and wrapped-provider tests.
 * Only this test's generated, non-photographic DNG is selected. No resource/admission overrides.
 * https://developer.android.com/training/testing/other-components/ui-automator
 * https://developer.android.com/training/data-storage/shared/media
 */
@RunWith(AndroidJUnit4::class)
class GpuDiagnosticFilesSelectionTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val target get() = instrumentation.targetContext
    private val device get() = UiDevice.getInstance(instrumentation)
    private var waitingFor = "setup"
    private var conditionBoundMs = 10_000L
    private lateinit var evidence: File

    @Test fun filesSelectsSyntheticDngAndRendersWithoutChangingOriginal() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Synthetic emulator only" }
        check(target.packageName == "dev.cinematic.p0.gpudiagnostic")
        evidence = File(checkNotNull(target.getExternalFilesDir("gpu-diagnostics")),
            "files-ui/filesSelectsSyntheticDng-${SystemClock.elapsedRealtime()}")
        check(evidence.mkdirs() || evidence.isDirectory)
        val resolver = target.contentResolver
        val name = "gpu-synthetic-${UUID.randomUUID()}.dng"
        var uri: Uri? = null
        var fingerprint: OriginalFingerprint? = null
        var scenario: ActivityScenario<GpuPreviewDiagnosticActivity>? = null
        var failure: Throwable? = null
        try {
            waitingFor = "create owned synthetic document"
            uri = checkNotNull(resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/x-adobe-dng")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                })) { "Synthetic document insert failed" }
            val owned = uri
            val bytes = checkNotNull(resolver.openOutputStream(owned, "w")).use {
                writeSyntheticTrialDng(it, 64, 48, 1)
            }
            fingerprint = DngContentVerifier.identify(bytes, { checkNotNull(resolver.openInputStream(owned)) })
            assertEquals("Existing 64x48 synthetic generator byte count", 6530L, fingerprint.bytes)
            assertEquals("Existing synthetic fixture SHA-256",
                "c2bea28b28d64a08df77443ce2b9eb3083e670c1fa295465907c76f60d4a4926", fingerprint.sha256)
            assertEquals(1, resolver.update(owned, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null))
            val identity = "generator=writeSyntheticTrialDng\nwidth=64\nheight=48\norientation=1\n" +
                "sample=164+(x*65+y*25)%3500\nbytes=$bytes\nsha256=${fingerprint.sha256}\n"
            File(evidence, "fixture.txt").writeText(identity)
            Log.i(TAG, "Synthetic DNG 64x48 orientation=1 bytes=$bytes sha256=${fingerprint.sha256}")

            scenario = ActivityScenario.launch(GpuPreviewDiagnosticActivity::class.java)
            val rendererRef = AtomicReference<GpuPreviewRenderer>()
            scenario.onActivity { rendererRef.set(it.renderer) }
            val renderer = checkNotNull(rendererRef.get())
            awaitFrame(renderer, 0L, "initial synthetic frame")
            val picker = find("app Files button", By.pkg(target.packageName).text("試験用DNGをFilesから選択"), Direction.DOWN)
            val beforePick = snapshot(renderer).drawCount
            picker.click()
            await("DocumentsUI foreground") {
                device.currentPackageName?.endsWith(".documentsui") == true
            }
            val filesPackage = checkNotNull(device.currentPackageName)
            openInternalDownload(filesPackage)
            find("owned synthetic DNG in internal Download", By.pkg(filesPackage).text(name), Direction.DOWN)
            captureEvidence("files-selection")
            // Re-observe after evidence collection; never click an arbitrary file or inject a result.
            find("owned synthetic DNG selection", By.pkg(filesPackage).text(name)).click()
            await("app returned from Files") { device.currentPackageName == target.packageName }
            val expectedStatus = "DNG AS_SHOT入力: 64 × 48（長辺1024・786,432画素以下）"
            // Same 60s native-decode bound as GpuDiagnosticDngPipelineTest, not a fixed delay.
            await("native DNG AS_SHOT 64x48 status", 60_000) {
                check(!device.hasObject(By.pkg(target.packageName).textStartsWith("DNGを読み込めません"))) {
                    "Native DNG input failed; resource/admission conditions were not changed"
                }
                if (device.hasObject(By.pkg(target.packageName).text(expectedStatus))) true else {
                    if (device.currentPackageName == target.packageName &&
                        !device.hasObject(By.pkg(target.packageName).textStartsWith("DNG原本"))) scroll(target.packageName)
                    false
                }
            }
            // Resuming Files can redraw the old synthetic input. Require another successful frame
            // requested through real UI only after the exact DNG load status has been observed.
            val afterLoad = snapshot(renderer).drawCount
            find("rotation control after DNG load", By.pkg(target.packageName).text("回転 0°"), Direction.DOWN).click()
            find("DNG rotation changed to 90 degrees", By.pkg(target.packageName).text("回転 90°"))
            find("loaded DNG status after rotation", By.pkg(target.packageName).text(expectedStatus), Direction.UP)
            val rendered = awaitFrame(renderer, afterLoad, "new successful DNG frame after rotation")
            assertTrue("Selected DNG must also render after the pre-pick frame", rendered.drawCount > beforePick)
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            assertEquals("Diagnostic app must own the active window", target.packageName,
                instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString())
            DngContentVerifier.verify(fingerprint, { checkNotNull(resolver.openInputStream(owned)) })
            File(evidence, "result.txt").writeText("status=DNG AS_SHOT 64x48\n" +
                "drawCountBefore=$beforePick\ndrawCountAfterLoad=$afterLoad\ndrawCountAfter=${rendered.drawCount}\n" +
                "windowCount=${rendered.windowCount}\nallocatedBytes=${rendered.allocatedBytes}\n" +
                "rotationDegrees=90\nresumed=true\nactiveApp=true\noriginalUnchanged=true\n")
            captureEvidence("app-loaded")
        } catch (caught: Throwable) {
            failure = caught
            runCatching { captureEvidence("failure") }.exceptionOrNull()?.let(caught::addSuppressed)
        } finally {
            // Capture first, while the failing window exists. On failure, pause cancels an in-flight
            // native load before release waits on its existing serial IO owner. No other URI is deleted.
            try {
                if (failure != null) scenario?.moveToState(Lifecycle.State.CREATED)
                waitingFor = "release loader and verify owned document before cleanup"
                GpuDiagnosticDngInput.release(target).get(30, TimeUnit.SECONDS)
                uri?.let { owned ->
                    fingerprint?.let { DngContentVerifier.verify(it, { checkNotNull(resolver.openInputStream(owned)) }) }
                    assertEquals("Delete only this test's inserted row", 1, resolver.delete(owned, null, null))
                }
            } catch (cleanup: Throwable) {
                runCatching { captureEvidence("cleanup-failure") }.exceptionOrNull()?.let(cleanup::addSuppressed)
                if (failure == null) failure = cleanup else failure.addSuppressed(cleanup)
            } finally {
                try { scenario?.close() } catch (close: Throwable) {
                    if (failure == null) failure = close else failure.addSuppressed(close)
                }
            }
        }
        failure?.let { throw it }
    }

    private fun openInternalDownload(filesPackage: String) {
        // Downloads/Recent roots can return a different provider, which the app deliberately rejects.
        // Observe the actual DocumentsUI package and the emulator's device label; select primary
        // storage via the drawer, then its real Download directory. Do not alter the OpenDocument intent.
        val labels = listOfNotNull(Build.MODEL,
            Settings.Global.getString(target.contentResolver, "device_name"),
            "Internal storage", "Internal shared storage", "内部ストレージ", "内部共有ストレージ")
        val internalRoot = By.pkg(filesPackage).text(Pattern.compile(labels.distinct().joinToString("|") { Pattern.quote(it) }))
        fun openRoots() {
            if (!device.hasObject(By.res(filesPackage, "roots_list"))) {
                find("Files navigation drawer", By.pkg(filesPackage).clazz("android.widget.ImageButton")
                    .desc(Pattern.compile("Show roots|Open navigation drawer|ルートを表示|ナビゲーション ドロワーを開く"))).click()
            }
            find("Files roots list", By.res(filesPackage, "roots_list"))
        }
        openRoots()
        if (!device.hasObject(internalRoot)) {
            device.pressBack()
            find("Files overflow menu", By.pkg(filesPackage)
                .desc(Pattern.compile("More options|その他のオプション"))).click()
            find("show internal storage menu", By.pkg(filesPackage)
                .text(Pattern.compile("Show internal storage|内部ストレージを表示"))).click()
            openRoots()
        }
        find("internal storage root", internalRoot).click()
        find("internal Download directory", By.pkg(filesPackage).text(Environment.DIRECTORY_DOWNLOADS), Direction.DOWN).click()
    }

    private fun find(label: String, selector: BySelector, scrollDirection: Direction? = null): UiObject2 {
        var result: UiObject2? = null
        await(label) {
            result = device.findObject(selector)
            if (result != null) true else {
                if (scrollDirection != null) device.currentPackageName?.let { scroll(it, scrollDirection) }
                false
            }
        }
        return checkNotNull(result)
    }

    private fun scroll(packageName: String, direction: Direction = Direction.DOWN) {
        device.findObject(By.pkg(packageName).scrollable(true))?.scroll(direction, 0.5f)
    }

    private fun snapshot(renderer: GpuPreviewRenderer): GpuPreviewSnapshot {
        val latest = AtomicReference<GpuPreviewSnapshot>()
        renderer.snapshot { latest.set(it) }
        await("renderer snapshot") { latest.get() != null }
        return checkNotNull(latest.get())
    }

    private fun awaitFrame(renderer: GpuPreviewRenderer, after: Long, label: String): GpuPreviewSnapshot {
        val latest = AtomicReference<GpuPreviewSnapshot>()
        await(label) {
            renderer.snapshot { latest.set(it) }
            latest.get()?.let { it.drawCount > after && it.windowCount == 1 && it.allocatedBytes > 0 } == true
        }
        return checkNotNull(latest.get())
    }

    private fun await(label: String, boundMs: Long = 10_000, condition: () -> Boolean) {
        // Existing UI/GL tests allow 10s for local transitions. Poll state rather than sleeping for it.
        waitingFor = label
        conditionBoundMs = boundMs
        Log.i(TAG, "WAIT $label boundMs=$boundMs")
        val deadline = SystemClock.elapsedRealtime() + boundMs
        do {
            if (condition()) return
            SystemClock.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AssertionError("Timed out: $label (boundMs=$boundMs)")
    }

    private fun captureEvidence(stage: String) {
        val folder = File(evidence, stage).apply { check(mkdirs() || isDirectory) }
        var failure: Throwable? = null
        fun collect(action: () -> Unit) {
            try { action() } catch (caught: Throwable) {
                if (failure == null) failure = caught else failure!!.addSuppressed(caught)
            }
        }
        collect { File(folder, "wait-state.txt").writeText("stage=$stage\nwaitingFor=$waitingFor\nconditionBoundMs=$conditionBoundMs\n") }
        collect { device.dumpWindowHierarchy(File(folder, "window.xml")) }
        collect { assertTrue("UI screenshot capture failed", device.takeScreenshot(File(folder, "screen.png"))) }
        // Raw XML/images/logs are local evidence only. Publish redacted state/hash summaries, never
        // document names, provider URIs or personal paths. UiDevice does not shell-unquote '*:S'.
        collect { File(folder, "logcat.txt").writeText(device.executeShellCommand(
            "logcat -d -v brief -t 300 ActivityTaskManager:I ActivityManager:I GpuFilesTest:I AndroidRuntime:E *:S")) }
        Log.i(TAG, "UI evidence saved: stage=$stage waitingFor=$waitingFor")
        failure?.let { throw it }
    }

    private companion object { const val TAG = "GpuFilesTest" }
}
