package dev.cinematic.p0.gpu.experimental

import android.opengl.GLES32
import dev.cinematic.p0.gpu.*
import dev.cinematic.p0.gpu.diag.GpuStageTap.Point
import dev.cinematic.p0.raw.StageClearCharacterV1
import kotlin.math.max
import kotlin.math.min

/**
 * #320 の限定実験。既に確定した ORTON から Character → skin cap → shoulder だけを処理する。
 * 製品/診断プレビューには接続しない。Glow/Mist/Orton の近傍処理はこの試験の対象外。
 *
 * 所有する一時画像は R32F 3枚だけ。入力と AS_SHOT/R7/Glow cache は一切書き換えない。
 * この API は同一画素だけを読む固定 shader に限定し、近傍 shader の追加口を持たない。
 * 近傍工程を追加する場合は、入力を別の場へ読み終えた後に barrier で確定させる設計が必要。
 */
internal class PlanarFinishExperiment private constructor(
    private val egl: EglContexts,
    private val planes: List<GpuTexture>,
    /** このインスタンスの import と固定3工程中に実際の台帳で観測した最大値。 */
    val peakLedgerBytes: Long,
) : AutoCloseable {
    private var closed = false
    private var rendered = false
    val width: Int get() = planes[0].width
    val height: Int get() = planes[0].height

    /** tap の3面は借用。tap が失敗した場合も呼出側の use で全3面を解放する。再実行不可。 */
    fun render(strength: Double, tap: (Point, List<GpuTexture>) -> Unit = { _, _ -> }) {
        require(strength.isFinite() && strength in 0.0..1.0)
        check(!closed && !rendered) { "Compute trial must be imported again before another render" }
        egl.requireCurrent()
        rendered = true
        tap(Point.ORTON, planes)
        dispatch(egl, planes, "character.comp") { program ->
            val aspect = width.toDouble() / height
            GLES32.glUniform2i(location(program, "u_imageSize"), width, height)
            GLES32.glUniform2i(location(program, "u_origin"), 0, 0)
            GLES32.glUniform2f(location(program, "u_aspect"), min(aspect, 1.0).toFloat(), max(aspect, 1.0).toFloat())
            GLES32.glUniform1f(location(program, "u_vignetteAmount"), (StageClearCharacterV1.VIGNETTE * strength).toFloat())
            GLES32.glUniform1f(location(program, "u_vibranceAmount"), (StageClearCharacterV1.VIBRANCE * strength).toFloat())
        }
        tap(Point.CHARACTER, planes)
        dispatch(egl, planes, "skin_chroma_cap.comp")
        tap(Point.SKIN_CAP, planes)
        dispatch(egl, planes, "shoulder.comp")
        tap(Point.SHOULDER, planes)
    }

    override fun close() {
        egl.glThread.requireCurrent()
        if (closed) return
        try { planes[0].close() } finally { try { planes[1].close() } finally { planes[2].close() } }
        closed = true
    }

    companion object {
        /** Borrow an immutable RGBA32F ORTON result; the returned trial owns only its new planes. */
        fun import(source: GpuTexture): PlanarFinishExperiment {
            source.requireOpen()
            require(source.format == GpuTexture.Format.RGBA32F)
            val egl = source.context
            val ledger = source.memoryLedger
            val planes = mutableListOf<GpuTexture>()
            var peak = ledger.allocatedBytes
            try {
                repeat(3) {
                    planes += GpuTexture.create(egl, ledger, source.width, source.height, GpuTexture.Format.R32F)
                    peak = maxOf(peak, ledger.allocatedBytes)
                }
                dispatch(egl, planes, "import.comp", listOf(TextureInput("s_input", source, 0)))
                return PlanarFinishExperiment(egl, planes.toList(), peak)
            } catch (failure: Throwable) {
                planes.asReversed().forEach { runCatching { it.close() }.exceptionOrNull()?.let(failure::addSuppressed) }
                throw failure
            }
        }

        private fun location(program: Int, name: String) = GLES32.glGetUniformLocation(program, name)
            .also { check(it >= 0) { "Missing compute uniform $name" } }

        private fun dispatch(egl: EglContexts, planes: List<GpuTexture>, shader: String,
            inputs: List<TextureInput> = emptyList(), uniforms: (Int) -> Unit = {}) {
            // The same pixel's RGB values are loaded completely before any imageStore.
            // All subsequent imageLoad, texture fetch and FBO readbacks see completed stores.
            val barriers = GLES32.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT or
                GLES32.GL_TEXTURE_FETCH_BARRIER_BIT or GLES32.GL_FRAMEBUFFER_BARRIER_BIT
            egl.passes().run(ComputePass(egl.shaders().compute("experimental/compute/$shader"), inputs,
                groupsX = (planes[0].width + 7) / 8, groupsY = (planes[0].height + 7) / 8,
                uniforms = uniforms, bindResources = {
                    planes.forEachIndexed { unit, texture ->
                        GLES32.glBindImageTexture(unit, texture.textureId, 0, false, 0,
                            GLES32.GL_READ_WRITE, GLES32.GL_R32F)
                    }
                }, memoryBarrier = barriers))
        }
    }
}
