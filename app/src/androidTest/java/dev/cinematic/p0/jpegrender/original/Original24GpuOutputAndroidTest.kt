package dev.cinematic.p0.jpegrender.original

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.gpu.EglContexts
import dev.cinematic.p0.gpu.GlThread
import dev.cinematic.p0.gpu.export.GpuExportRenderer
import dev.cinematic.p0.raw.original.OriginalRaster
import dev.cinematic.p0.rawcapture.GpuSaveFailureReason
import dev.cinematic.p0.rawcapture.GpuSaveRenderException
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.pow
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in synthetic emulator coverage only; never automatically uses a physical device. */
@RunWith(AndroidJUnit4::class)
class Original24GpuOutputAndroidTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private val assets get() = app.assets

    @Before fun requireOptInEmulator() {
        requireGpuSaveSafetyFixture()
    }

    private val raster = object : OriginalRaster {
        override val width = 257
        override val height = 513
        override fun rows(stop: () -> Boolean, consume: (Int, FloatArray) -> Unit) {
            for (y in 0 until height) {
                if (stop()) throw CancellationException()
                consume(y, FloatArray(width * 3) { x -> ((x * 13 + y * 7) % 1024) / 1023f })
            }
        }
    }
    private fun gains(warmth: Double): DoubleArray {
        val red = 2.0.pow(warmth / 200)
        val blue = 2.0.pow(-warmth / 200)
        val norm = .2126 * red + .7152 + .0722 * blue
        return doubleArrayOf(red / norm, 1 / norm, blue / norm)
    }

    @Test fun actualRendererRowsArePassedUnmodifiedForNeutralAndWarmExposure() {
        for (strength in listOf(0.0, .75)) for (warmth in listOf(0.0, 60.0)) {
            val ev = if (warmth == 0.0) 0.0 else .7
            val gains = gains(warmth)
            val expected = IntArray(raster.width * raster.height)
            GlThread().use { thread -> thread.call {
                EglContexts(thread, assets).use { egl ->
                    GpuExportRenderer(egl).render(raster, ev, gains, strength) { y, row ->
                        row.copyInto(expected, y * raster.width)
                    }
                }
            } }
            val actual = IntArray(expected.size)
            val caller = Thread.currentThread()
            var next = 0
            val owner = Original24GpuOutput(assets)
            owner.render(raster, ev, gains, strength, { false }) { y, row ->
                assertNotEquals(caller, Thread.currentThread())
                assertEquals(next++, y)
                row.copyInto(actual, y * raster.width)
            }
            assertEquals(raster.height, next)
            assertArrayEquals(expected, actual)
            assertFalse(owner.hasUnreleasedResources)
            owner.close()
        }
    }

    @Test fun consumerFailureDrainsAndIsRetryableOnlyAfterClose() {
        val owner = Original24GpuOutput(assets)
        try {
            owner.render(raster, 0.0, gains(0.0), .5, { false }) { _, _ -> throw IOException("synthetic") }
            fail()
        } catch (failure: GpuSaveRenderException) {
            assertEquals(GpuSaveFailureReason.RENDER_FAILED, failure.reason)
            assertTrue(failure.cleanupConfirmed)
        }
        assertFalse(owner.hasUnreleasedResources)
        owner.close()
    }

    @Test fun interruptionDrainsBeforeReturningAndNeverAuthorizesGpuRetry() {
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val blocked = object : OriginalRaster by raster {
            override fun rows(stop: () -> Boolean, consume: (Int, FloatArray) -> Unit) {
                entered.countDown()
                check(resume.await(10, TimeUnit.SECONDS))
                raster.rows(stop, consume)
            }
        }
        var result: Throwable? = null
        val owner = Original24GpuOutput(assets)
        val caller = Thread {
            try {
                owner.render(blocked, 0.0, gains(0.0), .5, { false }) { _, _ -> }
                fail()
            } catch (failure: Throwable) { result = failure }
            finally { completed.countDown() }
        }
        caller.start()
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            caller.interrupt()
            assertFalse(completed.await(100, TimeUnit.MILLISECONDS))
        } finally { resume.countDown() }
        assertTrue(completed.await(30, TimeUnit.SECONDS))
        caller.join()
        assertTrue(result is CancellationException)
        assertFalse(owner.hasUnreleasedResources)
        owner.close()
    }
}
