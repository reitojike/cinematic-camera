package dev.cinematic.p0

private fun previewFocusFrame(sample: CaptureSample) = sample.intent in setOf(
    CaptureSampleIntent.PREVIEW, CaptureSampleIntent.VIDEO_RECORD)

private fun autofocusDistanceValid(sample: CaptureSample, maximum: Float?): Boolean {
    val value = sample.actual.focus ?: return false
    if (sample.actual.afMode !in 1..4 || !value.isFinite() || value < 0f) return false
    // Null means the MF range is unavailable, not proof that an observed AF value is invalid.
    return maximum == null || maximum.isFinite() && maximum > 0f && value <= maximum
}

/** Camera-reported lens units only; never a proof of subject distance or successful focus. */
fun freshAutofocusSample(sample: CaptureSample?, epoch: Int, nowMs: Long, maximum: Float?): CaptureSample? =
    sample?.takeIf { it.epoch == epoch && previewFocusFrame(it) && autofocusDistanceValid(it, maximum) &&
        it.receivedAtMs >= 0 && nowMs >= it.receivedAtMs && nowMs - it.receivedAtMs <= 2_000L }

/** A mode transition closes observation until AF acceptance; old frames cannot reopen it. */
data class AutofocusObservations(
    val epoch: Int = 0, val accepting: Boolean = false, val afterMs: Long = -1,
    val lastFrame: Long = -1, val sample: CaptureSample? = null,
) {
    fun suspend() = copy(accepting = false, sample = null)

    fun controlsAccepted(currentEpoch: Int, atMs: Long, acceptedFocus: Float?, desiredFocus: Float?,
        afSupported: Boolean): AutofocusObservations = when {
        currentEpoch != epoch || atMs < 0 -> this
        acceptedFocus != null || desiredFocus != null || !afSupported -> suspend()
        accepting -> this
        else -> copy(accepting = true, afterMs = atMs, sample = null)
    }

    fun observe(candidate: CaptureSample, currentEpoch: Int, nowMs: Long, maximum: Float?): AutofocusObservations {
        if (!accepting || currentEpoch != epoch || candidate.epoch != epoch || !previewFocusFrame(candidate) ||
            candidate.frame <= lastFrame || candidate.receivedAtMs <= afterMs || candidate.receivedAtMs > nowMs ||
            nowMs - candidate.receivedAtMs > 2_000L) return this
        val next = copy(lastFrame = candidate.frame)
        // Explicit OFF/EDOF (or an unknown reported mode) supersedes an older AF observation.
        if (candidate.actual.afMode != null && candidate.actual.afMode !in 1..4) return next.copy(sample = null)
        return if (freshAutofocusSample(candidate, epoch, nowMs, maximum) != null) next.copy(sample = candidate) else next
    }
}

/** Callback-thread focus events, bounded to one pending value and one UI runnable. */
class AutofocusObservationMailbox(private val maximum: Float?) {
    private var epoch = -1
    private var lastFrame = -1L
    private var pending: CaptureSample? = null
    private var scheduled = false

    @Synchronized fun offer(sample: CaptureSample): Boolean {
        if (!previewFocusFrame(sample) || sample.receivedAtMs < 0 || sample.epoch < epoch ||
            sample.epoch == epoch && sample.frame <= lastFrame) return false
        if (sample.epoch != epoch) { epoch = sample.epoch; lastFrame = -1; pending = null }
        lastFrame = sample.frame
        val nonAf = sample.actual.afMode != null && sample.actual.afMode !in 1..4
        if (!nonAf && !autofocusDistanceValid(sample, maximum)) return false
        pending = sample
        if (scheduled) return false
        scheduled = true
        return true
    }

    @Synchronized fun drain(): CaptureSample? {
        val result = pending
        pending = null; scheduled = false
        return result
    }
}
