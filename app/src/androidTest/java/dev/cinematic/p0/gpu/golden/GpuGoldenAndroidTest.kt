package dev.cinematic.p0.gpu.golden

import android.opengl.GLES32
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.gpu.*
import dev.cinematic.p0.gpu.diag.GpuStageTap
import dev.cinematic.p0.gpu.export.GpuExportRenderer
import dev.cinematic.p0.gpu.reference.GpuReferenceImages
import dev.cinematic.p0.raw.LinearSrgbImage
import dev.cinematic.p0.raw.PhotoAdjustments
import dev.cinematic.p0.raw.original.OriginalRaster
import dev.cinematic.p0.raw.original.originalCheckpoint
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import kotlin.math.pow
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** #290: candidate capture only. Baseline adoption is a separate, owner-approved operation. */
@RunWith(AndroidJUnit4::class)
class GpuGoldenAndroidTest {
    private val args get() = InstrumentationRegistry.getArguments()

    @Before fun explicitEmulatorOnly() {
        assumeTrue(args.getString("gpuGolden") == "true")
        check(args.getString("isolated") == "true")
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Synthetic emulator only" }
    }

    @Test fun captureNinetyFullFramesAndCompareBothBandSizes() {
        val runId = checkNotNull(args.getString("gpuGoldenRunId"))
        require(runId.matches(Regex("[A-Za-z0-9_-]{1,80}")))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(checkNotNull(context.getExternalFilesDir("gpu-golden")), runId)
        check(!directory.exists()) { "Never overwrite a previous candidate" }
        check(directory.mkdirs())
        val cases = matrix()
        check(cases.size == 90 && cases.map { it.id }.toSet().size == 90)
        val records = JSONArray()
        val manifest = JSONObject().put("schema", 1).put("status", "incomplete")
            .put("matrixVersion", "stage-clear-90-v1").put("caseCount", 90)
            .put("fixtureSeed", GpuReferenceImages.SEED).put("encoding", "row-major RGB8 sRGB, top row first")
            .put("baselineApproved", false).put("cases", records)
        val manifestFile = File(directory, "capture.json")
        fun persist() = manifestFile.writeText(manifest.toString(2))
        persist()
        try {
            GlThread().use { thread -> thread.call {
                EglContexts(thread, context.assets).use { egl ->
                    GlCapabilities.inspect(egl).requireSupported()
                    manifest.put("android", JSONObject().put("sdk", Build.VERSION.SDK_INT)
                        .put("release", Build.VERSION.RELEASE).put("fingerprint", Build.FINGERPRINT)
                        .put("hardware", Build.HARDWARE)
                        .put("glVendor", GLES32.glGetString(GLES32.GL_VENDOR))
                        .put("glRenderer", GLES32.glGetString(GLES32.GL_RENDERER))
                        .put("glVersion", GLES32.glGetString(GLES32.GL_VERSION))
                        .put("glslVersion", GLES32.glGetString(GLES32.GL_SHADING_LANGUAGE_VERSION)))
                    persist()
                    for (case in cases) {
                        Log.i("GpuGolden", "BEGIN ${case.id}")
                        val input = GpuReferenceImages.create(case.kind, GpuReferenceImages.Size(case.width, case.height))
                        val full = if (case.bands) exportFullFrame(egl, input, case.params) else preview(egl, input, case.params)
                        val file = File(directory, "${case.id}.rgb8.gz")
                        val rgb = ByteArray(full.size * 3) { i -> (full[i / 3] ushr (16 - 8 * (i % 3))).toByte() }
                        GZIPOutputStream(file.outputStream().buffered()).use { it.write(rgb) }
                        val record = case.json().put("file", file.name).put("rgbSha256", sha(rgb))
                            .put("fileSha256", sha(file.readBytes())).put("compressedBytes", file.length())
                            .put("bandRowsPassed", JSONArray())
                        records.put(record)
                        // Persist full output before comparisons so a failure is inspectable, never adopted.
                        persist()
                        if (case.bands) for (rows in listOf(64, 256)) {
                            compareBand(egl, input, case.params, rows, full, record)
                            record.getJSONArray("bandRowsPassed").put(rows)
                            persist()
                        }
                        Log.i("GpuGolden", "PASS ${case.id}")
                    }
                }
            } }
            val bytes = directory.listFiles()!!.filter { it.extension == "gz" }.sumOf { it.length() }
            manifest.put("totalImageBytes", bytes)
            check(bytes <= 20_000_000L) { "Candidate images exceed 20 MB; consult owner before reducing coverage" }
            check(records.length() == 90)
            manifest.put("status", "complete")
        } finally { persist() }
    }

