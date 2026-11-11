package dev.cinematic.p0.photoroll

import android.content.*
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.PixelFormat
import android.media.ImageReader
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.gpu.*
import dev.cinematic.p0.gpu.preview.GpuInputRequestDeadline
import dev.cinematic.p0.gpu.preview.GpuPreviewSnapshot
import dev.cinematic.p0.photoedit.*
import dev.cinematic.p0.rawcapture.*
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

/** Synthetic emulator input → real product GPU owner. No physical device, camera or shared save. */
class GpuProductInputAndroidTest {
    @Test fun originalIsReadOnlyAtOpenAndAllPreviewOwnersReleaseAcrossPhotosAndResume() {
        check(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Synthetic emulator only" }
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val provider = Provider(target)
        val resolver = ContentResolver.wrap(provider)
        val root = File.createTempFile("gpu-product-private-", ".tmp", target.cacheDir)
        check(root.delete() && root.mkdir())
        val app = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getContentResolver(): ContentResolver = resolver
            override fun getNoBackupFilesDir(): File = root
        }
        val ready = PhotoRollResources(true, 0, false, 2L * 1024 * 1024 * 1024,
            PhotoRollCameraSnapshot(false, false, false, false), true, true)
        val queue = AndroidAutoDevelopQueue.get(app)
        queue.manualOriginalIo { Unit }.get(60, TimeUnit.SECONDS)
        DevelopExecution.updateConditions(ready.developConditions(true))
        val renderer = ManualTrialPhotoRollRenderer(app, { ready }, developEngine = DevelopEngine.GPU)
        val repository = PhotoRollRepository(resolver, target.packageName)
        val drain = HandlerThread("synthetic-gpu-surface").apply { start() }
        val imageReader = ImageReader.newInstance(320, 240, PixelFormat.RGBA_8888, 3)
        imageReader.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, Handler(drain.looper))
        var session: GpuPhotoRollSession? = null
        try {
            // Second photo and foreground re-open must each perform a fresh strong original check.
            for (id in listOf(1, 2, 2)) {
                renderer.discardTransientInputs()
                AndroidManualOriginalTrialSaves.discardPreview(app).get(30, TimeUnit.SECONDS)
                GpuPhotoRollRetiredOwners.releaseBeforeOpen()
                val opensBefore = provider.opens.get()
                val admitted = AtomicInteger()
                val loaded = renderer.openGpu(repository, provider.uri(id), false,
                    CancellationSignal(), GpuInputRequestDeadline(nowMillis = SystemClock::elapsedRealtime),
                    { failure -> assertNull(failure); admitted.incrementAndGet() }) { PhotoAdjustmentPolicy.defaults }
                assertEquals(1, admitted.get())
                assertTrue(provider.opens.get() > opensBefore)
                assertTrue(loaded.save.matches(loaded.original.reference, PhotoRollPreviewMode.MANUAL_TRIAL))
                val openedReads = provider.opens.get()
                val current = GpuPhotoRollSession(target.assets).also { session = it }
                current.renderer.attachSurface(imageReader.surface, 320, 240)
                current.renderer.setStarted(true)
                current.submit(loaded.values)
                current.renderer.openPhoto(loaded.input)
                await { current.failure.value?.let { throw AssertionError("GPU preview failed", it) }; current.presented.value == loaded.values }
                for (values in listOf(PhotoEditValues(.7, 0.0, 0.0), PhotoEditValues(.7, 35.0, 0.0), PhotoEditValues(.7, 35.0, .5))) {
                    current.submit(values)
                    await { current.presented.value == values }
                }
                val before = snapshot(current)
                current.submit(PhotoEditValues(.7, 35.0, .5), before = true)
                await { snapshot(current).drawCount > before.drawCount }
                assertEquals("frames/comparison never reopen the original", openedReads, provider.opens.get())
                provider.verifyUnchanged()
                current.renderer.setStarted(false)
                assertTrue(current.renderer.detachSurface(imageReader.surface))
                val stopped = snapshot(current)
                assertEquals(0L, stopped.allocatedBytes)
                assertEquals(0, stopped.windowCount)
                current.close()
                assertTrue("close includes native inputs and GL thread exit", current.renderer.awaitClosed(10_000))
                session = null
                GpuPhotoRollRetiredOwners.releaseBeforeOpen()
            }
            assertEquals(0, provider.writes.get())
        } finally {
            session?.close()
            session?.renderer?.awaitClosed(10_000)
            renderer.discardTransientInputs()
            AndroidManualOriginalTrialSaves.discardPreview(app).get(30, TimeUnit.SECONDS)
            GpuPhotoRollRetiredOwners.releaseBeforeOpen()
            imageReader.close(); drain.quitSafely(); drain.join(5_000)
            provider.verifyUnchanged()
            assertTrue("all source descriptors closed", provider.readers.none { it.fileDescriptor.valid() })
        }
    }

    private fun snapshot(session: GpuPhotoRollSession): GpuPreviewSnapshot {
        val result = LinkedBlockingQueue<GpuPreviewSnapshot>()
        session.renderer.snapshot { result.offer(it) }
        return checkNotNull(result.poll(10, TimeUnit.SECONDS))
    }
    private fun await(predicate: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 30_000
        do { if (predicate()) return; SystemClock.sleep(20) } while (SystemClock.uptimeMillis() < until)
        error("Synthetic GPU presentation did not complete")
    }

    internal class Provider(context: Context, width: Int = 256, height: Int = 192) : ContentProvider() {
        private val packageName = context.packageName
        private val files = (1..2).associateWith { id -> File.createTempFile("gpu-product-$id-", ".dng", context.cacheDir).apply {
            outputStream().use { writeSyntheticRollDng(it, width, height, if (id == 1) 1 else 6) }
        } }
        private val fingerprints = files.mapValues { (_, f) -> DngContentVerifier.identify(f.length(), { f.inputStream() }) }
        val opens = AtomicInteger()
        val writes = AtomicInteger()
        val readers = CopyOnWriteArrayList<ParcelFileDescriptor>()
        init { attachInfo(context, ProviderInfo().apply { authority = "media"; exported = false }) }
        fun verifyUnchanged() = files.forEach { (id, f) -> DngContentVerifier.verify(checkNotNull(fingerprints[id]), { f.inputStream() }) }
        fun uri(id: Int): Uri = ContentUris.withAppendedId(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), id.toLong())
        override fun onCreate() = true
        override fun getType(uri: Uri) = "image/x-adobe-dng"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
            val file = checkNotNull(files[uri.lastPathSegment?.toIntOrNull()])
            return MatrixCursor(checkNotNull(projection)).apply { addRow(Array<Any?>(projection.size) { when (projection[it]) {
                OpenableColumns.DISPLAY_NAME -> "SYNTHETIC.dng"
                OpenableColumns.SIZE -> file.length()
                MediaStore.MediaColumns.MIME_TYPE -> "image/x-adobe-dng"
                MediaStore.MediaColumns.VOLUME_NAME -> MediaStore.VOLUME_EXTERNAL_PRIMARY
                MediaStore.MediaColumns.DATE_MODIFIED -> 100L
                MediaStore.MediaColumns.OWNER_PACKAGE_NAME -> packageName
                MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.IS_TRASHED -> 0
                else -> null
            } }) }
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            check(mode == "r"); opens.incrementAndGet()
            return ParcelFileDescriptor.open(checkNotNull(files[uri.lastPathSegment?.toIntOrNull()]), ParcelFileDescriptor.MODE_READ_ONLY)
                .also { readers += it }
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri? { writes.incrementAndGet(); error("No shared writes") }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int { writes.incrementAndGet(); error("No shared writes") }
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int { writes.incrementAndGet(); error("No shared writes") }
    }
}
