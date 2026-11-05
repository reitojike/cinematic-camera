package dev.cinematic.p0.photoedit

/** All adjustment coordinates travel together. This is not a durable recipe or publication permit. */
internal data class PhotoAdjustmentRenderTicket(
    val owner: Any,
    val revision: Long,
    val values: PhotoEditValues,
)

/**
 * Render coordination for an already checked photo's edit session. Ranges, units and defaults remain
 * caller supplied. No renderer, original writer, publication owner or product defaults are installed.
 */
internal class PhotoAdjustmentRenderState private constructor(
    val session: PhotoEditSession,
    val requested: PhotoEditValues,
    val displayed: PhotoEditValues?,
    private val owner: Any,
    private val revision: Long,
) {
    val ticket get() = PhotoAdjustmentRenderTicket(owner, revision, requested)
    val hasUnappliedChanges get() = session.draft != requested

    /** Value readiness only: current render is visible and no draft difference remains.
     * Caller must also gate active gestures, lifecycle and save concurrency. The save adapter must
     * independently validate/freeze its durable recipe and original reference. */
    fun readyToSave(): PhotoAdjustmentRenderTicket? =
        ticket.takeIf { session.active && !hasUnappliedChanges && displayed == requested }

    fun change(token: PhotoEditInputToken, parameter: PhotoEditParameter, value: Double): PhotoAdjustmentRenderState {
        val changed = session.change(token, parameter, value)
        return if (changed === session) this else copy(session = changed)
    }

    fun step(token: PhotoEditInputToken, parameter: PhotoEditParameter, direction: Int): PhotoAdjustmentRenderState {
        val changed = session.step(token, parameter, direction)
        return if (changed === session) this else copy(session = changed)
    }

    fun request(token: PhotoEditInputToken): PhotoAdjustmentRenderState {
        if (!session.active || token != session.inputToken || !hasUnappliedChanges) return this
        return copy(requested = session.draft, displayed = null, revision = nextRevision())
    }

    /** Reset is a render request and invalidates an in-progress finger/keyboard gesture. */
    fun reset(token: PhotoEditInputToken): PhotoAdjustmentRenderState {
        val changed = session.reset(token)
        if (changed === session) return this
        return copy(session = changed, requested = changed.draft,
            displayed = displayed.takeIf { requested == changed.draft }, revision = nextRevision())
    }

    /** Restore entry values, reject all outstanding renders, and close input/save authority. */
    fun cancel(token: PhotoEditInputToken): PhotoAdjustmentRenderState {
        val changed = session.cancel(token)
        if (changed === session) return this
        return copy(session = changed, requested = changed.confirmed,
            displayed = displayed.takeIf { requested == changed.confirmed }, revision = nextRevision())
    }

    /** A cancelling session may still show its newly requested restoration, but cannot be saved. */
    fun shown(completed: PhotoAdjustmentRenderTicket): PhotoAdjustmentRenderState =
        if (completed == ticket) copy(displayed = completed.values) else this

    private fun nextRevision(): Long { check(revision < Long.MAX_VALUE); return revision + 1 }

    private fun copy(session: PhotoEditSession = this.session, requested: PhotoEditValues = this.requested,
        displayed: PhotoEditValues? = this.displayed, revision: Long = this.revision) =
        PhotoAdjustmentRenderState(session, requested, displayed, owner, revision)

    companion object {
        /** Entry requires a verified displayed result matching every confirmed value. */
        fun open(session: PhotoEditSession, displayed: PhotoEditValues): PhotoAdjustmentRenderState {
            require(session.active && session.draft == session.confirmed && displayed == session.confirmed)
            return PhotoAdjustmentRenderState(session, displayed, displayed, Any(), 0)
        }
    }
}
