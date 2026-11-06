package dev.cinematic.p0.rawcapture

/** Process-local delivery identity. Never persisted or used as a replacement for save journals. */
internal class ForegroundExportGate<T> {
    class Ticket<T> internal constructor(val value: T, val token: String) {
        @Volatile var stopRequested = false
            internal set
        @Volatile var started = false
            internal set
        @Volatile var held = false
            internal set
    }
    private var current: Ticket<T>? = null
    @Synchronized fun reserve(value: T, token: String): Ticket<T>? {
        require(token.isNotBlank())
        if (current != null) return null
        return Ticket(value, token).also { current = it }
    }
    @Synchronized fun find(token: String?): Ticket<T>? = current?.takeIf { it.token == token }
    @Synchronized fun begin(ticket: Ticket<T>): Boolean {
        if (current !== ticket || ticket.started || ticket.stopRequested) return false
        ticket.started = true
        return true
    }
    @Synchronized fun stop(ticket: Ticket<T>): Boolean {
        if (current !== ticket) return false
        ticket.stopRequested = true
        return true
    }
    @Synchronized fun finish(ticket: Ticket<T>, resourcesClosed: Boolean): Boolean {
        if (current !== ticket) return false
        if (!resourcesClosed) { ticket.held = true; return false }
        current = null
        return true
    }
    @Synchronized fun owns(value: T) = current?.value === value
    @Synchronized fun active() = current
}
