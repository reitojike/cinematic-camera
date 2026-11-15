package dev.cinematic.p0.photoroll

import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Debug
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.gpu.GpuPreviewInputSize
import dev.cinematic.p0.jpegrender.original.requireGpuInputSafetyFixture
import dev.cinematic.p0.photoedit.PhotoAdjustmentPolicy
import dev.cinematic.p0.raw.LinearSrgbImage
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Fresh process, synthetic input only. Measurements are evidence, not arbitrary memory tolerances. */
class GpuPreviewOwnerReuseAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun input(): LinearSrgbImage {
        val size = GpuPreviewInputSize.fit(1536, 1024)
        return LinearSrgbImage(size.width, size.height,
            FloatArray(size.width * size.height * 3) { .05f + (it % 251) / 300f })
    }

    @Test fun tenPhotoGenerationsReuseOneContextAndReleaseAllPhotoResources() {
        requireGpuInputSafetyFixture()
        val owner = GpuPhotoRollOwner(context.assets)
        val image = input()
        val samples = JSONArray()
        try {
            repeat(10) { index ->
                lateinit var session: GpuPhotoRollSession
                val start = SystemClock.elapsedRealtimeNanos()
                onMain { session = owner.open() }
                GpuPhotoRollRetiredOwners.releaseBeforeOpen()
                val runtime = checkNotNull(owner.runtimeForDiagnostics)
                present(session, image)
                val presented = SystemClock.elapsedRealtimeNanos()
                onMain { owner.exit(if (index % 2 == 0) GpuPreviewExit.PHOTO else GpuPreviewExit.BACKGROUND) }
                assertTrue(session.renderer.awaitClosed(10_000))
                GpuPhotoRollRetiredOwners.releaseBeforeOpen()
                assertTrue(runtime.reusable)
                assertEquals(1, runtime.contextCreationCount)
                assertNull(session.presented.value)
                // Old generation cannot publish or submit into the new generation.
                session.submit(PhotoAdjustmentPolicy.defaults)
                session.renderer.openPhoto(image)
                SystemClock.sleep(100)
                Runtime.getRuntime().gc(); System.runFinalization(); Runtime.getRuntime().gc()
                SystemClock.sleep(1_000)
                samples.put(JSONObject().put("iteration", index)
                    .put("openToPresentedNanos", presented - start)
                    .put("nativeHeapBytes", Debug.getNativeHeapAllocatedSize())
                    .put("contextCreations", runtime.contextCreationCount))
            }
        } finally {
            onMain { owner.exit(GpuPreviewExit.ACTIVITY_DESTROYED) }
            GpuPhotoRollRetiredOwners.releaseBeforeOpen()
        }
        val output = File(checkNotNull(context.getExternalFilesDir(null)), "additional_test_output/owner-reuse")
        check(output.mkdirs() || output.isDirectory)
        File(output, "ten-generations.json").writeText(JSONObject().put("samples", samples)
            .put("scope", "Activity owner API and real GL Surface; synthetic input, not full editor navigation or scanout").toString())
    }

    @Test fun memoryPressureDestroysAndNextOpenCreatesFreshContext() {
        requireGpuInputSafetyFixture()
        val owner = GpuPhotoRollOwner(context.assets)
        val image = input()
        try {
            lateinit var first: GpuPhotoRollSession
            onMain { first = owner.open() }
            present(first, image)
            val previous = checkNotNull(owner.runtimeForDiagnostics)
            onMain { owner.exit(GpuPreviewExit.MEMORY_PRESSURE) }
            GpuPhotoRollRetiredOwners.releaseBeforeOpen()
            assertTrue(previous.awaitClosed(10_000))
            lateinit var second: GpuPhotoRollSession
            onMain { second = owner.open() }
            assertNotSame(previous, owner.runtimeForDiagnostics)
            present(second, image)
            // Saving still performs a full context/thread shutdown, not a warm photo retirement.
            second.close()
            assertTrue(second.renderer.awaitClosed(10_000))
            assertFalse(checkNotNull(owner.runtimeForDiagnostics).reusable)
        } finally {
            onMain { owner.exit(GpuPreviewExit.ACTIVITY_DESTROYED) }
            GpuPhotoRollRetiredOwners.releaseBeforeOpen()
        }
    }

    @Test fun errorRacingWarmRetirementInvalidatesAnAlreadyReservedNextGeneration() {
        requireGpuInputSafetyFixture()
        val runtime = dev.cinematic.p0.gpu.preview.GpuPreviewRuntime(context.assets)
        val entered = java.util.concurrent.CountDownLatch(1)
        val continueFailure = java.util.concurrent.CountDownLatch(1)
        val nextFailure = java.util.concurrent.CountDownLatch(1)
        val first = dev.cinematic.p0.gpu.preview.GpuPreviewRenderer(context.assets,
            sharedRuntime = runtime, recoverContextOnSubmit = false,
            engineFactory = { _, _ ->
                entered.countDown()
                check(continueFailure.await(10, java.util.concurrent.TimeUnit.SECONDS))
                throw dev.cinematic.p0.gpu.GpuContextLostException("Synthetic error after warm retirement")
            })
        var second: dev.cinematic.p0.gpu.preview.GpuPreviewRenderer? = null
        try {
            first.openPhoto(input())
            assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
            first.retireForReuse()
            second = dev.cinematic.p0.gpu.preview.GpuPreviewRenderer(context.assets,
                sharedRuntime = runtime, recoverContextOnSubmit = false,
                onError = { nextFailure.countDown() })
            second.openPhoto(input())
            continueFailure.countDown()
            assertTrue(nextFailure.await(10, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(first.awaitClosed(10_000))
            assertTrue(second.awaitClosed(10_000))
            assertFalse(runtime.reusable)
            assertEquals(1, runtime.contextCreationCount)
        } finally {
            continueFailure.countDown()
            first.close(); second?.close(); runtime.close()
            assertTrue(runtime.awaitClosed(10_000))
        }
    }

    @Test fun reservedIdleGenerationIsRetiredBeforeFailedRuntimeThreadExits() {
        requireGpuInputSafetyFixture()
        val runtime = dev.cinematic.p0.gpu.preview.GpuPreviewRuntime(context.assets)
        val entered = java.util.concurrent.CountDownLatch(1)
        val proceed = java.util.concurrent.CountDownLatch(1)
        val first = dev.cinematic.p0.gpu.preview.GpuPreviewRenderer(context.assets,
            sharedRuntime = runtime, recoverContextOnSubmit = false, engineFactory = { _, _ ->
                entered.countDown()
                check(proceed.await(10, java.util.concurrent.TimeUnit.SECONDS))
                throw dev.cinematic.p0.gpu.GpuContextLostException("Synthetic delayed failure")
            })
        first.openPhoto(input())
        assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
        first.retireForReuse()
        val idle = dev.cinematic.p0.gpu.preview.GpuPreviewRenderer(context.assets, sharedRuntime = runtime)
        proceed.countDown()
        assertTrue(first.awaitClosed(10_000))
        assertTrue(idle.awaitClosed(10_000))
        assertTrue(runtime.awaitClosed(10_000))
        ImageReader.newInstance(64, 48, PixelFormat.RGBA_8888, 2).use { reader ->
            onMain {
                idle.openPhoto(input())
                idle.attachSurface(reader.surface, 64, 48)
                idle.setStarted(true)
                idle.retireForReuse()
            }
        }
        assertFalse(runtime.reusable)
    }

    @Test fun runtimeCannotExitBetweenLeaseReservationAndRetirementRegistration() {
        requireGpuInputSafetyFixture()
        val runtime = dev.cinematic.p0.gpu.preview.GpuPreviewRuntime(context.assets)
        val reserved = runtime.lease()
        runtime.close()
        assertFalse(runtime.awaitClosed(50))
        runtime.onRetiring(reserved) { runtime.thread.post { runtime.release(reserved, null) } }
        assertTrue(runtime.awaitClosed(10_000))
    }

    private fun present(session: GpuPhotoRollSession, image: LinearSrgbImage) {
        ImageReader.newInstance(64, 48, PixelFormat.RGBA_8888, 2).use { reader ->
            session.renderer.attachSurface(reader.surface, 64, 48)
            session.renderer.setStarted(true)
            session.submit(PhotoAdjustmentPolicy.defaults)
            session.renderer.openPhoto(image)
            val deadline = SystemClock.uptimeMillis() + 60_000
            while (session.presented.value == null) {
                session.failure.value?.let { throw AssertionError("Owner reuse failed", it) }
                check(SystemClock.uptimeMillis() < deadline) { "Owner reuse presentation timed out" }
                reader.acquireLatestImage()?.close()
                SystemClock.sleep(20)
            }
            reader.acquireLatestImage()?.close()
            assertTrue(session.renderer.detachSurface(reader.surface))
        }
    }
}
