package dev.cinematic.p0.raw

/** Optional exclusive FOUNDATION sub-intervals. No pixels, adjustment values or source identity. */
internal class R7StageTiming(
    private val kind: RawPreviewTiming.Kind,
    private val sequence: Long,
    private val workers: Int,
    private val clock: () -> Long = System::nanoTime,
    private val sink: (String) -> Unit,
) {
    /** One category per interval; dependency and the work measured here are different concepts. */
    enum class Category { STATISTICS, NEIGHBOURHOOD, PER_PIXEL, BOOKKEEPING }
    enum class Stage(val category: Category) {
        SETUP(Category.BOOKKEEPING),
        METER_LUMA(Category.PER_PIXEL),
        METER_QUANTILE(Category.STATISTICS),
        ROI(Category.PER_PIXEL),
        WARMTH(Category.PER_PIXEL),
        VALIDATE(Category.BOOKKEEPING),
        PROFILE(Category.PER_PIXEL),
        LOCAL_LOG_LUMA(Category.PER_PIXEL),
        LOCAL_BOX(Category.NEIGHBOURHOOD),
        LOCAL_BICUBIC(Category.NEIGHBOURHOOD),
        LOCAL_QUANTILES(Category.STATISTICS),
        LOCAL_APPLY(Category.PER_PIXEL),
        FOUNDATION(Category.PER_PIXEL),
        VIBRANCE(Category.PER_PIXEL),
        CRISP(Category.PER_PIXEL),
        SKIN(Category.PER_PIXEL),
    }
    enum class Outcome { SUCCESS, FAILED }
    private val start = clock()
    private var boundary = start
    private var stage = Stage.SETUP
    private val durations = LongArray(Stage.entries.size)
    private val visited = BooleanArray(Stage.entries.size)
    private var finished = false

    fun enter(next: Stage) {
        if (finished) return
        optional {
            record(clock())
            stage = next
        }
    }

    fun finish(outcome: Outcome) {
        if (finished) return
        optional {
            val now = clock()
            record(now)
            finished = true
            sink("kind=$kind sequence=$sequence workers=$workers outcome=$outcome " +
                "r7Visited=${visited[Stage.PROFILE.ordinal]} totalNs=${now - start} " +
                Stage.entries.filter { visited[it.ordinal] }.joinToString(" ") {
                    "${it}Ns=${durations[it.ordinal]}"
                })
        }
    }

    private fun record(now: Long) {
        durations[stage.ordinal] += now - boundary
        visited[stage.ordinal] = true
        boundary = now
    }

    private inline fun optional(block: () -> Unit) {
        // Optional diagnostics cannot replace the rendering result, even under memory pressure.
        try { block() } catch (_: Throwable) { finished = true }
    }

    companion object {
        inline fun optional(create: () -> R7StageTiming): R7StageTiming? =
            try { create() } catch (_: Throwable) { null }
    }
}
