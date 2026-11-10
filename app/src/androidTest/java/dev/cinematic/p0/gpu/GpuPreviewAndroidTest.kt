package dev.cinematic.p0.gpu

import android.graphics.PixelFormat
import android.media.ImageReader
import android.opengl.GLES32
import android.os.Handler
import android.os.HandlerThread
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
import java.util.concurrent.TimeUnit
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

    @Test fun presentationAspectFitsWithOpaqueBlackBarsAndEncodesLinearSrgb() = gpu { egl ->
        val linear = floatArrayOf(0.003f, 0.18f, 0.5f, 1f)
        val expected = linear.take(3).map { value ->
            ((if (value <= 0.0031308f) 12.92 * value else 1.055 * value.toDouble().pow(1.0 / 2.4) - 0.055) * 255).roundToInt()
        } + 255
        val ledger = GpuMemoryLedger()
        GpuTexture.create(egl, ledger, 4, 2, GpuTexture.Format.RGBA32F).use { input ->
            input.upload(floatPixels(FloatArray(4 * 2 * 4) { linear[it % 4] }))
            GpuTexture.create(egl, ledger, 8, 8, GpuTexture.Format.RGBA8).use { output ->
                for (rotation in listOf(0, 90, 180, 270)) {
                    present(egl, input, 8, 8, rotation, output)
                    val pixels = readRgba(egl, output)
                    repeat(8) { y -> repeat(8) { x ->
                        val bar = if (rotation == 0 || rotation == 180) y < 2 || y >= 6 else x < 2 || x >= 6
                        assertPixel("rotation=$rotation at $x,$y", pixels, 8, 8, x, y,
                            if (bar) listOf(0, 0, 0, 255) else expected)
                    } }
                }
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
