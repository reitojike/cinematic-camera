package dev.cinematic.p0.metrics

/** UI visibility, not foreground-service priority; UNKNOWN never means background. */
internal enum class SaveVisibility { FOREGROUND, BACKGROUND, UNKNOWN }
internal data class SaveTimingEnvironment(val visibility: SaveVisibility = SaveVisibility.UNKNOWN, val thermal: Int? = null)

/** No Activity identity or reference retained. Android start/stop callbacks are paired on main. */
internal class SaveVisibilityCounter {
    private var initialized = false
    private var healthy = true
    private var started = 0
    @Synchronized fun initialize() { initialized = true }
    @Synchronized fun started() { if (started == Int.MAX_VALUE) healthy = false else started++ }
    @Synchronized fun stopped() { if (started == 0) healthy = false else started-- }
    @Synchronized fun snapshot() = when {
        !initialized || !healthy -> SaveVisibility.UNKNOWN
        started > 0 -> SaveVisibility.FOREGROUND
        else -> SaveVisibility.BACKGROUND
    }
}
