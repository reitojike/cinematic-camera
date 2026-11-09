package dev.cinematic.p0.gpu.reference

import dev.cinematic.p0.gpu.GpuRenderParams
import dev.cinematic.p0.gpu.diag.GpuStageTap.Point
import dev.cinematic.p0.photoroll.TrialDisplayPixels
import dev.cinematic.p0.raw.*
import kotlin.math.max

/** 現行 CPU 関数の呼び出しだけで工程を保存する。GL / DNG / I/O は使わない。 */
internal object KotlinReference {
    /** Immutable per-image inputs shared across adjustment/strength comparisons. */
    class Source(val image: LinearSrgbImage) {
        val analysis: R7PreviewInputs.Prepared = R7PreviewInputs.prepare(
            R7PreviewInputs.AsShotInput(image, R7PreviewInputs.Source.SYNTHETIC_AS_SHOT), emptyList(),
        )
        val faces = FaceMask(image.width, image.height, FloatArray(image.pixelCount))
    }

    data class Prepared(
        val input: LinearSrgbImage,
        val warmth: LinearSrgbImage,
        val analysis: R7PreviewInputs.Prepared,
        val externalBaseEv: Double,
        val r7Stages: List<LinearSrgbImage>,
        val faces: FaceMask,
    ) {
        val r7Final: LinearSrgbImage get() = r7Stages[5]
        val vibrance: LinearSrgbImage get() = r7Stages[3]
        val crisp: LinearSrgbImage get() = r7Stages[4]
        // R7 入力は不変。強さに依存しない既存関数の結果だけを共有する。
        val skinWeight: FloatPlane by lazy { StageClearGlow.skinWeight(r7Final) }
    }

    data class Result(
        val prepared: Prepared,
        val images: Map<Point, LinearSrgbImage>,
        val skinWeight: FloatPlane,
        val mistAutoStrength: Double,
        val lightAddingStrength: Double,
    ) {
        /** SKIN_WEIGHT と DISPLAY8 は型の違う専用プロパティから取得する。 */
        operator fun get(point: Point): LinearSrgbImage = requireNotNull(images[point]) { "$point is not retained RGB float" }

        // SHOULDER のビット一致だけを調べる行列では、未使用の表示変換を行わない。
        val display8: IntArray by lazy {
            val shoulder = get(Point.SHOULDER)
            TrialDisplayPixels.encode(shoulder, max(shoulder.width, shoulder.height)) { false }
        }
    }

    /** AS_SHOT の解析は warmth / 調整EV より前。プレビュー契約 (長辺 <=1024) を保つ。 */
    fun prepare(input: LinearSrgbImage, adjustments: PhotoAdjustments): Prepared = prepare(Source(input), adjustments)

    fun prepare(source: Source, adjustments: PhotoAdjustments): Prepared {
        val warmth = adjustments.applyWarmth(source.image)
        val ev = source.analysis.exposure.externalBaseEv + adjustments.exposureEv
        return Prepared(source.image, warmth, source.analysis, ev,
            R7Base.stages(warmth, source.analysis.foundationSkinRoi, ev), source.faces)
    }

    fun render(input: LinearSrgbImage, params: GpuRenderParams): Result = render(prepare(input, params.adjustments), params.strength)

    /** 既定では全工程を保持。一致行列は保持を抑え、CPU 見本と中間画像を同時に積まない。 */
    fun render(prepared: Prepared, strength: Double, retainIntermediates: Boolean = true): Result {
        val images = linkedMapOf<Point, LinearSrgbImage>()
        fun tap(point: Point, image: LinearSrgbImage): LinearSrgbImage {
            if (retainIntermediates || point == Point.SHOULDER) images[point] = image
            return image
        }
        tap(Point.INPUT, prepared.input)
        tap(Point.WARMTH, prepared.warmth)
        tap(Point.R7_PROFILE, prepared.r7Stages[0])
        tap(Point.R7_TONE, prepared.r7Stages[1])
        tap(Point.R7_FOUNDATION, prepared.r7Stages[2])
        val base = tap(Point.R7_FINAL, prepared.r7Final)
        val skin = prepared.skinWeight
        var current = tap(Point.GLOW, StageClearGlow.render(base, strength, skin))
        val auto = StageClearMist.autoStrength(current)
        val mist = StageClearMist.render(current, skin, strength, auto)
        tap(Point.MIST, mist.image)
        current = tap(Point.ORTON, StageClearOrton.render(mist, skin))
        current = tap(Point.CHARACTER, StageClearCharacter.render(current, strength))
        current = tap(Point.SKIN_CAP, StageClearProtections.skinChromaCap(current, prepared.faces))
        tap(Point.SHOULDER, StageClearProtections.shoulder(current))
        return Result(prepared, images, skin, auto, mist.lightAddingStrength)
    }
}
