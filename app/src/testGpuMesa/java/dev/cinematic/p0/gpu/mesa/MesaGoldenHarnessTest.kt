package dev.cinematic.p0.gpu.mesa

import dev.cinematic.p0.gpu.*
import dev.cinematic.p0.gpu.diag.GpuStageTap
import dev.cinematic.p0.gpu.export.GpuExportRenderer
import dev.cinematic.p0.gpu.reference.GpuReferenceImages
import dev.cinematic.p0.raw.LinearSrgbImage
import dev.cinematic.p0.raw.PhotoAdjustments
import dev.cinematic.p0.raw.original.OriginalRaster
import dev.cinematic.p0.raw.original.originalCheckpoint
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import kotlin.math.pow
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test
import org.lwjgl.opengles.GLES32

class MesaGoldenHarnessTest {
    @Test fun captureGoldenMatrixOnMesa() {
        val matrix = matrix()
        check(matrix.size == 90 && matrix.map { it.id }.toSet().size == 90)
        checkBaseline(matrix)

        // 全件は10〜40分級のため、本人の指示により手動起動または main 向けに限る。
        // 既定の Mesa ジョブでは早期 return し、JUnit の skip を発生させない。
        if (System.getProperty("gpu.mesa.golden") != "true") return

        val runId = System.getProperty("gpu.mesa.goldenRunId", "mesa-run")
        require(runId.matches(Regex("[A-Za-z0-9_-]{1,80}"))) { "Invalid Mesa golden run ID: $runId" }
        val cases = selectCases(matrix)

        val parent = File(System.getProperty("gpu.mesa.goldenDir", "build/gpuMesaGolden"))
        val directory = File(parent, runId)
        check(!directory.exists()) { "Never overwrite a previous candidate: $runId" }
        check(parent.isDirectory || parent.mkdirs() || parent.isDirectory)
        check(directory.mkdir()) { "Cannot create candidate directory: $runId" }

        val records = mutableListOf<Map<String, Any?>>()
        val manifest = linkedMapOf<String, Any?>(
            "schema" to 1,
            "status" to "incomplete",
            "matrixVersion" to "stage-clear-90-v1",
            "caseCount" to cases.size,
            "runId" to runId,
            "fixtureSeed" to GpuReferenceImages.SEED,
            "encoding" to "row-major RGB8 sRGB, top row first",
            "baselineApproved" to false,
            "cases" to records,
        )
        val manifestFile = File(directory, "capture.json")
        fun persist() = manifestFile.writeText(MesaGoldenJson.encode(manifest))
        persist()
        try {
            MesaExecutionContext().use { context ->
                GlCapabilities.inspect(context).requireSupported()
                writeHost(directory, runId)
                for (case in cases) {
                    println("GPU_MESA_GOLDEN BEGIN ${case.id}")
                    val input = GpuReferenceImages.create(case.kind, GpuReferenceImages.Size(case.width, case.height))
                    val full = if (case.bands) exportFullFrame(context, input, case.params)
                        else preview(context, input, case.params)
                    val file = File(directory, "${case.id}.rgb8.gz")
                    val rgb = ByteArray(full.size * 3) { i -> (full[i / 3] ushr (16 - 8 * (i % 3))).toByte() }
                    GZIPOutputStream(file.outputStream().buffered()).use { it.write(rgb) }
                    val bandRowsPassed = mutableListOf<Int>()
                    val record = case.json().apply {
                        put("file", file.name)
                        put("rgbSha256", sha(rgb))
                        put("fileSha256", sha(file.readBytes()))
                        put("compressedBytes", file.length())
                        put("bandRowsPassed", bandRowsPassed)
                    }
                    records.add(record)
                    // 帯比較に失敗しても、全画面画像と比較結果を調べられるようにする。
                    persist()
                    if (case.bands) for (rows in listOf(64, 256)) {
                        compareBand(context, input, case.params, rows, full, record)
                        bandRowsPassed.add(rows)
                        persist()
                    }
                    println("GPU_MESA_GOLDEN PASS ${case.id}")
                }
            }
            val bytes = records.sumOf { it.getValue("compressedBytes") as Long }
            manifest["totalImageBytes"] = bytes
            check(records.size == cases.size)
            if (cases.size == matrix.size) {
                check(bytes <= 20_000_000L) { "Candidate images exceed 20 MB" }
                manifest["status"] = "complete"
            } else {
                manifest["status"] = "partial"
            }
        } finally {
            persist()
        }
    }

