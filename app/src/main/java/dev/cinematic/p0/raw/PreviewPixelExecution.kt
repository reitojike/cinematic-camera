package dev.cinematic.p0.raw

import java.io.Closeable
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean

/** Opt-in preview experiment. Product calls omit this context. Never shared across renders. */
internal class PreviewPixelExecution(
    workers: Int,
    private val shouldStop: () -> Boolean = { false },
) : Closeable {
    private val workerCount = workers.also { require(it in 1..2) }
    private val firstFailure = AtomicReference<Throwable?>(null)
    private val stopped = AtomicBoolean(false)
    private val pool = Executors.newFixedThreadPool(workers) { task ->
        Thread(task, "preview-pixel-candidate")
    }
    private var closed = false

    fun checkpoint() {
        if (stopped.get() || Thread.currentThread().isInterrupted || shouldStop()) {
            throw CancellationException("Preview candidate stopped")
        }
    }

    /** Completion order detects failure promptly; close joins even after cancellation. */
    fun ranges(count: Int, operation: (Int, Int) -> Unit) {
        check(!closed)
        checkpoint()
        val completions = ExecutorCompletionService<Unit>(pool)
        val tasks = (0 until minOf(workerCount, count)).map { worker ->
            val n = minOf(workerCount, count)
            completions.submit(Callable {
                try {
                    checkpoint()
                    operation(count * worker / n, count * (worker + 1) / n)
                    checkpoint()
                } catch (failure: Throwable) {
                    firstFailure.compareAndSet(null, failure)
                    stopped.set(true)
                    throw failure
                }
            })
        }
        try {
            repeat(tasks.size) { completions.take().get() }
        } catch (failure: Throwable) {
            stopped.set(true)
            tasks.forEach { it.cancel(true) }
            close()
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            if (failure is java.util.concurrent.ExecutionException) throw (firstFailure.get() ?: failure.cause ?: failure)
            throw failure
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        stopped.set(true)
        pool.shutdownNow()
        var interrupted = false
        while (!pool.isTerminated) {
            try { pool.awaitTermination(1, TimeUnit.SECONDS) }
            catch (_: InterruptedException) { interrupted = true }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }
}

internal fun previewPixelRanges(count: Int, execution: PreviewPixelExecution?, block: (Int, Int) -> Unit) {
    if (execution == null) block(0, count) else execution.ranges(count, block)
}

internal fun previewPixelCheckpoint(pixel: Int, execution: PreviewPixelExecution?) {
    if (pixel % 64 == 0) execution?.checkpoint()
}
