package dev.cinematic.p0.rawcapture

/** Short process-local admission boundary; no IO/callback/stream close runs under this lock. */
internal class RawHandoffAdmission {
    internal class Blocker internal constructor()
    internal class Attempt internal constructor() { @Volatile internal var stopped = false }
    private val blockers = mutableMapOf<Blocker, Boolean>()
    private var active: Attempt? = null

    @Synchronized fun newBlocker(): Blocker = Blocker().also {
        blockers[it] = true
        active?.stopped = true
    }
    @Synchronized fun block(owner: Blocker, value: Boolean) {
        require(owner in blockers) { "Foreign or released capture owner" }
        blockers[owner] = value
        if (value) active?.stopped = true
    }
    @Synchronized fun remove(owner: Blocker) {
        require(owner in blockers) { "Foreign or released capture owner" }
        blockers.remove(owner)
    }
    @Synchronized fun tryStart(): Attempt? {
        if (active != null || blockers.values.any { it }) return null
        return Attempt().also { active = it }
    }
    @Synchronized fun shouldStop(attempt: Attempt): Boolean = active !== attempt || attempt.stopped
    @Synchronized fun completeAfterResourcesClosed(attempt: Attempt) {
        require(active === attempt) { "Foreign or stale handoff attempt" }
        active = null
    }
}
