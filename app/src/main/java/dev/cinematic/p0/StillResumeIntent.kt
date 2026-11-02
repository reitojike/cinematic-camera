package dev.cinematic.p0

/** An unconfirmed AUTO request remains the logical intent after an API failure. */
fun autoResumeTarget(receipt: ControlReceipt): ControlSnapshot? = receipt.requested?.takeIf {
    it.exposure == null && receipt.phase in setOf(ControlPhase.PENDING, ControlPhase.UNKNOWN)
}

data class StillResumeRequest(val key: String, val sequence: Long, val controls: ControlSnapshot)

/** Logical target retained through failed binds; only its own new ACK can clear it. */
class StillResumeIntent {
    private var sequence = 0L
    private var pending: StillResumeRequest? = null

    fun remember(key: String, queued: ManualExposure?, live: ManualExposure?,
        pendingAuto: ControlSnapshot?, selected: ControlSnapshot) {
        val controls = when {
            queued != null -> selected.copy(exposure = queued)
            live != null -> selected.copy(exposure = live)
            pendingAuto != null -> pendingAuto
            else -> selected
        }
        // Locks belong to the old binding, not to the resumed logical exposure target.
        pending = StillResumeRequest(key, ++sequence, controls.copy(locks = AutoLocks()))
    }

    fun peek(key: String): StillResumeRequest? {
        if (pending?.key != key) pending = null
        return pending
    }

    fun accepted(key: String, request: StillResumeRequest): Boolean {
        if (request.key != key || pending !== request) return false
        pending = null
        return true
    }

    fun clear() { pending = null }
}
