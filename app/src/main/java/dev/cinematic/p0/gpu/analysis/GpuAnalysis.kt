package dev.cinematic.p0.gpu.analysis

import dev.cinematic.p0.gpu.GpuTexture

/** 共有ファイル。各担当は自分の関数の本体だけを実装する（#276 §6.4）。 */
internal object GpuAnalysis {
    /** #280: 原寸の平滑 log 輝度から p50, p92 の順に2要素を返す。 */
    @Suppress("UNUSED_PARAMETER")
    fun localToneQuantiles(input: GpuTexture, width: Int, height: Int): DoubleArray {
        TODO("#280: localToneQuantiles")
    }

    /** #281: Glow 後の線形輝度が NEAR_WHITE を超える画素数を返す。 */
    @Suppress("UNUSED_PARAMETER")
    fun nearWhiteCount(input: GpuTexture, width: Int, height: Int): Long {
        TODO("#281: nearWhiteCount")
    }
}
