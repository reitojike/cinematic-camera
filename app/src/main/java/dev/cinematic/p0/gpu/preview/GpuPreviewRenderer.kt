package dev.cinematic.p0.gpu.preview

import android.content.ComponentCallbacks2
import android.content.res.AssetManager
import android.opengl.GLES32
import android.view.Choreographer
import android.view.Surface
import dev.cinematic.p0.gpu.*
import dev.cinematic.p0.raw.LinearSrgbImage
import dev.cinematic.p0.raw.PhotoAdjustments
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** UI writes only the latest value; only the GL thread consumes/acknowledges frames. */
internal class LatestPreviewFrame<T>(initial: T, private val nowNanos: () -> Long = System::nanoTime) {
    data class Pending<T>(val value: T, val dirtySinceNanos: Long)
    private data class State<T>(val value: T, val displayed: T?, val dirtySince: Long?)
    private val state = AtomicReference(State(initial, null, nowNanos()))
    fun submit(value: T) {
        while (true) {
            val old = state.get()
            if (old.value == value) return
            val since = if (old.displayed == value) null else old.dirtySince ?: nowNanos()
            if (state.compareAndSet(old, State(value, old.displayed, since))) return
        }
    }
    fun next(): T? = state.get().let { if (it.dirtySince == null) null else it.value }
    /** Consume at frame start. New submissions during draw belong to the *next* frame. */
    fun beginFrame(): Pending<T>? {
        while (true) {
            val old = state.get()
            val since = old.dirtySince ?: return null
            if (state.compareAndSet(old, State(old.value, old.value, null))) return Pending(old.value, since)
        }
    }
    fun rendered(value: T) {
        while (true) {
            val old = state.get()
            val since = if (old.value == value) null else old.dirtySince ?: nowNanos()
            if (state.compareAndSet(old, State(old.value, value, since))) return
        }
    }
    fun invalidate() {
        while (true) {
            val old = state.get()
            if (state.compareAndSet(old, State(old.value, null, nowNanos()))) return
        }
    }
}

internal data class GpuPreviewFrame(
    val stats: GpuFrameStats,
    val frameTimeNanos: Long,
    val missedChangedFrames: Long,
    val allocatedBytes: Long,
)

internal data class GpuPreviewPhoto(val input: GpuTexture)
internal data class GpuPreviewSnapshot(val allocatedBytes: Long, val windowCount: Int, val drawCount: Long)

/**
 * Phase 0 input presenter. The optional draw function owns no input/target texture; #284 can
 * replace its contents with the R7/look graph. Before always bypasses it. Nothing reads pixels back.
 * All public operations are nonblocking except the surface lifetime boundary (see detachSurface).
 * #284 engineFactory replaces input ownership and drawing together, without a redundant output
 * texture. The legacy draw hook remains for isolated presentation/lifecycle regression tests.
 */
