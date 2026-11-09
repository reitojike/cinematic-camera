package dev.cinematic.p0.gpu.look

import dev.cinematic.p0.gpu.GpuTexture

// #281 / #282: ルック各工程のパス構成。契約 PR1 では実装しない。

/** #281 の出力 / #282 の入力。双方が独立して実装・試験できるよう型だけ共有する。 */
internal data class MistGpuResult(
    val image: GpuTexture, // RGBA32F
    val lightAddingStrength: Double,
    val skinWeight: GpuTexture, // R32F
)
