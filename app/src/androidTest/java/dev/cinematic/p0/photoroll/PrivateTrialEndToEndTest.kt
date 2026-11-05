package dev.cinematic.p0.photoroll

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Build
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.rawcapture.*
import dev.cinematic.p0.testing.writeSyntheticTrialDng
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real private factory/UI adapter with synthetic observations and generated DNG only.
 * No CameraX capture, Compose interaction, face protection or Pixel quality/performance assertion. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class PrivateTrialEndToEndTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val ready = PhotoRollResources(true, 0, false, 1024L * 1024 * 1024,
        PhotoRollCameraSnapshot(false, false, false, false), true, true)
    private val allowed get() = ready.developConditions(true)
    @Before fun isolatedOnly() {
        check(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        check(Build.HARDWARE in setOf("goldfish", "ranchu"))
        DevelopExecution.updateConditions(allowed) // Controlled functional observations, not measured device capacity.
    }

    @Test fun twelveMpDngUiAndFreshReadbackUseTheSameUnpublishedJpeg() = Fixture(4000, 3000).use { f ->
        val first = f.render()
        try {
            val handle = f.handle()
            val status = handle.status().get(30, TimeUnit.SECONDS)
            assertEquals(PrivateTrialPhase.READY, status.phase)
            assertEquals(RenderedFaceState.NOT_RUN, status.face)
            assertEquals(RenderedExposureState.DIAGNOSTIC, status.exposure)
            assertEquals(AutoRenderQuality.PRIVATE_TRIAL_1024, status.request.quality)
            assertEquals(768, first.bitmap.width); assertEquals(1024, first.bitmap.height)
            assertEquals(PhotoRollPreviewMode.PRIVATE_TRIAL, first.mode)
            assertNull(first.jpegSave)
            val comparison = requireNotNull(first.comparisonBitmap)
            assertEquals(first.bitmap.width, comparison.width); assertEquals(first.bitmap.height, comparison.height)
            assertNotSame(first.bitmap, comparison)
            assertNotNull(first.comparisonLabel)
            assertNotEquals(pixels(first.bitmap), pixels(comparison))
            // Independent same-source decode verifies orientation/scale and pre-R7/pre-look display pixels.
            val slot = requireNotNull(DevelopExecution.tryStartObserved(allowed))
            try {
                f.localReadOnlySource().use {
                    val raw = dev.cinematic.p0.raw.NativeDngDecoder.decode(it.fd, 1024, CancellationSignal(), 0)
                    val baseline = TrialDisplayPixels.encode(dev.cinematic.p0.raw.LinearSrgbImage(raw.width, raw.height, raw.pixels), 1024) { false }
                    val expected = Bitmap.createBitmap(baseline, raw.width, raw.height, Bitmap.Config.ARGB_8888)
                    try { assertEquals(pixels(expected), pixels(comparison)) } finally { expected.recycle() }
                }
            } finally { DevelopExecution.completeAfterResourcesClosed(slot) }
            val record = f.record()
            val file = f.output()
            val fingerprint = requireNotNull(status.fingerprint)
            DngContentVerifier.verify(fingerprint, { file.inputStream() })
            val bytes = file.readBytes()
            val decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            try { assertEquals(pixels(decoded), pixels(first.bitmap)) } finally { decoded.recycle() }
            val second = f.render()
            try {
                assertNotSame(first.bitmap, second.bitmap)
                assertFalse(first.bitmap.isRecycled)
                assertEquals(pixels(first.bitmap), pixels(second.bitmap))
                assertNotSame(comparison, second.comparisonBitmap)
                assertFalse(comparison.isRecycled)
                assertEquals(pixels(comparison), pixels(requireNotNull(second.comparisonBitmap)))
                assertEquals(record.attemptId, f.record().attemptId)
                assertEquals(status.request, handle.status().get(30, TimeUnit.SECONDS).request)
                assertEquals(fingerprint, handle.status().get(30, TimeUnit.SECONDS).fingerprint)
                DngContentVerifier.verify(fingerprint, { f.output().inputStream() })
            } finally { second.bitmap.recycle(); second.comparisonBitmap?.recycle() }
            f.assertUnpublishedAndOriginal()
        } finally { first.bitmap.recycle(); first.comparisonBitmap?.recycle() }
    }

    @Test fun cancellationCapturePriorityAndChangedPrivateBytesFailClosed() = Fixture(32, 24).use { f ->
        val signal = CancellationSignal()
        val interrupted = f.request { if (it == PhotoRollRenderStage.TRIAL_READBACK) signal.cancel() }
        assertThrows(OperationCanceledException::class.java) { f.renderer.render(interrupted, signal) }
        // This is an explicit new display request for READY output, not proof of RETRYABLE->retry generation.
        val resumed = f.renderer.render(f.request().copy(explicitRetry = true), CancellationSignal())
        assertTrue("Explicit display request failed: $resumed", resumed is PhotoRollRenderResult.Preview)
        (resumed as PhotoRollRenderResult.Preview).let { it.bitmap.recycle(); it.comparisonBitmap?.recycle() }
        val handle = f.handle()
        val before = f.record()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val read = handle.readPreview(allowed) {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
            false
        }
        val reservation = AtomicReference<AutoDevelopCoordinator.CaptureReservation?>()
        val abandoned = AtomicBoolean(false)
        val reserving = FutureTask {
            val owned = DevelopExecution.reserveCapture()
            reservation.set(owned)
            if (abandoned.get()) reservation.getAndSet(null)?.let(DevelopExecution::releaseCapture)
        }
        val reserver = Thread(reserving, "synthetic-capture-priority")
        var reserveStarted = false
        var capture: AutoDevelopCoordinator.CaptureReservation? = null
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            reserver.start(); reserveStarted = true
            reserving.get(5, TimeUnit.SECONDS) // Returns while readback is deliberately blocked.
            capture = requireNotNull(reservation.getAndSet(null))
            assertNull(DevelopExecution.tryStartObserved(allowed))
            DevelopExecution.releaseCapture(capture!!); capture = null
            assertNull(DevelopExecution.tryStartObserved(allowed)) // Stop is not a resource-close acknowledgement.
        } finally {
            abandoned.set(true)
            reservation.getAndSet(null)?.let(DevelopExecution::releaseCapture)
            capture?.let(DevelopExecution::releaseCapture)
            release.countDown()
            if (reserveStarted) {
                // Also collect a reservation that succeeded only after the assertion timed out.
                try { reserving.get(10, TimeUnit.SECONDS) } finally { reserver.join(1000) }
            }
        }
        val rejected = read.handle { delivery, failure ->
            delivery?.close()
            failure != null
        }.get(30, TimeUnit.SECONDS)
        assertTrue("Stopped readback returned a display result", rejected)
        val probe = DevelopExecution.tryStartObserved(allowed)
        assertNotNull(probe)
        assertTrue(DevelopExecution.completeAfterResourcesClosed(probe!!))
        val again = f.render()
        again.bitmap.recycle(); again.comparisonBitmap?.recycle()
        assertEquals(before.attemptId, f.record().attemptId)
        f.assertUnpublishedAndOriginal()
        val delivery = handle.readPreview(allowed) { false }.get(30, TimeUnit.SECONDS)
        val claimed = requireNotNull(delivery.claim())
        val baseline = requireNotNull(claimed.comparisonBitmap)
        handle.requestStop()
        assertFalse(claimed.bitmap.isRecycled); assertFalse(baseline.isRecycled)
        claimed.close(); delivery.close()
        assertTrue(claimed.bitmap.isRecycled); assertTrue(baseline.isRecycled)
        // Fault injection touches this test's closed private JPEG only, never the source.
        f.output().writeBytes("synthetic corrupt JPEG".toByteArray())
        assertThrows(java.util.concurrent.ExecutionException::class.java) {
            handle.readPreview(allowed) { false }.get(30, TimeUnit.SECONDS)
        }
        val failedUi = runCatching { f.renderer.render(f.request(), CancellationSignal()) }
        (failedUi.getOrNull() as? PhotoRollRenderResult.Preview)?.let { it.bitmap.recycle(); it.comparisonBitmap?.recycle() }
        assertFalse(failedUi.getOrNull() is PhotoRollRenderResult.Preview)
        f.assertUnpublishedAndOriginal()
    }

    private fun pixels(bitmap: Bitmap): String {
        assertTrue(bitmap.colorSpace!!.isSrgb)
        assertNotEquals(Bitmap.Config.HARDWARE, bitmap.config)
        val digest = MessageDigest.getInstance("SHA-256")
        val row = IntArray(bitmap.width)
        val packed = ByteBuffer.allocate(bitmap.width * 4)
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            packed.clear(); row.forEach { packed.putInt(it) }; digest.update(packed.array())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private inner class Fixture(width: Int, height: Int) : AutoCloseable {
        private val resolver = context.contentResolver
        private val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "synthetic_vertical_${UUID.randomUUID()}.dng")
            put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Cinematic Camera Synthetic Tests/")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        })!!
        val original: PhotoRollOriginal
        private val directory: File
        private var retained: PrivateTrialHandle? = null
        private val jpegCount = sharedJpegs()
        val renderer = PrivateTrialPhotoRollRenderer(context) { ready }
        init {
            resolver.openOutputStream(uri, "w")!!.use { writeSyntheticTrialDng(it, width, height, 6) }
            check(resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) == 1)
            original = renderer.checkOriginal(PhotoRollRepository(resolver, context.packageName), uri, false, CancellationSignal())
            assertEquals(OriginalGrant.APP_OWNED, original.grant)
            directory = File(File(context.noBackupFilesDir, "raw-captures"), ManualDevelopRequests.identity(original.reference))
            check(!directory.exists()) { "Refuse to reuse an existing operation" }
        }
        fun request(stage: (PhotoRollRenderStage) -> Unit = {}) = PhotoRollRenderRequest(original,
            mode = renderer.mode, maxEdge = renderer.maxEdge, onStage = stage)
        fun render(): PhotoRollRenderResult.Preview {
            val checked = renderer.checkOriginal(PhotoRollRepository(resolver, context.packageName), uri, false, CancellationSignal())
            assertEquals(original.reference, checked.reference)
            val result = renderer.render(request(), CancellationSignal())
            assertTrue("Private UI did not display: $result", result is PhotoRollRenderResult.Preview)
            return result as PhotoRollRenderResult.Preview
        }
        fun handle(): PrivateTrialHandle = retained ?: AndroidAutoDevelopQueue.preparePrivateTrial(context, original.reference)
            .get(30, TimeUnit.SECONDS).also { retained = it }
        fun localReadOnlySource() = resolver.openFileDescriptor(uri, "r")!!
        private fun journal() = AutoDevelopJournal(handle().request.id, handle().request.generation,
            AndroidAtomicJournalStorage(File(directory, "renders"), handle().request.generation.toString(), CaptureJournalFile.AUTO_DEVELOP))
        fun record() = requireNotNull(journal().read())
        fun output(): File {
            val attempt = requireNotNull(record().attemptId)
            check(attempt.matches(Regex("[0-9a-f]{32}")))
            return File(directory, "auto-attempts/$attempt/renders/1/output.jpg").canonicalFile.also {
                check(it.path.startsWith(directory.canonicalPath + File.separator) && it.isFile)
            }
        }
        fun assertUnpublishedAndOriginal() {
            DngContentVerifier.verify(original.fingerprint, { resolver.openInputStream(uri)!! })
            assertEquals(jpegCount, sharedJpegs())
            assertNull(AndroidAtomicJournalStorage(File(directory, "renders"), handle().request.generation.toString(),
                CaptureJournalFile.JPEG_PUBLICATION).read(JpegPublicationCodec.MAX_BYTES))
        }
        override fun close() {
            handle().requestStop()
            handle().status().get(30, TimeUnit.SECONDS)
            DevelopExecution.updateConditions(allowed)
            val probe = DevelopExecution.tryStartObserved(allowed)
            check(probe != null) { "Keep test artifacts until resources actually close" }
            DevelopExecution.completeAfterResourcesClosed(probe)
            check(resolver.delete(uri, null, null) == 1)
            check(directory.canonicalFile.parentFile == File(context.noBackupFilesDir, "raw-captures").canonicalFile)
            if (directory.exists()) check(directory.deleteRecursively())
        }
    }
    private fun sharedJpegs() = context.contentResolver.query(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
        arrayOf(MediaStore.Images.Media._ID), "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=? AND ${MediaStore.Images.Media.MIME_TYPE}=?",
        arrayOf(context.packageName, "image/jpeg"), null)!!.use { it.count }
}
