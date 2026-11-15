package dev.cinematic.p0.jpegrender.original

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.opengl.GLES32
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.gpu.*
import dev.cinematic.p0.gpu.diag.GpuStageTap
import dev.cinematic.p0.raw.LinearSrgbImage
import dev.cinematic.p0.raw.PhotoAdjustments
import dev.cinematic.p0.raw.R7PreviewInputs
import dev.cinematic.p0.raw.original.NativeOriginalRaster
import dev.cinematic.p0.raw.original.OriginalFields
import dev.cinematic.p0.raw.original.OriginalRasterFile
import dev.cinematic.p0.rawcapture.DngContentVerifier
import dev.cinematic.p0.rawcapture.OriginalGrant
import dev.cinematic.p0.rawcapture.SharedOriginalReference
import dev.cinematic.p0.testing.writeSyntheticTrialDng
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import android.os.Build
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic candidate comparison, not adoption, performance, publication or real-photo proof. */
@RunWith(AndroidJUnit4::class)
class HalfPreviewComparisonAndroidTest {
    private var glInfo = JSONObject()
    private fun sha(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private data class Case(val id: String, val kind: Int, val orientation: Int = 1,
        val width: Int = 2050, val height: Int = 1538, val ev: Double = 0.0, val strength: Double = .5)
    private val cases = listOf(
        Case("01-gradients-colors", 0), Case("02-fine-detail", 1),
        Case("03-stage-lights", 2, ev = -2.0), Case("04-color-edges", 3, strength = 1.0),
        Case("05-odd-portrait", 1, orientation = 6, width = 2051, height = 1539),
    )

    @Test fun captureFullHalfAndFullResolutionJpegCandidates() {
        requireGpuInputSafetyFixture()
        val args = InstrumentationRegistry.getArguments()
        check(args.getString("halfPreviewComparison") == "true")
        val sourceCommit = checkNotNull(args.getString("halfPreviewSourceCommit"))
        require(sourceCommit.matches(Regex("[a-f0-9]{40}")))
        val runId = checkNotNull(args.getString("halfPreviewRunId"))
        require(runId.matches(Regex("[A-Za-z0-9_-]{1,80}")))
        val directory = File(checkNotNull(app.getExternalFilesDir("half-preview-review")), runId)
        check(!directory.exists() && directory.mkdirs())
        val records = JSONArray()
        val manifest = JSONObject().put("schema", 1).put("status", "incomplete")
            .put("syntheticOnly", true).put("adopted", false).put("caseCount", cases.size)
            .put("sourceCommit", sourceCommit).put("runId", runId)
            .put("android", JSONObject().put("sdk", Build.VERSION.SDK_INT).put("fingerprint", Build.FINGERPRINT).put("hardware", Build.HARDWARE))
            .put("comparison", "DISPLAY8 current full / explicit half / full GPU export JPEG95; display-size JPEG uses Android bilinear scaling")
            .put("cases", records)
        val file = File(directory, "comparison.json")
        fun persist() = file.writeText(manifest.toString(2))
        persist()
        try {
            for (case in cases) {
                val work = File(app.cacheDir, "half-review-$runId-${case.id}")
                check(!work.exists() && work.mkdir())
                // Failed capture/close keeps source and partial raster evidence; never delete an unconfirmed owner.
                records.put(capture(case, work, directory))
                check(work.deleteRecursively())
                persist()
            }
            contactSheet(directory, records)
            manifest.put("overview", "00-overview.png").put("overviewSha256", sha(File(directory, "00-overview.png")))
            manifest.put("status", "complete")
        } finally { persist() }
    }

    @Test fun compareFullDemosaicCandidates() {
        requireGpuInputSafetyFixture()
        val args = InstrumentationRegistry.getArguments()
        check(args.getString("demosaicComparison") == "true")
        val run = checkNotNull(args.getString("halfPreviewRunId"))
        require(run.matches(Regex("[A-Za-z0-9_-]{1,80}")))
        val commit = checkNotNull(args.getString("halfPreviewSourceCommit"))
        require(commit.matches(Regex("[a-f0-9]{40}")))
        val out = File(checkNotNull(app.getExternalFilesDir("half-preview-review")), run)
        check(!out.exists() && out.mkdirs())
        val rows = JSONArray()
        val manifest = JSONObject().put("status", "incomplete").put("sourceCommit", commit).put("runId", run)
            .put("syntheticOnly", true).put("adopted", false).put("cases", rows)
            .put("timing", "One warmup per method, three measured iterations in rotated order. JNI decode includes unpack/demosaic/RGB16 write; excludes source copying, bounded reduction and GPU. AVD only, not Pixel or selection-to-edit latency.")
        fun persist() = File(out, "demosaic.json").writeText(manifest.toString(2))
        persist()
        val methods = listOf(-1, 0, 2)
        fun method(q: Int) = when(q) { -1 -> "ahd"; 0 -> "bilinear"; else -> "ppg" }
        try {
            for (case in cases) {
                val work = File(app.cacheDir, "demosaic-$run-${case.id}")
                check(!work.exists() && work.mkdir())
                val input = File(work, "synthetic.dng")
                input.outputStream().use { stream -> writeSyntheticTrialDng(stream, case.width, case.height, case.orientation) { x, y -> sample(case, x, y) } }
                val sourceHash = sha(input)
                val geometry = ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY).use {
                    Original24Preflight.inspect(it.fd, CancellationSignal())
                }
                val times = methods.associateWith { mutableListOf<Long>() }
                val rasters = methods.associateWith { File(work, "${method(it)}.rgb16") }
                for (iteration in 0..3) {
                    for (i in methods.indices) {
                        val q = methods[(i + iteration) % methods.size]
                        val file = checkNotNull(rasters[q])
                        if (file.exists()) check(file.delete())
                        check(file.createNewFile())
                        ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY).use { source ->
                            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE).use { target ->
                                val start = android.os.SystemClock.elapsedRealtimeNanos()
                                val report = if(q == -1) NativeOriginalRaster.decode24(source.fd, target.fd, CancellationSignal())
                                    else NativePreviewQualityTest.decode(source.fd, target.fd, CancellationSignal(), q)
                                val duration = android.os.SystemClock.elapsedRealtimeNanos() - start
                                if(iteration > 0) checkNotNull(times[q]).add(duration)
                                val r = JSONObject(report)
                                check(r.getInt("halfSize") == 0 && r.getInt("width") == geometry.outputWidth && r.getInt("height") == geometry.outputHeight)
                                check(file.length() == geometry.outputWidth.toLong() * geometry.outputHeight * 6)
                            }
                        }
                    }
                }
                val inputs = methods.associateWith { q ->
                    val raster = OriginalRasterFile(checkNotNull(rasters[q]), geometry.outputWidth, geometry.outputHeight, 2)
                    try {
                        val size = GpuPreviewInputSize.fit(raster.width, raster.height)
                        OriginalFields.reduce(raster, size.width, size.height, 3, { false }) { rgb, target -> rgb.copyInto(target) }.reducedRgb()
                    } finally { raster.closePending() }
                }
                val params = GpuRenderParams(case.strength, PhotoAdjustments(0.0, case.ev))
                val pixels = methods.associateWith { preview(checkNotNull(inputs[it]), params) }
                val reference = checkNotNull(inputs[-1])
                val row = JSONObject().put("id", case.id).put("width", reference.width).put("height", reference.height)
                    .put("sourceSha256", sourceHash).put("manualEv", case.ev).put("strength", case.strength)
                val hashes = JSONObject()
                for(q in methods) {
                    val name = "${case.id}-${method(q)}.png"
                    png(checkNotNull(pixels[q]), reference.width, reference.height, File(out, name))
                    hashes.put(name, sha(File(out, name)))
                    val samples = checkNotNull(times[q])
                    row.put(method(q), JSONObject().put("file", name).put("decodeNanos", JSONArray(samples))
                        .put("medianDecodeNanos", samples.sorted()[1]).put("baseEv", baseEv(checkNotNull(inputs[q])))
                        .put("vsAhdRgb8", differences(checkNotNull(pixels[q]), checkNotNull(pixels[-1])))
                        .put("vsAhdLinear", linearDifferences(checkNotNull(inputs[q]), reference)))
                }
                val save = File(out, "${case.id}-ahd-save.jpg")
                row.put("saveBaseEv", fullSave(input, File(work, "save.rgb16"), geometry, case, save))
                    .put("saveJpeg", save.name)
                hashes.put(save.name, sha(save))
                row.put("fileSha256", hashes)
                check(sha(input) == sourceHash)
                rows.put(row); check(work.deleteRecursively()); persist()
            }
            demosaicOverview(out, rows)
            manifest.put("status", "complete").put("gl", glInfo)
                .put("overviewSha256", sha(File(out, "00-demosaic-overview.png")))
        } finally { persist() }
    }

    private fun demosaicOverview(directory: File, rows: JSONArray) {
        val sheet = Bitmap.createBitmap(1080, 220 + rows.length() * 570, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(sheet); canvas.drawColor(Color.rgb(20, 20, 20))
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 28f }
            val filter = Paint(Paint.FILTER_BITMAP_FLAG)
            canvas.drawText("原寸デモザイク比較・合成画像のみ・未採用", 20f, 42f, text)
            canvas.drawText("現行 AHD               双線形 q=0                PPG q=2", 20f, 95f, text)
            text.textSize = 22f
            canvas.drawText("上：全体／下：中央160×80を2倍表示。保存JPEGはAHDのまま", 20f, 142f, text)
            canvas.drawText("時間：AVD展開＋RGB16書込。Pixel／編集可能までの時間ではありません。", 20f, 175f, text)
            for(i in 0 until rows.length()) {
                val row = rows.getJSONObject(i); val top = 190 + i * 570
                text.textSize = 26f; canvas.drawText(row.getString("id"), 20f, top + 30f, text)
                listOf("ahd", "bilinear", "ppg").forEachIndexed { col, method ->
                    val data = row.getJSONObject(method)
                    val bmp = checkNotNull(BitmapFactory.decodeFile(File(directory, data.getString("file")).absolutePath))
                    try {
                        val left = 20 + col * 353
                        val scale = min(333f / bmp.width, 265f / bmp.height)
                        val w = bmp.width * scale; val h = bmp.height * scale
                        canvas.drawBitmap(bmp, null, RectF(left+(333-w)/2, top+52f, left+(333+w)/2, top+52f+h), filter)
                        canvas.drawBitmap(bmp, Rect(bmp.width/2-80,bmp.height/2-40,bmp.width/2+80,bmp.height/2+40),
                            RectF(left.toFloat(),top+334f,left+320f,top+494f), null)
                        text.textSize = 20f
                        canvas.drawText("MAE %.3f / %.1f ms".format(java.util.Locale.ROOT,
                            data.getJSONObject("vsAhdRgb8").getDouble("maeRgb8"), data.getLong("medianDecodeNanos")/1e6),
                            left.toFloat(), top+530f, text)
                    } finally { bmp.recycle() }
                }
            }
            File(directory,"00-demosaic-overview.png").outputStream().use { check(sheet.compress(Bitmap.CompressFormat.PNG,100,it)) }
        } finally { sheet.recycle() }
    }

    /** Test-only decomposition. No JPEG, adoption, timing claim or product switch. */
    @Test fun separateLinearInputFromGpuStages() {
        requireGpuInputSafetyFixture()
        val args = InstrumentationRegistry.getArguments()
        check(args.getString("halfPreviewDecomposition") == "true")
        val run = checkNotNull(args.getString("halfPreviewRunId"))
        require(run.matches(Regex("[A-Za-z0-9_-]{1,80}")))
        val commit = checkNotNull(args.getString("halfPreviewSourceCommit"))
        require(commit.matches(Regex("[a-f0-9]{40}")))
        val out = File(checkNotNull(app.getExternalFilesDir("half-preview-review")), run)
        check(!out.exists() && out.mkdirs())
        val rows = JSONArray()
        val result = JSONObject().put("status", "incomplete").put("syntheticOnly", true)
            .put("sourceCommit", commit).put("runId", run).put("cases", rows)
            .put("conditions", "Both inputs use full-input photo analysis; local GPU quantiles remain input-dependent. Strength zero disables look mixing, not R7. All metrics are linear RGB, no clipping or resampling.")
        fun persist() = File(out, "decomposition.json").writeText(result.toString(2))
        persist()
        try {
            for (case in cases) {
                val work = File(app.cacheDir, "decomposition-$run-${case.id}")
                check(!work.exists() && work.mkdir())
                val sourceFile = File(work, "synthetic.dng")
                sourceFile.outputStream().use { stream -> writeSyntheticTrialDng(stream, case.width, case.height, case.orientation) { x, y -> sample(case, x, y) } }
                val source = SharedOriginalReference("content://example.test/decomposition/${case.id}",
                    DngContentVerifier.identify(sourceFile.length(), { sourceFile.inputStream() }), OriginalGrant.USER_SELECTED)
                val geometry = ParcelFileDescriptor.open(sourceFile, ParcelFileDescriptor.MODE_READ_ONLY).use {
                    Original24Preflight.inspect(it.fd, CancellationSignal())
                }
                fun load(mode: PreviewRasterMode) = Original24PreviewSession(source, File(work, mode.name), {}, geometry,
                    rasterMode = mode) { sourceFile.inputStream() }.use { session ->
                    session.loadGpuAsShotInput().also { assertFalse(session.hasUnreleasedResources) }
                }
                val full = load(PreviewRasterMode.FULL)
                val half = load(PreviewRasterMode.HALF_CANDIDATE)
                assertEquals(full.width, half.width); assertEquals(full.height, half.height)
                val prepared = R7PreviewInputs.prepare(R7PreviewInputs.AsShotInput(full,
                    R7PreviewInputs.Source.LIBRAW_PRELIMINARY_AS_SHOT), emptyList()).exposure
                val fixed = GpuPhotoAnalysis(prepared.externalBaseEv, prepared.centralP97)
                val row = JSONObject().put("id", case.id).put("width", full.width).put("height", full.height)
                    .put("sourceSha256", sha(sourceFile)).put("fixedBaseEv", fixed.meteredBaseEv)
                    .put("manualEv", case.ev).put("fixedCentralP97", fixed.centralP97)
                    .put("inputLinear", linearDifferences(half, full))
                for (strength in listOf(0.0, case.strength)) {
                    val params = GpuRenderParams(strength, PhotoAdjustments(0.0, case.ev))
                    val fullStages = stageSamples(full, params, fixed)
                    val halfStages = stageSamples(half, params, fixed)
                    val stages = JSONObject()
                    for (point in fullStages.keys) stages.put(point.name,
                        linearDifferences(checkNotNull(halfStages[point]), checkNotNull(fullStages[point])))
                    row.put("strength$strength", stages)
                }
                DngContentVerifier.verify(source.fingerprint, { sourceFile.inputStream() })
                rows.put(row)
                check(work.deleteRecursively())
                persist()
            }
            result.put("status", "complete").put("gl", glInfo)
        } finally { persist() }
    }

    private fun linearDifferences(actual: LinearSrgbImage, reference: LinearSrgbImage): JSONObject {
        check(actual.width == reference.width && actual.height == reference.height)
        var sum = 0.0; var maximum = 0.0; var chroma = 0.0
        for (p in 0 until actual.width * actual.height) {
            for (c in 0..2) {
                val delta = abs(actual[p * 3 + c].toDouble() - reference[p * 3 + c])
                check(delta.isFinite()); sum += delta; maximum = max(maximum, delta)
            }
            // Color-difference error relative to green, not a perceptual metric or neutral-color assumption.
            for (c in 0..2 step 2) chroma += abs((actual[p * 3 + c].toDouble() - actual[p * 3 + 1]) -
                (reference[p * 3 + c].toDouble() - reference[p * 3 + 1]))
        }
        val count = actual.width.toDouble() * actual.height
        return JSONObject().put("maeLinearRgb", sum / (count * 3)).put("maxLinearRgb", maximum)
            .put("maeRgBgDifference", chroma / (count * 2))
    }

    private fun stageSamples(input: LinearSrgbImage, params: GpuRenderParams, fixed: GpuPhotoAnalysis)
        : Map<GpuStageTap.Point, LinearSrgbImage> = GlThread().use { thread -> thread.call {
        EglContexts(thread, app.assets).use { egl ->
            GlCapabilities.inspect(egl).requireSupported()
            glInfo = JSONObject().put("renderer", GLES32.glGetString(GLES32.GL_RENDERER))
                .put("version", GLES32.glGetString(GLES32.GL_VERSION))
            val ledger = GpuMemoryLedger(GpuMemoryLedger.PREVIEW_BUDGET)
            val stages = linkedMapOf<GpuStageTap.Point, LinearSrgbImage>()
            DefaultStageClearGpu(egl, ledger) { texture, _ ->
                val connected = ConnectedStageClearGpuPipeline(texture, fixed, GpuFrameMode.FULL_RECOMPUTE)
                object : StageClearGpuPipeline {
                    override fun render(params: GpuRenderParams, tap: ((GpuStageTap.Point, GpuTexture) -> Unit)?,
                        consume: (GpuTexture, GpuDecisions) -> Unit) = connected.render(params, { point, image ->
                        if (point == GpuStageTap.Point.R7_FINAL || point == GpuStageTap.Point.SHOULDER)
                            stages[point] = GpuStageTap.readRgb(egl, image)
                        tap?.invoke(point, image)
                    }, consume)
                    override fun close() = connected.close()
                }
            }.use { engine -> engine.openPhoto(input) { false }.use { photo ->
                engine.renderForTest(photo, params, GpuStageTap.Point.SHOULDER)
            } }
            assertEquals(setOf(GpuStageTap.Point.R7_FINAL, GpuStageTap.Point.SHOULDER), stages.keys)
            assertEquals(0, ledger.textureCount); assertEquals(0L, ledger.allocatedBytes)
            stages
        }
    } }

    private fun capture(case: Case, work: File, out: File): JSONObject {
        val input = File(work, "synthetic.dng")
        input.outputStream().use { stream -> writeSyntheticTrialDng(stream, case.width, case.height, case.orientation) { x, y ->
            sample(case, x, y)
        } }
        val source = SharedOriginalReference("content://example.test/half-comparison/${case.id}",
            DngContentVerifier.identify(input.length(), { input.inputStream() }), OriginalGrant.USER_SELECTED)
        val geometry = ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY).use {
            Original24Preflight.inspect(it.fd, CancellationSignal())
        }
        fun load(mode: PreviewRasterMode) = Original24PreviewSession(source, File(work, mode.name), {}, geometry,
            rasterMode = mode) { input.inputStream() }.use { session ->
            session.loadGpuAsShotInput().also { assertFalse(session.hasUnreleasedResources) }
        }
        val full = load(PreviewRasterMode.FULL)
        val half = load(PreviewRasterMode.HALF_CANDIDATE)
        val params = GpuRenderParams(case.strength, PhotoAdjustments(0.0, case.ev))
        val fullPixels = preview(full, params)
        val halfPixels = preview(half, params)
        val fullName = "${case.id}-full-preview.png"
        val halfName = "${case.id}-half-preview.png"
        val jpegName = "${case.id}-full-save.jpg"
        val savedName = "${case.id}-save-display.png"
        png(fullPixels, full.width, full.height, File(out, fullName))
        png(halfPixels, half.width, half.height, File(out, halfName))
        val saveBaseEv = fullSave(input, File(work, "save.rgb16"), geometry, case, File(out, jpegName))
        val decoded = checkNotNull(BitmapFactory.decodeFile(File(out, jpegName).absolutePath))
        val saved = try { scaledPixels(decoded, full.width, full.height) } finally { decoded.recycle() }
        png(saved, full.width, full.height, File(out, savedName))
        val halfBitmap = Bitmap.createBitmap(halfPixels, half.width, half.height, Bitmap.Config.ARGB_8888)
        val normalizedHalf = try { scaledPixels(halfBitmap, full.width, full.height) } finally { halfBitmap.recycle() }
        DngContentVerifier.verify(source.fingerprint, { input.inputStream() })
        return JSONObject().put("id", case.id).put("rawWidth", case.width).put("rawHeight", case.height)
            .put("orientation", case.orientation).put("strength", case.strength).put("manualEv", case.ev)
            .put("fullWidth", full.width).put("fullHeight", full.height).put("halfWidth", half.width).put("halfHeight", half.height)
            .put("fullBaseEv", baseEv(full)).put("halfBaseEv", baseEv(half)).put("saveBaseEv", saveBaseEv)
            .put("fullPreview", fullName).put("halfPreview", halfName).put("fullSaveJpeg", jpegName).put("saveDisplay", savedName)
            .put("sourceSha256", sha(input)).put("gl", glInfo)
            .put("fileSha256", JSONObject().also { hashes ->
                listOf(fullName, halfName, jpegName, savedName).forEach { hashes.put(it, sha(File(out, it))) }
            })
            .put("halfResizedForMetrics", full.width != half.width || full.height != half.height)
            .put("halfVsCurrent", differences(normalizedHalf, fullPixels))
            .put("currentVsSave", differences(fullPixels, saved))
            .put("halfVsSave", differences(normalizedHalf, saved))
    }

    /** The same native full RGB16, analysis reduction, base EV, GPU row renderer and JPEG95 as Original24Attempt.
     * Private artifact only: deliberately no MediaStore publication, metadata or save-state mutation. */
    private fun fullSave(input: File, raw: File, geometry: Original24PreflightGeometry, case: Case, output: File): Double {
        check(raw.createNewFile())
        ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY).use { source ->
            ParcelFileDescriptor.open(raw, ParcelFileDescriptor.MODE_READ_WRITE).use { target ->
                val report = JSONObject(NativeOriginalRaster.decode24(source.fd, target.fd, CancellationSignal()))
                check(report.getInt("halfSize") == 0 && report.getInt("width") == geometry.outputWidth &&
                    report.getInt("height") == geometry.outputHeight)
            }
        }
        val raster = OriginalRasterFile(raw, geometry.outputWidth, geometry.outputHeight, 2)
        try {
            val scale = min(1.0, 1024.0 / max(raster.width, raster.height))
            val analysis = OriginalFields.reduce(raster, max(4, floor(raster.width * scale).toInt()),
                max(4, floor(raster.height * scale).toInt()), 3, { false }) { rgb, target -> rgb.copyInto(target) }.reducedRgb()
            val ev = baseEv(analysis)
            val bitmap = Bitmap.createBitmap(raster.width, raster.height, Bitmap.Config.ARGB_8888)
            try {
                bitmap.setHasAlpha(false)
                Original24GpuOutput(app.assets).use { gpu ->
                    gpu.render(raster, ev + case.ev, doubleArrayOf(1.0, 1.0, 1.0), case.strength, { false }) { y, row ->
                        bitmap.setPixels(row, 0, raster.width, 0, y, raster.width, 1)
                    }
                    assertFalse(gpu.hasUnreleasedResources)
                }
                output.outputStream().buffered().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
            } finally { bitmap.recycle() }
            return ev
        } finally { raster.closePending() }
    }

    private fun baseEv(input: LinearSrgbImage) = R7PreviewInputs.prepare(R7PreviewInputs.AsShotInput(input,
        R7PreviewInputs.Source.LIBRAW_PRELIMINARY_AS_SHOT), emptyList()).exposure.externalBaseEv

    private fun preview(input: LinearSrgbImage, params: GpuRenderParams): IntArray = GlThread().use { thread -> thread.call {
        EglContexts(thread, app.assets).use { egl ->
            GlCapabilities.inspect(egl).requireSupported()
            glInfo = JSONObject().put("vendor", GLES32.glGetString(GLES32.GL_VENDOR))
                .put("renderer", GLES32.glGetString(GLES32.GL_RENDERER)).put("version", GLES32.glGetString(GLES32.GL_VERSION))
            val ledger = GpuMemoryLedger(GpuMemoryLedger.PREVIEW_BUDGET)
            var pixels: IntArray? = null
            DefaultStageClearGpu(egl, ledger) { texture, analysis ->
                val connected = ConnectedStageClearGpuPipeline(texture, analysis, GpuFrameMode.FULL_RECOMPUTE)
                object : StageClearGpuPipeline {
                    override fun render(params: GpuRenderParams, tap: ((GpuStageTap.Point, GpuTexture) -> Unit)?,
                        consume: (GpuTexture, GpuDecisions) -> Unit) = connected.render(params, { point, image ->
                        if (point == GpuStageTap.Point.DISPLAY8) pixels = readArgb(egl, image)
                        tap?.invoke(point, image)
                    }, consume)
                    override fun close() = connected.close()
                }
            }.use { engine -> engine.openPhoto(input) { false }.use { photo ->
                engine.renderForTest(photo, params, GpuStageTap.Point.SHOULDER)
            } }
            assertEquals(0, ledger.textureCount); assertEquals(0L, ledger.allocatedBytes)
            checkNotNull(pixels)
        }
    } }

    private fun readArgb(egl: EglContexts, texture: GpuTexture): IntArray {
        check(texture.format == GpuTexture.Format.RGBA8)
        GpuFence(egl).use { it.await() }
        val bytes = ByteBuffer.allocateDirect(texture.width * texture.height * 4)
        GLES32.glBindFramebuffer(GLES32.GL_FRAMEBUFFER, texture.framebufferId)
        GLES32.glReadBuffer(GLES32.GL_COLOR_ATTACHMENT0)
        GLES32.glBindBuffer(GLES32.GL_PIXEL_PACK_BUFFER, 0)
        GLES32.glPixelStorei(GLES32.GL_PACK_ALIGNMENT, 1)
        GLES32.glPixelStorei(GLES32.GL_PACK_ROW_LENGTH, 0)
        GLES32.glPixelStorei(GLES32.GL_PACK_SKIP_PIXELS, 0)
        GLES32.glPixelStorei(GLES32.GL_PACK_SKIP_ROWS, 0)
        GLES32.glReadPixels(0, 0, texture.width, texture.height, GLES32.GL_RGBA, GLES32.GL_UNSIGNED_BYTE, bytes)
        egl.checkGl("Read half comparison DISPLAY8")
        return IntArray(texture.width * texture.height) { p ->
            check((bytes.get(p * 4 + 3).toInt() and 255) == 255)
            (255 shl 24) or ((bytes.get(p * 4).toInt() and 255) shl 16) or
                ((bytes.get(p * 4 + 1).toInt() and 255) shl 8) or (bytes.get(p * 4 + 2).toInt() and 255)
        }
    }

    private fun scaledPixels(bitmap: Bitmap, width: Int, height: Int): IntArray {
        val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
        return try { IntArray(width * height).also { scaled.getPixels(it, 0, width, 0, 0, width, height) } }
        finally { if (scaled !== bitmap) scaled.recycle() }
    }
    private fun png(pixels: IntArray, width: Int, height: Int, file: File) {
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        try { file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
    }
    private fun differences(actual: IntArray, reference: IntArray): JSONObject {
        check(actual.size == reference.size)
        var sum = 0L; var changed = 0L; var maximum = 0
        val histogram = IntArray(256)
        actual.indices.forEach { i ->
            var differs = false
            for (channel in 0..2) {
                val shift = 16 - 8 * channel
                val delta = abs(((actual[i] ushr shift) and 255) - ((reference[i] ushr shift) and 255))
                histogram[delta]++; sum += delta; maximum = max(maximum, delta); differs = differs || delta != 0
            }
            if (differs) changed++
        }
        var cumulative = 0L; var p99 = 0
        for (d in histogram.indices) { cumulative += histogram[d]; if (cumulative >= actual.size * 3L * .99) { p99 = d; break } }
        return JSONObject().put("maeRgb8", sum.toDouble() / (actual.size * 3)).put("maxRgb8", maximum)
            .put("p99Rgb8", p99).put("changedPixelPercent", changed * 100.0 / actual.size)
    }

    private val stripeColors = arrayOf(doubleArrayOf(.9,.06,.03), doubleArrayOf(.03,.7,.08), doubleArrayOf(.04,.08,.8),
        doubleArrayOf(.8,.65,.03), doubleArrayOf(.8,.04,.7), doubleArrayOf(.02,.7,.7))

    /** Test artifact layout only. All cells come from captured output, never generated illustrations. */
    private fun contactSheet(directory: File, records: JSONArray) {
        val sheet = Bitmap.createBitmap(1080, 250 + records.length() * 570, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(sheet)
            canvas.drawColor(Color.rgb(22, 24, 28))
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 28f }
            val filtered = Paint(Paint.FILTER_BITMAP_FLAG)
            canvas.drawText("half_size 比較 / 合成入力のみ・未採用", 20f, 44f, text)
            text.textSize = 23f
            canvas.drawText("左: 現行full   中: half候補   右: full保存JPEG95", 20f, 82f, text)
            canvas.drawText("同設定。JPEGは表示寸法へ縮小。", 20f, 116f, text)
            canvas.drawText("実写画質・実機速度の代用ではありません。", 20f, 150f, text)
            canvas.drawText("上:全体 / 下:中央160×80を2倍。原寸画像も用意。", 20f, 184f, text)
            val keys = listOf("fullPreview", "halfPreview", "saveDisplay")
            for (i in 0 until records.length()) {
                val record = records.getJSONObject(i)
                val top = 230 + i * 570
                text.textSize = 26f
                canvas.drawText(record.getString("id") + "  EV " + record.getDouble("manualEv") +
                    " / strength " + record.getDouble("strength"), 20f, top + 30f, text)
                keys.forEachIndexed { col, key ->
                    val bitmap = checkNotNull(BitmapFactory.decodeFile(File(directory, record.getString(key)).absolutePath))
                    try {
                        val left = 20 + col * 353
                        val scale = min(333f / bitmap.width, 265f / bitmap.height)
                        val w = bitmap.width * scale; val h = bitmap.height * scale
                        canvas.drawBitmap(bitmap, null, RectF(left + (333 - w) / 2, top + 52f,
                            left + (333 + w) / 2, top + 52f + h), filtered)
                        val crop = Rect(bitmap.width / 2 - 80, bitmap.height / 2 - 40,
                            bitmap.width / 2 + 80, bitmap.height / 2 + 40)
                        canvas.drawBitmap(bitmap, crop, RectF(left.toFloat(), top + 334f, left + 320f, top + 494f), null)
                    } finally { bitmap.recycle() }
                }
                text.textSize = 22f
                val delta = record.getJSONObject("halfVsCurrent")
                canvas.drawText("half－現行 RGB8: MAE %.3f / p99 %d / max %d".format(java.util.Locale.ROOT,
                    delta.getDouble("maeRgb8"), delta.getInt("p99Rgb8"), delta.getInt("maxRgb8")), 20f, top + 532f, text)
            }
            File(directory, "00-overview.png").outputStream().use { check(sheet.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { sheet.recycle() }
    }

    /** Bayer sampling of generated color/texture fields, not a photograph or camera calibration. */
    private fun sample(case: Case, x: Int, y: Int): Int {
        val color = if (y % 2 == 0) { if (x % 2 == 0) 0 else 1 } else { if (x % 2 == 0) 1 else 2 }
        val nx = x.toDouble() / (case.width - 1); val ny = y.toDouble() / (case.height - 1)
        val value = when (case.kind) {
            0 -> when (color) { 0 -> nx; 1 -> ny; else -> (nx + ny) / 2 } * .82 + .02
            1 -> { val cell = if (nx < .5) 3 else 11; val line = ((x / cell + y / cell) % 2 == 0)
                (if (line) .72 else .07) * (if (color == 1) 1.0 else .88) }
            2 -> { val dx = nx - .48; val dy = ny - .4; val r = dx * dx + dy * dy
                if (r < .012 || abs(nx - .8) < .006) .99 else .012 + .10 * ny }
            else -> { val stripe = (nx * 6).toInt().coerceAtMost(5)
                stripeColors[stripe][color] * (if (ny < .5) 1.0 else .35) }
        }
        return (64 + value.coerceIn(0.0, 1.0) * (4095 - 64)).toInt()
    }
}

/** Export exists only in debug cinematic_dng; absent from release and all product Kotlin. */
internal object NativePreviewQualityTest {
    init { System.loadLibrary("cinematic_dng") }
    external fun decode(sourceFd: Int, outputFd: Int, signal: CancellationSignal, quality: Int): String
}
