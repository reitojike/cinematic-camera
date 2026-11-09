package dev.cinematic.p0.gpu

/** 必須の OpenGL ES 能力を満たさない。message に写真や個人用パスを含めない。 */
internal class GpuUnavailableException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/** EGL コンテキストが失われた。CPU への暗黙の切り替えは行わない。 */
internal class GpuContextLostException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/** GPU メモリ台帳の予算を超えた。割り当てる前に失敗させる。 */
internal class GpuMemoryBudgetException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)
