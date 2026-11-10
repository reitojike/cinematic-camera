package dev.cinematic.p0.gpu.r7

import android.opengl.GLES32
import dev.cinematic.p0.gpu.*
import dev.cinematic.p0.gpu.analysis.GpuAnalysis
import dev.cinematic.p0.gpu.diag.GpuStageTap.Point
import dev.cinematic.p0.gpu.resample.GpuResample
import dev.cinematic.p0.raw.R7BaseV1
import dev.cinematic.p0.raw.StageClearBlur
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.round

/** 写真ごとに所有するR7。入力/ROIは借用かつ不変。中間は即時解放し、最終出力だけを保持する。 */
internal class R7Gpu(private val egl: EglContexts, private val ledger: GpuMemoryLedger) : AutoCloseable {
    private data class Key(val input: GpuTexture, val warmth: Double, val ev: Double, val roi: GpuTexture?)
    private val resample = GpuResample(egl, ledger)
    private var key: Key? = null
    private var result: GpuTexture? = null
    private var closed = false
    /** R7自身のfragment実行数。共有resample/computeは含まない。再利用では増えない。 */
    var fragmentPassCount = 0; private set

    /** 出力は本クラス所有。次の再計算/closeで失効する。tapは同期で読み、一時textureを保持しない。
     * tap指定時は検証のため再計算。強さはkeyに含まず、warmth/EV変更では全工程をやり直す。
     * externalBaseEvは既存R7PreviewInputs.prepareの結果に調整EVを加えた値。
     */
    fun render(input: GpuTexture, warmth: Double, externalBaseEv: Double, foundationRoi: GpuTexture? = null,
        tap: ((Point, GpuTexture) -> Unit)? = null,
        smoothLogTap: ((GpuTexture, DoubleArray) -> Unit)? = null): GpuTexture {
        egl.requireCurrent(); check(!closed) { "R7 is closed" }
        input.requireContext(egl)
        require(input.memoryLedger === ledger && input.format == GpuTexture.Format.RGBA32F)
        require(warmth.isFinite() && warmth in -100.0..100.0)
        val gain = 2.0.pow(externalBaseEv + R7BaseV1.BRIGHTNESS_EV + R7BaseV1.FOUNDATION_EV).toFloat()
        require(externalBaseEv.isFinite() && gain.isFinite() && gain > 0f)
        foundationRoi?.let {
            it.requireContext(egl)
            require(it.memoryLedger === ledger && it.format == GpuTexture.Format.R32F)
            require(it.width == input.width && it.height == input.height)
        }
        val next = Key(input, warmth, externalBaseEv, foundationRoi)
        if (next == key && tap == null && smoothLogTap == null) return checkNotNull(result).also { it.requireOpen() }
        result?.close(); result = null; key = null
        val owned = mutableSetOf<GpuTexture>()
        fun create(format: GpuTexture.Format = GpuTexture.Format.RGBA32F) =
            GpuTexture.create(egl, ledger, input.width, input.height, format).also { owned += it }
        fun release(texture: GpuTexture) { if (owned.remove(texture)) texture.close() }
        fun pass(path: String, inputs: List<TextureInput>, output: GpuTexture, uniforms: (Int) -> Unit = {}) {
            egl.passes().run(FragmentPass(egl.shaders().fragment(path), inputs, output, uniforms = uniforms)); fragmentPassCount++
        }
        val red = 2.0.pow(warmth / 200.0); val blue = 2.0.pow(-warmth / 200.0)
        val norm = .2126 * red + .7152 + .0722 * blue
        fun profile(output: GpuTexture, warmthOnly: Boolean) = pass("r7/profile.frag", listOf(TextureInput("s_input", input, 0)), output) { program ->
            GLES32.glUniform3f(GLES32.glGetUniformLocation(program, "u_warmthGain"),
                (red / norm).toFloat(), (1.0 / norm).toFloat(), (blue / norm).toFloat())
            GLES32.glUniform1i(GLES32.glGetUniformLocation(program, "u_warmthOnly"), if (warmthOnly) 1 else 0)
        }
        try {
            if (tap != null) {
                val warmthTexture = create(); profile(warmthTexture, true); tap(Point.WARMTH, warmthTexture); release(warmthTexture)
            }
            val profiled = create(); profile(profiled, false); tap?.invoke(Point.R7_PROFILE, profiled)
            val log = create(GpuTexture.Format.R32F)
            pass("r7/log_luma.frag", listOf(TextureInput("s_input", profiled, 0)), log)
            val cell = .04 * max(input.width, input.height)
            val small = resample.reduce(log, max(4, round(input.width / cell).toInt()), max(4, round(input.height / cell).toInt()))
            if (small !== log) owned += small
            val smooth = resample.upsampleFrom(small, input.width, input.height, StageClearBlur.Filter.BICUBIC)
            if (smooth !== small) owned += smooth
            if (small !== smooth) release(small)
            if (log !== smooth) release(log)
            val quantiles = GpuAnalysis.localToneQuantiles(smooth, input.width, input.height)
            smoothLogTap?.invoke(smooth, quantiles.copyOf())
            val toned = create()
            pass("r7/tone_apply.frag", listOf(TextureInput("s_input", profiled, 0), TextureInput("s_smoothLogLuma", smooth, 1)), toned) { program ->
                GLES32.glUniform2f(GLES32.glGetUniformLocation(program, "u_quantiles"), quantiles[0].toFloat(), quantiles[1].toFloat())
            }
            release(profiled); release(smooth); tap?.invoke(Point.R7_TONE, toned)
            val roi = foundationRoi ?: create(GpuTexture.Format.R32F).also {
                // glTexStorage2D has undefined contents. Explicit clear supplies the Phase 0 empty ROI.
                GLES32.glBindFramebuffer(GLES32.GL_FRAMEBUFFER, it.framebufferId)
                GLES32.glDisable(GLES32.GL_SCISSOR_TEST); GLES32.glColorMask(true, true, true, true)
                GLES32.glClearBufferfv(GLES32.GL_COLOR, 0, floatArrayOf(0f, 0f, 0f, 0f), 0)
                egl.checkGl("clear empty R7 ROI")
            }
            val foundation = create()
            pass("r7/foundation.frag", listOf(TextureInput("s_input", toned, 0), TextureInput("s_roi", roi, 1)), foundation) { program ->
                GLES32.glUniform1f(GLES32.glGetUniformLocation(program, "u_gain"), gain)
            }
            release(toned); if (foundationRoi == null) release(roi); tap?.invoke(Point.R7_FOUNDATION, foundation)
            val final = create(); pass("r7/finish.frag", listOf(TextureInput("s_input", foundation, 0)), final)
            release(foundation); tap?.invoke(Point.R7_FINAL, final)
            owned.remove(final); result = final; key = next
            return final
        } finally { owned.forEach { it.close() } }
    }
    override fun close() {
        egl.glThread.requireCurrent()
        if (closed) return
        result?.close(); result = null; key = null; resample.close(); closed = true
    }
}
