package dev.cinematic.p0.gpu

import android.opengl.GLES32
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.gpu.diag.GpuStageTap.Point
import dev.cinematic.p0.gpu.reference.GpuReferenceImages
import dev.cinematic.p0.gpu.reference.GpuTolerances
import dev.cinematic.p0.gpu.reference.ImageDiff
import dev.cinematic.p0.gpu.reference.KotlinReference
import dev.cinematic.p0.raw.PhotoAdjustments
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** #284 の全工程合成ゲート。実写、DNG 入力、実機性能の合格には読み替えない。 */
@RunWith(AndroidJUnit4::class)
class StageClearGpuPipelineAndroidTest {
    @Before fun isolatedEmulatorOnly() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish")) { "Synthetic emulator only" }
    }

    @Test fun displayAndMistMatchKotlinAcrossAll200Conditions() = gpu { egl ->
        val cases = listOf(
            Matrix(GpuReferenceImages.Size(256, 192), listOf(0.0, .25, .5, 1.0), listOf(
                PhotoAdjustments.NONE, PhotoAdjustments(60.0, 0.0),
                PhotoAdjustments(0.0, 1.0), PhotoAdjustments(-60.0, -1.0),
            )),
            Matrix(GpuReferenceImages.PREVIEW_SIZE, listOf(.5, 1.0), listOf(
                PhotoAdjustments.NONE, PhotoAdjustments(60.0, 1.0),
            )),
        )
        assertEquals("#278 fixture count", 10, GpuReferenceImages.Kind.entries.size)
        var conditions = 0
        var observedPeak = 0L
        for (case in cases) for (kind in GpuReferenceImages.Kind.entries) {
            val label = "$kind ${case.size.width}x${case.size.height}"
            val input = GpuReferenceImages.create(kind, case.size)
            val source = KotlinReference.Source(input)
            val ledger = previewLedger()
            var pipeline: ConnectedStageClearGpuPipeline? = null
            DefaultStageClearGpu(egl, ledger) { texture, analysis ->
                assertOwner(label, egl, ledger, texture)
                ConnectedStageClearGpuPipeline(texture, analysis).also { pipeline = it }
            }.use { engine ->
                val baseline = memory(ledger)
                val peakBeforeClose = engine.openPhoto(input) { false }.use { photo ->
                    assertEquals("$label AS_SHOT bytes", input.pixelCount * 16L, ledger.allocatedBytes)
                    assertEquals("$label AS_SHOT texture", 1, ledger.textureCount)
                    for (adjustments in case.adjustments) {
                        val prepared = KotlinReference.prepare(source, adjustments)
                        for (strength in case.strengths) {
                            val condition = "$label strength=$strength adjustments=$adjustments"
                            Log.i(TAG, "BEGIN $condition")
                            val expected = KotlinReference.render(prepared, strength, retainIntermediates = false)
                            val actual = engine.renderForTest(photo, GpuRenderParams(strength, adjustments), Point.DISPLAY8)
                            assertEquals("$condition width", input.width, actual.width)
                            assertEquals("$condition height", input.height, actual.height)
                            // DISPLAY8 is reconstructed linear RGB; re-encoding recovers its exact bytes.
                            val difference = ImageDiff.display(expected.display8, ImageDiff.encodeDisplay(actual),
                                input.width, input.height, "$condition DISPLAY8")
                            val decisions = checkNotNull(pipeline).lastDecisions
                            val mistDifference = ImageDiff.scalar(expected.mistAutoStrength, decisions.mistAutoStrength)
                            Log.i(TAG, "$condition max=${difference.maxDiff} p999=${difference.p999} " +
                                "mean=${difference.meanDiff} mistDelta=$mistDifference peakBytes=${ledger.peakAllocatedBytes}")
                            difference.assertDisplayWithin()
                            ImageDiff.assertScalarWithin(expected.mistAutoStrength, decisions.mistAutoStrength,
                                GpuTolerances.MIST_AUTO_STRENGTH, "$condition mistAutoStrength")
                            assertEquals("$condition faceCutFactor", 1.0, decisions.faceCutFactor, 0.0)
                            assertWithinBudget(condition, ledger)
                            conditions++
                        }
                    }
                    ledger.peakAllocatedBytes
                }
                // Check photo.close(), before engine.close() can conceal a photo-owned leak.
                assertReleased(label, ledger, baseline, peakBeforeClose)
                observedPeak = maxOf(observedPeak, ledger.peakAllocatedBytes)
            }
            assertEquals("$label engine close bytes", 0L, ledger.allocatedBytes)
            assertEquals("$label engine close textures", 0, ledger.textureCount)
        }
        assertEquals("All #284 conditions must run", 200, conditions)
        Log.i(TAG, "PASS DISPLAY8/Mist conditions=$conditions peakBytes=$observedPeak budgetBytes=$PREVIEW_BYTES " +
            "headroomBytes=${PREVIEW_BYTES - observedPeak}")
    }

    @Test fun strengthReusesBothCachesAndMatchesFreshRgbaBitsAtBothSizes() = gpu { egl ->
        var comparisons = 0
        var observedPeak = 0L
        for (size in listOf(GpuReferenceImages.Size(256, 192), GpuReferenceImages.PREVIEW_SIZE)) {
            val label = "MIXED ${size.width}x${size.height}"
            val input = GpuReferenceImages.create(GpuReferenceImages.Kind.MIXED, size)
            val ledger = previewLedger()
            var captured: ConnectedStageClearGpuPipeline? = null
            DefaultStageClearGpu(egl, ledger) { texture, analysis ->
                assertOwner(label, egl, ledger, texture)
                ConnectedStageClearGpuPipeline(texture, analysis).also { captured = it }
            }.use { engine ->
                val baseline = memory(ledger)
                val peakBeforeClose = engine.openPhoto(input) { false }.use { photo ->
                    // Initialize through the public photo API, then observe the real connected passes.
                    engine.renderForTest(photo, GpuRenderParams(.5, PhotoAdjustments.NONE), Point.SHOULDER)
                    val pipeline = checkNotNull(captured)
                    assertTrue("$label R7 prepared", pipeline.r7FragmentPassCount > 0)
                    assertTrue("$label Glow prepared", pipeline.glowPreparationCount > 0)
                    var previousAdjustments = PhotoAdjustments.NONE
                    // Warmth-only, EV-only, and return-to-original invalidations are distinct.
                    for (adjustments in listOf(PhotoAdjustments.NONE, PhotoAdjustments(60.0, 0.0),
                        PhotoAdjustments(60.0, 1.0), PhotoAdjustments.NONE)) {
                        for (strength in listOf(0.0, .25, .5, 1.0)) {
                            val params = GpuRenderParams(strength, adjustments)
                            val condition = "$label strength=$strength adjustments=$adjustments"
                            val beforeR7 = pipeline.r7FragmentPassCount
                            val beforeGlow = pipeline.glowPreparationCount
                            val beforeMemory = memory(ledger)
                            val cached = shoulderBits(condition, egl, ledger, pipeline, params, forceFresh = false)
                            if (adjustments == previousAdjustments) {
                                assertEquals("$condition R7 reused", beforeR7, pipeline.r7FragmentPassCount)
                                assertEquals("$condition Glow reused", beforeGlow, pipeline.glowPreparationCount)
                                assertEquals("$condition cached frame releases temporary textures", beforeMemory, memory(ledger))
                            } else {
                                assertTrue("$condition R7 invalidated", pipeline.r7FragmentPassCount > beforeR7)
                                assertEquals("$condition Glow invalidated", beforeGlow + 1, pipeline.glowPreparationCount)
                            }
                            previousAdjustments = adjustments
                            val cachedDecision = pipeline.lastDecisions
                            val cachedR7 = pipeline.r7FragmentPassCount
                            val cachedGlow = pipeline.glowPreparationCount
                            val cachedMemory = memory(ledger)
                            val fresh = shoulderBits(condition, egl, ledger, pipeline, params, forceFresh = true)
                            assertTrue("$condition forced R7 recomputation", pipeline.r7FragmentPassCount > cachedR7)
                            assertEquals("$condition forced Glow recomputation", cachedGlow + 1, pipeline.glowPreparationCount)
                            assertArrayEquals("$condition cached/fresh RGBA32F SHOULDER bits", fresh, cached)
                            assertEquals("$condition cached/fresh decisions", cachedDecision, pipeline.lastDecisions)
                            assertEquals("$condition fresh frame releases temporary textures", cachedMemory, memory(ledger))
                            assertWithinBudget(condition, ledger)
                            comparisons++
                        }
                    }
                    ledger.peakAllocatedBytes
                }
                assertReleased(label, ledger, baseline, peakBeforeClose)
                observedPeak = maxOf(observedPeak, ledger.peakAllocatedBytes)
            }
            assertEquals("$label engine close bytes", 0L, ledger.allocatedBytes)
            assertEquals("$label engine close textures", 0, ledger.textureCount)
        }
        assertEquals(32, comparisons)
        Log.i(TAG, "PASS cache/fresh RGBA32F comparisons=$comparisons peakBytes=$observedPeak budgetBytes=$PREVIEW_BYTES " +
            "headroomBytes=${PREVIEW_BYTES - observedPeak}")
    }

    private fun shoulderBits(label: String, egl: EglContexts, ledger: GpuMemoryLedger,
        pipeline: ConnectedStageClearGpuPipeline, params: GpuRenderParams, forceFresh: Boolean): IntArray {
        var result: IntArray? = null
        // Non-null tap forces fresh R7 and Glow preparation; null is the production cache path.
        val tap: ((Point, GpuTexture) -> Unit)? = if (forceFresh) { point, texture ->
            assertOwner("$label $point", egl, ledger, texture)
            assertWithinBudget("$label $point", ledger)
        } else null
        pipeline.render(params, tap) { output, decisions ->
            assertNull("$label emits exactly one frame", result)
            assertOwner("$label SHOULDER", egl, ledger, output)
            assertEquals(GpuTexture.Format.RGBA32F, output.format)
            assertEquals(1.0, decisions.faceCutFactor, 0.0)
            assertWithinBudget("$label consume", ledger)
            // Read inside consume: the output is borrowed and released when this callback returns.
            GpuFence(egl).use { it.await() }
            val bytes = ByteBuffer.allocateDirect(Math.toIntExact(output.byteSize)).order(ByteOrder.nativeOrder())
            GLES32.glBindFramebuffer(GLES32.GL_FRAMEBUFFER, output.framebufferId)
            GLES32.glReadBuffer(GLES32.GL_COLOR_ATTACHMENT0)
            GLES32.glBindBuffer(GLES32.GL_PIXEL_PACK_BUFFER, 0)
            GLES32.glPixelStorei(GLES32.GL_PACK_ALIGNMENT, 1)
            GLES32.glPixelStorei(GLES32.GL_PACK_ROW_LENGTH, 0)
            GLES32.glPixelStorei(GLES32.GL_PACK_SKIP_PIXELS, 0)
            GLES32.glPixelStorei(GLES32.GL_PACK_SKIP_ROWS, 0)
            GLES32.glReadPixels(0, 0, output.width, output.height, GLES32.GL_RGBA, GLES32.GL_FLOAT, bytes)
            egl.checkGl("read full-pipeline SHOULDER bits")
            val floats = bytes.asFloatBuffer()
            result = IntArray(output.width * output.height * 4) { i ->
                val value = floats.get(i)
                if (!value.isFinite()) throw AssertionError("$label non-finite RGBA channel $i")
                value.toRawBits()
            }
        }
        return checkNotNull(result) { "$label did not emit SHOULDER" }
    }

    private fun assertOwner(label: String, egl: EglContexts, ledger: GpuMemoryLedger, texture: GpuTexture) {
        texture.requireContext(egl)
        assertSame("$label shares engine ledger", ledger, texture.memoryLedger)
    }

    private fun previewLedger(): GpuMemoryLedger = GpuMemoryLedger().also {
        assertEquals("Gate 1 preview budget", PREVIEW_BYTES, it.budgetBytes)
        assertEquals("Export budget is unchanged", 192L * 1024 * 1024, GpuMemoryLedger.EXPORT_BUDGET)
        assertEquals(0L, it.allocatedBytes)
        assertEquals(0L, it.peakAllocatedBytes)
        assertEquals(0, it.textureCount)
    }

    private fun assertWithinBudget(label: String, ledger: GpuMemoryLedger) {
        assertTrue("$label allocated=${ledger.allocatedBytes} peak=${ledger.peakAllocatedBytes}",
            ledger.allocatedBytes in 0..ledger.peakAllocatedBytes)
        assertTrue("$label peak=${ledger.peakAllocatedBytes} budget=$PREVIEW_BYTES",
            ledger.peakAllocatedBytes <= PREVIEW_BYTES)
    }

    private fun assertReleased(label: String, ledger: GpuMemoryLedger, baseline: Memory, peakBeforeClose: Long) {
        assertEquals("$label photo.close restores bytes/count", baseline, memory(ledger))
        assertEquals("$label close preserves peak", peakBeforeClose, ledger.peakAllocatedBytes)
        assertTrue("$label records successful reservations after close", ledger.peakAllocatedBytes > baseline.bytes)
        assertWithinBudget(label, ledger)
        Log.i(TAG, "$label CLOSED bytes=${ledger.allocatedBytes} textures=${ledger.textureCount} " +
            "peakBytes=${ledger.peakAllocatedBytes} budgetBytes=${ledger.budgetBytes} " +
            "headroomBytes=${PREVIEW_BYTES - ledger.peakAllocatedBytes}")
    }

    private fun memory(ledger: GpuMemoryLedger) = Memory(ledger.allocatedBytes, ledger.textureCount)

    private fun gpu(block: (EglContexts) -> Unit) = GlThread().use { thread ->
        thread.call {
            EglContexts(thread, InstrumentationRegistry.getInstrumentation().targetContext.assets).use { egl ->
                GlCapabilities.inspect(egl).requireSupported()
                block(egl)
            }
        }
    }

    private data class Matrix(val size: GpuReferenceImages.Size, val strengths: List<Double>,
        val adjustments: List<PhotoAdjustments>)
    private data class Memory(val bytes: Long, val textures: Int)

    private companion object {
        const val TAG = "GpuPipeline"
        const val PREVIEW_BYTES = 96L * 1024 * 1024
    }
}
