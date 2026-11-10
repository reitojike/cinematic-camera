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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** UI writes only the latest value; only the GL thread consumes/acknowledges frames. */
internal class LatestPreviewFrame<T>(initial: T) {
    private val latest = AtomicReference(initial)
    private var rendered: T? = null
    fun submit(value: T) { latest.set(value) }
    fun next(): T? = latest.get().takeUnless { it == rendered }
    fun rendered(value: T) { rendered = value }
    fun invalidate() { rendered = null }
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
 */
internal class GpuPreviewRenderer(
    private val assets: AssetManager,
    private val onFrame: (GpuPreviewFrame) -> Unit = {},
    private val onError: (Throwable) -> Unit = {},
    private val draw: ((GpuPreviewPhoto, GpuRenderParams, GpuTexture) -> Unit)? = null,
) : AutoCloseable {
    private data class Request(val params: GpuRenderParams, val display: GpuDisplayOptions)
    private class SurfaceLease(val surface: Surface, var width: Int, var height: Int) {
        var valid = true
    }
    private val thread = GlThread()
    private val gate = Any()
    private val enqueueLock = Any()
    private val closed = AtomicBoolean()
    private val wakeQueued = AtomicBoolean()
    private val latest = LatestPreviewFrame(Request(GpuRenderParams(0.0, PhotoAdjustments.NONE), GpuDisplayOptions(false, 0)))
    // Surface lease/gate is shared solely to quiesce rendering before onDestroyed returns.
    private var lease: SurfaceLease? = null
    private var started = false
    private var egl: EglContexts? = null
    private val ledger = GpuMemoryLedger()
    private var photo: GpuPreviewPhoto? = null
    private var retainedInput: LinearSrgbImage? = null
    private var output: GpuTexture? = null
    private var window: EglContexts.WindowTarget? = null
    private var windowLease: SurfaceLease? = null
    private var choreographer: Choreographer? = null
    private var frameScheduled = false
    private var timer: PreviewGpuTimer? = null
    private var frameIntervalNanos = 16_666_667L
    private var drawCount = 0L
    private var missedChangedFrames = 0L
    private var pendingSinceNanos = 0L
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
        check(maxOf(input.width, input.height) <= 1024) { "Phase 0 preview input must have long edge <= 1024" }
        retainedInput = input
        if (contextLost) resetContext()
        uploadPhoto(input)
        latest.invalidate(); schedule()
    }

    private fun uploadPhoto(input: LinearSrgbImage) {
        releasePhoto()
        val context = context()
        context.makeOffscreenCurrent()
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
        synchronized(gate) {
            if (closed.get()) return
            lease?.valid = false
            lease = SurfaceLease(surface, width, height)
        }
        post {
            val hz = refreshRate.takeIf { it.isFinite() && it > 0 } ?: 60f
            frameIntervalNanos = (1_000_000_000.0 / hz).toLong().coerceAtLeast(1L)
            releaseWindow(); latest.invalidate(); schedule()
        }
    }

    fun resizeSurface(surface: Surface, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        synchronized(gate) {
            val current = lease?.takeIf { it.surface === surface } ?: return
            current.width = width; current.height = height
        }
        post { releaseWindow(); latest.invalidate(); schedule() }
    }

    /**
     * AndroidExternalSurface/SurfaceHolder requires no surface access after this returns.
     * Do not replace this barrier with a fire-and-forget destroy, nor call GlThread.call on UI.
     * The frame holds the same gate through create/makeCurrent/draw/swap. No GL work runs here.
     * https://developer.android.com/reference/kotlin/androidx/compose/foundation/SurfaceScope
     */
    fun detachSurface(surface: Surface) {
        synchronized(gate) {
            val current = lease?.takeIf { it.surface === surface } ?: return
            current.valid = false; lease = null
        }
        post { cancelFrame(); releaseWindow(); latest.invalidate(); schedule() }
    }

    fun setStarted(value: Boolean) {
        synchronized(gate) { started = value }
        post {
            if (!value) { cancelFrame(); releaseWindow() }
            else if (photo == null && !failed) retainedInput?.let(::uploadPhoto)
            latest.invalidate(); schedule()
        }
    }

    /** Tests/diagnostics: callback runs on GL, after previously accepted work. */
    fun snapshot(callback: (GpuPreviewSnapshot) -> Unit) = post {
        callback(GpuPreviewSnapshot(ledger.allocatedBytes, if (window == null) 0 else 1, drawCount))
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
        cleanup { timer?.close() }; timer = null
        cleanup { egl?.close() }; egl = null
        // Lost EGL objects can report teardown errors; EGL owns their GL names. Accounting still
        // has to be empty before starting again. Do not hide an actual ownership cleanup failure.
        check(ledger.allocatedBytes == 0L) { "Preview resources survived context reset" }
        failed = false; contextLost = false
        latest.invalidate()
        problem?.let { onError(it) }
    }

