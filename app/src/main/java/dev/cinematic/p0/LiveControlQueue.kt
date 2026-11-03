package dev.cinematic.p0

data class LiveControlEntry(val epoch: Int, val sequence: Long, val controls: ControlSnapshot)

/** Build the logical exposure operation before any STILL preview compensation or API submission. */
fun exposureOperationIntent(exposure: ManualExposure?, previous: ControlSnapshot, savedEv: Int,
    reset: Boolean = false, restored: ControlSnapshot? = null, savedOverrides: SensorOverrides? = null): ControlSnapshot {
    if (reset) return autoControlSnapshot(previous, savedEv, reset = true)
    val auto = autoControlSnapshot(previous, savedEv, reset = false)
    val extras = restored?.overrides ?: savedOverrides ?: if (exposure != null) previous.overrides else auto.overrides
    val locks = restored?.locks ?: if (savedOverrides != null) AutoLocks() else if (exposure != null)
        previous.locks.forControls(true, extras.whiteBalance) else auto.locks
    return ControlSnapshot(exposure, restored?.evIndex ?: if (exposure != null) previous.evIndex else auto.evIndex,
        extras, locks)
}

/** One logical authority, one API operation, and one replaceable pending snapshot. Main thread only. */
class LatestLiveControlQueue {
    private var sequence = 0L
    var active: LiveControlEntry? = null; private set
    var queued: LiveControlEntry? = null; private set
    var intent: ControlSnapshot? = null; private set
    val hasWork get() = active != null || queued != null

    fun offer(epoch: Int, controls: ControlSnapshot) {
        intent = controls
        queued = LiveControlEntry(epoch, ++sequence, controls)
    }

    fun take(): LiveControlEntry? {
        if (active != null) return null
        active = queued; queued = null
        return active
    }

    /** An ACK may advance accepted history, but can never erase a newer logical intent. */
    fun complete(entry: LiveControlEntry): Boolean {
        if (active != entry) return false
        active = null
        if (queued == null) intent = null
        return true
    }

    /** UNKNOWN retains the user's latest selection but never retries by itself. */
    fun cancel(keepIntent: Boolean = false) {
        active = null; queued = null
        if (!keepIntent) intent = null
    }
}

/** Callback-thread accumulator; UI drain is bounded to one scheduled runnable by its owner. */
class PreviewExposureMailbox {
    private var pending: PreviewExposureSamples? = null
    private var scheduled = false

    @Synchronized fun offer(sample: CaptureSample): Boolean {
        val previous = pending
        if (previous != null && sample.epoch < previous.epoch) return false
        pending = (previous ?: PreviewExposureSamples(epoch = sample.epoch)).observe(sample, sample.epoch)
        if (scheduled) return false
        scheduled = true
        return true
    }

    @Synchronized fun drain(): PreviewExposureSamples? {
        val result = pending
        pending = null; scheduled = false
        return result
    }
}

/** Raw values for an explicit AUTO-to-manual choice; display rounding never feeds the request. */
fun freshPreviewExposurePair(samples: PreviewExposureSamples, epoch: Int, nowMs: Long): ManualExposure? {
    if (samples.epoch != epoch) return null
    fun fresh(sample: CaptureSample?) = sample != null && sample.epoch == epoch &&
        sample.intent in setOf(CaptureSampleIntent.PREVIEW, CaptureSampleIntent.VIDEO_RECORD) &&
        sample.receivedAtMs >= 0 && nowMs >= sample.receivedAtMs && nowMs - sample.receivedAtMs <= 2_000L
    if (!fresh(samples.iso) || !fresh(samples.shutter)) return null
    val iso = samples.iso?.actual?.iso?.takeIf { it > 0 } ?: return null
    val ns = samples.shutter?.actual?.exposureNs?.takeIf { it > 0 } ?: return null
    return ManualExposure(iso, ns)
}

/** First manual input uses fresh raw AUTO observations; later inputs use the logical pair only. */
fun liveExposureSelection(key: CaptureKey, index: Int, choices: ExposureChoices, controls: ControlSnapshot,
    samples: PreviewExposureSamples, epoch: Int, nowMs: Long): ControlSnapshot? {
    val source = controls.exposure ?: freshPreviewExposurePair(samples, epoch, nowMs) ?: return null
    val base = choices.pair(source.iso, source.exposureNs) ?: return null
    val pair = when (key) {
        CaptureKey.ISO -> choices.iso.getOrNull(index)?.let { base.copy(iso = it) }
        CaptureKey.SHUTTER -> choices.shutter.getOrNull(index)?.let { base.copy(exposureNs = it.exposureNs) }
        else -> null
    } ?: return null
    return controls.copy(exposure = pair, locks = controls.locks.forControls(true, controls.overrides.whiteBalance))
}
