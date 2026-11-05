package dev.cinematic.p0.photoroll

/** Main-thread commit authority shared by successful and failed provider completions. */
internal class CameraRollListingWork {
    class Ticket internal constructor(val query: PhotoRollQuery, val accessEpoch: Int)
    private var active: Ticket? = null
    fun begin(query: PhotoRollQuery, accessEpoch: Int) = Ticket(query, accessEpoch).also { active = it }
    fun commit(ticket: Ticket, query: PhotoRollQuery, accessEpoch: Int, screenActive: Boolean,
        cancelled: Boolean, update: () -> Unit): Boolean {
        if (active !== ticket || ticket.query != query || ticket.accessEpoch != accessEpoch || !screenActive || cancelled) return false
        update()
        return true
    }
    fun finish(ticket: Ticket) { if (active === ticket) active = null }
}
