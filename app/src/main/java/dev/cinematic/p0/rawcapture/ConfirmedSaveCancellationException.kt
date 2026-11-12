package dev.cinematic.p0.rawcapture

import java.util.concurrent.CancellationException

/** In-process evidence only: the producer did not enter JPEG output, and every producer,
 * scratch and failed-output owner has confirmed cleanup. Never reconstructed from durable intent. */
internal class ConfirmedSaveCancellationException(cause: Throwable) : CancellationException("Save cancelled before JPEG output") {
    init { initCause(cause) }
}