internal class GpuPreviewRenderer(
    private val assets: AssetManager,
    private val onFrame: (GpuPreviewFrame) -> Unit = {},
    private val onError: (Throwable) -> Unit = {},
    private val draw: ((GpuPreviewPhoto, GpuRenderParams, GpuTexture) -> Unit)? = null,
    private val destroyWindow: (EglContexts.WindowTarget) -> Unit = { it.close() },
    private val engineFactory: ((EglContexts, GpuMemoryLedger) -> StageClearGpu)? = null,
) : AutoCloseable {
    init { require(draw == null || engineFactory == null) { "Choose one preview drawing owner" } }
    private data class Request(val params: GpuRenderParams, val display: GpuDisplayOptions)
    private class SurfaceLease(val surface: Surface, val width: Int, val height: Int) {
        val valid = AtomicBoolean(true)
    }
    private val thread = GlThread()
    private val enqueueLock = Any()
    private val closed = AtomicBoolean()
    private val wakeQueued = AtomicBoolean()
    private val latest = LatestPreviewFrame(Request(GpuRenderParams(0.0, PhotoAdjustments.NONE), GpuDisplayOptions(false, 0)))
    private val terminated = CountDownLatch(1)
    private val shutdownFailure = AtomicReference<Throwable?>()
    // A failed EGL destroy must never be hidden by an earlier ON_STOP/cleanup clearing the handle.
    private val surfaceDestroyFailure = AtomicReference<Throwable?>()
    private val lease = AtomicReference<SurfaceLease?>()
    private val started = AtomicBoolean()
    private var egl: EglContexts? = null
    private val ledger = GpuMemoryLedger()
    private var photo: GpuPreviewPhoto? = null
    private var engine: StageClearGpu? = null
    private var enginePhoto: StageClearGpuPhoto? = null
    private var retainedInput: LinearSrgbImage? = null
    private var output: GpuTexture? = null
    private var window: EglContexts.WindowTarget? = null
    private val liveWindows = AtomicInteger()
    internal val activeWindowCount: Int get() = liveWindows.get()
    private var windowLease: SurfaceLease? = null
    private var choreographer: Choreographer? = null
    private var frameScheduled = false
    private var timer: PreviewGpuTimer? = null
    private var linearFiltering = false
    private var frameIntervalNanos = 16_666_667L
    private var drawCount = 0L
    private var missedChangedFrames = 0L
    private var failed = false
    private var contextLost = false
    private val callback = Choreographer.FrameCallback { frameTime ->
        frameScheduled = false
        guarded { renderFrame(frameTime) }
    }

    fun submit(params: GpuRenderParams, display: GpuDisplayOptions) {
        require(params.strength.isFinite() && params.strength in 0.0..1.0)
        require(display.rotationDegrees in setOf(0, 90, 180, 270))
        if (closed.get()) return
        latest.submit(Request(params, display))
        wake()
    }

    fun openPhoto(input: LinearSrgbImage) = post {
        GpuPreviewInputSize.checkSupported(input.width, input.height)
        retainedInput = input
        if (contextLost) resetContext()
        uploadPhoto(input)
        latest.invalidate(); schedule()
    }

    private fun uploadPhoto(input: LinearSrgbImage) {
        releasePhoto()
        val context = context()
        context.makeOffscreenCurrent()
        val factory = engineFactory
        if (factory != null) {
            val pipeline = engine ?: factory(context, ledger).also { engine = it }
            enginePhoto = pipeline.openPhoto(input) { closed.get() }
            return
        }
        val texture = GpuTexture.create(context, ledger, input.width, input.height, GpuTexture.Format.RGBA32F)
        try {
            val bytes = ByteBuffer.allocateDirect(input.pixelCount * 16).order(ByteOrder.nativeOrder()).asFloatBuffer()
            repeat(input.pixelCount) { p ->
                bytes.put(input[p * 3]); bytes.put(input[p * 3 + 1]); bytes.put(input[p * 3 + 2]); bytes.put(1f)
            }
            bytes.rewind(); texture.upload(bytes)
            photo = GpuPreviewPhoto(texture)
        } catch (failure: Throwable) { texture.close(); throw failure }
    }

    /** Explicit retry after a reported failure. No implicit CPU fallback or retry loop. */
    fun retry() = post {
        resetContext()
        retainedInput?.let(::uploadPhoto)
        latest.invalidate(); schedule()
    }

    fun closePhoto() = post { cancelFrame(); retainedInput = null; releasePhoto(); latest.invalidate() }

    @Suppress("DEPRECATION")
    fun trimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) post {
            cancelFrame(); releasePhoto(); latest.invalidate()
        }
    }

    fun attachSurface(surface: Surface, width: Int, height: Int, refreshRate: Float = 60f) {
        require(width > 0 && height > 0)
        if (closed.get()) return
        lease.getAndSet(SurfaceLease(surface, width, height))?.valid?.set(false)
        post {
            val hz = refreshRate.takeIf { it.isFinite() && it > 0 } ?: 60f
            frameIntervalNanos = (1_000_000_000.0 / hz).toLong().coerceAtLeast(1L)
            releaseWindow(); latest.invalidate(); schedule()
        }
    }

    fun resizeSurface(surface: Surface, width: Int, height: Int) {
        if (width <= 0 || height <= 0 || closed.get()) return
        while (true) {
            val current = lease.get()?.takeIf { it.surface === surface } ?: return
            if (lease.compareAndSet(current, SurfaceLease(surface, width, height))) {
                current.valid.set(false); break
            }
        }
        post { releaseWindow(); latest.invalidate(); schedule() }
    }

    /**
     * Match SurfaceHolder's destruction boundary: no rendering AND no EGL window remain when
     * this returns normally. Only this lifecycle callback waits, never submit or GlThread.call.
     * The barrier is queued behind any active frame, then destroys on the owning GL thread.
     * A stalled driver fails explicitly after 2 seconds rather than claiming a safe detach.
     * https://developer.android.com/reference/kotlin/androidx/compose/foundation/SurfaceScope
     */
    fun detachSurface(surface: Surface) {
        while (true) {
            val current = lease.get()?.takeIf { it.surface === surface } ?: break
            current.valid.set(false)
            if (lease.compareAndSet(current, null)) break
        }
        val destroyed = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val completion = synchronized(enqueueLock) {
            if (closed.get()) terminated else {
                thread.post {
                    try {
                        cancelFrame()
                        if (windowLease?.surface === surface) releaseWindow()
                        latest.invalidate(); schedule()
                    } catch (problem: Throwable) {
                        failure.set(problem); failed = true; onError(problem)
                    } finally { destroyed.countDown() }
                }
                destroyed
            }
        }
        check(completion.await(2, TimeUnit.SECONDS)) { "Timed out destroying GPU preview EGL surface" }
        (failure.get() ?: surfaceDestroyFailure.get() ?: if (completion === terminated) shutdownFailure.get() else null)?.let {
            throw IllegalStateException("Could not destroy GPU preview EGL surface", it)
        }
    }

    fun setStarted(value: Boolean) {
        started.set(value)
        post {
            if (!value) { cancelFrame(); releaseWindow() }
            else if (photo == null && enginePhoto == null && !failed) retainedInput?.let(::uploadPhoto)
            latest.invalidate(); schedule()
        }
    }

    /** Tests/diagnostics: callback runs on GL, after previously accepted work. */
    fun snapshot(callback: (GpuPreviewSnapshot) -> Unit) = post {
        callback(GpuPreviewSnapshot(ledger.allocatedBytes, liveWindows.get(), drawCount))
    }

    private fun wake() {
        if (!closed.get() && wakeQueued.compareAndSet(false, true)) post {
            wakeQueued.set(false)
            // GPU_ENGINE §4.3: a new request after context loss rebuilds GPU state from the input.
            if (contextLost) { resetContext(); retainedInput?.let(::uploadPhoto); latest.invalidate() }
            schedule()
        }
    }

    private fun post(block: () -> Unit) {
        synchronized(enqueueLock) {
            if (closed.get()) return
            thread.post { guarded(block) }
        }
    }

    private fun guarded(block: () -> Unit) {
        try { block() } catch (failure: Throwable) {
            failed = true
            contextLost = generateSequence(failure) { it.cause }.any { it is GpuContextLostException }
            cancelFrame()
            runCatching { releaseWindow() }.exceptionOrNull()?.let(failure::addSuppressed)
            onError(failure)
        }
    }

    private fun resetContext() {
        cancelFrame()
        var problem: Throwable? = null
        fun cleanup(block: () -> Unit) {
            try { block() } catch (failure: Throwable) {
                if (problem == null) problem = failure else problem!!.addSuppressed(failure)
            }
        }
        cleanup { releaseWindow() }
        cleanup { releasePhoto() }
        cleanup { engine?.close(); engine = null }
        cleanup { timer?.close() }; timer = null
        cleanup { egl?.close() }; egl = null
        // Lost EGL objects can report teardown errors; EGL owns their GL names. Accounting still
        // has to be empty before starting again. Do not hide an actual ownership cleanup failure.
        check(ledger.allocatedBytes == 0L) { "Preview resources survived context reset" }
        surfaceDestroyFailure.get()?.let { throw IllegalStateException("Previous EGL surface destruction failed", it) }
        failed = false; contextLost = false
        latest.invalidate()
        problem?.let { onError(it) }
    }

    private fun context(): EglContexts = egl ?: EglContexts(thread, assets).also { context ->
        egl = context
        GlCapabilities.inspect(context).requireSupported()
        val extensions = GLES32.glGetString(GLES32.GL_EXTENSIONS).orEmpty().split(' ')
        linearFiltering = "GL_OES_texture_float_linear" in extensions
        timer = PreviewGpuTimer(context, "GL_EXT_disjoint_timer_query" in extensions)
    }

    private fun schedule() {
        thread.requireCurrent()
        if (closed.get() || failed || frameScheduled || (photo == null && enginePhoto == null)) return
        if (latest.next() == null && timer?.hasPending != true) return
        if (!started.get() || lease.get()?.valid?.get() != true) return
        val clock = choreographer ?: Choreographer.getInstance().also { choreographer = it }
        frameScheduled = true
        clock.postFrameCallback(callback)
    }

    private fun cancelFrame() {
        choreographer?.removeFrameCallback(callback)
        frameScheduled = false
    }

    private fun renderFrame(frameTime: Long) {
        if (closed.get() || failed) return
        timer?.poll()?.forEach(onFrame)
        val source = photo
        val fullPhoto = enginePhoto
        if (source == null && fullPhoto == null) return
        val current = lease.get()?.takeIf { it.valid.get() && started.get() } ?: return
        val pending = latest.beginFrame()
        if (pending == null) { schedule(); return }
        val request = pending.value
        run {
            val context = context()
            if (windowLease !== current || window == null) {
                releaseWindow()
                window = context.createWindowTarget(current.surface); windowLease = current
                liveWindows.incrementAndGet()
            }
            context.makeCurrent(checkNotNull(window))
            val cpuStart = System.nanoTime()
            var decisions = GpuDecisions(0.0, 1.0)
            timer?.begin()
            try {
                if (fullPhoto != null) {
                    decisions = checkNotNull(engine).renderPreview(fullPhoto, request.params,
                        StageClearGpuPreviewTarget(checkNotNull(window), current.width, current.height, linearFiltering),
                        request.display).decisions
                } else {
                    val inputPhoto = checkNotNull(source)
                    val image = if (request.display.showBefore || draw == null) inputPhoto.input else {
                        val result = output ?: GpuTexture.create(context, ledger, inputPhoto.input.width, inputPhoto.input.height, GpuTexture.Format.RGBA32F).also { output = it }
                        draw.invoke(inputPhoto, request.params, result)
                        result
                    }
                    present(context, image, current.width, current.height, request.display.rotationDegrees, linearFiltering = linearFiltering)
                }
            } catch (failure: Throwable) { timer?.cancelActive(); throw failure }
            timer?.end()
            val cpuNanos = System.nanoTime() - cpuStart
            context.swapBuffers(checkNotNull(window))
            latest.rendered(request); drawCount++
            // Count from the first undrawn submit, including time spent in the previous slow draw.
            // beginFrame clears that timestamp so submissions during this draw start the next one.
            missedChangedFrames += ((frameTime - pending.dirtySinceNanos).coerceAtLeast(0L) / frameIntervalNanos)
            val frame = GpuPreviewFrame(GpuFrameStats(null, cpuNanos, decisions), frameTime, missedChangedFrames, ledger.allocatedBytes)
            // Complete the query around GPU work only (before swap); callbacks remain FIFO.
            timer?.complete(frame)?.forEach(onFrame) ?: onFrame(frame)
        }
        schedule() // A concurrent submit replaces the mailbox rather than appending another frame.
    }

    private fun releaseWindow() {
        val old = window; window = null; windowLease = null
        if (old != null) {
            try { destroyWindow(old); liveWindows.decrementAndGet() }
            catch (failure: Throwable) { surfaceDestroyFailure.compareAndSet(null, failure); throw failure }
        }
    }
    private fun releasePhoto() {
        enginePhoto?.close(); enginePhoto = null
        val oldOutput = output; val oldPhoto = photo
        output = null; photo = null
        try { oldOutput?.close() } finally { oldPhoto?.input?.close() }
    }

    override fun close() {
        synchronized(enqueueLock) {
            if (!closed.compareAndSet(false, true)) return
            lease.getAndSet(null)?.valid?.set(false); started.set(false)
            // Cleanup is accepted before quitSafely; no UI join. detachSurface can await terminated.
            thread.post {
                try {
                    cancelFrame()
                    try { releaseWindow() } finally {
                        try { retainedInput = null; releasePhoto() } finally {
                            try { engine?.close() } finally {
                                try { timer?.close() } finally { egl?.close() }
                            }
                        }
                    }
                } catch (failure: Throwable) { shutdownFailure.set(failure); onError(failure) }
                finally { thread.close(); terminated.countDown() }
            }
        }
    }

}

