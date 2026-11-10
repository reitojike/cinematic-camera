package dev.cinematic.p0.gpu

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.gpu.diag.GpuStageTap
import dev.cinematic.p0.gpu.reference.GpuReferenceImages
import dev.cinematic.p0.raw.PhotoAdjustments
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** #323: real GL storage ownership, full-frame recomputation and bounded retention. */
@RunWith(AndroidJUnit4::class)
class GpuResourceReuseAndroidTest {
    @Before fun isolatedEmulatorOnly() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
    }

    @Test fun reuseNeverRevivesClosedLeaseOrAliasesAnActiveTexture() = gpu { egl ->
        val ledger = GpuMemoryLedger(128)
        GpuTexturePool(egl, ledger).use { pool ->
            val first = GpuTexture.create(egl, ledger, 2, 2, GpuTexture.Format.RGBA32F, pool)
            val name = first.textureId
            val framebuffer = first.framebufferId
            first.close()
            assertEquals(64L, ledger.allocatedBytes) // Idle storage is still counted.
            val reused = GpuTexture.create(egl, ledger, 2, 2, GpuTexture.Format.RGBA32F, pool)
            assertEquals(name, reused.textureId)
            assertEquals(framebuffer, reused.framebufferId)
            assertThrows(IllegalStateException::class.java) { first.requireOpen() }
            first.close() // An old lease must not close the newly borrowed allocation.
            reused.requireOpen()
            val concurrent = reused.createSibling()
            assertNotEquals(name, concurrent.textureId)
            assertThrows(GpuMemoryBudgetException::class.java) { reused.createSibling() }
            assertEquals(128L, ledger.allocatedBytes)
            concurrent.close(); reused.close()
            // Different shape: evict idle storage before allocating without exceeding the real budget.
            GpuTexture.create(egl, ledger, 4, 2, GpuTexture.Format.RGBA32F, pool).use { larger ->
                assertEquals(128L, ledger.allocatedBytes)
                assertEquals(128L, larger.byteSize)
            }
            assertEquals(1, pool.reuseCount)
        }
        assertEquals(0L, ledger.allocatedBytes)
        assertEquals(0, ledger.textureCount)
        assertEquals(128L, ledger.peakAllocatedBytes)
    }

    @Test fun allFourShapesRecomputeEveryFrameAndReleaseTheirPersistentStorage() = gpu { egl ->
        for ((width, height) in listOf(1024 to 768, 768 to 1024, 1024 to 576, 886 to 886)) {
            val ledger = GpuMemoryLedger()
            val input = GpuReferenceImages.create(GpuReferenceImages.Kind.MIXED, GpuReferenceImages.Size(width, height))
            var pipeline: ConnectedStageClearGpuPipeline? = null
            DefaultStageClearGpu(egl, ledger) { texture, analysis ->
                ConnectedStageClearGpuPipeline(texture, analysis).also { pipeline = it }
            }.use { engine ->
                engine.openPhoto(input) { false }.use { photo ->
                    for ((index, strength) in listOf(.5, .5, 1.0, 0.0, .5).withIndex()) {
                        val beforeAnalysis = engine.photoAnalysisCount
                        val beforeR7 = pipeline?.r7FragmentPassCount ?: 0
                        val beforeGlow = pipeline?.glowPreparationCount ?: 0
                        engine.renderForTest(photo, GpuRenderParams(strength, PhotoAdjustments.NONE), GpuStageTap.Point.SHOULDER)
                        val passes = checkNotNull(pipeline)
                        assertEquals(beforeAnalysis + 1, engine.photoAnalysisCount)
                        assertTrue(passes.r7FragmentPassCount > beforeR7)
                        assertEquals(beforeGlow + 1, passes.glowPreparationCount)
                        assertTrue(passes.resourceReuseCount > 0)
                        assertTrue(passes.coefficientTableCount > 0)
                        assertTrue(ledger.peakAllocatedBytes <= GpuMemoryLedger.PREVIEW_BUDGET)
                        Log.i("GpuResourceReuse", "shape=${width}x$height frame=$index peak=${ledger.peakAllocatedBytes} " +
                            "persistent=${ledger.allocatedBytes} allocations=${passes.resourceAllocationCount} " +
                            "reuses=${passes.resourceReuseCount} coefficientTables=${passes.coefficientTableCount}")
                    }
                }
                assertEquals(0L, ledger.allocatedBytes)
                assertEquals(0, ledger.textureCount)
            }
        }
    }

    private fun gpu(block: (EglContexts) -> Unit) = GlThread().use { thread ->
        thread.call {
            EglContexts(thread, InstrumentationRegistry.getInstrumentation().targetContext.assets).use { egl ->
                GlCapabilities.inspect(egl).requireSupported()
                block(egl)
            }
        }
    }
}
