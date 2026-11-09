package dev.cinematic.p0.gpu

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask

/** GL の全操作を直列化する。所有リソースを解放してから close する。 */
internal class GlThread : AutoCloseable {
    private val thread = HandlerThread("gpu-engine").apply { start() }
    private val handler = Handler(thread.looper)
    private val lock = Any()
    @Volatile private var closed = false
    @Volatile private var asynchronousFailure: Throwable? = null

    fun requireCurrent() {
        check(Looper.myLooper() === thread.looper) { "GL operation must run on gpu-engine" }
    }

    fun <T> call(block: () -> T): T {
        check(Looper.myLooper() !== Looper.getMainLooper()) { "GlThread.call cannot block the UI thread" }
        synchronized(lock) { checkOpen() }
        if (Looper.myLooper() === thread.looper) return block()
        val task = FutureTask(block)
        synchronized(lock) { checkOpen(); check(handler.post(task)) { "GL thread rejected work" } }
        return try { task.get() } catch (e: ExecutionException) { throw (e.cause ?: e) }
    }

    fun post(block: () -> Unit) {
        synchronized(lock) {
            checkOpen()
            check(handler.post {
                try { checkOpen(); block() } catch (failure: Throwable) { asynchronousFailure = failure }
            }) { "GL thread rejected work" }
        }
    }

    private fun checkOpen() {
        check(!closed) { "GL thread is closed" }
        asynchronousFailure?.let { throw IllegalStateException("An asynchronous GL request failed", it) }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            thread.quitSafely()
        }
        if (Thread.currentThread() !== thread) thread.join()
    }
}
