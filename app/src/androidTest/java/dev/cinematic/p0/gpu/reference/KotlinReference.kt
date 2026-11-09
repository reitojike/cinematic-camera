package dev.cinematic.p0.gpu.reference

import dev.cinematic.p0.gpu.GpuRenderParams
import dev.cinematic.p0.gpu.diag.GpuStageTap.Point
import dev.cinematic.p0.photoroll.TrialDisplayPixels
import dev.cinematic.p0.raw.*
import kotlin.math.max

/** 現行 CPU 関数の呼び出しだけで工程を保存する。GL / DNG / I/O は使わない。 */
internal object KotlinReference {
    data class Prepared(
        val input: LinearSrgbImage,
        val warmth: LinearSrgbImage,
        val analysis: R7PreviewInputs.Prepared,
        val externalBaseEv: Double,
        val r7Stages: List<LinearSrgbImage>,
    ) {
        val r7Final: LinearSrgbImage get() = r7Stages[5]
        val vibrance: LinearSrgbImage get() = r7Stages[3]
        val crisp: LinearSrgbImage get() = r7Stages[4]
    }

    data class Result(
        val prepared: Prepared,
        val images: Map<Point, LinearSrgbImage>,
        val skinWeight: FloatPlane,
        val display8: IntArray,
        val mistAutoStrength: Double,
        val lightAddingStrength: Double,
    ) {
        /** SKIN_WEIGHT と DISPLAY8 は型の違う専用プロパティから取得する。 */
        operator fun get(point: Point): LinearSrgbImage = requireNotNull(images[point]) { "$point is not RGB float" }
    }

    /** AS_SHOT の解析は warmth / 調整EV より前。プレビュー契約 (長辺 <=1024) を保つ。 */
    fun prepare(input: LinearSrgbImage, adjustments: PhotoAdjustments): Prepared {
        val analysis = R7PreviewInputs.prepare(
            R7PreviewInputs.AsShotInput(input, R7PreviewInputs.Source.SYNTHETIC_AS_SHOT), emptyList(),
        )
        val warmth = adjustments.applyWarmth(input)
        val ev = analysis.exposure.externalBaseEv + adjustments.exposureEv
        return Prepared(input, warmth, analysis, ev, R7Base.stages(warmth, analysis.foundationSkinRoi, ev))
    }

    fun render(input: LinearSrgbImage, params: GpuRenderParams): Result = render(prepare(input, params.adjustments), params.strength)

    /** 強さの行列試験では prepare を共有できる。各ルックの計算は共有・近似しない。 */
    fun render(prepared: Prepared, strength: Double): Result {
        val base = prepared.r7Final
        val skin = StageClearGlow.skinWeight(base)
        val glow = StageClearGlow.render(base, strength, skin)
        val auto = StageClearMist.autoStrength(glow)
        val mist = StageClearMist.render(glow, skin, strength)
        val orton = StageClearOrton.render(mist, skin)
        val character = StageClearCharacter.render(orton, strength)
        val faces = FaceMask(base.width, base.height, FloatArray(base.pixelCount))
        val cap = StageClearProtections.skinChromaCap(character, faces)
        val shoulder = StageClearProtections.shoulder(cap)
        return Result(prepared, linkedMapOf(
            Point.INPUT to prepared.input, Point.WARMTH to prepared.warmth,
            Point.R7_PROFILE to prepared.r7Stages[0], Point.R7_TONE to prepared.r7Stages[1],
            Point.R7_FOUNDATION to prepared.r7Stages[2], Point.R7_FINAL to base,
            Point.GLOW to glow, Point.MIST to mist.image, Point.ORTON to orton,
            Point.CHARACTER to character, Point.SKIN_CAP to cap, Point.SHOULDER to shoulder,
        ), skin, TrialDisplayPixels.encode(shoulder, max(base.width, base.height)) { false }, auto, mist.lightAddingStrength)
    }
}
