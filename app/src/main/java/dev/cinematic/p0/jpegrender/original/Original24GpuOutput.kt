package dev.cinematic.p0.jpegrender.original

import android.content.res.AssetManager
import dev.cinematic.p0.BuildConfig
import dev.cinematic.p0.gpu.*
import dev.cinematic.p0.gpu.diag.GpuStageTap
import dev.cinematic.p0.gpu.export.GpuExportRenderer
import dev.cinematic.p0.raw.original.OriginalRaster
import dev.cinematic.p0.rawcapture.GpuSaveFailureReason
import dev.cinematic.p0.rawcapture.GpuSaveRenderException
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** A save owns its GL thread/context. No preview context, full-frame Float image or CPU fallback.
 * A caller interrupt requests cancellation but still drains the GL work before raw/Bitmap cleanup.
 * Unconfirmed context/thread close remains held, including on a later cleanup call.
 */
internal class Original24GpuOutput(private val assets: AssetManager) : AutoCloseable {
    private var started = false
    private var held = false
    val hasUnreleasedResources get() = held

    fun render(source: OriginalRaster, ev: Double, gains: DoubleArray, strength: Double,
        stop: () -> Boolean, consume: (Int, IntArray) -> Unit) {
        check(!started); started = true
        // Freeze one observer for this attempt. Release/internal builds cannot register or invoke it.
        val observer = if (observationsAllowed) diagnosticObserver.get() else null
        val interrupted = AtomicBoolean(false)
        var failure: Throwable? = null
        var cleanupConfirmed = false
        held = true
        val thread = try { GlThread() } catch (error: Exception) {
            // HandlerThread construction may have partially succeeded. Do not authorize retry.
            throw GpuSaveRenderException(reason(error), false, error)
        }
        try {
            val completed = CompletableFuture<Unit>()
            thread.post {
                var egl: EglContexts? = null
                var error: Throwable? = null
                try {
                    val ledger = GpuMemoryLedger(GpuMemoryLedger.EXPORT_BUDGET)
                    fun snapshot() = TextureBalance(ledger.allocatedBytes, ledger.textureCount, ledger.peakAllocatedBytes)
                    egl = EglContexts(thread, assets)
                    try {
                        GpuExportRenderer(egl, ledger).render(source, ev, gains, strength,
                            stop = { interrupted.get() || stop() },
                            tap = observer?.let { observation -> { point, origin, texture ->
                                observation.insideBand(point, origin, texture.height, snapshot())
                            } },
                            onBandComplete = observer?.let { observation -> { boundary, origin, rows ->
                                observation.bandComplete(boundary, origin, rows, snapshot())
                            } }, consumeRow = consume)
                    } catch (caught: Throwable) { error = caught; throw caught }
                    finally {
                        // Renderer.use has returned/unwound. Observe before EGL teardown; this is
                        // a texture balance, not a claim that context/thread cleanup has succeeded.
                        try { observer?.rendererReleased(snapshot()) }
                        catch (observation: Throwable) {
                            // Reporting must never replace the render failure or its cleanup evidence.
                            val primary = error
                            if (primary == null) throw observation
                            if (primary !== observation) primary.addSuppressed(observation)
                        }
                    }
                } catch (caught: Throwable) { error = caught }
                finally {
                    try {
                        // A failed constructor closes its partial resources itself; a suppressed
                        // cleanup failure means their state cannot be reconstructed here.
                        egl?.close()
                        cleanupConfirmed = error?.suppressed?.isEmpty() != false
                    } catch (cleanup: Throwable) {
                        if (error == null) error = cleanup else if (cleanup !== error) error.addSuppressed(cleanup)
                        cleanupConfirmed = false
                    }
                    if (error == null) completed.complete(Unit) else completed.completeExceptionally(error)
                }
            }
            // Never abandon a task that still reads the source or writes the Bitmap.
            while (true) {
                try { completed.get(); break }
                catch (_: InterruptedException) { interrupted.set(true) }
                catch (error: ExecutionException) { failure = error.cause ?: error; break }
                catch (cancelled: CancellationException) { failure = cancelled; break }
            }
        } catch (error: Throwable) { failure = error; cleanupConfirmed = false }
        finally {
            try { thread.close() }
            catch (cleanup: Throwable) {
                cleanupConfirmed = false
                if (failure == null) failure = cleanup else if (cleanup !== failure) failure.addSuppressed(cleanup)
            }
            held = !cleanupConfirmed
            if (interrupted.get()) Thread.currentThread().interrupt()
        }
        if (interrupted.get() || stop() || failure is CancellationException || failure is android.os.OperationCanceledException)
            throw CancellationException("GPU export stopped").apply { failure?.let(::initCause) }
        failure?.let {
            if (it !is Exception) throw it
            throw GpuSaveRenderException(reason(it), cleanupConfirmed, it)
        }
    }

    override fun close() {
        if (held) throw IOException("GPU export close is unconfirmed")
    }

    private fun reason(failure: Throwable) = when (failure) {
        is GpuUnavailableException -> GpuSaveFailureReason.UNAVAILABLE
        is GpuContextLostException -> GpuSaveFailureReason.CONTEXT_LOST
        is GpuMemoryBudgetException -> GpuSaveFailureReason.MEMORY_BUDGET
        else -> GpuSaveFailureReason.RENDER_FAILED
    }

    /** Aggregate-only GPU diagnostic seam. No textures, source pixels, URI or owner can escape. */
    internal data class TextureBalance(val bytes: Long, val textures: Int, val peakBytes: Long)
    internal interface DiagnosticObserver {
        fun insideBand(point: GpuStageTap.Point, origin: Int, rows: Int, balance: TextureBalance) {}
        fun bandComplete(boundary: String, origin: Int, rows: Int, balance: TextureBalance) {}
        fun rendererReleased(balance: TextureBalance) {}
    }
    companion object {
        private val observationsAllowed = BuildConfig.BUILD_TYPE == "gpuDiagnostic" ||
            (BuildConfig.BUILD_TYPE == "debug" && BuildConfig.DEBUG)
        private val diagnosticObserver = AtomicReference<DiagnosticObserver?>()
        internal fun observeForDiagnostics(observer: DiagnosticObserver): AutoCloseable {
            check(observationsAllowed) { "GPU observations require debug instrumentation or gpuDiagnostic" }
            check(diagnosticObserver.compareAndSet(null, observer)) { "Export diagnostic observer already installed" }
            return AutoCloseable {
                check(diagnosticObserver.compareAndSet(observer, null)) { "Export diagnostic observer changed" }
            }
        }
    }
}