/** Borrowed texture sampling state is restored even when drawing fails. */
internal fun present(egl: EglContexts, image: GpuTexture, width: Int, height: Int, rotation: Int, target: GpuTexture? = null,
    linearFiltering: Boolean = false) {
    egl.requireCurrent()
    // GL_LINEAR on RGBA32F needs OES_texture_float_linear; core ES uses the same four-tap
    // interpolation in the display shader instead of raising the engine's minimum capabilities.
    val filtering = if (linearFiltering) GLES32.GL_LINEAR else GLES32.GL_NEAREST
    GLES32.glActiveTexture(GLES32.GL_TEXTURE0)
    GLES32.glBindTexture(GLES32.GL_TEXTURE_2D, image.textureId)
    val oldMin = IntArray(1); val oldMag = IntArray(1)
    GLES32.glGetTexParameteriv(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_MIN_FILTER, oldMin, 0)
    GLES32.glGetTexParameteriv(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_MAG_FILTER, oldMag, 0)
    try {
        GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_MIN_FILTER, filtering)
        GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_MAG_FILTER, filtering)
        egl.passes().run(FragmentPass(egl.shaders().fragment("display/present.frag"), listOf(TextureInput("s_input", image, 0)), target,
            GpuViewport(0, 0, width, height), uniforms = { id ->
                GLES32.glUniform2f(GLES32.glGetUniformLocation(id, "u_surfaceSize"), width.toFloat(), height.toFloat())
                GLES32.glUniform1i(GLES32.glGetUniformLocation(id, "u_rotation"), rotation)
                GLES32.glUniform1i(GLES32.glGetUniformLocation(id, "u_linearFiltering"), if (linearFiltering) 1 else 0)
            }))
    } finally {
        GLES32.glActiveTexture(GLES32.GL_TEXTURE0)
        GLES32.glBindTexture(GLES32.GL_TEXTURE_2D, image.textureId)
        GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_MIN_FILTER, oldMin[0])
        GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_MAG_FILTER, oldMag[0])
    }
}

