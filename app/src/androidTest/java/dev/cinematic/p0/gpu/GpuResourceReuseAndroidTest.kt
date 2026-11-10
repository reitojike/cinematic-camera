package dev.cinematic.p0.gpu

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.gpu.diag.GpuStageTap
import dev.cinematic.p0.gpu.reference.GpuReferenceImages
import dev.cinematic.p0.raw.PhotoAdjustments
import java.nio.ByteBuffer
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** #323: real GL storage ownership, separate normal/measurement modes and bounded retention. */
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

    @Test fun bothModesMatchAcrossFourShapesAndReleaseTheirPersistentStorage() = gpu { egl ->
        for ((width, height) in listOf(1024 to 768, 768 to 1024, 1024 to 576, 886 to 886)) {
            val input = GpuReferenceImages.create(GpuReferenceImages.Kind.MIXED, GpuReferenceImages.Size(width, height))
            var normalHashes: List<String>? = null
            for (mode in GpuFrameMode.entries) {
                val ledger = GpuMemoryLedger()
                val hashes = mutableListOf<String>()
                var pipeline: ConnectedStageClearGpuPipeline? = null
                DefaultStageClearGpu(egl, ledger, mode) { texture, analysis ->
                    val connected = ConnectedStageClearGpuPipeline(texture, analysis, mode).also { pipeline = it }
                    // Test the untapped production/measurement path. Read only its borrowed final
                    // output; a normal stage tap would intentionally invalidate the product caches.
                    object : StageClearGpuPipeline {
                        override fun render(params: GpuRenderParams, tap: ((GpuStageTap.Point, GpuTexture) -> Unit)?,
                            consume: (GpuTexture, GpuDecisions) -> Unit) = connected.render(params) { output, decisions ->
                            tap?.invoke(GpuStageTap.Point.SHOULDER, output)
                            consume(output, decisions)
                        }
                        override fun close() = connected.close()
                    }
                }.use { engine ->
                    engine.openPhoto(input) { false }.use { photo ->
                        val analysis = photo.analysis
                        assertEquals(1, engine.photoAnalysisCount)
                        for ((index, strength) in listOf(.5, .5, 1.0, 0.0, .5).withIndex()) {
                            val beforeR7 = pipeline?.r7FragmentPassCount ?: 0
                            val beforeGlow = pipeline?.glowPreparationCount ?: 0
                            val image = engine.renderForTest(photo, GpuRenderParams(strength, PhotoAdjustments.NONE),
                                GpuStageTap.Point.SHOULDER)
                            val bytes = ByteBuffer.allocate(image.pixelCount * 3 * 4)
                            repeat(image.pixelCount * 3) { bytes.putInt(image[it].toRawBits()) }
                            hashes += MessageDigest.getInstance("SHA-256").digest(bytes.array())
                                .joinToString("") { "%02x".format(it) }
                            val passes = checkNotNull(pipeline)
                            assertEquals("$mode initial photo analysis only", 1, engine.photoAnalysisCount)
                            assertSame(analysis, photo.analysis)
                            if (index == 0 || mode == GpuFrameMode.FULL_RECOMPUTE) {
                                assertTrue(passes.r7FragmentPassCount > beforeR7)
                                assertEquals(beforeGlow + 1, passes.glowPreparationCount)
                            } else {
                                assertEquals(beforeR7, passes.r7FragmentPassCount)
                                assertEquals(beforeGlow, passes.glowPreparationCount)
                            }
                            assertTrue(passes.resourceReuseCount > 0)
                            assertTrue(passes.coefficientTableCount > 0)
                            assertTrue(ledger.peakAllocatedBytes <= GpuMemoryLedger.PREVIEW_BUDGET)
                            Log.i("GpuResourceReuse", "mode=$mode shape=${width}x$height frame=$index peak=${ledger.peakAllocatedBytes} " +
                                "persistent=${ledger.allocatedBytes} allocations=${passes.resourceAllocationCount} " +
                                "reuses=${passes.resourceReuseCount} coefficientTables=${passes.coefficientTableCount} " +
                                "photoAnalyses=${engine.photoAnalysisCount} r7Passes=${passes.r7FragmentPassCount} glowPreparations=${passes.glowPreparationCount}")
                        }
                    }
                    assertEquals(0L, ledger.allocatedBytes)
                    assertEquals(0, ledger.textureCount)
                    Log.i("GpuResourceReuse", "mode=$mode shape=${width}x$height closeBytes=${ledger.allocatedBytes}")
                }
                if (mode == GpuFrameMode.NORMAL) normalHashes = hashes
                else assertEquals("${width}x$height normal/full raw-bit hashes", normalHashes, hashes)
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
