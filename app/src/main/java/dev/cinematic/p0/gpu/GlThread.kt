package dev.cinematic.p0.gpu

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask

/** Serializes all GL work. Close owned resources before closing this thread. */
internal class GlThread : AutoCloseable {
    private val thread = HandlerThread("gpu-engine").apply { start() }
    private val handler = Handler(thread.looper)
    private val lock = Any()
    private var closed = false
    private var asynchronousFailure: Throwable? = null

    fun requireCurrent() {
        check(Thread.currentThread() === thread) { "GL operation must run on gpu-engine" }
    }

    fun <T> call(block: () -> T): T {
        check(Looper.myLooper() !== Looper.getMainLooper()) { "GlThread.call cannot block the UI thread" }
        if (Thread.currentThread() === thread) { reportAsynchronousFailure(); return block() }
        // Check pending failures at execution time, after earlier queued posts have finished.
        val task = FutureTask { reportAsynchronousFailure(); block() }
        synchronized(lock) { checkOpen(); check(handler.post(task)) { "GL thread rejected work" } }
        return try { task.get() } catch (e: ExecutionException) { throw (e.cause ?: e) }
    }

    fun post(block: () -> Unit) {
        synchronized(lock) {
            checkOpen()
            check(handler.post {
                // Accepted work must drain even if close() has already called quitSafely().
                try { block() } catch (failure: Throwable) {
                    synchronized(lock) {
                        val previous = asynchronousFailure
                        if (previous == null) asynchronousFailure = failure else if (previous !== failure) previous.addSuppressed(failure)
                    }
                }
            }) { "GL thread rejected work" }
        }
    }

    private fun checkOpen() { check(!closed) { "GL thread is closed" } }
    private fun reportAsynchronousFailure() {
        val failure = synchronized(lock) { asynchronousFailure.also { asynchronousFailure = null } }
        // Report once, then permit a follow-up call to release/recreate the failed context.
        if (failure != null) throw IllegalStateException("An asynchronous GL request failed", failure)
    }

    /** Observe actual HandlerThread exit after queued cleanup, with no unbounded join. */
    fun awaitTermination(timeoutMillis: Long): Boolean {
        require(timeoutMillis >= 0)
        check(Looper.myLooper() !== Looper.getMainLooper()) { "Await GL thread exit off the UI thread" }
        check(Thread.currentThread() !== thread) { "GL thread cannot await its own exit" }
        // Thread.join(0) waits forever, so a zero remaining budget must only inspect state.
        if (timeoutMillis > 0) thread.join(timeoutMillis)
        return !thread.isAlive
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            thread.quitSafely()
        }
        if (Thread.currentThread() !== thread) { thread.join(); reportAsynchronousFailure() }
    }
}
