package spike.backgroundsave

/** Unconnected contract prototype. Does not own images, journals, queues, or OS services.
 * Call on one serialized control lane. A ticket is process-local, never restored from disk.
 * Resource closure must come from the existing save owner, not cancellation/Future completion.
 */
internal class ExportLifetime {
    class Ticket internal constructor(val operation: String)
    enum class Phase { IDLE, RUNNING, STOPPING, RELEASE_UNCONFIRMED }
    enum class StopCause { USER, CAPTURE, UNSAFE_RESOURCES, TIMEOUT }
    private var active: Ticket? = null
    var phase = Phase.IDLE
        private set
    var stopCause: StopCause? = null
        private set

    /** This is not admission: the real coordinator must still acquire its sole slot.
     * operation is an immutable capture+generation reference, never a mutable UI recipe.
     */
    fun claim(operation: String, explicitForegroundSave: Boolean): Ticket? {
        require(operation.isNotBlank())
        if (!explicitForegroundSave || active != null) return null
        return Ticket(operation).also { active = it; phase = Phase.RUNNING; stopCause = null }
    }

    /** Returns true exactly once: adapter sends cancellation to the SAME owner. No release. */
    fun stop(ticket: Ticket, cause: StopCause): Boolean {
        if (active !== ticket || phase != Phase.RUNNING) return false
        stopCause = cause
        phase = Phase.STOPPING
        return true
    }

    fun mustStop(ticket: Ticket) = active !== ticket || phase != Phase.RUNNING

    /** An OS stopSelf deadline does not acknowledge resource closure. Keep this process gate
     * closed even after service shutdown if a worker/FD/readback is still unconfirmed.
     */
    fun ownerReturned(ticket: Ticket, resourcesClosed: Boolean): Boolean {
        if (active !== ticket) return false
        if (!resourcesClosed) { phase = Phase.RELEASE_UNCONFIRMED; return false }
        active = null
        phase = Phase.IDLE
        return true
    }
}

/** A classification, not permission to call advance(). Production must decode/match existing
 * intent/journal/source/recipe and inspect publication using the SAME existing target/token.
 */
internal enum class RecoveryEvidence { NO_INTENT, INTENT_ONLY, VERIFIED_JOURNAL, UNKNOWN_JOURNAL, INVALID_OR_UNREADABLE }
internal enum class RecoveryPlan { WAIT_FOR_EXPLICIT_SAVE, RECONCILE_EXISTING_ONLY, HOLD }
internal fun recoveryPlan(evidence: RecoveryEvidence) = when (evidence) {
    RecoveryEvidence.NO_INTENT -> RecoveryPlan.WAIT_FOR_EXPLICIT_SAVE
    RecoveryEvidence.VERIFIED_JOURNAL -> RecoveryPlan.RECONCILE_EXISTING_ONLY
    RecoveryEvidence.INTENT_ONLY, RecoveryEvidence.UNKNOWN_JOURNAL,
    RecoveryEvidence.INVALID_OR_UNREADABLE -> RecoveryPlan.HOLD
}
