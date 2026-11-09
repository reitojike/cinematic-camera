package dev.cinematic.p0.gpu

import dev.cinematic.p0.gpu.diag.GpuStageTap
import dev.cinematic.p0.raw.LinearSrgbImage
import dev.cinematic.p0.raw.PhotoAdjustments

/** 写真1枚ぶんの GPU 側の状態。GlThread 上でのみ生成・使用・解放する。 */
internal interface StageClearGpuPhoto : AutoCloseable {
    val width: Int            // 入力（AS_SHOT）の幅
    val height: Int
    /** 写真ごとに1回求める値。プレビューの描画中は固定。 */
    val analysis: GpuPhotoAnalysis
}

internal data class GpuPhotoAnalysis(
    val meteredBaseEv: Double,        // R7PreviewInputs.prepare の externalBaseEv と同じ定義（調整 EV を含まない）
    val centralP97: Float,
)

/** 1フレームの描画条件。値はすべて検証済みの既存型の範囲に従う。 */
internal data class GpuRenderParams(
    val strength: Double,             // 0..1
    val adjustments: PhotoAdjustments // warmth -100..100、exposureEv -2..2（既存型をそのまま使う）
)

internal interface StageClearGpu : AutoCloseable {
    /** AS_SHOT の線形 sRGB（D65）入力をアップロードし、写真ごとの解析を行う。 */
    fun openPhoto(input: LinearSrgbImage, stop: () -> Boolean): StageClearGpuPhoto

    /** プレビュー: 画面用サーフェスへ描画。CPU への読み戻しはしない。 */
    fun renderPreview(photo: StageClearGpuPhoto, params: GpuRenderParams, target: GpuPreviewTarget,
        display: GpuDisplayOptions): GpuFrameStats

    /** 試験・診断専用: 指定の工程の結果を線形 RGB float で読み戻す。 */
    fun renderForTest(photo: StageClearGpuPhoto, params: GpuRenderParams, tap: GpuStageTap.Point): LinearSrgbImage
}

internal data class GpuDisplayOptions(
    val showBefore: Boolean,          // true: AS_SHOT 入力をそのまま sRGB 表示（現行の比較画像と同じ定義）
    val rotationDegrees: Int,         // 0/90/180/270（向きは既存の判定結果を渡すだけ）
)

internal data class GpuFrameStats(val gpuNanos: Long?, val cpuSubmitNanos: Long, val decisions: GpuDecisions)

/** 現行 StageClearDecisions と同じ意味。プレビューでは解像度ごとに GPU で求める。 */
internal data class GpuDecisions(val mistAutoStrength: Double, val faceCutFactor: Double /* 現状は常に 1.0 */)
