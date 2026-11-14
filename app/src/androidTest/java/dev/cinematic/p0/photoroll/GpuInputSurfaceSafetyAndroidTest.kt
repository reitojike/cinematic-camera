package dev.cinematic.p0.photoroll

import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.gpu.preview.GpuPreviewSnapshot
import dev.cinematic.p0.gpu.preview.GpuPreviewSurface
import dev.cinematic.p0.jpegrender.original.*
import dev.cinematic.p0.photoedit.PhotoEditValues
import dev.cinematic.p0.rawcapture.*
import dev.cinematic.p0.testing.writeSyntheticTrialDng
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** The real Compose surface and GL owner; only input IO is a private native fixture. */
class GpuInputSurfaceSafetyAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    @get:Rule val fixture = SyntheticFixtureRule()

    @Test fun surfaceRecreationAndInputDeliveredWithoutSurfaceRenderOnResume() {
        requireGpuInputSafetyFixture()
        val context = instrumentation.targetContext
        val root = File.createTempFile("gpu-surface-input-", ".tmp", context.cacheDir.canonicalFile)
        check(root.delete() && root.mkdir())
        val input = File(root, "input.dng")
        input.outputStream().use { writeSyntheticTrialDng(it, 64, 48, 1) }
        val source = SharedOriginalReference("content://example.test/gpu-surface/input",
            DngContentVerifier.identify(input.length(), { input.inputStream() }), OriginalGrant.USER_SELECTED)
        val geometry = ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY).use {
            Original24Preflight.inspect(it.fd, CancellationSignal())
        }
        val image = Original24PreviewSession(source, File(root, "native"), {}, geometry) { input.inputStream() }.use {
            it.loadGpuAsShotInput().also { _ -> assertFalse(it.hasUnreleasedResources) }
        }
        val imageBits = image.copyPixels().map { it.toRawBits() }.toIntArray()
        val owner = GpuPhotoRollSession(context.assets)
        val visible = mutableStateOf(true)
        fixture.content = { if (visible.value) GpuPreviewSurface(owner.renderer, Modifier.fillMaxSize()) }
        var succeeded = false
        try {
            ActivityScenario.launch(PhotoRollActivity::class.java).use { scenario ->
                val initial = PhotoEditValues(.5, 0.0, 0.0)
                owner.submit(initial)
                owner.renderer.openPhoto(image)
                await { owner.failure.value?.let { throw AssertionError("GPU surface failure", it) }
                    owner.presented.value == initial && snapshot(owner).windowCount == 1 }
                val before = snapshot(owner)
                // Remove only the Compose surface. This must not close/recreate the photo owner.
                scenario.onActivity { visible.value = false }
                await { snapshot(owner).let { !it.surfaceAttached && it.windowCount == 0 } }
                scenario.onActivity { visible.value = true }
                await { snapshot(owner).let { it.drawCount > before.drawCount && it.windowCount == 1 } }
                assertNull(owner.failure.value)

                scenario.moveToState(Lifecycle.State.CREATED)
                await { snapshot(owner).let { !it.surfaceAttached && it.windowCount == 0 } }
                val paused = snapshot(owner)
                val replacement = PhotoEditValues(.8, 20.0, .5)
                owner.submit(replacement)
                owner.renderer.openPhoto(image)
                val pending = snapshot(owner) // GL barrier after the input mailbox is consumed.
                assertEquals("No presentation without a window", paused.drawCount, pending.drawCount)
                assertEquals(0, pending.windowCount)
                scenario.moveToState(Lifecycle.State.RESUMED)
                await { owner.presented.value == replacement && snapshot(owner).let {
                    it.drawCount > pending.drawCount && it.windowCount == 1 && it.allocatedBytes > 0L } }
                assertNull(owner.failure.value)
                assertArrayEquals(imageBits, image.copyPixels().map { it.toRawBits() }.toIntArray())
                DngContentVerifier.verify(source.fingerprint, { input.inputStream() })
            }
            owner.close()
            assertTrue(owner.renderer.awaitClosed(10_000))
            GpuPhotoRollRetiredOwners.releaseBeforeOpen()
            succeeded = true
        } finally {
            owner.close()
            assertTrue(owner.renderer.awaitClosed(10_000))
            GpuPhotoRollRetiredOwners.releaseBeforeOpen()
            if (succeeded) { assertTrue(input.delete()); assertTrue(root.delete()) }
        }
    }

    private fun snapshot(owner: GpuPhotoRollSession): GpuPreviewSnapshot {
        val queue = LinkedBlockingQueue<GpuPreviewSnapshot>()
        owner.renderer.snapshot { queue.offer(it) }
        return checkNotNull(queue.poll(10, TimeUnit.SECONDS))
    }
    private fun await(condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 30_000
        do {
            instrumentation.waitForIdleSync()
            if (condition()) return
            SystemClock.sleep(20)
        } while (SystemClock.uptimeMillis() < until)
        error("GPU surface condition did not complete")
    }
}
