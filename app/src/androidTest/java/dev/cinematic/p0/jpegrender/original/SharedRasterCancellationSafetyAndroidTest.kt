package dev.cinematic.p0.jpegrender.original

import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.raw.original.*
import java.io.File
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

/** Retained shared-raster ownership contract, not a CPU image-quality comparison. */
class SharedRasterCancellationSafetyAndroidTest {
    @Test fun fullSizePartialRasterRemainsOwnedUntilWorkersDrainAndExplicitDispose() {
        requireGpuSaveSafetyFixture()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scratch = File(context.noBackupFilesDir, "shared-raster-cancel-${UUID.randomUUID()}")
        val work = OriginalRasterWorkspace(scratch)
        val cancelled = AtomicBoolean(false)
        val workers = ConcurrentHashMap.newKeySet<Thread>()
        val input = object : OriginalRaster {
            override val width = 6000
            override val height = 4000
            override fun rows(stop: () -> Boolean, consume: (Int, FloatArray) -> Unit) {
                val row = FloatArray(width * 3) { .42f }
                repeat(height) { y -> originalCheckpoint(stop); consume(y, row) }
            }
        }
        var primary: Throwable? = null
        try {
            assertThrows(CancellationException::class.java) {
                work.mapParallelRows(input, { cancelled.get() }, 4) { y, row ->
                    workers.add(Thread.currentThread())
                    if (y == 128) cancelled.set(true)
                    originalCheckpoint { cancelled.get() || Thread.currentThread().isInterrupted }
                    row
                }
            }
            val partial = scratch.listFiles().orEmpty().single()
            assertTrue("Ordered rows reached the private partial file", partial.length() > 0)
            assertTrue(partial.length() < 6000L * 4000 * 12)
            assertTrue("Exercise real workers", workers.isNotEmpty())
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            workers.forEach { worker ->
                val remaining = deadline - System.nanoTime()
                assertTrue("Owned worker drain deadline", remaining > 0)
                worker.join(TimeUnit.NANOSECONDS.toMillis(remaining) + 1)
                assertFalse("Owned worker remains alive: ${worker.name}", worker.isAlive)
            }
        } catch (failure: Throwable) { primary = failure; throw failure }
        finally {
            try {
                // Never dispose after unconfirmed close; no recursive test-side deletion.
                work.closePending()
                work.disposeConfirmed()
                assertFalse(scratch.exists())
            } catch (cleanup: Throwable) {
                val failure = primary
                if (failure == null) throw cleanup
                if (failure !== cleanup) failure.addSuppressed(cleanup)
            }
        }
    }
}
