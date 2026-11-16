package dev.cinematic.p0.gpu.golden

import android.graphics.Bitmap
import android.os.Build
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.gpu.*
import dev.cinematic.p0.jpegrender.original.Original24Preflight
import dev.cinematic.p0.jpegrender.original.Original24PreviewSession
import dev.cinematic.p0.jpegrender.original.PreviewRasterMode
import dev.cinematic.p0.raw.PhotoAdjustments
import dev.cinematic.p0.rawcapture.DngContentVerifier
import dev.cinematic.p0.rawcapture.OriginalGrant
import dev.cinematic.p0.rawcapture.SharedOriginalReference
import dev.cinematic.p0.testing.writeSyntheticTrialDng
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** 36 representative cases complement, not replace, the original 90-case matrix. */
class LargePreviewHalfInputAndroidTest {
    @Test fun compareTwelveAndTwentyFourMegapixelRawCoordinateStimuli() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("largePreviewHalf") == "true")
        check(args.getString("isolated") == "true")
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Synthetic emulator only" }
        val runId = checkNotNull(args.getString("largePreviewHalfRunId"))
        require(runId.matches(Regex("[A-Za-z0-9_-]{1,80}")))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(checkNotNull(context.getExternalFilesDir("preview-half-input")), runId)
        check(!root.exists() && root.mkdirs())
        val cases = JSONArray()
        val report = JSONObject().put("schema", 1).put("matrixVersion", "large-raw-36-v1")
            .put("status", "incomplete").put("productAdopted", false).put("cases", cases)
            .put("input", "direct RAW-coordinate synthetic RGGB, identity DNG ColorMatrix, neutral camera WB; not calibrated camera or skin")
            .put("timing", "quality comparison only; no Pixel or display timing conclusion")
        fun persist() = File(root, "comparison.json").writeText(report.toString(2))
        persist()
        val golden = GpuGoldenAndroidTest()
        val metrics = PreviewHalfInputComparisonAndroidTest()
        try {
            GlThread().use { thread -> thread.call {
                EglContexts(thread, context.assets).use { egl ->
                    GlCapabilities.inspect(egl).requireSupported()
                    for ((width, height) in listOf(4000 to 3000, 6000 to 4000)) {
                        check(width.toLong() * height <= 24_000_000)
                        for (kind in LargeBayerStimuli.Kind.entries) {
                            val file = File(root, "synthetic-input.dng")
                            check(!file.exists())
                            file.outputStream().buffered().use { stream ->
                                writeSyntheticTrialDng(stream, width, height, 1) { x, y ->
                                    LargeBayerStimuli.sample(kind, x, y, width, height)
                                }
                            }
                            val source = SharedOriginalReference("content://example.test/large-half/synthetic",
                                DngContentVerifier.identify(file.length(), { file.inputStream() }), OriginalGrant.USER_SELECTED)
                            val geometry = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use {
                                Original24Preflight.inspect(it.fd, CancellationSignal())
                            }
                            fun load(mode: PreviewRasterMode) = Original24PreviewSession(source,
                                File(root, "session-${mode.name}"), {}, geometry, rasterMode = mode) { file.inputStream() }.use {
                                it.loadGpuAsShotInput().also { _ -> assertFalse(it.hasUnreleasedResources) }
                            }
                            val fullInput = load(PreviewRasterMode.FULL)
                            val halfInput = load(PreviewRasterMode.HALF_CANDIDATE)
                            val expected = GpuPreviewInputSize.fit(width, height)
                            assertEquals(expected.width, fullInput.width); assertEquals(expected.height, fullInput.height)
                            assertEquals(fullInput.width, halfInput.width); assertEquals(fullInput.height, halfInput.height)
                            val params = listOf(GpuRenderParams(0.0, PhotoAdjustments(0.0, 0.0)),
                                GpuRenderParams(.5, PhotoAdjustments(0.0, 0.0)),
                                GpuRenderParams(1.0, PhotoAdjustments(100.0, 2.0)))
                            for (value in params) {
                                val id = "l%03d".format(java.util.Locale.ROOT, cases.length() + 1)
                                val full = golden.preview(egl, fullInput, value)
                                val half = golden.preview(egl, halfInput, value)
                                assertEquals(full.size, half.size)
                                val record = JSONObject().put("id", id).put("kind", kind.name)
                                    .put("strength", value.strength).put("warmth", value.adjustments.warmth)
                                    .put("ev", value.adjustments.exposureEv).put("rawWidth", width).put("rawHeight", height)
                                    .put("comparisonWidth", fullInput.width).put("comparisonHeight", fullInput.height)
                                    .put("analysis", "production-photo-analysis-in-both-paths")
                                    .put("metrics", metrics.metrics(full, half))
                                for ((mode, pixels) in listOf("full" to full, "half" to half)) {
                                    val filename = "$id-$mode.png"
                                    val bitmap = Bitmap.createBitmap(pixels, fullInput.width, fullInput.height, Bitmap.Config.ARGB_8888)
                                    try { File(root, filename).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
                                    finally { bitmap.recycle() }
                                    record.put(mode, filename)
                                }
                                cases.put(record); persist()
                            }
                            DngContentVerifier.verify(source.fingerprint, { file.inputStream() })
                            check(file.delete())
                        }
                    }
                }
            } }
            check(cases.length() == 36)
            report.put("status", "complete")
        } finally { persist() }
    }

    @Test fun streamingStimuliAreBoundedDeterministicAndContainRawScaleDetail() {
        for (kind in LargeBayerStimuli.Kind.entries) for ((w, h) in listOf(4000 to 3000, 6000 to 4000)) {
            for ((x, y) in listOf(0 to 0, 1 to 0, w / 2 to h / 2, w - 1 to h - 1)) {
                val sample = LargeBayerStimuli.sample(kind, x, y, w, h)
                assertTrue(sample in 64..4095)
                assertEquals(sample, LargeBayerStimuli.sample(kind, x, y, w, h))
            }
        }
        // Native-pixel high frequency does not become a larger nearest-neighbour thumbnail block.
        assertNotEquals(LargeBayerStimuli.sample(LargeBayerStimuli.Kind.MICRO_DETAIL, 0, 0, 6000, 4000),
            LargeBayerStimuli.sample(LargeBayerStimuli.Kind.MICRO_DETAIL, 1, 0, 6000, 4000))
    }
}