    private fun context(): EglContexts = egl ?: EglContexts(thread, assets).also { context ->
        egl = context
        GlCapabilities.inspect(context).requireSupported()
        val extensions = GLES32.glGetString(GLES32.GL_EXTENSIONS).orEmpty().split(' ')
        timer = PreviewGpuTimer(context, "GL_EXT_disjoint_timer_query" in extensions)
    }

    private fun schedule() {
        thread.requireCurrent()
        if (closed.get() || failed || frameScheduled || photo == null) return
        if (latest.next() == null && timer?.hasPending != true) return
        synchronized(gate) { if (!started || lease?.valid != true) return }
        val clock = choreographer ?: Choreographer.getInstance().also { choreographer = it }
        frameScheduled = true
        pendingSinceNanos = System.nanoTime()
        clock.postFrameCallback(callback)
    }

    private fun cancelFrame() {
        choreographer?.removeFrameCallback(callback)
        frameScheduled = false
        pendingSinceNanos = 0L
    }

    private fun renderFrame(frameTime: Long) {
        if (closed.get() || failed) return
        timer?.poll()?.forEach(onFrame)
        val request = latest.next()
        if (request == null) { schedule(); return }
        val source = photo ?: return
        synchronized(gate) {
            val current = lease?.takeIf { it.valid && started } ?: return
            val context = context()
            if (windowLease !== current || window == null) {
                releaseWindow()
                window = context.createWindowTarget(current.surface); windowLease = current
            }
            context.makeCurrent(checkNotNull(window))
            val cpuStart = System.nanoTime()
            timer?.begin()
            try {
                val image = if (request.display.showBefore || draw == null) source.input else {
                    val result = output ?: GpuTexture.create(context, ledger, source.input.width, source.input.height, GpuTexture.Format.RGBA32F).also { output = it }
                    draw.invoke(source, request.params, result)
                    result
                }
                present(context, image, current.width, current.height, request.display.rotationDegrees)
            } catch (failure: Throwable) { timer?.cancelActive(); throw failure }
            timer?.end()
            val cpuNanos = System.nanoTime() - cpuStart
            context.swapBuffers(checkNotNull(window))
            latest.rendered(request); drawCount++
            // Count vsync slots missed while a changed request awaited this callback, not coalesced UI updates.
            missedChangedFrames += ((frameTime - pendingSinceNanos).coerceAtLeast(0L) / frameIntervalNanos)
            val frame = GpuPreviewFrame(GpuFrameStats(null, cpuNanos, GpuDecisions(0.0, 1.0)), frameTime, missedChangedFrames, ledger.allocatedBytes)
            // Complete the query around GPU work only (before swap); callbacks remain FIFO.
            timer?.complete(frame)?.forEach(onFrame) ?: onFrame(frame)
        }
        schedule() // A concurrent submit replaces the mailbox rather than appending another frame.
    }

    private fun releaseWindow() {
        val old = window; window = null; windowLease = null
        old?.close()
    }
    private fun releasePhoto() {
        val oldOutput = output; val oldPhoto = photo
        output = null; photo = null
        try { oldOutput?.close() } finally { oldPhoto?.input?.close() }
    }

    override fun close() {
        synchronized(gate) {
            synchronized(enqueueLock) { if (!closed.compareAndSet(false, true)) return }
            lease?.valid = false; lease = null; started = false
            // Cleanup is accepted before quitSafely; never join a rendering thread from the UI.
            thread.post {
                try {
                    cancelFrame()
                    try { releaseWindow() } finally {
                        try { retainedInput = null; releasePhoto() } finally {
                            try { timer?.close() } finally { egl?.close() }
                        }
                    }
                } catch (failure: Throwable) { onError(failure) }
                finally { thread.close() }
            }
        }
    }
}

/** Borrowed texture sampling state is restored even when drawing fails. */
internal fun present(egl: EglContexts, image: GpuTexture, width: Int, height: Int, rotation: Int, target: GpuTexture? = null) {
    egl.requireCurrent()
    // GL_LINEAR on RGBA32F needs OES_texture_float_linear; core ES uses the same four-tap
    // interpolation in the display shader instead of raising the engine's minimum capabilities.
    val linearFiltering = "GL_OES_texture_float_linear" in GLES32.glGetString(GLES32.GL_EXTENSIONS).orEmpty().split(' ')
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
