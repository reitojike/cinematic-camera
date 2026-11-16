package dev.cinematic.p0.gpu.golden

import android.graphics.Bitmap
import android.os.Build
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.gpu.*
import dev.cinematic.p0.gpu.reference.GpuReferenceImages
import dev.cinematic.p0.jpegrender.original.Original24Preflight
import dev.cinematic.p0.jpegrender.original.Original24PreviewSession
import dev.cinematic.p0.jpegrender.original.PreviewRasterMode
import dev.cinematic.p0.raw.LinearSrgbImage
import dev.cinematic.p0.rawcapture.DngContentVerifier
import dev.cinematic.p0.rawcapture.OriginalGrant
import dev.cinematic.p0.rawcapture.SharedOriginalReference
import dev.cinematic.p0.testing.writeSyntheticTrialDng
import java.io.File
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Additional RAW-input experiment, not replacement/adoption of the RGB golden baseline. */
class PreviewHalfInputComparisonAndroidTest {
    @Test fun compareSameNinetyConditionsThroughFullAndHalfBayerInputs() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("previewHalfComparison") == "true")
        check(args.getString("isolated") == "true")
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Synthetic emulator only" }
        val runId = checkNotNull(args.getString("previewHalfRunId"))
        require(runId.matches(Regex("[A-Za-z0-9_-]{1,80}")))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(checkNotNull(context.getExternalFilesDir("preview-half-input")), runId)
        check(!root.exists() && root.mkdirs())
        val records = JSONArray()
        val report = JSONObject().put("schema", 1).put("status", "incomplete")
            .put("matrixVersion", "stage-clear-90-v1-raw-input-experiment")
            .put("productAdopted", false).put("cases", records)
            .put("input", "synthetic RGGB; original RGB fixtures mapped to camera channels, peak 4 -> white")
            .put("timing", "session wall time includes snapshot/verification/cleanup; not decode-only or display latency")
        fun persist() = File(root, "comparison.json").writeText(report.toString(2))
        persist()
        val golden = GpuGoldenAndroidTest()
        val cases = golden.matrix()
        check(cases.size == 90)
        try {
            GlThread().use { thread -> thread.call {
                EglContexts(thread, context.assets).use { egl ->
                    GlCapabilities.inspect(egl).requireSupported()
                    for (case in cases) {
                        // Preserve aspect class, not the old small/export raster sizes. All experiments
                        // use the product preview cap. Even RAW dimensions avoid half ceil ambiguity.
                        val target = GpuPreviewInputSize.fit(case.width * 4, case.height * 4)
                        val rawWidth = target.width * 2
                        val rawHeight = target.height * 2
                        val input = File(root, "synthetic-input.dng")
                        writeFixture(input, case.kind, rawWidth, rawHeight)
                        val source = SharedOriginalReference("content://example.test/half-comparison/synthetic",
                            DngContentVerifier.identify(input.length(), { input.inputStream() }), OriginalGrant.USER_SELECTED)
                        val geometry = ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY).use {
                            Original24Preflight.inspect(it.fd, CancellationSignal())
                        }
                        val timings = JSONObject()
                        fun render(mode: PreviewRasterMode): Pair<LinearSrgbImage, IntArray> {
                            val start = System.nanoTime()
                            val image = Original24PreviewSession(source, File(root, "session-${mode.name}"), {}, geometry,
                                rasterMode = mode) { input.inputStream() }.use { session ->
                                session.loadGpuAsShotInput().also { assertFalse(session.hasUnreleasedResources) }
                            }
                            timings.put("${mode.name}SessionNs", System.nanoTime() - start)
                            val gpuStart = System.nanoTime()
                            val pixels = golden.preview(egl, image, case.params)
                            timings.put("${mode.name}GpuReadbackNs", System.nanoTime() - gpuStart)
                            return image to pixels
                        }
                        val (fullInput, full) = render(PreviewRasterMode.FULL)
                        val (halfInput, half) = render(PreviewRasterMode.HALF_CANDIDATE)
                        assertEquals(fullInput.width, halfInput.width)
                        assertEquals(fullInput.height, halfInput.height)
                        assertEquals(full.size, half.size)
                        val record = case.json().put("rawWidth", rawWidth).put("rawHeight", rawHeight)
                            .put("comparisonWidth", fullInput.width).put("comparisonHeight", fullInput.height)
                            .put("analysis", "production-photo-analysis-in-both-paths")
                            .put("metrics", metrics(full, half)).put("timing", timings)
                        for ((name, pixels) in listOf("full" to full, "half" to half)) {
                            val filename = "${case.id}-$name.png"
                            Bitmap.createBitmap(pixels, fullInput.width, fullInput.height, Bitmap.Config.ARGB_8888).let { bitmap ->
                                try { File(root, filename).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
                                finally { bitmap.recycle() }
                            }
                            record.put(name, filename)
                        }
                        DngContentVerifier.verify(source.fingerprint, { input.inputStream() })
                        check(input.delete())
                        records.put(record)
                        persist()
                    }
                }
            } }
            check(records.length() == 90)
            report.put("status", "complete")
        } finally { persist() }
    }

    private fun writeFixture(file: File, kind: GpuReferenceImages.Kind, width: Int, height: Int) {
        val image = GpuReferenceImages.create(kind, GpuReferenceImages.Size(width, height))
        file.outputStream().use { output -> writeSyntheticTrialDng(output, width, height, 1) { x, y ->
            val channel = if (y % 2 == 0) { if (x % 2 == 0) 0 else 1 } else { if (x % 2 == 0) 1 else 2 }
            // Deliberately synthetic camera-channel stimulus, not an inverse-camera model.
            (64 + image[(y * width + x) * 3 + channel].coerceIn(0f, 4f) / 4f * 4031).roundToInt()
        } }
    }

    internal fun metrics(a: IntArray, b: IntArray): JSONObject {
        var total = 0L; var squared = 0L; var maximum = 0; var changedPixels = 0L
        val histogram = IntArray(256)
        val shifts = intArrayOf(16, 8, 0)
        for (i in a.indices) {
            var changed = false
            for (shift in shifts) {
                val difference = abs(((a[i] ushr shift) and 255) - ((b[i] ushr shift) and 255))
                total += difference; squared += difference.toLong() * difference
                maximum = maxOf(maximum, difference); histogram[difference]++
                changed = changed || difference != 0
            }
            if (changed) changedPixels++
        }
        val count = a.size.toLong() * 3
        val mse = squared.toDouble() / count
        var cumulative = 0L
        val p99 = histogram.indices.first { cumulative += histogram[it]; cumulative >= (count * 99 + 99) / 100 }
        return JSONObject().put("maeRgb8", total.toDouble() / count).put("maxRgb8", maximum)
            .put("p99Rgb8", p99).put("rmseRgb8", kotlin.math.sqrt(mse))
            .put("psnrDb", if (mse == 0.0) JSONObject.NULL else 10 * log10(255.0 * 255.0 / mse))
            .put("changedPixelFraction", changedPixels.toDouble() / a.size)
    }
}