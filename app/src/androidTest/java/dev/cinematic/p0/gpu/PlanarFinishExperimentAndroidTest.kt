package dev.cinematic.p0.gpu

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.gpu.diag.GpuStageTap
import dev.cinematic.p0.gpu.diag.GpuStageTap.Point
import dev.cinematic.p0.gpu.experimental.PlanarFinishExperiment
import dev.cinematic.p0.gpu.reference.GpuReferenceImages
import dev.cinematic.p0.gpu.reference.ImageDiff
import dev.cinematic.p0.gpu.reference.KotlinReference
import dev.cinematic.p0.raw.LinearSrgbImage
import dev.cinematic.p0.raw.PhotoAdjustments
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/** Low-priority synthetic trial. Never schedule ahead of #306 -> #310 -> #284. */
@RunWith(AndroidJUnit4::class)
class PlanarFinishExperimentAndroidTest {
    @Before fun emulatorOnly() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish")) { "Synthetic emulator only" }
    }
    private fun gpu(block: (EglContexts) -> Unit) = GlThread().use { thread ->
        thread.call {
            EglContexts(thread, InstrumentationRegistry.getInstrumentation().targetContext.assets).use { egl ->
                val caps = GlCapabilities.inspect(egl)
                Log.i("PlanarFinishTrial", "capabilities=$caps"); caps.requireSupported()
                block(egl)
            }
        }
    }
    private fun upload(egl: EglContexts, ledger: GpuMemoryLedger, image: LinearSrgbImage): GpuTexture {
        val result = GpuTexture.create(egl, ledger, image.width, image.height, GpuTexture.Format.RGBA32F)
        try {
            val data = ByteBuffer.allocateDirect(image.pixelCount * 16).order(ByteOrder.nativeOrder()).asFloatBuffer()
            repeat(image.pixelCount) { p -> repeat(3) { data.put(image[p * 3 + it]) }; data.put(1f) }
            data.flip(); result.upload(data); return result
        } catch (failure: Throwable) { result.close(); throw failure }
    }
    private fun read(egl: EglContexts, planes: List<GpuTexture>): LinearSrgbImage {
        val values = planes.map { GpuStageTap.readPlane(egl, it) }
        return LinearSrgbImage.takeOwnership(planes[0].width, planes[0].height,
            FloatArray(planes[0].width * planes[0].height * 3) { values[it % 3][it / 3] })
    }
    private fun compare(expected: LinearSrgbImage, actual: LinearSrgbImage, label: String) {
        repeat(expected.pixelCount * 3) { i ->
            val e = expected[i].toDouble(); val a = actual[i].toDouble()
            val limit = maxOf(1.0, abs(e)) * 1e-4
            assertTrue("$label [$i] expected=$e actual=$a limit=$limit", a.isFinite() && abs(a - e) <= limit)
        }
        ImageDiff.display(expected, actual, label).assertDisplayWithin(maxDiff = 1)
    }

    @Test fun sameKotlinReferenceAndFixedTolerancesAtEveryPointwiseStage() = gpu { egl ->
        val ledger = GpuMemoryLedger()
        var conditions = 0
        for (size in listOf(GpuReferenceImages.Size(256, 192), GpuReferenceImages.PREVIEW_SIZE)) {
            for (kind in GpuReferenceImages.Kind.entries) {
                val prepared = KotlinReference.prepare(GpuReferenceImages.create(kind, size), PhotoAdjustments())
                for (strength in listOf(0.0, .25, .5, 1.0)) {
                    val expected = KotlinReference.render(prepared, strength)
                    upload(egl, ledger, expected[Point.ORTON]).use { input ->
                        PlanarFinishExperiment.import(input).use { trial ->
                            val allocated = ledger.allocatedBytes
                            val ids = mutableListOf<List<Int>>()
                            trial.render(strength) { stage, planes ->
                                ids += planes.map { it.textureId }
                                compare(expected[stage], read(egl, planes), "$kind $size $strength $stage")
                                assertEquals("No pointwise allocation", allocated, ledger.allocatedBytes)
                            }
                            assertEquals(1, ids.distinct().size)
                            assertThrows(IllegalStateException::class.java) { trial.render(strength) }
                        }
                        val after = GpuStageTap.readRgb(egl, input)
                        repeat(after.pixelCount * 3) { assertEquals(expected[Point.ORTON][it].toRawBits(), after[it].toRawBits()) }
                    }
                    assertEquals(0L, ledger.allocatedBytes); conditions++
                }
            }
        }
        assertEquals(80, conditions)
    }

    @Test fun actualLedgerMeasuresPointwiseTailWithImmutableRetainedInputs() = gpu { egl ->
        val w = 1024; val h = 769
        val ledger = GpuMemoryLedger()
        val retained = mutableListOf<GpuTexture>()
        try {
            // Exact retained formats/dimensions: AS_SHOT, R7, bloom, skin, halation.
            // These allocated fixtures model cache residency, NOT full Glow/Mist/Orton execution.
            repeat(3) { retained += GpuTexture.create(egl, ledger, w, h, GpuTexture.Format.RGBA32F) }
            repeat(2) { retained += GpuTexture.create(egl, ledger, w, h, GpuTexture.Format.R32F) }
            val image = GpuReferenceImages.create(GpuReferenceImages.Kind.MIXED, GpuReferenceImages.PREVIEW_SIZE)
            upload(egl, ledger, image).use { orton ->
                val before = ledger.allocatedBytes
                PlanarFinishExperiment.import(orton).use { trial ->
                    trial.render(.5)
                    assertEquals(before + w.toLong() * h * 12, trial.peakLedgerBytes)
                    assertEquals(trial.peakLedgerBytes, ledger.allocatedBytes)
                    assertTrue(trial.peakLedgerBytes <= GpuMemoryLedger.PREVIEW_BUDGET)
                    Log.i("PlanarFinishTrial", "pointwiseTailPeakBytes=${trial.peakLedgerBytes}; retainedBytes=${before - orton.byteSize}; fullPipeline=NOT_MEASURED")
                }
                assertEquals(before, ledger.allocatedBytes)
                compare(image, GpuStageTap.readRgb(egl, orton), "immutable ORTON")
            }
        } finally { retained.asReversed().forEach { it.close() } }
        assertEquals(0L, ledger.allocatedBytes)
    }

    @Test fun partialAllocationAndTapFailureReleaseOwnedPlanesOnly() = gpu { egl ->
        val image = GpuReferenceImages.create(GpuReferenceImages.Kind.MIXED, GpuReferenceImages.Size(17, 9))
        val bytes = image.pixelCount.toLong() * 4
        val ledger = GpuMemoryLedger(bytes * 6) // input + only two of the three planes fit.
        upload(egl, ledger, image).use { input ->
            assertThrows(GpuMemoryBudgetException::class.java) { PlanarFinishExperiment.import(input) }
            assertEquals(input.byteSize, ledger.allocatedBytes)
            compare(image, GpuStageTap.readRgb(egl, input), "allocation failure preserves input")
        }
        assertEquals(0L, ledger.allocatedBytes)
        val roomy = GpuMemoryLedger()
        upload(egl, roomy, image).use { input ->
            assertThrows(IllegalStateException::class.java) {
                PlanarFinishExperiment.import(input).use { trial -> trial.render(.5) { _, _ -> error("synthetic tap") } }
            }
            assertEquals(input.byteSize, roomy.allocatedBytes)
            input.requireOpen()
        }
        assertEquals(0L, roomy.allocatedBytes)
    }
}
