package dev.cinematic.p0.jpegrender.original

import android.content.res.AssetManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.gpu.*
import dev.cinematic.p0.raw.original.OriginalRaster
import dev.cinematic.p0.raw.original.originalCheckpoint
import dev.cinematic.p0.rawcapture.GpuSaveFailureReason
import dev.cinematic.p0.rawcapture.GpuSaveRenderException
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** #347: diagnostic reporting must not replace a render failure or erase unknown cleanup.
 * Real GPU adapter with a small in-memory raster, not factory/DNG/JPEG/FGS acceptance.
 * Callback exceptions and suppressed cleanup evidence are synthetic; neither a driver loss nor
 * an actual GL cleanup failure is induced. The conservative held owner owns no files or media.
 */
@RunWith(AndroidJUnit4::class)
class GpuSaveObserverSafetyAndroidTest {
    @Test fun renderFailureRemainsPrimaryWhenReleaseObserverAlsoFails() = runCase(cancel = false, unknownCleanup = false)
    @Test fun suppressedCleanupFailureCannotBecomeConfirmedWhenReleaseObserverFails() = runCase(cancel = false, unknownCleanup = true)
    @Test fun cancelledRenderKeepsUnknownCleanupWhenReleaseObserverFails() = runCase(cancel = true, unknownCleanup = true)

    @Test fun releaseObserverRethrowingSameFailureKeepsCleanupEvidence() =
        runCase(cancel = false, unknownCleanup = true, rethrowSameFailure = true)

    @Test fun releaseObserverFailureAfterSuccessfulRenderStillCompletesAndCloses() = withGpu { assets ->
        val observerFailure = IllegalStateException("Synthetic release observer failure after success")
        val callbackThread = AtomicReference<Thread>()
        val releaseCalls = AtomicInteger()
        val writtenRows = AtomicInteger()
        val releasedBalance = AtomicReference<Original24GpuOutput.TextureBalance>()
        val observer = object : Original24GpuOutput.DiagnosticObserver {
            override fun bandComplete(boundary: String, origin: Int, rows: Int, balance: Original24GpuOutput.TextureBalance) {
                callbackThread.set(Thread.currentThread())
            }
            override fun rendererReleased(balance: Original24GpuOutput.TextureBalance) {
                releasedBalance.set(balance)
                releaseCalls.incrementAndGet()
                throw observerFailure
            }
        }
        val output = Original24GpuOutput(assets)
        val failure = Original24GpuOutput.observeForDiagnostics(observer).use {
            assertThrows(GpuSaveRenderException::class.java) {
                output.render(raster(), .3, doubleArrayOf(1.1, 1.0, .9), .65, { false }) { y, _ ->
                    assertEquals(writtenRows.getAndIncrement(), y)
                }
            }
        }
        assertEquals(258, writtenRows.get())
        assertEquals(1, releaseCalls.get())
        assertEquals(0L, requireNotNull(releasedBalance.get()).bytes)
        assertEquals(0, requireNotNull(releasedBalance.get()).textures)
        assertFalse(requireNotNull(callbackThread.get()).isAlive)
        assertSame(observerFailure, failure.cause)
        assertEquals(GpuSaveFailureReason.RENDER_FAILED, failure.reason)
        assertTrue("No earlier render/cleanup failure exists to erase", failure.cleanupConfirmed)
        assertFalse(output.hasUnreleasedResources)
        output.close()
    }

    private fun runCase(cancel: Boolean, unknownCleanup: Boolean, rethrowSameFailure: Boolean = false) = withGpu { assets ->
        val cleanupFailure = IOException("Synthetic unconfirmed renderer cleanup")
        val renderFailure = if (cancel) CancellationException("Synthetic render cancellation")
            else GpuContextLostException("Synthetic render failure, not driver context loss")
        if (unknownCleanup) renderFailure.addSuppressed(cleanupFailure)
        val observerFailure = if (rethrowSameFailure) renderFailure
            else IllegalStateException("Synthetic release observer failure")
        val stopped = AtomicBoolean()
        val callbackThread = AtomicReference<Thread>()
        val bandCalls = AtomicInteger()
        val releaseCalls = AtomicInteger()
        val writtenRows = AtomicInteger()
        val releasedBalance = AtomicReference<Original24GpuOutput.TextureBalance>()
        val observer = object : Original24GpuOutput.DiagnosticObserver {
            override fun bandComplete(boundary: String, origin: Int, rows: Int, balance: Original24GpuOutput.TextureBalance) {
                assertEquals("B1", boundary); assertEquals(0, origin); assertEquals(256, rows)
                assertTrue(balance.bytes > 0 && balance.textures > 0)
                callbackThread.set(Thread.currentThread())
                bandCalls.incrementAndGet()
                stopped.set(cancel)
                throw renderFailure
            }
            override fun rendererReleased(balance: Original24GpuOutput.TextureBalance) {
                releasedBalance.set(balance)
                releaseCalls.incrementAndGet()
                throw observerFailure
            }
        }
        val output = Original24GpuOutput(assets)
        val failure = Original24GpuOutput.observeForDiagnostics(observer).use {
            try {
                output.render(raster(), .3, doubleArrayOf(1.1, 1.0, .9), .65, stopped::get) { _, _ -> writtenRows.incrementAndGet() }
                null
            } catch (caught: Throwable) { caught }
        }
        assertNotNull("Both callback failures must be reported, never treated as success", failure)
        assertEquals(1, bandCalls.get()); assertEquals(1, releaseCalls.get())
        assertEquals(0, writtenRows.get())
        assertEquals(0L, requireNotNull(releasedBalance.get()).bytes)
        assertEquals(0, requireNotNull(releasedBalance.get()).textures)
        assertFalse("GL thread must finish despite both failures", requireNotNull(callbackThread.get()).isAlive)
        if (cancel) {
            assertTrue(failure is CancellationException)
        } else {
            assertTrue(failure is GpuSaveRenderException)
            val reported = failure as GpuSaveRenderException
            assertEquals(GpuSaveFailureReason.CONTEXT_LOST, reported.reason)
            assertFalse("Observer failure cannot turn cleanup into retry permission", reported.cleanupConfirmed)
        }
        assertSame("The original render failure must remain the reported cause", renderFailure, requireNotNull(failure).cause)
        val expected = buildList<Throwable> {
            if (unknownCleanup) add(cleanupFailure)
            if (observerFailure !== renderFailure) add(observerFailure)
        }
        assertEquals(expected, renderFailure.suppressed.toList())
        assertTrue("Unknown cleanup must remain held even when the texture balance is zero", output.hasUnreleasedResources)
        repeat(2) {
            assertThrows(IOException::class.java) { output.close() }
            assertTrue("A later close cannot silently authorize retry", output.hasUnreleasedResources)
        }
    }

    private fun raster() = object : OriginalRaster {
        override val width = 64
        override val height = 258
        override fun rows(stop: () -> Boolean, consume: (Int, FloatArray) -> Unit) {
            repeat(height) { y ->
                originalCheckpoint(stop)
                consume(y, FloatArray(width * 3) { x -> .01f + ((x * 13 + y * 7) % 80) / 100f })
            }
        }
    }

    private fun withGpu(run: (AssetManager) -> Unit) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        requireGpuSaveSafetyFixture()
        val capabilities = GlThread().use { thread -> thread.call {
            EglContexts(thread, app.assets).use { GlCapabilities.inspect(it) }
        } }
        assertTrue("Required GPU capabilities: ${capabilities.missing}", capabilities.supported)
        run(app.assets)
    }
}
