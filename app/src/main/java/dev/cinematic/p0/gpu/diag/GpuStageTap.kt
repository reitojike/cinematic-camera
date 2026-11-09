package dev.cinematic.p0.gpu.diag

/** 試験・診断専用。読み戻しの実装は #277 PR2。 */
internal object GpuStageTap {
    /** 工程名と順序は #277 PR1 の契約として固定する。 */
    enum class Point {
        INPUT,
        WARMTH,
        R7_PROFILE,
        R7_TONE,
        R7_FOUNDATION,
        R7_FINAL,
        SKIN_WEIGHT,
        GLOW,
        MIST,
        ORTON,
        CHARACTER,
        SKIN_CAP,
        SHOULDER,
        DISPLAY8,
    }
}
