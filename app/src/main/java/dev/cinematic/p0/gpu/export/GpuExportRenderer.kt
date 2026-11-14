package dev.cinematic.p0.gpu.export

import dev.cinematic.p0.gpu.constants.R7BaseV1
import dev.cinematic.p0.gpu.constants.StageClearCharacterV1
import dev.cinematic.p0.gpu.constants.StageClearOrtonV1
import dev.cinematic.p0.gpu.constants.StageClearMistV1
import dev.cinematic.p0.gpu.constants.StageClearGlowV1
import android.opengl.GLES32
import dev.cinematic.p0.gpu.*
import dev.cinematic.p0.gpu.analysis.GpuAnalysis
import dev.cinematic.p0.gpu.diag.GpuStageTap
import dev.cinematic.p0.gpu.look.StageClearGlowGpu
import dev.cinematic.p0.gpu.resample.GpuResample
import dev.cinematic.p0.raw.*
import dev.cinematic.p0.raw.original.OriginalRaster
import dev.cinematic.p0.raw.original.originalCheckpoint
import dev.cinematic.p0.raw.original.validateOriginalSize
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round

/** GL-thread-only, bounded export. No full-size GPU image or intermediate disk raster.
 * EGL and ledger are borrowed. Every render owns and releases all of its fields/textures.
 * Rows are 0xffRRGGBB for the existing Bitmap.setPixels path; their array is borrowed.
 */
