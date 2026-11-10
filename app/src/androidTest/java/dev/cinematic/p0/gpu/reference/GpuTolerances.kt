package dev.cinematic.p0.gpu.reference

/** #276 §4.11 / #280 の受入条件。工程ごとに独自の許容差を追加しない。 */
internal object GpuTolerances {
    const val DISPLAY_MAX_DIFF = 2
    const val DISPLAY_P999_DIFF = 1.0
    const val MIST_AUTO_STRENGTH = .005
    const val RELATIVE_DENOMINATOR_FLOOR = 1e-4
    const val LOCAL_TONE_QUANTILE_ABSOLUTE = 1e-4

    // Kotlin の平滑 log 輝度との比較は絶対差。GPU 自身の値からの厳密な選択は #280 で別に試験する。
    fun localToneQuantile(reference: Float): Double {
        require(reference.isFinite())
        return LOCAL_TONE_QUANTILE_ABSOLUTE
    }
}
