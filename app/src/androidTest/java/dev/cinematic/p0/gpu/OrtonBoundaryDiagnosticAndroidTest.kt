package dev.cinematic.p0.gpu

import dev.cinematic.p0.gpu.constants.StageClearOrtonV1
import android.opengl.GLES32
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.gpu.diag.OrtonDisplayAcceptance
import dev.cinematic.p0.gpu.diag.GpuStageTap
import dev.cinematic.p0.gpu.diag.GpuStageTap.Point
import dev.cinematic.p0.gpu.reference.GpuReferenceImages
import dev.cinematic.p0.gpu.reference.ImageDiff
import dev.cinematic.p0.gpu.reference.KotlinReference
import dev.cinematic.p0.gpu.resample.GpuResample
import dev.cinematic.p0.raw.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** #284 の失敗条件だけの観測。診断完走は全工程の一致合格を意味しない。 */
@RunWith(AndroidJUnit4::class)
class OrtonBoundaryDiagnosticAndroidTest {
    @Before fun isolatedEmulatorOnly() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish")) { "Synthetic emulator only" }
    }

    @Test fun captureBoundaryAndReportHistoricalDisplayReference() = GlThread().use { thread ->
        thread.call {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            // The non-debuggable diagnostic APK cannot use run-as. App-specific external storage
            // makes only these synthetic, isolated-emulator artifacts retrievable with adb pull.
            val root = checkNotNull(context.getExternalFilesDir("gpu-diagnostics")) { "Synthetic artifact storage unavailable" }
            val folder = File(root, "orton-boundary-diagnostic")
            check(folder.mkdirs() || folder.isDirectory)
            val input = GpuReferenceImages.create(GpuReferenceImages.Kind.MIXED, GpuReferenceImages.Size(256, 192))
            val params = GpuRenderParams(.5, PhotoAdjustments(0.0, 1.0))
            val reference = KotlinReference.render(input, params)
            reference.images.forEach { (point, image) -> write(folder, "cpu_${point.name}", image.copyPixels()) }
            write(folder, "cpu_SKIN_WEIGHT", reference.skinWeight.copyValues())
            writeDisplay(folder, "cpu_DISPLAY8", reference.display8)
            val cpuMist = reference[Point.MIST]
            val capturedOrton = BoundaryStageClearOrton.render(
                StageClearMist.Result(cpuMist, reference.lightAddingStrength), reference.skinWeight)
            assertBits("Instrumented CPU Orton equals actual reference", reference[Point.ORTON], capturedOrton)
            val method = StageClearOrton::class.java.getDeclaredMethod("mixOrton", LinearSrgbImage::class.java,
                PreviewPixelExecution::class.java).also { it.isAccessible = true }
            val actualMix = method.invoke(StageClearOrton, cpuMist, null) as LinearSrgbImage
            assertArrayEquals("Instrumented CPU mix equals original private mix", actualMix.copyPixels().map { it.toRawBits() }.toIntArray(),
                BoundaryStageClearOrton.diagnosticMixed.map { it.toRawBits() }.toIntArray())
            write(folder, "cpu_ORTON_VALUE", BoundaryStageClearOrton.diagnosticValues)
            write(folder, "cpu_ORTON_MIX", BoundaryStageClearOrton.diagnosticMixed)
            EglContexts(thread, context.assets).use { egl ->
                GlCapabilities.inspect(egl).requireSupported()
                val report = JSONObject().put("scope", "Single-condition diagnostic; approved Kotlin-only boundary max exception; no full-matrix acceptance")
                    .put("width", input.width).put("height", input.height).put("strength", .5).put("warmth", 0.0).put("exposureEv", 1.0)
                    .put("renderer", GLES32.glGetString(GLES32.GL_RENDERER)).put("version", GLES32.glGetString(GLES32.GL_VERSION))
                val ledger = GpuMemoryLedger()
                val images = mutableMapOf<Point, LinearSrgbImage>()
                var skin: FloatPlane? = null
                var actualDisplay: IntArray? = null
                var decisions: GpuDecisions? = null
                DefaultStageClearGpu(egl, ledger) { texture, analysis ->
                    val connected = ConnectedStageClearGpuPipeline(texture, analysis, GpuFrameMode.FULL_RECOMPUTE)
                    object : StageClearGpuPipeline {
                        override fun render(params: GpuRenderParams, tap: ((Point, GpuTexture) -> Unit)?,
                            consume: (GpuTexture, GpuDecisions) -> Unit) = connected.render(params, { point, image ->
                            when (point) {
                                Point.DISPLAY8 -> actualDisplay = read8(egl, image).also { writeDisplay(folder, "gpu_DISPLAY8", it) }
                                Point.SKIN_WEIGHT -> skin = GpuStageTap.readPlane(egl, image).also { write(folder, "gpu_SKIN_WEIGHT", it.copyValues()) }
                                else -> images[point] = GpuStageTap.readRgb(egl, image).also { write(folder, "gpu_${point.name}", it.copyPixels()) }
                            }
                            tap?.invoke(point, image)
                        }) { output, value -> decisions = value; consume(output, value) }
                override fun close() = connected.close()
                    }
                }.use { engine ->
                    engine.openPhoto(input) { false }.use { photo ->
                        report.put("meteredBaseEv", photo.analysis.meteredBaseEv)
                        engine.renderForTest(photo, params, Point.SHOULDER)
                    }
                }
                assertEquals(0L, ledger.allocatedBytes); assertEquals(0, ledger.textureCount)
                val display = checkNotNull(actualDisplay)
                val diff = ImageDiff.display(reference.display8, display, input.width, input.height, "unchanged boundary")
                val gpuMist = checkNotNull(images[Point.MIST])
                val acceptance = OrtonDisplayAcceptance.evaluate(input.width, input.height,
                    { cpuMist[it] }, { gpuMist[it] }, reference.display8, display)
                val acceptanceLog = acceptance.logLines().map { "CPU_REFERENCE_ONLY ${it.replace("passed=", "historicalWithinReference=")}" }.toList()
                File(folder, "display-acceptance.log").writeText(acceptanceLog.joinToString("\n", postfix = "\n"))
                acceptanceLog.forEach { Log.i("OrtonBoundary", it) }
                assertEquals("All RGB remain in p999", diff.p999, acceptance.p999AllRgb, 0.0)
                report.put("displayMax", diff.maxDiff).put("displayP999", diff.p999).put("displayMean", diff.meanDiff)
                    .put("changedPixels", diff.changedPixels).put("historicalAllPixelWithinReference", diff.maxDiff <= 2 && diff.p999 <= 1)
                    .put("historicalMaskedDisplayWithinReference", acceptance.passed).put("maskSource", "Kotlin MIST only; any RGB within 1e-5 of 1.0")
                    .put("maskedPixels", acceptance.maskedPixels.size).put("maskedFraction", acceptance.maskFraction)
                    .put("maskWithinLimit", acceptance.maskWithinLimit).put("maxOutsideMask", acceptance.maxDiffOutsideMask)
                    .put("p999AllRgb", acceptance.p999AllRgb)
                    .put("pipelinePeakBytes", ledger.peakAllocatedBytes).put("pipelineClosedBytes", ledger.allocatedBytes)
                    .put("mistAutoStrengthCpu", reference.mistAutoStrength).put("mistAutoStrengthGpu", checkNotNull(decisions).mistAutoStrength)
                File(folder, "result.json").writeText(report.toString(2))
                // Re-upload exact captured GPU input only in this isolated diagnostic replay, after the real pipeline closes.
                val diagnosticLedger = GpuMemoryLedger()
                val gpuSkin = checkNotNull(skin)
                upload(egl, diagnosticLedger, gpuMist).use { mist ->
                    upload(egl, diagnosticLedger, gpuSkin).use { skinTexture ->
                        GpuResample(egl, diagnosticLedger).use { resample ->
                            pass(egl, diagnosticLedger, "look/orton_prepare.frag", mist,
                                listOf(TextureInput("s_mist", mist, 0))).use { bright ->
                                resample.blurRgb(bright, StageClearOrtonV1.RADIUS).use { soft ->
                                    val inputs = listOf(TextureInput("s_mist", mist, 0), TextureInput("s_soft", soft, 1))
                                    pass(egl, diagnosticLedger, "look/orton_mix.frag", mist, inputs).use { mixed ->
                                        val original = GpuStageTap.readRgb(egl, mixed)
                                        write(folder, "gpu_ORTON_MIX", original.copyPixels())
                                        val source = ShaderSource({ path -> context.assets.open("gpu/$path").bufferedReader().use { it.readText() } })
                                            .expand("look/orton_mix.frag")
                                        val marker = "layout(location = 0) out vec4 o_color;"
                                        val output = "    o_color = vec4(mixed, 1.0);"
                                        check(source.contains(marker) && source.indexOf(marker) == source.lastIndexOf(marker) && source.contains(output))
                                        val diagnostic = source.replace(marker, "$marker\nlayout(location = 1) out vec4 o_value;")
                                            .replace(output, "$output\n    o_value = vec4(value, 1.0);")
                                        egl.shaders().fragmentSource("Orton value diagnostic MRT", diagnostic).use { program ->
                                            texture(egl, diagnosticLedger, mist).use { diagnosticMixed ->
                                                texture(egl, diagnosticLedger, mist).use { value ->
                                                    egl.passes().run(FragmentPass(program, inputs, diagnosticMixed, additionalOutputs = listOf(value)))
                                                    assertBits("MRT preserves original mix", original, GpuStageTap.readRgb(egl, diagnosticMixed))
                                                    write(folder, "gpu_ORTON_VALUE", GpuStageTap.readRgb(egl, value).copyPixels())
                                                }
                                            }
                                        }
                                        pass(egl, diagnosticLedger, "look/orton_protect.frag", mist,
                                            listOf(TextureInput("s_mist", mist, 0), TextureInput("s_mixed", mixed, 1), TextureInput("s_skinWeight", skinTexture, 2))) { program ->
                                            GLES32.glUniform1f(GLES32.glGetUniformLocation(program, "u_amount"),
                                                (checkNotNull(decisions).mistAutoStrength * params.strength).toFloat())
                                        }.use { protected -> assertBits("Diagnostic replay equals full pipeline ORTON", checkNotNull(images[Point.ORTON]), GpuStageTap.readRgb(egl, protected)) }
                                    }
                                }
                            }
                        }
                    }
                }
                assertEquals(0L, diagnosticLedger.allocatedBytes)
                report.put("cpuInstrumentationRawBitsEqual", true).put("gpuMrtMixedRawBitsEqual", true)
                    .put("gpuReplayOrtonRawBitsEqual", true).put("diagnosticClosedBytes", diagnosticLedger.allocatedBytes)
                File(folder, "result.json").writeText(report.toString(2))
                Log.i("OrtonBoundary", "DIAGNOSTIC COMPLETE maxAll=${diff.maxDiff} maxOutside=${acceptance.maxDiffOutsideMask} " +
                    "p999All=${acceptance.p999AllRgb} CPU_REFERENCE_ONLY historicalWithinReference=${acceptance.passed}; artifacts=${folder.name}")
                Unit
            }
        }
    }

    private fun assertBits(label: String, expected: LinearSrgbImage, actual: LinearSrgbImage) {
        assertArrayEquals(label, expected.copyPixels().map { it.toRawBits() }.toIntArray(), actual.copyPixels().map { it.toRawBits() }.toIntArray())
    }
    private fun write(folder: File, name: String, values: FloatArray) {
        val bytes = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        bytes.asFloatBuffer().put(values); File(folder, "$name.bin").writeBytes(bytes.array())
    }
    private fun writeDisplay(folder: File, name: String, pixels: IntArray) = File(folder, "$name.bin").writeBytes(
        ByteArray(pixels.size * 3) { i -> (pixels[i / 3] ushr (16 - 8 * (i % 3))).toByte() })
    private fun texture(egl: EglContexts, ledger: GpuMemoryLedger, size: GpuTexture) =
        GpuTexture.create(egl, ledger, size.width, size.height, GpuTexture.Format.RGBA32F)
    private fun pass(egl: EglContexts, ledger: GpuMemoryLedger, path: String, size: GpuTexture,
        inputs: List<TextureInput>, uniforms: (Int) -> Unit = {}): GpuTexture {
        val result = texture(egl, ledger, size)
        try { egl.passes().run(FragmentPass(egl.shaders().fragment(path), inputs, result, uniforms = uniforms)); return result }
        catch (failure: Throwable) { result.close(); throw failure }
    }
    private fun upload(egl: EglContexts, ledger: GpuMemoryLedger, image: LinearSrgbImage): GpuTexture {
        val texture = GpuTexture.create(egl, ledger, image.width, image.height, GpuTexture.Format.RGBA32F)
        val data = ByteBuffer.allocateDirect(image.pixelCount * 16).order(ByteOrder.nativeOrder()).asFloatBuffer()
        repeat(image.pixelCount) { p -> repeat(3) { data.put(image[p * 3 + it]) }; data.put(1f) }
        data.flip(); texture.upload(data); return texture
    }
    private fun upload(egl: EglContexts, ledger: GpuMemoryLedger, image: FloatPlane): GpuTexture {
        val texture = GpuTexture.create(egl, ledger, image.width, image.height, GpuTexture.Format.R32F)
        val data = ByteBuffer.allocateDirect(image.width * image.height * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        data.put(image.copyValues()); data.flip(); texture.upload(data); return texture
    }
    private fun read8(egl: EglContexts, image: GpuTexture): IntArray {
        GpuFence(egl).use { it.await() }
        val data = ByteBuffer.allocateDirect(image.width * image.height * 4)
        GLES32.glBindFramebuffer(GLES32.GL_FRAMEBUFFER, image.framebufferId)
        GLES32.glReadBuffer(GLES32.GL_COLOR_ATTACHMENT0)
        GLES32.glBindBuffer(GLES32.GL_PIXEL_PACK_BUFFER, 0); GLES32.glPixelStorei(GLES32.GL_PACK_ALIGNMENT, 1)
        GLES32.glPixelStorei(GLES32.GL_PACK_ROW_LENGTH, 0); GLES32.glPixelStorei(GLES32.GL_PACK_SKIP_PIXELS, 0); GLES32.glPixelStorei(GLES32.GL_PACK_SKIP_ROWS, 0)
        GLES32.glReadPixels(0, 0, image.width, image.height, GLES32.GL_RGBA, GLES32.GL_UNSIGNED_BYTE, data)
        egl.checkGl("read boundary DISPLAY8")
        return IntArray(image.width * image.height) { p -> (0xff shl 24) or ((data.get(p * 4).toInt() and 255) shl 16) or
            ((data.get(p * 4 + 1).toInt() and 255) shl 8) or (data.get(p * 4 + 2).toInt() and 255) }
    }
}
