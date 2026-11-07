package dev.cinematic.p0.photoroll

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Caller cancels its old LaunchedEffect job before replacing the request.
 * Mutex removes cancelled waiters; an executing block keeps the permit until it returns.
 * This is not native/queue resource admission and never substitutes for their cleanup. */
internal class PreviewRequestGate {
    private val mutex = Mutex()

    suspend fun <T> run(enabled: Boolean, block: suspend () -> T): T =
        if (!enabled) block() else mutex.withLock {
            currentCoroutineContext().ensureActive()
            block()
        }

    companion object { val process = PreviewRequestGate() }
}

/** Does not adopt a value or replace the caller's original exception/cleanup contract. */
internal fun awaitPreviewOwnerCompletion(future: java.util.concurrent.CompletableFuture<*>) {
    if (!future.isDone) future.handle { _, _ -> Unit }.join()
}
