package dev.cinematic.p0.gpu.preview

import android.content.res.AssetManager
import dev.cinematic.p0.gpu.EglContexts
import dev.cinematic.p0.gpu.GlThread
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Activity-owned context, never a photo owner. All leases execute on the same serial GL queue. */
internal class GpuPreviewRuntime(
    private val assets: AssetManager,
    private var predecessor: GpuPreviewRuntime? = null,
) {
    val thread = GlThread()
    private val accepting = AtomicBoolean(true)
    private val destroyQueued = AtomicBoolean()
    private val destroyRequested = AtomicBoolean()
    private val terminated = CountDownLatch(1)
    private val failure = AtomicReference<Throwable?>()
    private var egl: EglContexts? = null
    @Volatile internal var contextCreationCount = 0
        private set
    private val retirementListeners = mutableMapOf<Long, () -> Unit>()
    private val unregisteredLeases = mutableSetOf<Long>()
    private var notificationsInFlight = 0
    private var issued = 0L
    private var released = 0L
    private var active = 0L
    val reusable: Boolean get() = accepting.get() && failure.get() == null

    @Synchronized fun lease(): Long {
        check(reusable) { "Preview runtime is retiring" }
        return (++issued).also { unregisteredLeases += it }
    }

    fun onRetiring(generation: Long, retire: () -> Unit) {
        val alreadyRetiring = synchronized(this) {
            check(unregisteredLeases.remove(generation)) { "Unknown or duplicate runtime registration" }
            if (accepting.get()) { retirementListeners[generation] = retire; false }
            else { notificationsInFlight++; true }
        }
        if (alreadyRetiring) notifyRetirement(retire)
    }

    private fun notifyRetirement(callback: () -> Unit) {
        try { callback() } catch (problem: Throwable) { failure.compareAndSet(null, problem) }
        finally {
            synchronized(this) { notificationsInFlight-- }
            enqueueDestroyIfReady()
        }
    }

    private fun acquire(generation: Long) {
        thread.requireCurrent()
        failure.get()?.let { throw IllegalStateException("Previous preview release failed", it) }
        check(active == generation || (active == 0L && generation == released + 1)) {
            "Previous preview generation is still owned"
        }
        active = generation
    }

    fun context(generation: Long): EglContexts {
        check(reusable) { "Preview runtime cannot accept GPU work" }
        acquire(generation)
        return egl ?: run {
            check(predecessor?.awaitClosed(2_000) != false) { "Previous GPU runtime did not terminate" }
            predecessor = null
            EglContexts(thread, assets).also { egl = it; contextCreationCount++ }
        }
    }

    /** A failed release is sticky; no later generation may conceal it. */
    fun release(generation: Long, problem: Throwable?) {
        thread.requireCurrent()
        synchronized(this) { retirementListeners.remove(generation) }
        if (problem != null) {
            failure.compareAndSet(null, problem)
            stopAccepting()
        } else {
            acquire(generation)
            active = 0L
            released = generation
        }
    }

    fun stopAccepting() {
        val retire = synchronized(this) {
            if (!accepting.compareAndSet(true, false)) return
            retirementListeners.values.toList().also {
                retirementListeners.clear(); notificationsInFlight += it.size
            }
        }
        // No runtime or renderer lock is held while closing every reserved generation.
        retire.forEach(::notifyRetirement)
        enqueueDestroyIfReady()
    }

    /** May be called on UI. Cleanup is queued after the active renderer's retirement. */
    fun close() {
        destroyRequested.set(true)
        stopAccepting()
        enqueueDestroyIfReady()
    }

    private fun enqueueDestroyIfReady() {
        // Another thread may request close while retirement callbacks are still queuing cleanup.
        if (!destroyRequested.get()) return
        val ready = synchronized(this) {
            !accepting.get() && unregisteredLeases.isEmpty() && notificationsInFlight == 0
        }
        if (!ready) return
        if (!destroyQueued.compareAndSet(false, true)) return
        thread.post {
            try {
                try { check(active == 0L) { "Preview context still has a photo owner" } }
                finally { egl?.close(); egl = null }
            } catch (problem: Throwable) { failure.compareAndSet(null, problem) }
            finally { thread.close(); terminated.countDown() }
        }
    }

    fun awaitClosed(timeoutMillis: Long): Boolean {
        val start = System.nanoTime()
        if (!terminated.await(timeoutMillis, TimeUnit.MILLISECONDS)) return false
        val remaining = (timeoutMillis - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).coerceAtLeast(0)
        return thread.awaitTermination(remaining) && failure.get() == null && egl == null
    }
}
