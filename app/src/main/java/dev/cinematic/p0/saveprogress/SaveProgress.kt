package dev.cinematic.p0.saveprogress

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Display observations only. No URI, owner, pixels, retry or publication authority. */
internal enum class SaveProgressPhase { PREPARING, DEVELOPING, ENCODING, SAVING }
internal data class SaveProgress(
    val phase: SaveProgressPhase = SaveProgressPhase.PREPARING,
    val developedFraction: Float? = null,
    val running: Boolean = true,
)
internal interface SaveProgressSink {
    fun phase(phase: SaveProgressPhase)
    fun developed(fraction: Float)
}

/** One backend's presentation mailbox. Old leases cannot update a subsequent attempt.
 * No timers, sleeping, IO, diagnostics or callbacks into the save authority. */
internal class SaveProgressStore(private val nanoTime: () -> Long = System::nanoTime) {
    private val mutable = MutableStateFlow(SaveProgress())
    val state: StateFlow<SaveProgress> = mutable.asStateFlow()
    private var epoch = 0L
    private var accepting = false
    private var latest = 0f
    private var lastBar = 0L

    @Synchronized fun begin(): SaveProgressSink {
        val own = ++epoch
        accepting = true
        latest = 0f
        mutable.value = SaveProgress()
        return object : SaveProgressSink {
            override fun phase(phase: SaveProgressPhase) = bestEffort { advance(own, phase) }
            override fun developed(fraction: Float) = bestEffort { update(own, fraction) }
        }
    }
    @Synchronized private fun advance(own: Long, phase: SaveProgressPhase) {
        if (!accepting || own != epoch || phase.ordinal <= mutable.value.phase.ordinal) return
        if (phase == SaveProgressPhase.DEVELOPING) lastBar = nanoTime()
        mutable.value = SaveProgress(phase, if (phase == SaveProgressPhase.DEVELOPING) 0f else null)
    }
    @Synchronized private fun update(own: Long, fraction: Float) {
        if (!accepting || own != epoch || mutable.value.phase != SaveProgressPhase.DEVELOPING ||
            !fraction.isFinite() || fraction < 0f) return
        // This is completed development work, never a claim of successful publication.
        latest = maxOf(latest, fraction.coerceAtMost(MAX_FRACTION))
        val now = nanoTime()
        if (now - lastBar < BAR_INTERVAL_NANOS) return
        lastBar = now
        mutable.value = SaveProgress(SaveProgressPhase.DEVELOPING, latest)
    }
    /** Terminal/cancel observations bypass throttling; there is no delayed value to replay. */
    @Synchronized fun finish() {
        accepting = false
        ++epoch
        mutable.value = SaveProgress(SaveProgressPhase.SAVING, running = false)
    }
    companion object {
        const val BAR_INTERVAL_NANOS = 100_000_000L
        const val MAX_FRACTION = .999f
    }
}

/** Telemetry must not turn an otherwise valid save into a failure. */
internal inline fun bestEffort(action: () -> Unit) { try { action() } catch (_: Throwable) { } }