    private fun preview(egl: EglContexts, input: LinearSrgbImage, params: GpuRenderParams): IntArray {
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
        }.use { engine ->
            engine.openPhoto(input) { false }.use { photo ->
                // Public engine does actual once-per-photo analysis. No CPU rendering oracle.
                engine.renderForTest(photo, params, GpuStageTap.Point.SHOULDER)
            }
        }
        released(ledger)
        return checkNotNull(pixels)
    }

    private fun exportFullFrame(egl: EglContexts, input: LinearSrgbImage, params: GpuRenderParams): IntArray {
        // Full-frame comparison oracle only. Product band exporter retains its 192 MiB budget.
        val ledger = GpuMemoryLedger(512L * 1024 * 1024)
        var pixels: IntArray? = null
        GpuTexture.create(egl, ledger, input.width, input.height, GpuTexture.Format.RGBA32F).use { texture ->
            val rgba = ByteBuffer.allocateDirect(input.pixelCount * 16).order(ByteOrder.nativeOrder()).asFloatBuffer()
            repeat(input.pixelCount) { p -> repeat(3) { c -> rgba.put(input[p * 3 + c]) }; rgba.put(1f) }
            rgba.flip(); texture.upload(rgba)
            // Export fixtures use a declared fixed metered base EV of zero, as existing export parity does.
            ConnectedStageClearGpuPipeline(texture, GpuPhotoAnalysis(0.0, 0f), GpuFrameMode.FULL_RECOMPUTE).use { pipeline ->
                pipeline.render(params, { point, image ->
                    if (point == GpuStageTap.Point.DISPLAY8) pixels = readArgb(egl, image)
                }) { _, _ -> }
            }
        }
        released(ledger)
        return checkNotNull(pixels)
    }

    private fun compareBand(egl: EglContexts, input: LinearSrgbImage, params: GpuRenderParams,
        rows: Int, full: IntArray, record: JSONObject) {
        val raster = object : OriginalRaster {
            override val width = input.width
            override val height = input.height
            override fun rows(stop: () -> Boolean, consume: (Int, FloatArray) -> Unit) {
                val row = FloatArray(width * 3)
                repeat(height) { y ->
                    originalCheckpoint(stop)
                    for (i in row.indices) row[i] = input[y * row.size + i]
                    consume(y, row)
                }
            }
        }
        val red = 2.0.pow(params.adjustments.warmth / 200.0)
        val blue = 2.0.pow(-params.adjustments.warmth / 200.0)
        val norm = .2126 * red + .7152 + .0722 * blue
        val ledger = GpuMemoryLedger(GpuMemoryLedger.EXPORT_BUDGET)
        var next = 0
        var different = 0L
        var maxDelta = 0
        var first = ""
        GpuExportRenderer(egl, ledger, rows).render(raster, params.adjustments.exposureEv,
            doubleArrayOf(red / norm, 1.0 / norm, blue / norm), params.strength) { y, row ->
            assertEquals("Output row order", next++, y)
            assertEquals(input.width, row.size)
            for (x in row.indices) {
                val expected = full[y * input.width + x]
                if (row[x] != expected) {
                    different++
                    if (first.isEmpty()) first = "$x,$y"
                    for (shift in listOf(0, 8, 16)) maxDelta = maxOf(maxDelta,
                        kotlin.math.abs(((expected ushr shift) and 255) - ((row[x] ushr shift) and 255)))
                }
            }
        }
        released(ledger)
        record.put("bands$rows", JSONObject().put("changedPixels", different).put("maxDelta", maxDelta)
            .put("firstChangedXY", first).put("peakTextureBytes", ledger.peakAllocatedBytes))
        assertEquals(input.height, next)
        assertEquals("${record.getString("id")} band=$rows maxDelta=$maxDelta firstXY=$first", 0L, different)
    }

    private fun released(ledger: GpuMemoryLedger) {
        assertEquals("All textures released", 0, ledger.textureCount)
        assertEquals("All texture bytes released", 0L, ledger.allocatedBytes)
    }

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
        egl.checkGl("Read golden DISPLAY8")
        return IntArray(texture.width * texture.height) { p ->
            check((bytes.get(p * 4 + 3).toInt() and 255) == 255)
            (255 shl 24) or ((bytes.get(p * 4).toInt() and 255) shl 16) or
                ((bytes.get(p * 4 + 1).toInt() and 255) shl 8) or (bytes.get(p * 4 + 2).toInt() and 255)
        }
    }

    private data class Case(val id: String, val group: String, val kind: GpuReferenceImages.Kind,
        val width: Int, val height: Int, val params: GpuRenderParams, val bands: Boolean) {
        fun json() = JSONObject().put("id", id).put("group", group).put("kind", kind.name)
            .put("width", width).put("height", height).put("strength", params.strength)
            .put("warmth", params.adjustments.warmth).put("ev", params.adjustments.exposureEv)
            .put("analysis", if (bands) "fixed-base-ev-0" else "production-photo-analysis")
    }

    private fun matrix(): List<Case> = buildList {
        fun addCase(group: String, kind: GpuReferenceImages.Kind, width: Int, height: Int,
            strength: Double, warmth: Double = 0.0, ev: Double = 0.0, bands: Boolean = false) {
            val id = "c%03d".format(java.util.Locale.ROOT, size + 1)
            add(Case(id, group, kind, width, height, GpuRenderParams(strength, PhotoAdjustments(warmth, ev)), bands))
        }
        for (kind in GpuReferenceImages.Kind.entries) for (strength in listOf(0.0, .25, .5, 1.0))
            addCase("small", kind, 256, 192, strength)
        val representatives = listOf(GpuReferenceImages.Kind.DARK, GpuReferenceImages.Kind.SKIN, GpuReferenceImages.Kind.MIXED)
        for (kind in representatives) for ((warmth, ev) in listOf(-100.0 to 0.0, 100.0 to 0.0,
            0.0 to -2.0, 0.0 to 2.0, -100.0 to -2.0, 100.0 to 2.0))
            addCase("small", kind, 256, 192, .5, warmth, ev)
        check(size == 58)
        for (kind in representatives) {
            for ((w, h) in listOf(1024 to 768, 768 to 1024, 1024 to 576, 886 to 887))
                addCase("preview", kind, w, h, .5)
            addCase("preview", kind, 1024, 768, 1.0, -100.0, -2.0)
            addCase("preview", kind, 1024, 768, 1.0, 100.0, 2.0)
        }
        check(size == 76)
        val exports = listOf(GpuReferenceImages.Kind.GREY, GpuReferenceImages.Kind.DARK,
            GpuReferenceImages.Kind.HDR_LIGHTS, GpuReferenceImages.Kind.SKIN,
            GpuReferenceImages.Kind.SATURATED, GpuReferenceImages.Kind.NEAR_WHITE_2, GpuReferenceImages.Kind.MIXED)
        for ((w, h) in listOf(1600 to 1200, 1597 to 1199)) for ((i, kind) in exports.withIndex())
            addCase("export", kind, w, h, listOf(0.0, .25, .5, 1.0)[i % 4],
                if (i == 3) -100.0 else if (i == 6) 100.0 else 0.0,
                if (i == 3) -2.0 else if (i == 6) 2.0 else 0.0, bands = true)
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
