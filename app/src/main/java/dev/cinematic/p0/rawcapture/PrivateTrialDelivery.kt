package dev.cinematic.p0.rawcapture

import java.io.Closeable
import java.util.concurrent.CancellationException

/** Queue owns the unclaimed display result, including after a future has completed.
 * Claim transfers ownership once; cancellation never recycles an already claimed UI image. */
internal class PrivateTrialDelivery<T : Closeable>(private val value: T, private val control: ManualDevelopControl,
    private val ticket: ManualDevelopControl.Ticket) : Closeable {
    private enum class State { PENDING, CLAIMED, CLOSING, CLOSED }
    private var state = State.PENDING
    init {
        if (!control.holdPreview(this, ticket)) { value.close(); state = State.CLOSED; throw CancellationException("Private preview not current") }
    }
    fun claim(): T? {
        val accepted = synchronized(this) {
            if (state == State.PENDING && control.accepts(ticket)) { state = State.CLAIMED; true } else false
        }
        if (accepted) { control.releasePreview(this); return value }
        close()
        return null
    }
    override fun close() {
        synchronized(this) { if (state != State.PENDING) return; state = State.CLOSING }
        try { value.close(); synchronized(this) { state = State.CLOSED }; control.releasePreview(this) }
        catch (failure: Throwable) { synchronized(this) { state = State.PENDING }; throw failure }
    }
}