/** EXT timer uses core query entry points with EXT enum; samples stay paired with their frames. */
private class PreviewGpuTimer(private val egl: EglContexts, private val supported: Boolean) : AutoCloseable {
    private data class Pending(val query: Int, val frame: GpuPreviewFrame)
    private val queries = ArrayDeque<Pending>()
    private var active = 0
    private var ended = 0
    val hasPending get() = queries.isNotEmpty()
    fun begin() {
        if (!supported || queries.size >= 4) return
        active = IntArray(1).also { GLES32.glGenQueries(1, it, 0) }[0]
        GLES32.glBeginQuery(0x88BF, active) // GL_TIME_ELAPSED_EXT
    }
    fun end() {
        if (active == 0) return
        GLES32.glEndQuery(0x88BF); ended = active; active = 0
    }
    fun complete(frame: GpuPreviewFrame): List<GpuPreviewFrame> {
        if (ended == 0) {
            // Preserve frame ordering even when the bounded query pool is full.
            val ready = discardPending()
            return ready + frame
        }
        queries.addLast(Pending(ended, frame)); ended = 0
        return emptyList()
    }
    fun poll(): List<GpuPreviewFrame> {
        if (!supported || queries.isEmpty()) return emptyList()
        val disjoint = IntArray(1).also { GLES32.glGetIntegerv(0x8FBB, it, 0) }[0]
        if (disjoint != 0) return discardPending()
        val ready = mutableListOf<GpuPreviewFrame>()
        while (queries.isNotEmpty()) {
            val item = queries.first()
            val available = IntArray(1).also { GLES32.glGetQueryObjectuiv(item.query, GLES32.GL_QUERY_RESULT_AVAILABLE, it, 0) }[0]
            if (available == 0) break
            val result = IntArray(1).also { GLES32.glGetQueryObjectuiv(item.query, GLES32.GL_QUERY_RESULT, it, 0) }[0].toLong() and 0xffffffffL
            queries.removeFirst(); GLES32.glDeleteQueries(1, intArrayOf(item.query), 0)
            // 32-bit query results saturate; do not report the sentinel as a measured duration.
            ready += item.frame.copy(stats = item.frame.stats.copy(gpuNanos = result.takeIf { it < 0xffffffffL }))
        }
        return ready
    }
    private fun discardPending(): List<GpuPreviewFrame> {
        val frames = queries.map { it.frame }
        if (queries.isNotEmpty()) egl.releaseResource { GLES32.glDeleteQueries(queries.size, queries.map { it.query }.toIntArray(), 0) }
        queries.clear()
        return frames
    }
    fun cancelActive() {
        if (active != 0) end()
        if (ended != 0) egl.releaseResource { GLES32.glDeleteQueries(1, intArrayOf(ended), 0) }
        ended = 0
    }
    override fun close() { cancelActive(); discardPending() }
}