    private fun selectCases(matrix: List<Case>): List<Case> {
        val selection = System.getProperty("gpu.mesa.goldenCases").orEmpty()
        if (selection.isBlank()) return matrix
        val tokens = selection.split(',').map { it.trim() }.toSet()
        val unknown = tokens.filter { token -> matrix.none { it.id == token || it.group == token } }
        require(unknown.isEmpty()) { "Unknown Mesa golden case or group: $unknown" }
        return matrix.filter { it.id in tokens || it.group in tokens }
    }

    private fun checkBaseline(cases: List<Case>) {
        val path = System.getProperty("gpu.mesa.goldenBaseline")
        if (path == null) {
            println("GPU_MESA_GOLDEN baseline check skipped")
            return
        }
        val baseline = MesaGoldenJson.decode(File(path).readText()) as Map<*, *>
        val records = (baseline["cases"] as List<*>).map { it as Map<*, *> }
        check(baseline["caseCount"] == 90.0 && records.size == 90) { "Expected 90 baseline cases" }
        val byId = records.associateBy { it["id"] }
        check(byId.size == 90) { "Duplicate baseline case IDs" }
        for (case in cases) {
            val record = checkNotNull(byId[case.id]) { "Missing baseline id=${case.id}" }
            for ((field, value) in case.json()) {
                val expected = record[field]
                val actual = if (value is Number) value.toDouble() else value
                check(expected == actual) {
                    "Baseline ${case.id} field $field: expected=$expected actual=$actual"
                }
            }
        }
    }

    private fun writeHost(directory: File, runId: String) {
        val host = linkedMapOf(
            "schema" to 1,
            "sourceCommit" to System.getProperty("gpu.mesa.goldenBaseCommit", "unknown"),
            "sourceTree" to System.getProperty("gpu.mesa.goldenBaseTree", "unknown"),
            "glVendor" to GLES32.glGetString(GLES32.GL_VENDOR),
            "glRenderer" to GLES32.glGetString(GLES32.GL_RENDERER),
            "glVersion" to GLES32.glGetString(GLES32.GL_VERSION),
            "glslVersion" to GLES32.glGetString(GLES32.GL_SHADING_LANGUAGE_VERSION),
            "javaVersion" to System.getProperty("java.version"),
            "osName" to System.getProperty("os.name"),
            "osArch" to System.getProperty("os.arch"),
            "mesaEnabled" to true,
            "runId" to runId,
            "capturedUtc" to Instant.now().toString(),
            "cpu" to readHostCpu(),
            "baseLibraries" to readBaseLibraryVersions(),
        )
        File(directory, "host.json").writeText(MesaGoldenJson.encode(host))
    }

    // ステップ5の再現性確認で、2回の実行が同じ CPU 機種に当たり、CPU 差を確認できなかった場合を判別する。
    private fun readHostCpu(): Map<String, Any> {
        val unknown = mapOf("modelName" to "unknown", "flags" to emptyList<String>())
        val lines = try {
            File("/proc/cpuinfo").readLines()
        } catch (_: IOException) {
            return unknown
        } catch (_: SecurityException) {
            return unknown
        }
        val entries = lines.filter { ':' in it }
            .groupBy({ it.substringBefore(':').trim() }, { it.substringAfter(':').trim() })
        val flags = entries["flags"]?.firstOrNull()?.split(Regex("\\s+"))?.toSet().orEmpty()
        val vectorFlags = listOf(
            "avx", "avx2", "avx512f", "avx512bw", "avx512vl", "avx512dq",
            "sse4_1", "sse4_2", "ssse3", "fma", "f16c",
        )
        return mapOf(
            "modelName" to (entries["model name"]?.firstOrNull()?.ifBlank { "unknown" } ?: "unknown"),
            "flags" to vectorFlags.filter { it in flags },
            "count" to entries["processor"].orEmpty().size,
        )
    }

