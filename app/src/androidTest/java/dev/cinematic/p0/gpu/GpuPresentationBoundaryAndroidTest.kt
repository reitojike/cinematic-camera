package dev.cinematic.p0.gpu

import android.graphics.PixelFormat
import android.media.ImageReader
import android.opengl.GLES32
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.gpu.diag.GpuStageTap
import dev.cinematic.p0.raw.LinearSrgbImage
import dev.cinematic.p0.raw.PhotoAdjustments
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Synthetic GL-boundary checks, not Pixel performance or image-quality acceptance. */
class GpuPresentationBoundaryAndroidTest {
    private val params = GpuRenderParams(.5, PhotoAdjustments.NONE)

    @Before fun syntheticOnly() {
        check(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
    }

    private fun gpu(block: (EglContexts, GpuMemoryLedger, EglContexts.WindowTarget) -> Unit) = GlThread().use { thread ->
        thread.call {
            EglContexts(thread, InstrumentationRegistry.getInstrumentation().targetContext.assets).use { egl ->
                GlCapabilities.inspect(egl).requireSupported()
                val ledger = GpuMemoryLedger()
                ImageReader.newInstance(16, 16, PixelFormat.RGBA_8888, 2).use { reader ->
                    egl.createWindowTarget(reader.surface).use { window -> block(egl, ledger, window) }
                }
                assertEquals(0L, ledger.allocatedBytes)
                assertEquals(0, ledger.textureCount)
            }
        }
    }

    @Test fun beforeBypassesPipelineAndFailedPresentationPreservesLastDecision() = gpu { egl, ledger, window ->
        var calls = 0
        var foreign = false
        val otherLedger = GpuMemoryLedger()
        DefaultStageClearGpu(egl, ledger, createPresenter = windowTestPresenter(egl, ledger)) { input, _ ->
            object : StageClearGpuPipeline {
                override fun render(params: GpuRenderParams, tap: ((GpuStageTap.Point, GpuTexture) -> Unit)?,
                    consume: (GpuTexture, GpuDecisions) -> Unit) {
                    calls++
                    if (foreign) {
                        GpuTexture.create(egl, otherLedger, input.width, input.height, GpuTexture.Format.RGBA32F).use {
                            consume(it, GpuDecisions(.75, 1.0))
                        }
                    } else consume(input, GpuDecisions(.25, 1.0))
                }
                override fun close() = Unit
            }
        }.use { engine ->
            engine.openPhoto(LinearSrgbImage(4, 4, FloatArray(48) { .5f })) { false }.use { photo ->
                val target = StageClearGpuPreviewTarget(window, 16, 16)
                val before = GpuDisplayOptions(true, 0)
                assertEquals(0.0, engine.renderPreview(photo, params, target, before).decisions.mistAutoStrength, 0.0)
                assertEquals(0, calls)
                assertEquals(.25, engine.renderPreview(photo, params, target, before.copy(showBefore = false)).decisions.mistAutoStrength, 0.0)
                foreign = true
                assertThrows(IllegalArgumentException::class.java) {
                    engine.renderPreview(photo, params, target, before.copy(showBefore = false))
                }
                assertEquals(0L, otherLedger.allocatedBytes)
                assertEquals(.25, engine.renderPreview(photo, params, target, before).decisions.mistAutoStrength, 0.0)
                assertEquals(2, calls)
                foreign = false
                engine.renderPreview(photo, params, target, before.copy(showBefore = false))
                assertEquals(3, calls)
            }
        }
    }

    @Test fun temporaryOutputIsConsumedBeforeCloseAndViewportOffsetIsForwarded() = gpu { egl, ledger, window ->
        var released = false
        DefaultStageClearGpu(egl, ledger, createPresenter = windowTestPresenter(egl, ledger)) { input, _ ->
            object : StageClearGpuPipeline {
                override fun render(params: GpuRenderParams, tap: ((GpuStageTap.Point, GpuTexture) -> Unit)?,
                    consume: (GpuTexture, GpuDecisions) -> Unit) {
                    GpuTexture.create(egl, ledger, input.width, input.height, GpuTexture.Format.RGBA32F).use { output ->
                        output.upload(ByteBuffer.allocateDirect(input.width * input.height * 16)
                            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                                repeat(input.width * input.height * 4) { put(1f) }; flip()
                            })
                        consume(output, GpuDecisions(.5, 1.0))
                        output.requireOpen()
                    }
                    released = true
                }
                override fun close() = Unit
            }
        }.use { engine ->
            engine.openPhoto(LinearSrgbImage(4, 4, FloatArray(48))) { false }.use { photo ->
                val target = StageClearGpuPreviewTarget(window, 16, 8, viewportY = 8)
                egl.makeCurrent(window)
                GLES32.glBindFramebuffer(GLES32.GL_FRAMEBUFFER, 0)
                GLES32.glViewport(0, 0, 16, 16)
                GLES32.glClearColor(0f, 0f, 0f, 1f)
                GLES32.glClear(GLES32.GL_COLOR_BUFFER_BIT)
                engine.renderPreview(photo, params, target, GpuDisplayOptions(false, 0))
                assertTrue(released)
                assertEquals(4 * 4 * 16L, ledger.allocatedBytes)
                val viewport = IntArray(4)
                GLES32.glGetIntegerv(GLES32.GL_VIEWPORT, viewport, 0)
                assertArrayEquals(intArrayOf(0, 8, 16, 8), viewport)
                val pixels = ByteBuffer.allocateDirect(16 * 16 * 4)
                GLES32.glBindFramebuffer(GLES32.GL_FRAMEBUFFER, 0)
                GLES32.glReadPixels(0, 0, 16, 16, GLES32.GL_RGBA, GLES32.GL_UNSIGNED_BYTE, pixels)
                egl.checkGl("D1 viewport output")
                // GL rows begin at the bottom: only the upper viewport contains the centered square.
                assertEquals(0, pixels.get((4 * 16 + 8) * 4).toInt() and 255)
                assertEquals(255, pixels.get((12 * 16 + 8) * 4).toInt() and 255)
            }
        }
    }

    @Test fun invalidPresentationIsRejectedBeforePipelineRuns() = gpu { egl, ledger, window ->
        var calls = 0
        DefaultStageClearGpu(egl, ledger, createPresenter = windowTestPresenter(egl, ledger)) { _, _ -> calls++; error("Must not create pipeline") }.use { engine ->
            engine.openPhoto(LinearSrgbImage(4, 4, FloatArray(48))) { false }.use { photo ->
                assertThrows(IllegalArgumentException::class.java) {
                    engine.renderPreview(photo, params, StageClearGpuPreviewTarget(window, 16, 16), GpuDisplayOptions(false, 45))
                }
                assertThrows(IllegalArgumentException::class.java) {
                    engine.renderPreview(photo, params, window, GpuDisplayOptions(false, 0))
                }
                assertEquals(0, calls)
            }
        }
    }
}
