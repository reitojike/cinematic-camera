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
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue

/** Synthetic emulator input → real product GPU owner. No physical device, camera or shared save. */
class GpuProductInputAndroidTest {
    @Test fun originalIsReadOnlyAtOpenAndAllPreviewOwnersReleaseAcrossPhotosAndResume() = exercise(256, 192)

    @Test fun optIn24MpOpenEditCompareAndResumeKeepGeometryAndOwners() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("gpuProduct24") == "true")
        exercise(6000, 4000)
    }

    @Test fun memoryDiagnosticDefaultGpuEmitsTextureLedgerAndReleasesOwners() {
        assumeTrue(dev.cinematic.p0.BuildConfig.BUILD_TYPE == "memoryDiagnostic")
        check(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Synthetic emulator only" }
        check(dev.cinematic.p0.memory.MemoryAdmissionDiagnostic.enabled)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val setting = DevelopEngineSetting(instrumentation.targetContext)
        assertFalse(setting.visible)
        assertEquals(DevelopEngine.GPU, setting.current())
        assertEquals(DevelopEngine.GPU, setting.beginExport())
        val started = SystemClock.elapsedRealtime()
        exercise(256, 192, useConfiguredEngine = true)
        // Read only this synthetic test process and fixed diagnostic tag; never clear the shared log buffer.
        val command = "logcat -d --pid=${android.os.Process.myPid()} -s CinematicMemory:I *:S"
        val lines = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readLines() }
        val pattern = Regex("GPU_TEXTURE_LEDGER elapsedMs=(\\d+).*? reservedBytes=(\\d+)")
        val reservations = lines.mapNotNull { line -> pattern.find(line)?.let { match ->
            if (match.groupValues[1].toLong() >= started) match.groupValues[2].toLong() else null
        } }
        assertTrue("Real GPU texture reservations must be recorded in this run", reservations.any { it > 0 })
        assertEquals("Last recorded ledger must be released", 0L, reservations.last().toLong())
    }

    private fun exercise(width: Int, height: Int, useConfiguredEngine: Boolean = false) {
        check(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Synthetic emulator only" }
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File.createTempFile("gpu-product-private-", ".tmp", target.cacheDir.canonicalFile)
        var heldProvider: Provider? = null
        var heldApp: Context? = null
        var heldRenderer: ManualTrialPhotoRollRenderer? = null
        var heldDrain: HandlerThread? = null
        var heldReader: ImageReader? = null
        var session: GpuPhotoRollSession? = null
        var primary: Throwable? = null
        try {
            check(root.delete() && root.mkdir())
            val fixtureContext = object : ContextWrapper(target) {
                override fun getCacheDir(): File = root
            }
            val provider = Provider(fixtureContext, width, height).also { heldProvider = it }
            val resolver = ContentResolver.wrap(provider)
            val app = object : ContextWrapper(target) {
                override fun getApplicationContext(): Context = this
                override fun getContentResolver(): ContentResolver = resolver
                override fun getNoBackupFilesDir(): File = root
            }
            val ready = PhotoRollResources(true, 0, false, 2L * 1024 * 1024 * 1024,
                PhotoRollCameraSnapshot(false, false, false, false), true, true)
            val queue = AndroidAutoDevelopQueue.get(app)
            queue.manualOriginalIo { Unit }.get(60, TimeUnit.SECONDS)
            heldApp = app // Cleanup must not initialize/restart a queue whose startup failed.
            DevelopExecution.updateConditions(ready.developConditions(true))
            val configuredEngine = if (useConfiguredEngine) DevelopEngineSetting(app).current() else DevelopEngine.GPU
            assertEquals(DevelopEngine.GPU, configuredEngine)
            val renderer = ManualTrialPhotoRollRenderer(app, { ready }, developEngine = configuredEngine).also { heldRenderer = it }
            val repository = PhotoRollRepository(resolver, target.packageName)
            val drain = HandlerThread("synthetic-gpu-surface").also { heldDrain = it; it.start() }
            val imageReader = ImageReader.newInstance(320, 240, PixelFormat.RGBA_8888, 3).also { heldReader = it }
            imageReader.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, Handler(drain.looper))
            // Second photo and foreground re-open must each perform a fresh strong original check.
            for ((index, id) in listOf(1, 2, 2).withIndex()) {
                renderer.discardTransientInputs()
                AndroidManualOriginalTrialSaves.discardPreview(app).get(30, TimeUnit.SECONDS)
                GpuPhotoRollRetiredOwners.releaseBeforeOpen()
                val opensBefore = provider.opens.get()
                val admitted = AtomicInteger()
                val loaded = renderer.openGpu(repository, provider.uri(id), false,
                    CancellationSignal(), GpuInputRequestDeadline(nowMillis = SystemClock::elapsedRealtime),
                    { failure -> assertNull(failure); admitted.incrementAndGet() }) { PhotoAdjustmentPolicy.defaults }
                assertEquals(1, admitted.get())
                assertEquals(width, loaded.geometry.rawWidth); assertEquals(height, loaded.geometry.rawHeight)
                assertEquals(width, loaded.geometry.sourceWidth); assertEquals(height, loaded.geometry.sourceHeight)
                assertEquals(if (id == 1) 1 else 6, loaded.geometry.orientation)
                assertEquals(if (id == 1) width else height, loaded.geometry.outputWidth)
                assertEquals(if (id == 1) height else width, loaded.geometry.outputHeight)
                assertTrue(loaded.input.width in 1..1024 && loaded.input.height in 1..1024)
                assertTrue(loaded.input.width.toLong() * loaded.input.height <= 1024L * 768)
                assertTrue(provider.opens.get() > opensBefore)
                val prepared = prepareMetadataWhileGpuOwnsInput(app, renderer, loaded, ready, provider, firstPrepare = index < 2)
                assertTrue(prepared.matches(loaded.original.reference, PhotoRollPreviewMode.MANUAL_TRIAL))
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
        } catch (failure: Throwable) { primary = failure; throw failure }
        finally {
            var cleanup: Throwable? = null
            fun attempt(action: () -> Unit) {
                try { action() } catch (failure: Throwable) {
                    val first = cleanup
                    if (first == null) cleanup = failure else if (first !== failure) first.addSuppressed(failure)
                }
            }
            attempt { session?.close() }
            attempt { session?.let { check(it.renderer.awaitClosed(10_000)) } }
            attempt { heldRenderer?.discardTransientInputs() }
            attempt { heldApp?.let { AndroidManualOriginalTrialSaves.discardPreview(it).get(30, TimeUnit.SECONDS) } }
            attempt { GpuPhotoRollRetiredOwners.releaseBeforeOpen() }
            attempt { heldReader?.close() }
            attempt { heldDrain?.let { it.quitSafely(); it.join(5_000); check(!it.isAlive) } }
            attempt { heldProvider?.verifyUnchanged() }
            attempt { heldProvider?.let { assertTrue("all source descriptors closed", it.readers.none { fd -> fd.fileDescriptor.valid() }) } }
            // A failed open may retain a private native/snapshot owner outside this test's FD list.
            // Keep this fixture on any primary or cleanup failure; remove it only after success.
            if (primary == null && cleanup == null) attempt {
                check(root.canonicalFile == root.absoluteFile && root.parentFile.canonicalFile == target.cacheDir.canonicalFile)
                check(root.name.startsWith("gpu-product-private-"))
                check(root.deleteRecursively())
            }
            cleanup?.let { failure ->
                val first = primary
                if (first == null) throw failure
                if (first !== failure) first.addSuppressed(failure)
            }
        }
    }

    /** Hold the real GPU admission while metadata uses only the already verified geometry.
     * A new source has no Authority geometry/request cache, so another preflight would fail here. */
    private fun prepareMetadataWhileGpuOwnsInput(app: Context, renderer: ManualTrialPhotoRollRenderer,
        loaded: ManualTrialPhotoRollRenderer.GpuInput, ready: PhotoRollResources, provider: Provider,
        firstPrepare: Boolean): PhotoRollJpegSaveOwner {
        val source = loaded.original.reference
        val conditions = ready.developConditions(true)
        val storage = AndroidAtomicJournalStorage(File(app.noBackupFilesDir, "manual-original24-requests"),
            ManualOriginalTrialRequests.identity(source), CaptureJournalFile.MANUAL_REQUEST)
        if (firstPrepare) assertNull("new source has no prepared generation", storage.read(ManualOriginalTrialRequestCodec.MAX_BYTES))
        val gpu = checkNotNull(DevelopExecution.tryStartGpuDiagnosticPreviewObserved(conditions).attempt)
        var primary: Throwable? = null
        try {
            val unexpectedCpu = DevelopExecution.tryStartPreviewObserved(conditions)
            if (unexpectedCpu != null) {
                val failure = AssertionError("CPU preview must remain excluded while the GPU input token is held")
                try { check(DevelopExecution.completeAfterResourcesClosed(unexpectedCpu)) }
                catch (closing: Throwable) { if (closing !== failure) failure.addSuppressed(closing) }
                throw failure
            }
            assertFalse("GPU owner must still be admitted", DevelopExecution.shouldStop(gpu))
            val readsBeforeMetadata = provider.opens.get()
            val prepared = renderer.prepareGpuSave(loaded, loaded.values, CancellationSignal())
            assertEquals("GPU metadata must not reopen/re-preflight the original", readsBeforeMetadata, provider.opens.get())
            assertFalse("metadata must not stop the GPU input owner", DevelopExecution.shouldStop(gpu))
            val handle = checkNotNull(AndroidManualOriginalTrialSaves.knownPrepared(source))
            val request = handle.request
            val bytes = checkNotNull(storage.read(ManualOriginalTrialRequestCodec.MAX_BYTES))
            assertEquals(request, ManualOriginalTrialRequestCodec.decode(bytes))
            assertEquals(source, request.source)
            assertEquals(loaded.geometry, handle.geometry)
            assertEquals(loaded.values.strength, request.strength, 0.0)
            assertEquals(PhotoAdjustmentPolicy.adjustments(loaded.values), request.adjustments)
            assertEquals(ManualOriginalTrialSavePhase.READY, handle.state.value.phase)
            assertFalse(handle.hasUnreleasedResources)
            if (firstPrepare) assertEquals(1L, request.generation)

            // A structurally valid but different orientation must fail before a different recipe
            // can allocate/supersede a generation. No save/publication is attempted in this test.
            val wrongGeometry = loaded.geometry.copy(orientation = if (loaded.geometry.orientation == 1) 2 else 1,
                outputWidth = loaded.geometry.sourceWidth, outputHeight = loaded.geometry.sourceHeight)
            val otherValues = PhotoEditValues(.7, 35.0, .5)
            assertNotEquals(loaded.geometry, wrongGeometry)
            assertNotEquals(loaded.values, otherValues)
            try {
                AndroidManualOriginalTrialSaves.prepareGpu(app, source, wrongGeometry, otherValues.strength,
                    conditions, PhotoAdjustmentPolicy.adjustments(otherValues)) { false }.get(30, TimeUnit.SECONDS)
                fail("Changed GPU input geometry must be rejected")
            } catch (failure: ExecutionException) {
                assertTrue("geometry failure must remain explicit", failure.cause is IOException)
                assertEquals("GPU input geometry changed", failure.cause?.message)
            }
            assertSame("failed geometry must not publish a new handle", handle, AndroidManualOriginalTrialSaves.knownPrepared(source))
            val unchanged = checkNotNull(storage.read(ManualOriginalTrialRequestCodec.MAX_BYTES))
            assertArrayEquals("failed geometry must not rewrite source/generation/recipe", bytes, unchanged)
            assertEquals(request, ManualOriginalTrialRequestCodec.decode(unchanged))
            assertEquals(loaded.geometry, handle.geometry)
            val resumed = AndroidManualOriginalTrialSaves.prepareGpu(app, source, loaded.geometry, loaded.values.strength,
                conditions, PhotoAdjustmentPolicy.adjustments(loaded.values)) { false }.get(30, TimeUnit.SECONDS)
            assertSame("original geometry must still reuse its exact generation", handle, resumed)
            assertEquals(request, resumed.request)
            assertArrayEquals(bytes, storage.read(ManualOriginalTrialRequestCodec.MAX_BYTES))
            assertEquals("all GPU metadata remains source-read-free", readsBeforeMetadata, provider.opens.get())
            assertFalse(DevelopExecution.shouldStop(gpu))
            return prepared
        } catch (failure: Throwable) { primary = failure; throw failure }
        finally {
            try { check(DevelopExecution.completeAfterResourcesClosed(gpu)) }
            catch (closing: Throwable) {
                val failure = primary
                if (failure == null) throw closing
                if (failure !== closing) failure.addSuppressed(closing)
            }
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
