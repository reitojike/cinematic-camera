package dev.cinematic.p0.gpu

import dev.cinematic.p0.gpu.constants.StageClearMistV1
import dev.cinematic.p0.gpu.constants.StageClearGlowV1
import dev.cinematic.p0.gpu.constants.StageClearV1
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.gpu.analysis.GpuAnalysis
import dev.cinematic.p0.gpu.diag.GpuStageTap
import dev.cinematic.p0.gpu.look.StageClearGlowGpu
import dev.cinematic.p0.gpu.look.StageClearMistGpu
import dev.cinematic.p0.gpu.reference.GpuReferenceImages
import dev.cinematic.p0.gpu.reference.ImageDiff
import dev.cinematic.p0.gpu.reference.KotlinReference
import dev.cinematic.p0.gpu.resample.GpuResample
import dev.cinematic.p0.raw.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** #281: synthetic R7_FINAL inputs, never photos. Run only after Mesa and asset acceptance. */
@RunWith(AndroidJUnit4::class)
class GlowMistParityAndroidTest {
    @Before fun syntheticEmulatorOnly() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
    }
    private fun gpu(block: (EglContexts, GpuMemoryLedger) -> Unit) = GlThread().use { thread ->
        thread.call {
            EglContexts(thread, InstrumentationRegistry.getInstrumentation().targetContext.assets).use { egl ->
                val caps = GlCapabilities.inspect(egl)
                Log.i(TAG, "capabilities=$caps"); caps.requireSupported()
                val ledger = GpuMemoryLedger()
                block(egl, ledger)
                assertEquals("all texture ownership released", 0L, ledger.allocatedBytes)
            }
        }
    }
    private fun upload(egl: EglContexts, ledger: GpuMemoryLedger, image: LinearSrgbImage): GpuTexture {
        val rgba = FloatArray(image.pixelCount * 4) { i -> if (i % 4 == 3) 1f else image[i / 4 * 3 + i % 4] }
        val result = GpuTexture.create(egl, ledger, image.width, image.height, GpuTexture.Format.RGBA32F)
        try {
            result.upload(ByteBuffer.allocateDirect(rgba.size * 4).order(ByteOrder.nativeOrder())
                .asFloatBuffer().apply { put(rgba); flip() })
            return result
        } catch (failure: Throwable) { result.close(); throw failure }
    }
    private fun reportRgb(reference: LinearSrgbImage, actual: LinearSrgbImage, label: String) {
        Log.i(TAG, "CPU_REFERENCE_ONLY ${ImageDiff.linear(reference, actual, label)}")
        Log.i(TAG, "CPU_REFERENCE_ONLY ${ImageDiff.display(reference, actual, label)}")
    }
    private fun count(image: LinearSrgbImage): Long {
        val luma = StageClearV1.LUMA
        var result = 0L
        repeat(image.pixelCount) { p ->
            val i = p * 3
            val y = image[i] * luma[0] + image[i + 1] * luma[1] + image[i + 2] * luma[2]
            if (y > StageClearMistV1.NEAR_WHITE.toFloat()) result++
        }
        return result
    }

    @Test fun everyReferenceImageReportsSkinGlowMistAtBothSizesAndAllStrengths() = gpu { egl, ledger ->
        var conditions = 0
        val sizes = listOf(GpuReferenceImages.Size(256, 192), GpuReferenceImages.PREVIEW_SIZE)
        for (size in sizes) for (kind in GpuReferenceImages.Kind.entries) {
            val reference = KotlinReference.prepare(GpuReferenceImages.create(kind, size), PhotoAdjustments())
            // The complete engine keeps its immutable AS_SHOT resident throughout the look.
            upload(egl, ledger, reference.input).use { asShot ->
                upload(egl, ledger, reference.r7Final).use { base ->
                    GpuResample(egl, ledger).use { resample ->
                        StageClearGlowGpu.prepare(base, resample).use { prepared ->
                            val skin = GpuStageTap.readPlane(egl, prepared.skinWeight)
                            val skinDiff = ImageDiff.linear(reference.skinWeight, skin, "$kind $size SKIN_WEIGHT")
                            Log.i(TAG, "CPU_REFERENCE_ONLY $skinDiff")
                            for (strength in listOf(0.0, .25, .5, 1.0)) {
                                val start = System.nanoTime()
                                val expectedGlow = StageClearGlow.render(reference.r7Final, strength, reference.skinWeight)
                                val expectedMist = StageClearMist.render(expectedGlow, reference.skinWeight, strength)
                                val glowed = prepared.render(strength)
                                try {
                                    val actualGlow = GpuStageTap.readRgb(egl, glowed)
                                    reportRgb(expectedGlow, actualGlow, "$kind $size $strength GLOW")
                                    val nearWhite = GpuAnalysis.nearWhiteCount(glowed, glowed.width, glowed.height)
                                    assertTrue(nearWhite in 0L..actualGlow.pixelCount.toLong())
                                    Log.i(TAG, "CPU_REFERENCE_ONLY same-input nearWhite cpu=${count(actualGlow)} gpu=$nearWhite")
                                    val auto = StageClearMistGpu.autoStrength(glowed)
                                    Log.i(TAG, ImageDiff.referenceScalar(StageClearMist.autoStrength(expectedGlow), auto, "$kind $size $strength Mist auto"))
                                    // Exercise both the integration override and the default measurement path.
                                    val mist = if (strength == 0.0)
                                        StageClearMistGpu.render(glowed, prepared.skinWeight, strength, resample)
                                    else StageClearMistGpu.render(glowed, prepared.skinWeight, strength, resample, auto)
                                    try {
                                        if (strength == 0.0) {
                                            assertSame(base, glowed); assertSame(base, mist.image)
                                            repeat(reference.r7Final.pixelCount * 3) { i ->
                                                assertEquals(reference.r7Final[i].toRawBits(), actualGlow[i].toRawBits())
                                            }
                                        }
                                        reportRgb(expectedMist.image, GpuStageTap.readRgb(egl, mist.image), "$kind $size $strength MIST")
                                        Log.i(TAG, ImageDiff.referenceScalar(expectedMist.lightAddingStrength, mist.lightAddingStrength, "$kind $size $strength Mist amount"))
                                    } finally { if (mist.image !== glowed) mist.image.close() }
                                } finally { if (glowed !== base) glowed.close() }
                                Log.i(TAG, "$kind $size strength=$strength elapsedMs=${(System.nanoTime() - start) / 1e6}; ledger=${ledger.allocatedBytes}")
                                conditions++
                            }
                        }
                    }
                }
                asShot.requireOpen()
                val original = GpuStageTap.readRgb(egl, asShot)
                repeat(original.pixelCount * 3) { i -> assertEquals(reference.input[i].toRawBits(), original[i].toRawBits()) }
            }
            assertEquals(0L, ledger.allocatedBytes)
        }
        assertTrue(ledger.peakAllocatedBytes <= GpuMemoryLedger.PREVIEW_BUDGET)
        assertEquals(0, ledger.textureCount)
        assertEquals(80, conditions)
        Log.i(TAG, "conditions=$conditions; skin=20; glow=80; mist=80; counts=80; residentAS_SHOT included; defaultPreviewBudget=${ledger.budgetBytes} enforced; peakBytes=${ledger.peakAllocatedBytes}; marginBytes=${ledger.budgetBytes - ledger.peakAllocatedBytes}")
    }

    @Test fun changingStrengthReusesAllPreparedFields() = gpu { egl, ledger ->
        val image = GpuReferenceImages.create(GpuReferenceImages.Kind.MIXED, GpuReferenceImages.Size(256, 192))
        upload(egl, ledger, image).use { base ->
            GpuResample(egl, ledger).use { resample ->
                val calls = mutableListOf<String>()
                StageClearGlowGpu.prepare(base, resample) { calls += it }.use { prepared ->
                    val preparedCalls = calls.toList()
                    assertEquals(StageClearGlowV1.BLOOM_KERNELS.size + 2, preparedCalls.size)
                    prepared.render(.25).use { }
                    prepared.render(.75).use { }
                    assertEquals(preparedCalls, calls.filter { it != "diffusion" })
                    assertEquals(2, calls.count { it == "diffusion" })
                    assertSame(base, prepared.render(0.0))
                    for (invalid in listOf(-.01, 1.01, Double.NaN, Double.POSITIVE_INFINITY)) {
                        assertThrows(IllegalArgumentException::class.java) {
                            StageClearMistGpu.render(base, prepared.skinWeight, .5, resample, invalid)
                        }
                    }
                }
                base.requireOpen()
            }
        }
    }

    @Test fun nearWhiteStrictBoundaryCounterResetsAndValidatesDimensions() = gpu { egl, ledger ->
        val threshold = StageClearMistV1.NEAR_WHITE.toFloat()
        val values = floatArrayOf(Math.nextDown(threshold), threshold, Math.nextUp(threshold), 0f, 1f, 4f)
        val image = LinearSrgbImage(values.size, 1, FloatArray(values.size * 3) { values[it / 3] })
        upload(egl, ledger, image).use { source ->
            val first = GpuAnalysis.nearWhiteCount(source, source.width, source.height)
            assertTrue(first in 0L..image.pixelCount.toLong())
            Log.i(TAG, "CPU_REFERENCE_ONLY boundary nearWhite cpu=${count(image)} gpu=$first")
            repeat(3) { assertEquals("GPU counter must reset between calls", first, GpuAnalysis.nearWhiteCount(source, source.width, source.height)) }
            assertThrows(IllegalArgumentException::class.java) { GpuAnalysis.nearWhiteCount(source, source.width + 1, source.height) }
            source.requireOpen()
        }
        val dark = LinearSrgbImage(17, 19, FloatArray(17 * 19 * 3))
        upload(egl, ledger, dark).use { assertEquals(0L, GpuAnalysis.nearWhiteCount(it, it.width, it.height)) }
    }

    companion object { private const val TAG = "GlowMistParity" }
}
