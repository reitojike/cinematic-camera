package dev.cinematic.p0.halfcompare

import java.util.concurrent.atomic.AtomicBoolean

/** Includes cancellation/drain. A failed release keeps owners reachable and blocks all retries. */
internal object ComparisonLease {
    private val occupied = AtomicBoolean()
    private val retained = mutableListOf<Any>()
    @Volatile var blocked = false; private set
    @Synchronized fun retain(owner: Any) { retained.add(owner); blocked = true }
    fun acquire(): AutoCloseable {
        check(!PrivatePhotoComparison.cleanupBlocked) { "COMPARE_CLEANUP" }
        check(!blocked && occupied.compareAndSet(false, true)) { "COMPARE_BUSY" }
        return AutoCloseable { if (!blocked) occupied.set(false) }
    }
}