internal class GpuExportRenderer(
    private val egl: EglContexts,
    private val ledger: GpuMemoryLedger = GpuMemoryLedger(GpuMemoryLedger.EXPORT_BUDGET),
    private val bandHeight: Int = GpuExportBands.HEIGHT,
) {
    internal data class Result(val toneQuantiles: DoubleArray, val nearWhiteCount: Long,
        val mistAutoStrength: Double, val peakTextureBytes: Long, val boundaryNanos: Map<String, Long>)
    private var rendering = false

    fun render(input: OriginalRaster, externalEv: Double, warmthGains: DoubleArray, strength: Double,
        stop: () -> Boolean = { false },
        tap: ((GpuStageTap.Point, Int, GpuTexture) -> Unit)? = null,
        onBandComplete: ((String, Int, Int) -> Unit)? = null,
        consumeRow: (Int, IntArray) -> Unit): Result {
        egl.requireCurrent()
        check(!rendering) { "Export renderer is already running" }
        validateOriginalSize(input.width, input.height)
        val cell = .04 * max(input.width, input.height)
        val toneWidth = max(4, round(input.width / cell).toInt())
        val toneHeight = max(4, round(input.height / cell).toInt())
        require(toneWidth <= input.width && toneHeight <= input.height) {
            "Export source must be at least as large as its global tone grid"
        }
        require(strength.isFinite() && strength in 0.0..1.0)
        require(warmthGains.size == 3 && warmthGains.all { it.isFinite() && it > 0.0 && it.toFloat().isFinite() })
        val gain = 2.0.pow(externalEv + R7BaseV1.BRIGHTNESS_EV + R7BaseV1.FOUNDATION_EV).toFloat()
        require(externalEv.isFinite() && gain.isFinite() && gain > 0f)
        require(bandHeight == 256 || bandHeight == 64)
        require(ledger.budgetBytes <= GpuMemoryLedger.EXPORT_BUDGET) { "Export budget must not exceed 192 MiB" }
        originalCheckpoint(stop)
        val capabilities = GlCapabilities.inspect(egl)
        capabilities.requireSupported()
        require(input.width <= capabilities.maxTextureSize && input.height <= capabilities.maxTextureSize)
        rendering = true
        try {
            return Session(input, gain, warmthGains.copyOf(), strength, stop, tap, onBandComplete, toneWidth, toneHeight).use { it.run(consumeRow) }
        } finally { rendering = false }
    }

    private inner class Session(val input: OriginalRaster, val gain: Float, val warmth: DoubleArray,
        val strength: Double, val stop: () -> Boolean,
        val tap: ((GpuStageTap.Point, Int, GpuTexture) -> Unit)?,
        val onBandComplete: ((String, Int, Int) -> Unit)?,
        val toneWidth: Int, val toneHeight: Int) : AutoCloseable {
        val width = input.width; val height = input.height
        val resample = GpuResample(egl, ledger)
        private val fields = mutableListOf<GpuExportField>()
        private val times = linkedMapOf<String, Long>()
        private var boundaryName = ""
        lateinit var tone: GpuExportField
        lateinit var skin: GpuExportField
        val bloom = mutableListOf<GpuExportField>()
        lateinit var halation: GpuExportField
        lateinit var diffusion: GpuExportField
        val mist = mutableListOf<GpuExportField>()
        lateinit var orton: GpuExportField
        var quantiles = doubleArrayOf(0.0, 0.0)
        var nearWhite = 0L
        var autoStrength = 1.0
        val amount get() = autoStrength * strength

        private fun field(w: Int, h: Int, format: GpuTexture.Format,
            filter: StageClearBlur.Filter = StageClearBlur.Filter.BILINEAR) =
            GpuExportField(egl, ledger, resample, width, height, w, h, format, filter).also(fields::add)
        private fun blur(radius: Double, format: GpuTexture.Format): GpuExportField {
            val (w, h) = StageClearBlur.reducedSize(width, height, radius)
            return field(w, h, format)
        }
        private fun boundary(name: String, action: () -> Unit) {
            originalCheckpoint(stop)
            val start = System.nanoTime()
            boundaryName = name
            action()
            GpuFence(egl).use { it.await(stop) }
            times[name] = System.nanoTime() - start
            originalCheckpoint(stop)
        }
        private fun scan(action: (Band) -> Unit) {
            GpuExportBands.forEach(input, bandHeight, stop) { origin, rows, rgba ->
                val upload = GpuTexture.create(egl, ledger, width, rows, GpuTexture.Format.RGBA32F)
                try { upload.upload(rgba) } catch (failure: Throwable) { upload.close(); throw failure }
                Band(origin, upload).use { band ->
                    action(band)
                    // Do not queue many bands ahead of cancellation. At most this band's work
                    // remains submitted when stop becomes true.
                    GpuFence(egl).use { it.await(stop) }
                }
                onBandComplete?.invoke(boundaryName, origin, rows)
            }
        }

        fun run(consumeRow: (Int, IntArray) -> Unit): Result {
            boundary("B1") {
                tone = field(toneWidth, toneHeight,
                    GpuTexture.Format.R32F, StageClearBlur.Filter.BICUBIC)
                scan { band ->
                    val profile = band.profile()
                    val log = band.draw("r7/log_luma.frag", listOf("s_input" to profile), GpuTexture.Format.R32F)
                    band.release(profile)
                    tone.accept(band.origin, log)
                }
            }
            boundary("B1_QUANTILES") {
                quantiles = GpuExportAnalysis.quantiles(egl, width, height, stop) { consume ->
                    for (origin in 0 until height step bandHeight) {
                        originalCheckpoint(stop)
                        tone.band(origin, minOf(bandHeight, height - origin)).use { band ->
                            consume(band)
                            GpuFence(egl).use { it.await(stop) }
                        }
                        onBandComplete?.invoke(boundaryName, origin, minOf(bandHeight, height - origin))
                        originalCheckpoint(stop)
                    }
                }
            }
            if (strength != 0.0) {
                boundary("B2") {
                    skin = blur(StageClearGlowV1.SKIN_RADIUS, GpuTexture.Format.R32F)
                    StageClearGlowV1.BLOOM_KERNELS.forEach { (radius, _) -> bloom += blur(radius, GpuTexture.Format.RGBA32F) }
                    halation = blur(StageClearGlowV1.HALATION_RADIUS, GpuTexture.Format.R32F)
                    scan { band ->
                        val base = band.base()
                        val weights = band.draw("look/skin_weight.frag", listOf("s_base" to base), GpuTexture.Format.R32F)
                        skin.accept(band.origin, weights); band.release(weights)
                        val source = band.draw("look/glow_source.frag", listOf("s_base" to base))
                        band.release(base)
                        bloom.forEach { it.accept(band.origin, source) }
                        val excess = band.draw("look/glow_excess.frag", listOf("s_source" to source), GpuTexture.Format.R32F)
                        band.release(source); halation.accept(band.origin, excess)
                    }
                }
                boundary("B3") {
                    diffusion = blur(StageClearGlowV1.DIFFUSION_RADIUS, GpuTexture.Format.RGBA32F)
                    scan { band -> diffusion.accept(band.origin, band.glow(withDiffusion = false)) }
                }
            }
            boundary("B4") {
                if (strength != 0.0) StageClearMistV1.KERNELS.forEach { (radius, _) -> mist += blur(radius, GpuTexture.Format.RGBA32F) }
                scan { band ->
                    val glowed = band.glow()
                    nearWhite += GpuAnalysis.nearWhiteCount(glowed, width, glowed.height)
                    if (strength != 0.0) {
                        val source = band.draw("look/mist_source.frag", listOf("s_glowed" to glowed))
                        band.release(glowed); mist.forEach { it.accept(band.origin, source) }
                    }
                }
                val ratio = ((nearWhite.toDouble() / (width.toLong() * height) - StageClearMistV1.AUTO_START) /
                    StageClearMistV1.AUTO_SPAN).coerceIn(0.0, 1.0)
                autoStrength = 1 - StageClearMistV1.AUTO_REDUCTION * ratio
            }
            if (amount != 0.0) boundary("B5") {
                orton = blur(StageClearOrtonV1.RADIUS, GpuTexture.Format.RGBA32F)
                scan { band ->
                    val misted = band.misted()
                    val bright = band.draw("look/orton_prepare.frag", listOf("s_mist" to misted))
                    band.release(misted); orton.accept(band.origin, bright)
                }
            }
            boundary("FINAL") {
                val bytes = ByteBuffer.allocateDirect(Math.multiplyExact(width * minOf(height, bandHeight), 4))
                val row = IntArray(width)
                scan { band ->
                    val output = band.finish()
                    GpuFence(egl).use { it.await(stop) }
                    bytes.clear()
                    GLES32.glBindFramebuffer(GLES32.GL_FRAMEBUFFER, output.framebufferId)
                    GLES32.glReadBuffer(GLES32.GL_COLOR_ATTACHMENT0)
                    GLES32.glBindBuffer(GLES32.GL_PIXEL_PACK_BUFFER, 0)
                    GLES32.glPixelStorei(GLES32.GL_PACK_ALIGNMENT, 1)
                    GLES32.glPixelStorei(GLES32.GL_PACK_ROW_LENGTH, 0)
                    GLES32.glPixelStorei(GLES32.GL_PACK_SKIP_PIXELS, 0)
                    GLES32.glPixelStorei(GLES32.GL_PACK_SKIP_ROWS, 0)
                    GLES32.glReadPixels(0, 0, width, output.height, GLES32.GL_RGBA, GLES32.GL_UNSIGNED_BYTE, bytes)
                    egl.checkGl("read export band RGBA8")
                    repeat(output.height) { y ->
                        originalCheckpoint(stop)
                        for (x in 0 until width) {
                            val i = (y * width + x) * 4
                            row[x] = (255 shl 24) or ((bytes.get(i).toInt() and 255) shl 16) or
                                ((bytes.get(i + 1).toInt() and 255) shl 8) or (bytes.get(i + 2).toInt() and 255)
                        }
                        consumeRow(band.origin + y, row)
                    }
                }
            }
            return Result(quantiles.copyOf(), nearWhite, autoStrength, ledger.peakAllocatedBytes, times.toMap())
        }

        inner class Band(val origin: Int, private val inputTexture: GpuTexture) : AutoCloseable {
            private val owned = linkedSetOf(inputTexture)
            private var clippedSkin: GpuTexture? = null
            val rows = inputTexture.height
            fun own(texture: GpuTexture) = texture.also { owned += it }
            fun release(texture: GpuTexture) { if (texture in owned) { texture.close(); owned.remove(texture) } }
            fun draw(path: String, inputs: List<Pair<String, GpuTexture>>, format: GpuTexture.Format = GpuTexture.Format.RGBA32F,
                uniforms: (Int) -> Unit = {}): GpuTexture {
                val output = own(GpuTexture.create(egl, ledger, width, rows, format))
                egl.passes().run(FragmentPass(egl.shaders().fragment(path), inputs.mapIndexed { unit, (name, texture) ->
                    TextureInput(name, texture, unit) }, output, uniforms = uniforms))
                return output
            }
            private fun expanded(field: GpuExportField) = own(field.band(origin, rows))
            fun profile(): GpuTexture {
                val profile = draw("r7/profile.frag", listOf("s_input" to inputTexture)) { program ->
                    GLES32.glUniform3f(location(program, "u_warmthGain"), warmth[0].toFloat(), warmth[1].toFloat(), warmth[2].toFloat())
                    GLES32.glUniform1i(location(program, "u_warmthOnly"), 0)
                }
                release(inputTexture)
                return profile
            }
            fun base(): GpuTexture {
                val profile = profile(); val smooth = expanded(tone)
                val toned = draw("r7/tone_apply.frag", listOf("s_input" to profile, "s_smoothLogLuma" to smooth)) { program ->
                    GLES32.glUniform2f(location(program, "u_quantiles"), quantiles[0].toFloat(), quantiles[1].toFloat())
                }
                release(profile); release(smooth)
                val roi = own(GpuTexture.create(egl, ledger, width, rows, GpuTexture.Format.R32F))
                GLES32.glBindFramebuffer(GLES32.GL_FRAMEBUFFER, roi.framebufferId)
                GLES32.glDisable(GLES32.GL_SCISSOR_TEST); GLES32.glColorMask(true, true, true, true)
                GLES32.glClearBufferfv(GLES32.GL_COLOR, 0, floatArrayOf(0f, 0f, 0f, 0f), 0)
                val foundation = draw("r7/foundation.frag", listOf("s_input" to toned, "s_roi" to roi)) { program ->
                    GLES32.glUniform1f(location(program, "u_gain"), gain)
                }
                release(toned); release(roi)
                val result = draw("r7/finish.frag", listOf("s_input" to foundation)); release(foundation)
                return result
            }
            private fun skinWeight(): GpuTexture = clippedSkin ?: run {
                val raw = expanded(skin)
                val result = draw("look/glow_skin_clip.frag", listOf("s_blurredSkin" to raw), GpuTexture.Format.R32F)
                release(raw); clippedSkin = result; result
            }
            fun glow(withDiffusion: Boolean = true): GpuTexture {
                val base = base()
                if (strength == 0.0) return base
                val skin = skinWeight()
                var kernel = 0
                val bloomBand = own(StageClearGlowGpu.accumulateBloom(base) { _, radius ->
                    check(radius == StageClearGlowV1.BLOOM_KERNELS[kernel].first)
                    bloom[kernel++].band(origin, rows)
                })
                val halo = expanded(halation)
                val inputs = listOf("s_base" to base, "s_skinWeight" to skin, "s_bloom" to bloomBand, "s_halation" to halo)
                var soft: GpuTexture? = null
                val result = if (withDiffusion && (StageClearGlowV1.DIFFUSION * strength).toFloat() != 0f) {
                    soft = expanded(diffusion)
                    draw("look/glow_diffusion.frag", inputs + ("s_soft" to soft)) { program ->
                        glowUniforms(program)
                        GLES32.glUniform1f(location(program, "u_diffusionAmount"), (StageClearGlowV1.DIFFUSION * strength).toFloat())
                    }
                } else draw("look/glow_compose.frag", inputs, uniforms = ::glowUniforms)
                release(base); release(bloomBand); release(halo); soft?.let(::release)
                return result
            }
            private fun glowUniforms(program: Int) {
                GLES32.glUniform1f(location(program, "u_bloomAmount"), (StageClearGlowV1.BLOOM * strength).toFloat())
                GLES32.glUniform1f(location(program, "u_halationAmount"), (StageClearGlowV1.HALATION * strength).toFloat())
            }
            fun misted(): GpuTexture {
                val glowed = glow()
                if (strength == 0.0) return glowed
                check(mist.size == 2) { "Mist shader must match all kernels" }
                val halos = mist.map(::expanded)
                // The shared shader's last vertical pass is identity on these already expanded
                // band textures. Preserve its composition arithmetic without a shader copy.
                val identity = resample.weights(rows, rows, StageClearBlur.Filter.BILINEAR)
                val result = draw("look/mist_compose.frag", listOf("s_glowed" to glowed, "s_skinWeight" to skinWeight(),
                    "s_halo0" to halos[0], "s_halo1" to halos[1], "s_weights0" to identity.values,
                    "s_first0" to identity.first, "s_weights1" to identity.values, "s_first1" to identity.first)) { program ->
                    GLES32.glUniform1f(location(program, "u_amount"), amount.toFloat())
                }
                release(glowed); halos.forEach(::release)
                return result
            }
            fun finish(): GpuTexture {
                var current = misted()
                tap?.invoke(GpuStageTap.Point.MIST, origin, current)
                if (amount != 0.0) {
                    val soft = expanded(orton)
                    val mixed = draw("look/orton_mix.frag", listOf("s_mist" to current, "s_soft" to soft)); release(soft)
                    val protected = draw("look/orton_protect.frag", listOf("s_mist" to current, "s_mixed" to mixed,
                        "s_skinWeight" to skinWeight())) { program -> GLES32.glUniform1f(location(program, "u_amount"), amount.toFloat()) }
                    release(mixed); release(current); current = protected
                }
                clippedSkin?.let(::release); clippedSkin = null
                val character = draw("look/character.frag", listOf("s_orton" to current)) { program ->
                    val aspect = width.toDouble() / height
                    GLES32.glUniform2i(location(program, "u_imageSize"), width, height)
                    GLES32.glUniform2i(location(program, "u_origin"), 0, origin)
                    GLES32.glUniform2f(location(program, "u_aspect"), min(aspect, 1.0).toFloat(), max(aspect, 1.0).toFloat())
                    GLES32.glUniform1f(location(program, "u_vignetteAmount"), (StageClearCharacterV1.VIGNETTE * strength).toFloat())
                    GLES32.glUniform1f(location(program, "u_vibranceAmount"), (StageClearCharacterV1.VIBRANCE * strength).toFloat())
                }
                release(current)
                val cap = draw("look/skin_chroma_cap.frag", listOf("s_character" to character, "s_faceMask" to character)) {
                    GLES32.glUniform1i(location(it, "u_hasFaceMask"), 0)
                }
                release(character)
                val shoulder = draw("look/shoulder.frag", listOf("s_skinCap" to cap)); release(cap)
                val encoded = draw("display/encode.frag", listOf("s_linear" to shoulder), GpuTexture.Format.RGBA8)
                release(shoulder)
                return encoded
            }
            override fun close() = closeAll(owned.toList().asReversed().map { texture -> { release(texture) } })
        }
        override fun close() {
            closeAll(fields.asReversed().map { field -> { field.close() } } + { resample.close() })
        }
    }
    private fun closeAll(actions: List<() -> Unit>) {
        var failure: Throwable? = null
        actions.forEach { action ->
            try { action() } catch (problem: Throwable) {
                if (failure == null) failure = problem else if (failure !== problem) failure!!.addSuppressed(problem)
            }
        }
        failure?.let { throw it }
    }
    private fun location(program: Int, name: String) = GLES32.glGetUniformLocation(program, name).also {
        check(it >= 0) { "Missing export uniform $name" }
    }
}
