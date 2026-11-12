package dev.cinematic.p0.gpu

import android.graphics.PixelFormat
import android.media.ImageReader
import android.opengl.GLES32
import android.util.Log
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.gpu.preview.GpuPreviewFrame
import dev.cinematic.p0.gpu.preview.GpuPreviewRenderer
import dev.cinematic.p0.gpu.preview.GpuPreviewSnapshot
import dev.cinematic.p0.gpu.preview.present
import dev.cinematic.p0.raw.LinearSrgbImage
import dev.cinematic.p0.raw.PhotoAdjustments
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/** Synthetic emulator correctness checks, never device performance/quality acceptance. */
@RunWith(AndroidJUnit4::class)
class GpuPreviewAndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Before fun syntheticEmulatorOnly() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish")) { "Synthetic emulator only" }
    }

    private fun gpu(block: (EglContexts) -> Unit): Unit = GlThread().use { thread ->
        thread.call {
            EglContexts(thread, instrumentation.targetContext.assets).use { egl ->
                GlCapabilities.inspect(egl).requireSupported()
                block(egl)
            }
        }
    }

    @Test fun presentationPreservesTopLeftRowsAndAllClockwiseQuarterTurns() = gpu { egl ->
        // Row-major source: top red/green, bottom blue/white. GL readback begins at bottom.
        val colors = arrayOf(floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 1f, 0f),
            floatArrayOf(0f, 0f, 1f), floatArrayOf(1f, 1f, 1f))
        val topLeftOrder = mapOf(0 to intArrayOf(0, 1, 2, 3), 90 to intArrayOf(2, 0, 3, 1),
            180 to intArrayOf(3, 2, 1, 0), 270 to intArrayOf(1, 3, 0, 2))
        val ledger = GpuMemoryLedger()
        GpuTexture.create(egl, ledger, 2, 2, GpuTexture.Format.RGBA32F).use { input ->
            input.upload(floatPixels(colors.flatMap { it.toList() + 1f }.toFloatArray()))
            GpuTexture.create(egl, ledger, 2, 2, GpuTexture.Format.RGBA8).use { output ->
                for ((rotation, order) in topLeftOrder) {
                    present(egl, input, 2, 2, rotation, output)
                    val pixels = readRgba(egl, output)
                    repeat(2) { y -> repeat(2) { x ->
                        val expected = colors[order[y * 2 + x]].map { (it * 255).toInt() } + 255
                        assertPixel("rotation=$rotation at $x,$y", pixels, 2, 2, x, y, expected)
                    } }
                }
                // Presentation must not leave borrowed texture sampling state changed.
                GLES32.glBindTexture(GLES32.GL_TEXTURE_2D, input.textureId)
                for (parameter in intArrayOf(GLES32.GL_TEXTURE_MIN_FILTER, GLES32.GL_TEXTURE_MAG_FILTER)) {
                    val actual = IntArray(1)
                    GLES32.glGetTexParameteriv(GLES32.GL_TEXTURE_2D, parameter, actual, 0)
                    assertEquals(GLES32.GL_NEAREST, actual[0])
                }
            }
        }
        assertEquals(0L, ledger.allocatedBytes)
    }

    @Test fun presentationAspectFitsWithOpaqueBlackBarsAndReportsCpuEncoding() = gpu { egl ->
        val linear = floatArrayOf(0.003f, 0.18f, 0.5f, 1f)
        val expected = linear.take(3).map { value ->
            ((if (value <= 0.0031308f) 12.92 * value else 1.055 * value.toDouble().pow(1.0 / 2.4) - 0.055) * 255).roundToInt()
        } + 255
        val ledger = GpuMemoryLedger()
        GpuTexture.create(egl, ledger, 4, 2, GpuTexture.Format.RGBA32F).use { input ->
            input.upload(floatPixels(FloatArray(4 * 2 * 4) { linear[it % 4] }))
            GpuTexture.create(egl, ledger, 8, 8, GpuTexture.Format.RGBA8).use { output ->
                var cpuMaximum = 0
                for (rotation in listOf(0, 90, 180, 270)) {
                    present(egl, input, 8, 8, rotation, output)
                    val pixels = readRgba(egl, output)
                    repeat(8) { y -> repeat(8) { x ->
                        val bar = if (rotation == 0 || rotation == 180) y < 2 || y >= 6 else x < 2 || x >= 6
                        if (bar) {
                            assertPixel("rotation=$rotation black bar at $x,$y", pixels, 8, 8, x, y, listOf(0, 0, 0, 255))
                        } else {
                            val offset = ((7 - y) * 8 + x) * 4
                            assertEquals("opaque image at $x,$y", 255, pixels.get(offset + 3).toInt() and 255)
                            // A non-black image region must not be confused with its letterbox.
                            assertTrue("image region at $x,$y", (0..2).any { (pixels.get(offset + it).toInt() and 255) != 0 })
                            repeat(3) { channel ->
                                cpuMaximum = maxOf(cpuMaximum, abs((pixels.get(offset + channel).toInt() and 255) - expected[channel]))
                            }
                        }
                    } }
                }
                Log.i("GpuPreview", "CPU_REFERENCE_ONLY midtone encoding max8=$cpuMaximum")
            }
        }
        assertEquals(0L, ledger.allocatedBytes)
    }

    @Test fun tenSurfaceRecreationsAndStopStartReleaseWindowsAndRedrawSameInput() {
        val failure = AtomicReference<Throwable?>()
        val frames = ArrayBlockingQueue<GpuPreviewFrame>(32)
        val renderer = GpuPreviewRenderer(instrumentation.targetContext.assets,
            onFrame = { check(frames.offer(it)) { "Unexpected repeated unchanged frames" } },
            onError = { failure.compareAndSet(null, it) })
        val consumer = HandlerThread("PreviewTestConsumer").apply { start() }
        val handler = Handler(consumer.looper)
        try {
            renderer.setStarted(true)
            val input = LinearSrgbImage(4, 2, FloatArray(4 * 2 * 3) { 0.18f })
            val params = GpuRenderParams(0.75, PhotoAdjustments.NONE)
            val display = GpuDisplayOptions(false, 0)
            renderer.submit(params, display)
            repeat(10) { cycle ->
                ImageReader.newInstance(16, 16, PixelFormat.RGBA_8888, 2).use { reader ->
                    reader.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, handler)
                    val surface = reader.surface
                    try {
                        renderer.openPhoto(input)
                        renderer.attachSurface(surface, 16, 16)
                        awaitFrame(frames, failure)
                        val active = snapshot(renderer, failure)
                        assertEquals("cycle $cycle", 1, active.windowCount)
                        assertEquals(4L * 2 * 16, active.allocatedBytes)
                        renderer.submit(params, display)
                        assertEquals(active.drawCount, snapshot(renderer, failure).drawCount)

                        renderer.setStarted(false)
                        val stopped = snapshot(renderer, failure)
                        assertEquals(0, stopped.windowCount)
                        renderer.submit(params, display)
                        assertEquals(stopped.drawCount, snapshot(renderer, failure).drawCount)
                        renderer.setStarted(true)
                        awaitFrame(frames, failure)
                        assertEquals(stopped.drawCount + 1, snapshot(renderer, failure).drawCount)
                    } finally {
                        renderer.detachSurface(surface)
                        assertEquals("EGL window must already be destroyed when detach returns", 0, renderer.activeWindowCount)
                        val detached = snapshot(renderer, failure)
                        assertEquals("cycle $cycle detached", 0, detached.windowCount)
                        renderer.submit(params, display)
                        val afterSubmit = snapshot(renderer, failure)
                        assertEquals(detached.drawCount, afterSubmit.drawCount)
                        assertEquals(0, afterSubmit.windowCount)
                        renderer.closePhoto()
                        assertEquals("cycle $cycle photo closed", 0L, snapshot(renderer, failure).allocatedBytes)
                    }
                }
            }
            assertNull(failure.get())
        } finally {
            renderer.close()
            consumer.quitSafely()
            consumer.join(5_000)
            assertFalse("Image consumer did not stop", consumer.isAlive)
        }
    }

    @Test fun nextSubmitRecoversInjectedContextLossAndBeforeBypassesLookDraw() {
        val failure = AtomicReference<Throwable?>()
        val frames = ArrayBlockingQueue<GpuPreviewFrame>(8)
        val drawCalls = AtomicInteger()
        val errorCalls = AtomicInteger()
        val lost = CountDownLatch(1)
        val firstContext = AtomicReference<EglContexts>()
        val injected = GpuContextLostException("synthetic preview context loss")
        val renderer = GpuPreviewRenderer(instrumentation.targetContext.assets,
            onFrame = { check(frames.offer(it)) },
            onError = {
                if (errorCalls.incrementAndGet() == 1 && it === injected) lost.countDown()
                else failure.compareAndSet(null, it)
            },
            draw = { photo, _, output ->
                val context = photo.input.context
                context.glThread.requireCurrent()
                context.requireCurrent()
                if (drawCalls.incrementAndGet() == 1) {
                    firstContext.set(context)
                    throw injected
                }
                assertNotSame("Recovery must replace the EGL context", firstContext.get(), context)
                assertSame(context, output.context)
                GLES32.glBindFramebuffer(GLES32.GL_FRAMEBUFFER, output.framebufferId)
                GLES32.glDisable(GLES32.GL_SCISSOR_TEST)
                GLES32.glClearColor(0.1f, 0.2f, 0.3f, 1f)
                GLES32.glClear(GLES32.GL_COLOR_BUFFER_BIT)
                context.checkGl("synthetic recovered look draw")
            })
        val consumer = HandlerThread("PreviewRecoveryConsumer").apply { start() }
        try {
            ImageReader.newInstance(16, 16, PixelFormat.RGBA_8888, 2).use { reader ->
                reader.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, Handler(consumer.looper))
                val surface = reader.surface
                try {
                    val params = GpuRenderParams(0.75, PhotoAdjustments.NONE)
                    val display = GpuDisplayOptions(false, 0)
                    renderer.submit(params, display)
                    renderer.setStarted(true)
                    renderer.openPhoto(LinearSrgbImage(4, 2, FloatArray(4 * 2 * 3) { 0.18f }))
                    renderer.attachSurface(surface, 16, 16)
                    assertTrue("Injected context loss was not reported", lost.await(5, TimeUnit.SECONDS))
                    val failed = snapshot(renderer, failure)
                    assertEquals(0, failed.windowCount)
                    assertEquals(0L, failed.drawCount)
                    assertEquals(1, drawCalls.get())

                    // Identical request is enough to rebuild context and re-upload retained input.
                    renderer.submit(params, display)
                    awaitFrame(frames, failure)
                    val recovered = snapshot(renderer, failure)
                    assertEquals(1, recovered.windowCount)
                    assertEquals(1L, recovered.drawCount)
                    assertEquals(4L * 2 * 16 * 2, recovered.allocatedBytes)
                    assertEquals(2, drawCalls.get())

                    renderer.submit(params, GpuDisplayOptions(true, 0))
                    awaitFrame(frames, failure)
                    assertEquals(2L, snapshot(renderer, failure).drawCount)
                    assertEquals("Before must bypass the injected look", 2, drawCalls.get())
                    assertEquals("No additional recovery errors", 1, errorCalls.get())
                } finally {
                    renderer.detachSurface(surface)
                    assertEquals("EGL window must already be destroyed when detach returns", 0, renderer.activeWindowCount)
                    assertEquals(0, snapshot(renderer, failure).windowCount)
                    renderer.closePhoto()
                    assertEquals(0L, snapshot(renderer, failure).allocatedBytes)
                }
            }
        } finally {
            renderer.close()
            consumer.quitSafely()
            consumer.join(5_000)
            assertFalse("Image consumer did not stop", consumer.isAlive)
        }
    }

    @Test fun slowDrawCountsVsyncsMissedByCoalescedUpdatesAndRendersFinalValue() {
        val failure = AtomicReference<Throwable?>()
        val frames = ArrayBlockingQueue<GpuPreviewFrame>(8)
        val drawnStrengths = LinkedBlockingQueue<Double>()
        val drawing = CountDownLatch(1)
        val releaseDraw = CountDownLatch(1)
        val drawCalls = AtomicInteger()
        val renderer = GpuPreviewRenderer(instrumentation.targetContext.assets,
            onFrame = { check(frames.offer(it)) },
            onError = { failure.compareAndSet(null, it) },
            draw = { photo, params, output ->
                val context = photo.input.context
                context.glThread.requireCurrent()
                drawnStrengths.add(params.strength)
                if (drawCalls.incrementAndGet() == 1) {
                    drawing.countDown()
                    check(releaseDraw.await(5, TimeUnit.SECONDS)) { "Test did not release slow draw" }
                }
                GLES32.glBindFramebuffer(GLES32.GL_FRAMEBUFFER, output.framebufferId)
                GLES32.glDisable(GLES32.GL_SCISSOR_TEST)
                GLES32.glClearColor(0.1f, 0.2f, 0.3f, 1f)
                GLES32.glClear(GLES32.GL_COLOR_BUFFER_BIT)
                context.checkGl("synthetic slow preview draw")
            })
        val consumer = HandlerThread("PreviewSlowDrawConsumer").apply { start() }
        try {
            ImageReader.newInstance(16, 16, PixelFormat.RGBA_8888, 2).use { reader ->
                reader.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, Handler(consumer.looper))
                val surface = reader.surface
                try {
                    val display = GpuDisplayOptions(false, 0)
                    renderer.submit(GpuRenderParams(0.0, PhotoAdjustments.NONE), display)
                    renderer.setStarted(true)
                    renderer.openPhoto(LinearSrgbImage(4, 2, FloatArray(4 * 2 * 3) { 0.18f }))
                    renderer.attachSurface(surface, 16, 16, 60f)
                    assertTrue("First draw did not begin", drawing.await(5, TimeUnit.SECONDS))
                    // Every update is submitted while GL is inside the same blocked draw.
                    instrumentation.runOnMainSync {
                        repeat(100) { update ->
                            renderer.submit(GpuRenderParams((update + 1) / 100.0, PhotoAdjustments.NONE), display)
                        }
                    }
                    SystemClock.sleep(80) // More than four nominal vsync periods after first dirty submit.
                    releaseDraw.countDown()
                    val first = frames.poll(5, TimeUnit.SECONDS)
                    val finalFrame = frames.poll(5, TimeUnit.SECONDS)
                    failure.get()?.let { throw AssertionError("Renderer failure", it) }
                    assertNotNull("First frame statistics missing", first)
                    assertNotNull("Final frame statistics missing", finalFrame)
                    assertTrue("Coalesced updates must accrue missed changed frames",
                        checkNotNull(finalFrame).missedChangedFrames > checkNotNull(first).missedChangedFrames)
                    assertEquals(listOf(0.0, 1.0), drawnStrengths.toList())
                    assertEquals(2L, snapshot(renderer, failure).drawCount)
                } finally {
                    releaseDraw.countDown()
                    renderer.detachSurface(surface)
                    assertEquals("EGL window must already be destroyed when detach returns", 0, renderer.activeWindowCount)
                    assertEquals(0, snapshot(renderer, failure).windowCount)
                    renderer.closePhoto()
                    assertEquals(0L, snapshot(renderer, failure).allocatedBytes)
                }
            }
        } finally {
            releaseDraw.countDown()
            renderer.close()
            consumer.quitSafely()
            consumer.join(5_000)
            assertFalse("Image consumer did not stop", consumer.isAlive)
        }
    }

    @Test fun delayedDrawDetachReportsFailureAndBlocksRetryUntilBarrierCompletes() {
        val failure = AtomicReference<Throwable?>()
        val frames = ArrayBlockingQueue<GpuPreviewFrame>(8)
        val presented = LinkedBlockingQueue<Pair<GpuRenderParams, GpuDisplayOptions>>()
        val drawing = CountDownLatch(1)
        val releaseDraw = CountDownLatch(1)
        val renderer = GpuPreviewRenderer(instrumentation.targetContext.assets,
            onFrame = { frames.offer(it) }, onError = { failure.set(it) },
            draw = { photo, _, output ->
                drawing.countDown()
                check(releaseDraw.await(15, TimeUnit.SECONDS)) { "Test did not release stalled draw" }
                GLES32.glBindFramebuffer(GLES32.GL_FRAMEBUFFER, output.framebufferId)
                GLES32.glClearColor(0.1f, 0.2f, 0.3f, 1f)
                GLES32.glClear(GLES32.GL_COLOR_BUFFER_BIT)
                photo.input.context.checkGl("synthetic delayed detach")
            }, onPresented = { params, display -> presented.add(params to display) })
        val consumer = HandlerThread("PreviewDetachTimeoutConsumer").apply { start() }
        try {
            ImageReader.newInstance(16, 16, PixelFormat.RGBA_8888, 2).use { reader ->
                reader.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, Handler(consumer.looper))
                try {
                    val params = GpuRenderParams(0.75, PhotoAdjustments.NONE)
                    val display = GpuDisplayOptions(false, 90)
                    renderer.submit(params, display)
                    renderer.setStarted(true)
                    renderer.openPhoto(LinearSrgbImage(4, 2, FloatArray(24) { 0.18f }))
                    renderer.attachSurface(reader.surface, 16, 16)
                    assertTrue(drawing.await(5, TimeUnit.SECONDS))
                    instrumentation.runOnMainSync {
                        assertFalse(renderer.detachSurface(reader.surface))
                    }
                    assertTrue(failure.get() is GpuSurfaceTeardownException)
                    assertTrue(renderer.teardownPending)
                    assertEquals(1, renderer.activeWindowCount)
                    assertFalse(renderer.retry())
                    renderer.attachSurface(reader.surface, 16, 16)
                    releaseDraw.countDown()
                    val failed = snapshot(renderer, AtomicReference(null))
                    assertFalse(renderer.teardownPending)
                    assertEquals(0L, failed.drawCount)
                    assertEquals(0, failed.windowCount)
                    assertTrue("Invalidated frame must not become a photo result", presented.isEmpty())
                    assertTrue("Timeout must not automatically recover", frames.isEmpty())
                    failure.set(null)
                    assertTrue(renderer.retry())
                    assertEquals(params to display, presented.poll(5, TimeUnit.SECONDS))
                    awaitFrame(frames, failure)
                    assertTrue(renderer.detachSurface(reader.surface))
                } finally {
                    releaseDraw.countDown()
                    renderer.close()
                    assertTrue(renderer.awaitClosed(5_000))
                }
            }
        } finally {
            releaseDraw.countDown()
            renderer.close()
            consumer.quitSafely(); consumer.join(5_000)
            assertFalse(consumer.isAlive)
        }
    }

    @Test fun glTerminationWaitDoesNotConfuseQuitRequestWithThreadExit() {
        val thread = GlThread()
        val quitRequested = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        try {
            thread.post {
                thread.close()
                quitRequested.countDown()
                check(releaseCallback.await(5, TimeUnit.SECONDS))
            }
            assertTrue(quitRequested.await(5, TimeUnit.SECONDS))
            assertFalse(thread.awaitTermination(0))
            assertFalse(thread.awaitTermination(20))
            releaseCallback.countDown()
            assertTrue(thread.awaitTermination(5_000))
            assertTrue(thread.awaitTermination(0))
        } finally {
            releaseCallback.countDown()
            assertTrue(thread.awaitTermination(5_000))
        }
    }

    @Test fun closeWhileDrawIsDelayedCannotClaimCleanupOrPublishFrame() {
        val drawing = CountDownLatch(1)
        val releaseDraw = CountDownLatch(1)
        val presented = AtomicInteger()
        val renderer = GpuPreviewRenderer(instrumentation.targetContext.assets,
            draw = { _, _, _ ->
                drawing.countDown()
                check(releaseDraw.await(10, TimeUnit.SECONDS))
            }, onPresented = { _, _ -> presented.incrementAndGet() })
        ImageReader.newInstance(16, 16, PixelFormat.RGBA_8888, 2).use { reader ->
            try {
                renderer.setStarted(true)
                renderer.openPhoto(LinearSrgbImage(4, 2, FloatArray(24) { 0.18f }))
                renderer.attachSurface(reader.surface, 16, 16)
                assertTrue(drawing.await(5, TimeUnit.SECONDS))
                renderer.close()
                assertFalse(renderer.awaitClosed(20))
                assertTrue(renderer.teardownPending)
                assertFalse(renderer.retry())
                releaseDraw.countDown()
                assertTrue(renderer.awaitClosed(5_000))
                assertFalse(renderer.teardownPending)
                assertEquals(0, presented.get())
                assertEquals(0, renderer.activeWindowCount)
            } finally {
                releaseDraw.countDown()
                renderer.close()
                assertTrue(renderer.awaitClosed(5_000))
            }
        }
    }

    @Test fun stopTimeWindowDestructionFailureRemainsVisibleAtLaterDetachAndShutdown() {
        val failure = AtomicReference<Throwable?>()
        val frames = ArrayBlockingQueue<GpuPreviewFrame>(8)
        val destructionFailed = CountDownLatch(1)
        val injected = IllegalStateException("synthetic EGL window destruction failure")
        val renderer = GpuPreviewRenderer(instrumentation.targetContext.assets,
            onFrame = { check(frames.offer(it)) },
            onError = { failure.compareAndSet(null, it); destructionFailed.countDown() },
            destroyWindow = { throw injected })
        val consumer = HandlerThread("PreviewDestroyFailureConsumer").apply { start() }
        try {
            ImageReader.newInstance(16, 16, PixelFormat.RGBA_8888, 2).use { reader ->
                reader.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, Handler(consumer.looper))
                val surface = reader.surface
                try {
                    renderer.setStarted(true)
                    renderer.openPhoto(LinearSrgbImage(4, 2, FloatArray(4 * 2 * 3) { 0.18f }))
                    renderer.attachSurface(surface, 16, 16)
                    awaitFrame(frames, failure)
                    assertEquals(1, renderer.activeWindowCount)

                    // Failure originates in the earlier lifecycle stop, not detach's own barrier.
                    renderer.setStarted(false)
                    assertTrue("Stop did not report destruction failure", destructionFailed.await(5, TimeUnit.SECONDS))
                    assertTrue(failure.get() is GpuSurfaceTeardownException)
                    assertSame(injected, failure.get()?.cause)
                    assertEquals("Failed destruction must not claim the window was released", 1, renderer.activeWindowCount)
                    assertFalse("Detach reports failure without crashing the lifecycle callback", renderer.detachSurface(surface))
                    assertFalse("Failed destruction cannot be retried as if ownership ended", renderer.retry())

                    renderer.close()
                    // This also waits for context shutdown, which closes the real target directly.
                    assertFalse("Shutdown must not erase earlier destruction failure", renderer.detachSurface(surface))
                    assertFalse(renderer.awaitClosed(5_000))
                } finally {
                    renderer.close()
                    // The injected failure remains expected; still wait for shutdown before reader.close.
                    runCatching { renderer.detachSurface(surface) }
                }
            }
        } finally {
            renderer.close()
            consumer.quitSafely()
            consumer.join(5_000)
            assertFalse("Image consumer did not stop", consumer.isAlive)
        }
    }

    private fun awaitFrame(frames: ArrayBlockingQueue<GpuPreviewFrame>, failure: AtomicReference<Throwable?>) {
        val frame = frames.poll(5, TimeUnit.SECONDS)
        failure.get()?.let { throw AssertionError("Renderer failure", it) }
        assertNotNull("Timed out waiting for changed preview frame", frame)
    }

    private fun snapshot(renderer: GpuPreviewRenderer, failure: AtomicReference<Throwable?>): GpuPreviewSnapshot {
        val ready = CountDownLatch(1)
        val value = AtomicReference<GpuPreviewSnapshot>()
        renderer.snapshot { value.set(it); ready.countDown() }
        assertTrue("Timed out waiting for renderer snapshot", ready.await(5, TimeUnit.SECONDS))
        failure.get()?.let { throw AssertionError("Renderer failure", it) }
        return checkNotNull(value.get())
    }

    private fun floatPixels(data: FloatArray) = ByteBuffer.allocateDirect(data.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(data); rewind() }

    private fun readRgba(egl: EglContexts, texture: GpuTexture): ByteBuffer {
        val bytes = ByteBuffer.allocateDirect(texture.width * texture.height * 4)
        GLES32.glBindFramebuffer(GLES32.GL_FRAMEBUFFER, texture.framebufferId)
        GLES32.glReadPixels(0, 0, texture.width, texture.height, GLES32.GL_RGBA, GLES32.GL_UNSIGNED_BYTE, bytes)
        egl.checkGl("preview synthetic readback")
        return bytes
    }

    private fun assertPixel(label: String, bytes: ByteBuffer, width: Int, height: Int, x: Int, y: Int, expected: List<Int>) {
        val offset = ((height - 1 - y) * width + x) * 4
        repeat(4) { channel ->
            val actual = bytes.get(offset + channel).toInt() and 255
            assertTrue("$label channel=$channel expected=${expected[channel]} actual=$actual", abs(actual - expected[channel]) <= 1)
        }
    }
}
