package dev.cinematic.p0.gpu

import android.content.ComponentCallbacks2
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.gpu.diag.GpuStageTap
import dev.cinematic.p0.gpu.preview.GpuPreviewFrame
import dev.cinematic.p0.gpu.preview.GpuPreviewRenderer
import dev.cinematic.p0.gpu.preview.GpuPreviewSnapshot
import dev.cinematic.p0.photoroll.TrialDisplayPixels
import dev.cinematic.p0.raw.LinearSrgbImage
import dev.cinematic.p0.raw.PhotoAdjustments
import dev.cinematic.p0.raw.R7PreviewInputs
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Synthetic ownership/wiring checks. These do not claim R7/look parity or device performance. */
@RunWith(AndroidJUnit4::class)
class StageClearGpuAndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val params = GpuRenderParams(.5, PhotoAdjustments.NONE)

    @Before fun isolatedEmulatorOnly() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish")) { "Synthetic emulator only" }
    }

    private fun gpu(block: (EglContexts, GpuMemoryLedger) -> Unit) = GlThread().use { thread ->
        thread.call {
            EglContexts(thread, instrumentation.targetContext.assets).use { egl ->
                GlCapabilities.inspect(egl).requireSupported()
                val ledger = GpuMemoryLedger()
                block(egl, ledger)
                assertEquals("All photo owners must close", 0L, ledger.allocatedBytes)
            }
        }
    }

    @Test fun inputUploadAndAutomaticExposurePreserveCpuContract() = gpu { egl, ledger ->
        val input = input()
        val expected = R7PreviewInputs.prepare(R7PreviewInputs.AsShotInput(input,
            R7PreviewInputs.Source.LIBRAW_PRELIMINARY_AS_SHOT), emptyList()).exposure
        DefaultStageClearGpu(egl, ledger).use { engine ->
            engine.openPhoto(input) { false }.use { photo ->
                assertEquals(input.width, photo.width)
                assertEquals(input.height, photo.height)
                assertEquals(expected.externalBaseEv.toRawBits(), photo.analysis.meteredBaseEv.toRawBits())
                assertEquals(expected.centralP97.toRawBits(), photo.analysis.centralP97.toRawBits())
                val result = engine.renderForTest(photo, params, GpuStageTap.Point.INPUT)
                repeat(input.pixelCount * 3) { assertEquals("channel $it", input[it].toRawBits(), result[it].toRawBits()) }
                assertEquals(input.pixelCount * 16L, ledger.allocatedBytes)
            }
        }
    }

    @Test fun cancelledPreparationAndUnsupportedInputLeaveNoTextureOwners() = gpu { egl, ledger ->
        DefaultStageClearGpu(egl, ledger).use { engine ->
            assertThrows(CancellationException::class.java) { engine.openPhoto(input()) { true } }
            // Cancel after texture allocation, exercising upload-buffer preparation cleanup.
            assertThrows(CancellationException::class.java) { engine.openPhoto(input()) { ledger.allocatedBytes > 0 } }
            assertEquals(0L, ledger.allocatedBytes)
            assertThrows(IllegalArgumentException::class.java) {
                engine.openPhoto(LinearSrgbImage(1025, 4, FloatArray(1025 * 4 * 3))) { false }
            }
        }
    }

    @Test fun missingOrFailingPassesAreErrorsAndCloseReleasesTheirResources() = gpu { egl, ledger ->
        DefaultStageClearGpu(egl, ledger) { _, _ -> error("synthetic missing pipeline") }.use { missing ->
            missing.openPhoto(input()) { false }.use { photo ->
                assertThrows(IllegalStateException::class.java) {
                    missing.renderForTest(photo, params, GpuStageTap.Point.R7_FINAL)
                }
                assertEquals(photo.width * photo.height * 16L, ledger.allocatedBytes)
            }
        }
        var closes = 0
        DefaultStageClearGpu(egl, ledger) { texture, _ ->
            val held = GpuTexture.create(egl, ledger, texture.width, texture.height, GpuTexture.Format.RGBA32F)
            object : StageClearGpuPipeline {
                override fun render(params: GpuRenderParams, tap: ((GpuStageTap.Point, GpuTexture) -> Unit)?,
                    consume: (GpuTexture, GpuDecisions) -> Unit) { error("synthetic pass failure") }
                override fun close() { held.close(); closes++ }
            }
        }.use { engine ->
            val photo = engine.openPhoto(input()) { false }
            assertThrows(IllegalStateException::class.java) { engine.renderForTest(photo, params, GpuStageTap.Point.R7_FINAL) }
            assertEquals(photo.width * photo.height * 32L, ledger.allocatedBytes)
            photo.close(); photo.close()
            assertEquals(1, closes)
            assertThrows(IllegalStateException::class.java) { engine.renderForTest(photo, params, GpuStageTap.Point.INPUT) }
        }
    }

    @Test fun stageTapConsumesTemporaryPlaneAndDisplayTexturesSynchronously() = gpu { egl, ledger ->
        DefaultStageClearGpu(egl, ledger) { texture, _ ->
            object : StageClearGpuPipeline {
                override fun render(params: GpuRenderParams, tap: ((GpuStageTap.Point, GpuTexture) -> Unit)?,
                    consume: (GpuTexture, GpuDecisions) -> Unit) {
                    GpuTexture.create(egl, ledger, texture.width, texture.height, GpuTexture.Format.R32F).use { plane ->
                        plane.upload(ByteBuffer.allocateDirect(texture.width * texture.height * 4)
                            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                                repeat(texture.width * texture.height) { put(it / 255f) }; flip()
                            })
                        tap?.invoke(GpuStageTap.Point.SKIN_WEIGHT, plane)
                    }
                    GpuTexture.create(egl, ledger, texture.width, texture.height, GpuTexture.Format.RGBA8).use { display ->
                        display.upload(ByteBuffer.allocateDirect(texture.width * texture.height * 4).apply {
                            repeat(texture.width * texture.height) { p ->
                                put(p.toByte()); put((255 - p).toByte()); put(128.toByte()); put(255.toByte())
                            }; flip()
                        })
                        tap?.invoke(GpuStageTap.Point.DISPLAY8, display)
                    }
                    consume(texture, GpuDecisions(.25, 1.0))
                }
                override fun close() = Unit
            }
        }.use { engine ->
            engine.openPhoto(LinearSrgbImage(16, 16, FloatArray(16 * 16 * 3))) { false }.use { photo ->
                val plane = engine.renderForTest(photo, params, GpuStageTap.Point.SKIN_WEIGHT)
                repeat(256 * 3) { assertEquals(it / 3 / 255f, plane[it], 0f) }
                val display = engine.renderForTest(photo, params, GpuStageTap.Point.DISPLAY8)
                val pixels = TrialDisplayPixels.encode(display, 16) { false }
                repeat(256) { p -> assertEquals((255 shl 24) or (p shl 16) or ((255 - p) shl 8) or 128, pixels[p]) }
                assertEquals(16 * 16 * 16L, ledger.allocatedBytes)
            }
        }
    }

    @Test fun failedPipelineCloseRetainsOwnersAndEngineCloseRetriesAllPhotos() = gpu { egl, ledger ->
        var closes = 0
        val engine = DefaultStageClearGpu(egl, ledger) { texture, _ ->
            val held = GpuTexture.create(egl, ledger, texture.width, texture.height, GpuTexture.Format.RGBA32F)
            object : StageClearGpuPipeline {
                override fun render(params: GpuRenderParams, tap: ((GpuStageTap.Point, GpuTexture) -> Unit)?,
                    consume: (GpuTexture, GpuDecisions) -> Unit) {
                    tap?.invoke(GpuStageTap.Point.R7_FINAL, texture)
                    consume(texture, GpuDecisions(0.0, 1.0))
                }
                override fun close() {
                    if (++closes == 1) error("synthetic close failure")
                    held.close()
                }
            }
        }
        try {
            val first = engine.openPhoto(input()) { false }
            val second = engine.openPhoto(input()) { false }
            engine.renderForTest(first, params, GpuStageTap.Point.R7_FINAL)
            assertEquals(3 * 4 * 4 * 16L, ledger.allocatedBytes)
            assertThrows(IllegalStateException::class.java) { first.close() }
            assertEquals("Failed pipeline still owns its input", 3 * 4 * 4 * 16L, ledger.allocatedBytes)
            engine.close()
            assertEquals(0L, ledger.allocatedBytes)
            first.close(); second.close(); engine.close()
            assertEquals(2, closes)
        } finally { engine.close() }
    }

    @Test fun budgetFailureAndForeignPhotoKeepExistingPhotoValid() = gpu { egl, _ ->
        val ledger = GpuMemoryLedger(4 * 4 * 16L)
        DefaultStageClearGpu(egl, ledger).use { engine ->
            val first = engine.openPhoto(input()) { false }
            assertThrows(GpuMemoryBudgetException::class.java) { engine.openPhoto(input()) { false } }
            assertEquals(4 * 4 * 16L, ledger.allocatedBytes)
            DefaultStageClearGpu(egl, GpuMemoryLedger()).use { foreign ->
                assertThrows(IllegalStateException::class.java) { foreign.renderForTest(first, params, GpuStageTap.Point.INPUT) }
            }
            assertEquals(4, engine.renderForTest(first, params, GpuStageTap.Point.INPUT).width)
            first.close()
        }
        assertEquals(0L, ledger.allocatedBytes)
    }

    @Test fun rendererRecreatesEngineAndPhotoAfterContextLossOnNextSubmit() {
        val errors = AtomicReference<Throwable?>()
        val lost = CountDownLatch(1)
        val frames = LinkedBlockingQueue<GpuPreviewFrame>()
        val creations = AtomicInteger()
        val closes = AtomicInteger()
        val injected = GpuContextLostException("synthetic engine context loss")
        val renderer = GpuPreviewRenderer(instrumentation.targetContext.assets,
            onFrame = { frames.offer(it) }, onError = { if (it === injected) lost.countDown() else errors.compareAndSet(null, it) },
            engineFactory = { egl, ledger ->
                val generation = creations.incrementAndGet()
                DefaultStageClearGpu(egl, ledger) { texture, _ ->
                    object : StageClearGpuPipeline {
                        override fun render(params: GpuRenderParams, tap: ((GpuStageTap.Point, GpuTexture) -> Unit)?,
                            consume: (GpuTexture, GpuDecisions) -> Unit) {
                            if (generation == 1) throw injected
                            consume(texture, GpuDecisions(.5, 1.0))
                        }
                        override fun close() { closes.incrementAndGet() }
                    }
                }
            })
        val consumer = HandlerThread("StageClearGpuRecoveryConsumer").apply { start() }
        try {
            ImageReader.newInstance(16, 16, PixelFormat.RGBA_8888, 2).use { reader ->
                reader.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, Handler(consumer.looper))
                renderer.submit(params, GpuDisplayOptions(false, 0))
                renderer.openPhoto(input()); renderer.setStarted(true)
                renderer.attachSurface(reader.surface, 16, 16)
                try {
                    assertTrue(lost.await(10, TimeUnit.SECONDS))
                    renderer.submit(params, GpuDisplayOptions(false, 0))
                    val recovered = requireNotNull(frames.poll(10, TimeUnit.SECONDS))
                    assertEquals(.5, recovered.stats.decisions.mistAutoStrength, 0.0)
                    assertEquals(2, creations.get())
                    assertEquals(1, closes.get())
                    assertEquals(4 * 4 * 16L, snapshot(renderer).allocatedBytes)
                    renderer.closePhoto()
                    assertEquals(0L, snapshot(renderer).allocatedBytes)
                    assertEquals(2, closes.get())
                    assertNull(errors.get())
                } finally { renderer.detachSurface(reader.surface) }
            }
        } finally { renderer.close(); consumer.quitSafely(); consumer.join(5000) }
        assertFalse(consumer.isAlive)
    }

    @Test fun rendererConnectsEngineWithoutExtraOutputAndReleasesItOnTrim() {
        val errors = AtomicReference<Throwable?>()
        val frames = LinkedBlockingQueue<GpuPreviewFrame>()
        val renders = AtomicInteger()
        val pipelineCloses = AtomicInteger()
        val renderer = GpuPreviewRenderer(instrumentation.targetContext.assets,
            onFrame = { frames.offer(it) }, onError = { errors.compareAndSet(null, it) },
            engineFactory = { egl, ledger -> DefaultStageClearGpu(egl, ledger) { texture, _ ->
                object : StageClearGpuPipeline {
                    override fun render(params: GpuRenderParams, tap: ((GpuStageTap.Point, GpuTexture) -> Unit)?,
                        consume: (GpuTexture, GpuDecisions) -> Unit) {
                        renders.incrementAndGet()
                        consume(texture, GpuDecisions(.375, 1.0))
                    }
                    override fun close() { pipelineCloses.incrementAndGet() }
                }
            } })
        val consumer = HandlerThread("StageClearGpuTestConsumer").apply { start() }
        try {
            ImageReader.newInstance(16, 16, PixelFormat.RGBA_8888, 2).use { reader ->
                reader.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, Handler(consumer.looper))
                renderer.submit(params, GpuDisplayOptions(true, 0))
                renderer.openPhoto(input())
                renderer.setStarted(true)
                renderer.attachSurface(reader.surface, 16, 16)
                try {
                    assertNotNull(frames.poll(10, TimeUnit.SECONDS))
                    assertEquals(0, renders.get())
                    renderer.submit(params, GpuDisplayOptions(false, 0))
                    val frame = requireNotNull(frames.poll(10, TimeUnit.SECONDS))
                    assertEquals(.375, frame.stats.decisions.mistAutoStrength, 0.0)
                    assertEquals(4 * 4 * 16L, snapshot(renderer).allocatedBytes)
                    @Suppress("DEPRECATION")
                    renderer.trimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
                    assertEquals(0L, snapshot(renderer).allocatedBytes)
                    assertEquals(1, pipelineCloses.get())
                    renderer.setStarted(true)
                    assertNotNull(frames.poll(10, TimeUnit.SECONDS))
                    renderer.closePhoto()
                    assertEquals(0L, snapshot(renderer).allocatedBytes)
                    assertEquals(2, pipelineCloses.get())
                    assertNull(errors.get())
                } finally { renderer.detachSurface(reader.surface) }
            }
        } finally { renderer.close(); consumer.quitSafely(); consumer.join(5000) }
        assertFalse(consumer.isAlive)
    }

    private fun snapshot(renderer: GpuPreviewRenderer): GpuPreviewSnapshot {
        val queue = LinkedBlockingQueue<GpuPreviewSnapshot>()
        renderer.snapshot { queue.offer(it) }
        return requireNotNull(queue.poll(10, TimeUnit.SECONDS))
    }
    private fun input() = LinearSrgbImage(4, 4, FloatArray(4 * 4 * 3) { (it - 5) / 31f })
}