    // マニフェストでインストール対象の区分Bについて、Mesa ジョブの記録から実際の版と由来を残す。
    // 由来はランナーの版を使ったか、保管版をインストールしたかを示す。
    private fun readBaseLibraryVersions(): Map<String, Map<String, String>> {
        val path = System.getProperty("gpu.mesa.debManifest")
        check(path != null) { "Missing gpu.mesa.debManifest" }
        val lines = try {
            File(path).readLines()
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
        check(lines != null) { "Cannot read Mesa deb manifest: $path" }
        val packages = lines.asSequence()
            .map { it.trim() }
            .filterNot { it.startsWith("#") }
            .map { it.split(" | ") }
            .filter { it.size == 7 && it[5] == "B" && it[6] == "yes" && it[1].isNotBlank() }
            .map { it[1] }
            .toSet()
        check(packages.isNotEmpty()) { "Mesa deb manifest has no category B installation targets" }

        val recordPath = System.getProperty("gpu.mesa.installRecord")
        check(recordPath != null) { "Missing gpu.mesa.installRecord" }
        val records = try {
            File(recordPath).readLines()
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
        check(records != null) { "Cannot read Mesa install record: $recordPath" }

        val recordPattern = Regex("""(\S+) class=([AB]) source=(runner|stored) (\S+) install ok installed""")
        val installed = mutableMapOf<String, Map<String, String>>()
        for ((index, line) in records.withIndex()) {
            val match = recordPattern.matchEntire(line)
            check(match != null) { "Invalid Mesa install record at line ${index + 1}" }
            val (name, category, source, version) = match.destructured
            if (category != "B") continue
            check(name !in installed) { "Duplicate category B package in Mesa install record: $name" }
            installed[name] = mapOf("version" to version, "source" to source)
        }
        check(installed.keys == packages) {
            "Mesa install record category B mismatch: missing=${packages - installed.keys}, extra=${installed.keys - packages}"
        }
        return packages.associateWith { installed.getValue(it) }
    }

    // renderForTest は presenter を使わない。Mesa のコンテキストは既に current。
    private val presenter = object : GpuPreviewPresenter {
        override fun makeCurrent() = Unit
        override fun present(image: GpuTexture) = Unit
    }

    private fun preview(egl: MesaExecutionContext, input: LinearSrgbImage, params: GpuRenderParams): IntArray {
        val ledger = GpuMemoryLedger(GpuMemoryLedger.PREVIEW_BUDGET)
        var pixels: IntArray? = null
        DefaultStageClearGpu(egl, ledger, createPresenter = { _, _ -> presenter }) { texture, analysis ->
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
                // 製品エンジンで写真ごとの解析を実行する。
                engine.renderForTest(photo, params, GpuStageTap.Point.SHOULDER)
            }
        }
        released(ledger)
        return checkNotNull(pixels)
    }

    private fun exportFullFrame(egl: MesaExecutionContext, input: LinearSrgbImage, params: GpuRenderParams): IntArray {
        // 全画面の比較用台帳。製品の帯出力では 192 MiB を使う。
        val ledger = GpuMemoryLedger(512L * 1024 * 1024)
        var pixels: IntArray? = null
        GpuTexture.create(egl, ledger, input.width, input.height, GpuTexture.Format.RGBA32F).use { texture ->
            val rgba = ByteBuffer.allocateDirect(input.pixelCount * 16).order(ByteOrder.nativeOrder()).asFloatBuffer()
            repeat(input.pixelCount) { p ->
                repeat(3) { c -> rgba.put(input[p * 3 + c]) }
                rgba.put(1f)
            }
            rgba.flip()
            texture.upload(rgba)
            // 帯出力との比較に合わせ、基準 EV を 0 に固定する。
            ConnectedStageClearGpuPipeline(texture, GpuPhotoAnalysis(0.0, 0f), GpuFrameMode.FULL_RECOMPUTE).use { pipeline ->
                pipeline.render(params, { point, image ->
                    if (point == GpuStageTap.Point.DISPLAY8) pixels = readArgb(egl, image)
                }) { _, _ -> }
            }
        }
        released(ledger)
        return checkNotNull(pixels)
    }

    private fun compareBand(egl: MesaExecutionContext, input: LinearSrgbImage, params: GpuRenderParams,
        rows: Int, full: IntArray, record: MutableMap<String, Any?>) {
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
        record["bands$rows"] = linkedMapOf(
            "changedPixels" to different, "maxDelta" to maxDelta,
            "firstChangedXY" to first, "peakTextureBytes" to ledger.peakAllocatedBytes,
        )
        assertEquals(input.height, next)
        assertEquals("${record["id"]} band=$rows maxDelta=$maxDelta firstXY=$first", 0L, different)
    }

    private fun released(ledger: GpuMemoryLedger) {
        assertEquals("All textures released", 0, ledger.textureCount)
        assertEquals("All texture bytes released", 0L, ledger.allocatedBytes)
    }

    private fun readArgb(egl: MesaExecutionContext, texture: GpuTexture): IntArray {
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
        fun json() = linkedMapOf<String, Any?>(
            "id" to id, "group" to group, "kind" to kind.name,
            "width" to width, "height" to height, "strength" to params.strength,
            "warmth" to params.adjustments.warmth, "ev" to params.adjustments.exposureEv,
            "analysis" to if (bands) "fixed-base-ev-0" else "production-photo-analysis",
        )
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
