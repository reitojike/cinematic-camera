package dev.cinematic.p0.raw

/** Exclusive elapsed session intervals. Not physical input-to-display latency. No image metadata. */
internal class RawPreviewTiming(
    private val kind: Kind,
    private val sequence: Long,
    private val clock: () -> Long = System::nanoTime,
    private val sink: (String) -> Unit,
) {
    enum class Kind { EXACT, DRAFT }
    enum class Stage {
        ADMISSION, VERIFY_BEFORE, INPUT, FOUNDATION, GLOW_PREPARE, SKIN_EXPAND,
        GLOW, MIST_AUTO, MIST, ORTON, CHARACTER, PROTECTIONS, BITMAP, VERIFY_AFTER, CLEANUP,
    }
    enum class Outcome { SUCCESS, FAILED }
    private val start = clock()
    private var boundary = start
    private var stage = Stage.ADMISSION
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
            sink("kind=$kind sequence=$sequence outcome=$outcome totalNs=${now - start} " +
                Stage.entries.filter { visited[it.ordinal] }.joinToString(" ") {
                    "${it}Ns=${durations[it.ordinal]}"
                })
        }
    }

    private inline fun optional(block: () -> Unit) {
        // Diagnostics must not skip cleanup or replace the rendering result, including under OOM.
        try { block() } catch (_: Throwable) { finished = true }
    }

    private fun record(now: Long) {
        durations[stage.ordinal] += now - boundary
        visited[stage.ordinal] = true
        boundary = now
    }

    companion object {
        inline fun optional(create: () -> RawPreviewTiming): RawPreviewTiming? =
            try { create() } catch (_: Throwable) { null }
    }
}
