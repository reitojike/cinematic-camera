package dev.cinematic.p0.gpu.reference

/** #276 §4.11 / docs/GPU_ENGINE.md の事前登録値。工程ごとに緩めない。 */
internal object GpuTolerances {
    const val DISPLAY_MAX_DIFF = 2
    const val DISPLAY_P999_DIFF = 1.0
    const val MIST_AUTO_STRENGTH = .005
    const val RELATIVE_DENOMINATOR_FLOOR = 1e-4

    // p50/p92 の契約は「単精度の範囲」。固定の絶対 epsilon に置き換えない。
    fun localToneQuantile(reference: Float): Double {
        require(reference.isFinite())
        return Math.ulp(reference).toDouble()
    }
}
