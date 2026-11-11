package dev.cinematic.p0.gpu

import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.Settings
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
import dev.cinematic.p0.raw.LinearSrgbImage
import dev.cinematic.p0.raw.PhotoAdjustments
import dev.cinematic.p0.rawcapture.DngContentVerifier
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.regex.Pattern

/** Memory-only real Files path. No DNG creation/deletion, permission grant, input/admission override,
 * image export, CPU reference render or speed claim. APK/source/fixture approval precedes execution. */
@RunWith(AndroidJUnit4::class)
class GpuGate1Part2DngLedgerTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val target get() = instrumentation.targetContext
    private val device get() = UiDevice.getInstance(instrumentation)
    private var lastPhase = "setup"

    @Test fun measureSelectedDngLedgerAndRelease() {
        gate1Part2OptIn("dev.cinematic.p0.gpu.GpuGate1Part2DngLedgerTest#measureSelectedDngLedgerAndRelease", "dng-ledger-v1")
        try { runMeasurement() } catch (_: Throwable) {
            // Also sanitize setup/report-write failures. Never attach the original cause/suppressed.
            throw AssertionError("Gate 1 DNG ledger failed; see private metadata report")
        }
    }

    private fun runMeasurement() {
        val args = InstrumentationRegistry.getArguments()
        val documentId = checkNotNull(args.getString("gate1DngDocumentId"))
        // The exact preregistered public/synthetic file must already exist. No arbitrary file fallback.
        check(documentId.matches(Regex("primary:Download/[A-Za-z0-9_.-]+\\.dng")))
        val name = documentId.substringAfterLast('/')
        val expectedHash = checkNotNull(args.getString("gate1DngSha256")).lowercase()
        check(expectedHash.matches(Regex("[a-f0-9]{64}")))
        val expectedBytes = checkNotNull(args.getString("gate1DngBytes")).toLong().also { check(it > 0) }
        val expectedWidth = checkNotNull(args.getString("gate1DngWidth")).toInt()
        val expectedHeight = checkNotNull(args.getString("gate1DngHeight")).toInt()
        check(maxOf(expectedWidth, expectedHeight) == 1024)
        GpuPreviewInputSize.checkSupported(expectedWidth, expectedHeight)
        val uri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", documentId)
        val rows = JSONArray()
        val report = JSONObject().put("version", "dng-ledger-v1").put("frameMode", "FULL_RECOMPUTE")
            .put("measurement", "DNG lifecycle ledger; discrete render params, not gesture or speed comparison")
            .put("peakScope", "reset once while empty before Files; DNG load through explicit close; never reset within DNG interval")
            .put("samples", rows)
        val output = File(checkNotNull(target.getExternalFilesDir("gpu-diagnostics")), "gate1-dng-ledger-v1.json")
        val initialEnvironment = environment()
        report.put("start", initialEnvironment)
        check(initialEnvironment.getInt("thermalStatus") in 0..PowerManager.THERMAL_STATUS_LIGHT)
        check(initialEnvironment.getBoolean("interactive"))
        var scenario: ActivityScenario<GpuPreviewDiagnosticActivity>? = null
        var renderer: GpuPreviewRenderer? = null
        var failure: Throwable? = null
        try {
            val active = ActivityScenario.launch(GpuPreviewDiagnosticActivity::class.java).also { scenario = it }
            val reference = AtomicReference<GpuPreviewRenderer>()
            active.onActivity { reference.set(it.renderer) }
            val render = checkNotNull(reference.get()).also { renderer = it }
            val synthetic = awaitLedger(render, "initial synthetic render") { it.snapshot.drawCount > 0 && it.bytes > 0 }
            rows.put(synthetic.json("initialSynthetic")) // Preserve this peak, do not silently discard it.
            render.closePhoto()
            rows.put(awaitLedger(render, "initial synthetic closed") { it.bytes == 0L && it.textures == 0 }.json("initialSyntheticClosed"))
            val reset = ledger(render, resetPeakWhileEmpty = true)
            assertEquals(0L, reset.bytes); assertEquals(0, reset.textures); assertEquals(0L, reset.peak)
            rows.put(reset.json("dngIntervalStart"))

            find("Files button", By.pkg(target.packageName).text("試験用DNGをFilesから選択"), Direction.DOWN).click()
            await("DocumentsUI foreground") { device.currentPackageName?.endsWith(".documentsui") == true }
            val filesPackage = checkNotNull(device.currentPackageName)
            openInternalDownload(filesPackage)
            find("preregistered DNG", By.pkg(filesPackage).text(name), Direction.DOWN).click()
            await("diagnostic app returned") { device.currentPackageName == target.packageName }
            await("native DNG load", 60_000) {
                val state = AtomicReference<GpuPreviewDiagnosticActivity.DiagnosticState>()
                active.onActivity { state.set(it.diagnosticState()) }
                val observed = checkNotNull(state.get())
                check(observed.resumed && !observed.gpuError)
                check(observed.inputResult !in setOf("load_error", "readiness_timeout"))
                observed.inputResult == "loaded" && !observed.loading
            }
            check(target.checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION)
                == PackageManager.PERMISSION_GRANTED) { "Expected exact Files result read grant" }
            val fingerprint = DngContentVerifier.identify(expectedBytes, { checkNotNull(target.contentResolver.openInputStream(uri)) })
            assertEquals(expectedHash, fingerprint.sha256)
            assertEquals(expectedBytes, fingerprint.bytes)
            report.put("sourceBytes", fingerprint.bytes).put("sourceSha256", fingerprint.sha256)
            val afterLoad = ledger(render, captureInputIdentity = true)
            assertEquals(expectedWidth, afterLoad.inputWidth); assertEquals(expectedHeight, afterLoad.inputHeight)
            rows.put(afterLoad.json("nativeAsShotLoaded"))
            report.put("cpuAsShotComparison", "not performed; identity recorded for separate CPU comparison")
            report.put("asShotFloatEncoding", "SHA-256 of RGB float raw bits, little endian, row-major; no extra photo copy")

            val conditions = listOf(
                GpuRenderParams(.5, PhotoAdjustments.NONE),
                GpuRenderParams(1.0, PhotoAdjustments.NONE),
                GpuRenderParams(.5, PhotoAdjustments(60.0, 0.0)),
                GpuRenderParams(.5, PhotoAdjustments(0.0, 1.0)),
                GpuRenderParams(1.0, PhotoAdjustments(60.0, 1.0)),
            )
            for ((index, params) in conditions.withIndex()) {
                requireForegroundStable(active, initialEnvironment)
                val before = ledger(render).snapshot.drawCount
                render.submit(params, GpuDisplayOptions(false, 0))
                val rendered = awaitLedger(render, "DNG full frame $index") { it.snapshot.drawCount > before && it.bytes > 0 && it.settled && it.requestedParams == params }
                assertTrue(rendered.peak <= rendered.budget)
                rows.put(rendered.json("dngFrame$index").put("strength", params.strength)
                    .put("warmth", params.adjustments.warmth).put("exposureEv", params.adjustments.exposureEv))
                requireForegroundStable(active, initialEnvironment)
            }
            render.closePhoto()
            val closed = awaitLedger(render, "DNG GPU close") { it.bytes == 0L && it.textures == 0 }
            rows.put(closed.json("dngClosed"))
            assertTrue(closed.peak > 0L && closed.peak <= closed.budget)
            GpuDiagnosticDngInput.release(target).get(30, TimeUnit.SECONDS)
            DngContentVerifier.verify(fingerprint, { checkNotNull(target.contentResolver.openInputStream(uri)) })
            report.put("originalUnchanged", true).put("outcome", "completed")
        } catch (caught: Throwable) {
            failure = caught
            report.put("outcome", "failed_or_stopped").put("failureClass", caught.javaClass.simpleName).put("failurePhase", lastPhase)
        } finally {
            try {
                renderer?.let { render ->
                    render.closePhoto()
                    rows.put(awaitLedger(render, "final GPU cleanup") { it.bytes == 0L && it.textures == 0 }.json("finalCleanup"))
                }
                GpuDiagnosticDngInput.release(target).get(30, TimeUnit.SECONDS)
            } catch (cleanup: Throwable) {
                report.put("cleanupFailureClass", cleanup.javaClass.simpleName).put("outcome", "failed_or_stopped")
                if (failure == null) failure = cleanup else failure.addSuppressed(cleanup)
            } finally {
                try { scenario?.close() } catch (closing: Throwable) {
                    report.put("closeFailureClass", closing.javaClass.simpleName).put("outcome", "failed_or_stopped")
                    if (failure == null) failure = closing else failure.addSuppressed(closing)
                }
                val end = environment()
                report.put("end", end)
                if (end.getInt("thermalStatus") > initialEnvironment.getInt("thermalStatus") ||
                    end.getInt("plugged") != initialEnvironment.getInt("plugged") ||
                    end.getInt("batteryStatus") != initialEnvironment.getInt("batteryStatus") || !end.getBoolean("interactive")) {
                    report.put("outcome", "environment_changed")
                    if (failure == null) failure = AssertionError("Environment changed")
                }
                output.writeText(report.toString(2))
            }
        }
        if (failure != null) throw AssertionError("Gate 1 DNG ledger failed; see private metadata report")
    }

    private data class Ledger(val snapshot: GpuPreviewSnapshot, val bytes: Long, val peak: Long,
        val textures: Int, val budget: Long, val inputWidth: Int? = null, val inputHeight: Int? = null,
        val inputSha: String? = null, val requestedParams: GpuRenderParams? = null, val settled: Boolean = false) {
        fun json(phase: String) = JSONObject().put("phase", phase).put("allocatedBytes", bytes)
            .put("peakBytes", peak).put("textureCount", textures).put("budgetBytes", budget)
            .put("drawCount", snapshot.drawCount).put("windowCount", snapshot.windowCount)
            .put("asShotWidth", inputWidth ?: JSONObject.NULL).put("asShotHeight", inputHeight ?: JSONObject.NULL)
            .put("asShotFloatSha256", inputSha ?: JSONObject.NULL).put("requestSettled", settled)
    }

    /** Reflection exists only in this test APK. The callback executes on the renderer's owning GL
     * queue after accepted work. Reset only the observational high-water mark, while ALL allocations
     * are zero. Source/main/CPU behavior stays byte-for-byte unchanged. Any renamed field fails closed. */
    private fun ledger(renderer: GpuPreviewRenderer, resetPeakWhileEmpty: Boolean = false,
        captureInputIdentity: Boolean = false): Ledger {
        val completed = CountDownLatch(1)
        val result = AtomicReference<Ledger>()
        val failure = AtomicReference<Throwable>()
        renderer.snapshot { snapshot ->
            try {
                val value = GpuPreviewRenderer::class.java.getDeclaredField("ledger").apply { isAccessible = true }
                    .get(renderer) as GpuMemoryLedger
                assertEquals(snapshot.allocatedBytes, value.allocatedBytes)
                if (resetPeakWhileEmpty) {
                    check(value.allocatedBytes == 0L && value.textureCount == 0)
                    GpuMemoryLedger::class.java.getDeclaredField("peakAllocatedBytes").apply { isAccessible = true }.setLong(value, 0L)
                }
                val input = if (captureInputIdentity) GpuPreviewRenderer::class.java.getDeclaredField("retainedInput")
                    .apply { isAccessible = true }.get(renderer) as LinearSrgbImage else null
                val latest = field(renderer, "latest")!!
                val state = (field(latest, "state") as AtomicReference<*>).get()!!
                val request = field(state, "value")!!
                val params = field(request, "params") as GpuRenderParams
                val settled = field(state, "dirtySince") == null && request == field(state, "displayed")
                result.set(Ledger(snapshot, value.allocatedBytes, value.peakAllocatedBytes, value.textureCount,
                    value.budgetBytes, input?.width, input?.height, input?.let(::floatHash), params, settled))
            } catch (caught: Throwable) { failure.set(caught) } finally { completed.countDown() }
        }
        check(completed.await(10, TimeUnit.SECONDS)) { "GL ledger observation timed out" }
        failure.get()?.let { throw it }
        return checkNotNull(result.get())
    }

    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(owner)

    private fun floatHash(input: LinearSrgbImage): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val sample = ByteArray(4)
        repeat(input.pixelCount * 3) { index ->
            val bits = input[index].toRawBits()
            repeat(4) { sample[it] = (bits ushr (it * 8)).toByte() }
            digest.update(sample)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun awaitLedger(renderer: GpuPreviewRenderer, label: String, predicate: (Ledger) -> Boolean): Ledger {
        var last: Ledger? = null
        await(label, 30_000) { ledger(renderer).also { last = it }.let(predicate) }
        return checkNotNull(last)
    }

    private fun requireForegroundStable(scenario: ActivityScenario<GpuPreviewDiagnosticActivity>, initial: JSONObject) {
        scenario.onActivity { check(it.diagnosticState().let { state -> state.resumed && !state.gpuError && state.inputResult == "loaded" }) }
        check(device.currentPackageName == target.packageName)
        val current = environment()
        check(current.getBoolean("interactive"))
        check(current.getInt("thermalStatus") <= initial.getInt("thermalStatus"))
        check(current.getInt("plugged") == initial.getInt("plugged"))
        check(current.getInt("batteryStatus") == initial.getInt("batteryStatus"))
    }

    private fun environment(): JSONObject {
        val power = target.getSystemService(PowerManager::class.java)
        val battery = target.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return JSONObject().put("thermalStatus", power.currentThermalStatus).put("interactive", power.isInteractive)
            .put("batteryDeciC", battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1)
            .put("plugged", battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1)
            .put("batteryStatus", battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1)
    }

    private fun openInternalDownload(filesPackage: String) {
        val labels = listOfNotNull(Build.MODEL, Settings.Global.getString(target.contentResolver, "device_name"),
            "Internal storage", "Internal shared storage", "内部ストレージ", "内部共有ストレージ")
        val rootsList = By.res(filesPackage, "roots_list")
        val internalRoot = By.pkg(filesPackage).text(Pattern.compile(labels.distinct().joinToString("|") { Pattern.quote(it) }))
            .hasAncestor(rootsList)
        fun openRoots() {
            if (!device.hasObject(rootsList)) find("Files drawer", By.pkg(filesPackage).clazz("android.widget.ImageButton")
                .desc(Pattern.compile("Show roots|Open navigation drawer|ルートを表示|ナビゲーション ドロワーを開く"))).click()
            find("Files roots", rootsList)
        }
        openRoots()
        if (!device.hasObject(internalRoot)) {
            device.pressBack()
            find("Files overflow", By.pkg(filesPackage).desc(Pattern.compile("More options|その他のオプション"))).click()
            find("show internal storage", By.pkg(filesPackage).text(Pattern.compile("Show internal storage|内部ストレージを表示"))).click()
            openRoots()
        }
        find("scoped internal root", internalRoot).click()
        await("drawer closed") { !device.hasObject(rootsList) }
        rewindDirectory(filesPackage)
        find("internal Download", By.pkg(filesPackage).text(Environment.DIRECTORY_DOWNLOADS), Direction.DOWN).click()
        rewindDirectory(filesPackage)
    }

    /** DocumentsUI retains scroll position. Scope scrolling to the actual file list, not its outer
     * ScrollView, and start at the top before searching. This never changes the picker result. */
    private fun rewindDirectory(filesPackage: String) {
        device.waitForIdle(1_000)
        await("Files directory at start") {
            val directory = device.findObject(By.res(filesPackage, "dir_list"))
            directory != null && (!directory.isScrollable || !directory.scroll(Direction.UP, 0.8f))
        }
    }

    private fun find(label: String, selector: BySelector, direction: Direction? = null): UiObject2 {
        var result: UiObject2? = null
        await(label) {
            result = device.findObject(selector)
            if (result != null) true else {
                if (direction != null) device.currentPackageName?.let { packageName ->
                    val directory = device.findObject(By.res(packageName, "dir_list").scrollable(true))
                    (directory ?: device.findObject(By.pkg(packageName).scrollable(true)))?.scroll(direction, 0.5f)
                }
                false
            }
        }
        return checkNotNull(result)
    }

    private fun await(label: String, timeoutMs: Long = 10_000, predicate: () -> Boolean) {
        lastPhase = label
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) {
            if (predicate()) return
            SystemClock.sleep(25)
        }
        error("Timed out: $label")
    }
}
