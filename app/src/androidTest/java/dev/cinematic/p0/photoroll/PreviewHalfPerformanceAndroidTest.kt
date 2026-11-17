package dev.cinematic.p0.photoroll

import android.os.Build
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.BuildConfig
import dev.cinematic.p0.gpu.GpuPreviewInputSize
import dev.cinematic.p0.gpu.preview.GpuPreviewSurface
import dev.cinematic.p0.jpegrender.original.*
import dev.cinematic.p0.metrics.*
import dev.cinematic.p0.photoedit.PhotoAdjustmentPolicy
import dev.cinematic.p0.rawcapture.*
import dev.cinematic.p0.testing.writeSyntheticTrialDng
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Synthetic main-image FULL vs half, NOT half guide + FULL, and NOT quality readback timing.
 * Same collector start in LaunchedEffect(session) and same onPresented normal-swap endpoint as
 * #456. Repository/admission are replaced by a local synthetic check: not full editor latency. */
class PreviewHalfPerformanceAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    @get:Rule val fixture = SyntheticFixtureRule()

    @Test fun syntheticMainInputAbbaThroughNormalSwap() {
        val args = InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue(args.getString("previewHalfPerformance") == "true")
        check(args.getString("isolated") == "true")
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Synthetic emulator only" }
        check(BuildConfig.DEBUG && BuildConfig.APPLICATION_ID.endsWith(".initialpreviewcompare"))
        check(!BuildConfig.INITIAL_HALF_GUIDE) { "No guide path in main-input performance comparison" }
        val megapixels = args.getString("previewHalfMegapixels") ?: "12"
        val (width, height) = when (megapixels) {
            "12" -> 4000 to 3000
            "24" -> 6000 to 4000
            else -> error("Only fixed synthetic 12/24 MP fixtures are permitted")
        }
        val context = instrumentation.targetContext
        val root = File.createTempFile("half-performance-", ".tmp", context.cacheDir.canonicalFile)
        check(root.delete() && root.mkdir())
        val input = File(root, "synthetic.dng")
        input.outputStream().use { writeSyntheticTrialDng(it, width, height, 1) }
        val fingerprint = DngContentVerifier.identify(input.length(), { input.inputStream() })
        val source = SharedOriginalReference("content://example.test/half-performance/synthetic",
            fingerprint, OriginalGrant.USER_SELECTED)
        val output = File(checkNotNull(context.getExternalFilesDir("half-performance")), "synthetic-${megapixels}mp.json")
        val runs = JSONArray()
        val report = JSONObject().put("schema", 1).put("status", "incomplete")
            .put("scope", "synthetic LaunchedEffect OPEN to normal GPU swap; not full editor/provider/admission latency")
            .put("order", "ABBA; A=FULL main input, B=HALF_CANDIDATE main input; no guide")
            .put("owner", "fresh GL owner each run; filesystem/driver caches uncontrolled; no forced GC")
            .put("meaning", "exploratory emulator observations; no Pixel performance or adoption claim")
            .put("rawWidth", width).put("rawHeight", height).put("runs", runs)
        fun persist() { output.writeText(report.toString(2)) }
        persist()
        var complete = false
        try {
            for ((index, mode) in listOf(PreviewRasterMode.FULL, PreviewRasterMode.HALF_CANDIDATE,
                PreviewRasterMode.HALF_CANDIDATE, PreviewRasterMode.FULL).withIndex()) {
                runs.put(run(source, input, File(root, "run-$index"), mode, width, height).put("index", index))
                persist()
            }
            DngContentVerifier.verify(fingerprint, { input.inputStream() })
            report.put("status", "complete"); persist(); complete = true
        } finally {
            GpuPhotoRollRetiredOwners.releaseBeforeOpen()
            // Never recursively remove unknown or incompletely released owners after failure.
            if (complete) { check(input.delete()); check(root.delete()) }
        }
    }

    private fun run(source: SharedOriginalReference, input: File, scratch: File,
        mode: PreviewRasterMode, width: Int, height: Int): JSONObject {
        val context = instrumentation.targetContext
        val thermal = context.getSystemService(PowerManager::class.java)
        val owner = GpuPhotoRollSession(context.assets)
        val rows = CompletableFuture<List<GpuPreviewTimingRecord>>()
        val startThermal = java.util.concurrent.atomic.AtomicInteger(-1)
        val endThermal = java.util.concurrent.atomic.AtomicInteger(-1)
        val sessionRef = java.util.concurrent.atomic.AtomicReference<Original24PreviewSession?>()
        val signal = CancellationSignal()
        val started = java.util.concurrent.atomic.AtomicBoolean()
        val finished = java.util.concurrent.CountDownLatch(1)
        fixture.content = {
            GpuPreviewSurface(owner.renderer, Modifier.fillMaxSize())
            LaunchedEffect(owner) {
                started.set(true)
                // Same logical start as GpuPhotoRollEditor, before check/preflight/input IO.
                val timing = checkNotNull(GpuPreviewTiming.start(1, {
                    endThermal.set(thermal.currentThermalStatus); rows.complete(it); Unit
                }))
                owner.openTiming = timing
                startThermal.set(thermal.currentThermalStatus)
                try {
                    val image = withContext(Dispatchers.IO) {
                        timing.measure(GpuPreviewTimingStage.CHECK_ORIGINAL) {
                            DngContentVerifier.verify(source.fingerprint, { input.inputStream() }, signal::isCanceled)
                        }
                        val geometry = timing.measure(GpuPreviewTimingStage.PREFLIGHT) {
                            ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY).use {
                                Original24Preflight.inspect(it.fd, signal)
                            }
                        }
                        timing.pixels(geometry.outputWidth, geometry.outputHeight)
                        val expected = GpuPreviewInputSize.fit(width, height)
                        assertEquals(expected, GpuPreviewInputSize.fit((width + 1) / 2, (height + 1) / 2))
                        Original24PreviewSession(source, scratch, {}, geometry, rasterMode = mode,
                            timing = timing) { input.inputStream() }.also { sessionRef.set(it) }.use {
                            it.loadGpuAsShotInput(signal::isCanceled).also { image ->
                                assertEquals(expected.width, image.width); assertEquals(expected.height, image.height)
                                assertFalse(it.hasUnreleasedResources)
                            }
                        }.also { sessionRef.set(null) }
                    }
                    timing.firstFrameStarted()
                    owner.submit(PhotoAdjustmentPolicy.defaults)
                    owner.renderer.openPhoto(image)
                } catch (error: Throwable) { timing.failed(error) }
                finally { finished.countDown() }
            }
        }
        try {
            ActivityScenario.launch(PhotoRollActivity::class.java).use {
                val records = rows.get(180, TimeUnit.SECONDS).associateBy { it.stage }
                assertEquals(GpuPreviewTimingOutcome.PRESENTED, records.getValue(GpuPreviewTimingStage.OPEN).outcome)
                assertEquals(GpuPreviewTimingOutcome.NOT_REACHED, records.getValue(GpuPreviewTimingStage.GUIDE).outcome)
                val result = JSONObject().put("mode", mode.name).put("thermalStart", startThermal.get())
                    .put("thermalEnd", endThermal.get()).put("stages", JSONArray())
                val stages = result.getJSONArray("stages")
                for (stage in GpuPreviewTimingStage.entries) {
                    val record = records.getValue(stage)
                    if (stage != GpuPreviewTimingStage.GUIDE) assertNotNull(record.nanos)
                    stages.put(JSONObject().put("stage", stage.name).put("outcome", record.outcome.name)
                        .put("nanos", record.nanos ?: JSONObject.NULL))
                }
                return result
            }
        } finally {
            signal.cancel(); sessionRef.get()?.requestStop()
            try {
                if (started.get()) check(finished.await(30, TimeUnit.SECONDS)) { "Synthetic input owner did not drain" }
                sessionRef.get()?.let { it.close(); check(!it.hasUnreleasedResources) }
            } finally {
                instrumentation.runOnMainSync { fixture.content = null; owner.close() }
                check(owner.renderer.awaitClosed(10_000))
                GpuPhotoRollRetiredOwners.releaseBeforeOpen()
            }
        }
    }
}
