package dev.cinematic.p0.photoroll

/** Request acceptance to UI-state adoption, not physical touch-to-photon latency. No source data. */
internal class PreviewTiming(
    private val revision: Long,
    private val operation: PreviewOperation = PreviewOperation.OPEN,
    private val clock: () -> Long = System::nanoTime,
    private val sink: (String) -> Unit,
) {
    private val started = clock()
    private var boundary = started
    private var stage = "CHECK_ORIGINAL"
    private val durations = linkedMapOf<String, Long>()
    private var finished = false

    @Synchronized fun enter(next: String) {
        if (finished) return
        val now = clock()
        record(now)
        stage = next
    }

    @Synchronized fun finish(outcome: String) {
        if (finished) return
        val now = clock()
        record(now)
        finished = true
        sink("operation=$operation revision=$revision outcome=$outcome last=$stage totalNs=${now - started} " +
            durations.entries.joinToString(" ") { "${it.key}Ns=${it.value}" })
    }

    private fun record(now: Long) {
        durations[stage] = (durations[stage] ?: 0) + (now - boundary)
        boundary = now
    }
}

internal enum class PreviewOperation { OPEN, STRENGTH, TEMPERATURE, BRIGHTNESS, MULTIPLE }
