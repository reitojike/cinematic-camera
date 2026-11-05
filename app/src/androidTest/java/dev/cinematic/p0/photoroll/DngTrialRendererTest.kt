package dev.cinematic.p0.photoroll

import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.rawcapture.DngContentVerifier
import dev.cinematic.p0.rawcapture.OriginalGrant
import dev.cinematic.p0.rawcapture.AndroidRawCaptureQueue
import dev.cinematic.p0.rawcapture.CaptureReceiptPhase
import dev.cinematic.p0.rawcapture.DngPublicationPhase
import dev.cinematic.p0.rawcapture.DngPublicationRecord
import dev.cinematic.p0.rawcapture.MediaStoreDngPendingRows
import dev.cinematic.p0.rawcapture.RawCaptureNotice
import dev.cinematic.p0.rawcapture.RawCaptureObserver
import dev.cinematic.p0.rawcapture.RawCaptureTicket
import dev.cinematic.p0.rawcapture.RawOutput
import dev.cinematic.p0.rawcapture.AndroidAutoDevelopQueue
import dev.cinematic.p0.rawcapture.DevelopExecution
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic input only. The queue case publishes its own DNG; no case captures a camera image. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class DngTrialRendererTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val resources = PhotoRollResources(true, 0, false, PhotoRollResources.REQUIRED_AVAILABLE_BYTES)
    private var queueObserver: RawCaptureObserver? = null

    @Test fun cancelledSourceReadKeepsCommonSlotUntilReadAndCloseActuallyFinish() {
        val ready = PhotoRollResources(true, 0, false, 1024L * 1024 * 1024,
            PhotoRollCameraSnapshot(false, false, false, false), true, true)
        val conditions = ready.developConditions(true)
        DevelopExecution.updateConditions(conditions)
        val entered = CountDownLatch(1); val finishRead = CountDownLatch(1)
        val closing = CountDownLatch(1); val finishClose = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val signal = CancellationSignal()
        val worker = Thread {
            try {
                PhotoRollOriginalChecks.run({ ready }, signal) { reader, _ ->
                    reader.open { object : java.io.InputStream() {
                        override fun read(): Int { entered.countDown(); check(finishRead.await(15, TimeUnit.SECONDS)); return -1 }
                        override fun close() { closing.countDown(); check(finishClose.await(15, TimeUnit.SECONDS)) }
                    } }.use { it.read() }
                }
            } catch (error: Throwable) { failure.set(error) }
        }.apply { isDaemon = true; start() }
        try {
            assertTrue(entered.await(15, TimeUnit.SECONDS))
            signal.cancel()
            assertNull(DevelopExecution.tryStartObserved(conditions))
            finishRead.countDown()
            assertTrue(closing.await(15, TimeUnit.SECONDS))
            val camera = DevelopExecution.reserveCapture() // Priority does not wait for the blocked close.
            DevelopExecution.releaseCapture(camera)
            assertNull(DevelopExecution.tryStartObserved(conditions))
            finishClose.countDown()
            worker.join(15_000)
            assertFalse(worker.isAlive)
            assertNull(failure.get())
            val next = requireNotNull(DevelopExecution.tryStartObserved(conditions))
            DevelopExecution.completeAfterResourcesClosed(next)
        } finally { finishRead.countDown(); finishClose.countDown(); worker.join(15_000) }
    }

    /** Real shared-original→retained private JPEG→fresh readback→UI display copy, without camera capture. */
    @Test fun privateTrialRendererReopensSameArtifactWithoutPublishingOrRecyclingDisplay() = withOriginal { original ->
        val resolver = context.contentResolver
        assertEquals(1, resolver.update(original.uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null))
        val ready = PhotoRollResources(true, 0, false, 1024L * 1024 * 1024,
            PhotoRollCameraSnapshot(false, false, false, false), true, true)
        DevelopExecution.updateConditions(ready.developConditions(true))
        val renderer = PrivateTrialPhotoRollRenderer(context) { ready }
        val checked = renderer.checkOriginal(PhotoRollRepository(resolver, context.packageName), original.uri, false, CancellationSignal())
        val request = PhotoRollRenderRequest(checked, mode = renderer.mode, maxEdge = renderer.maxEdge)
        val before = sharedJpegCount()
        val first = renderer.render(request, CancellationSignal()) as PhotoRollRenderResult.Preview
        val handle = AndroidAutoDevelopQueue.preparePrivateTrial(context, checked.reference).get(15, TimeUnit.SECONDS)
        val observed = handle.status().get(15, TimeUnit.SECONDS)
        val output = requireNotNull(observed.output)
        val second = renderer.render(request, CancellationSignal()) as PhotoRollRenderResult.Preview
        try {
            assertEquals(PhotoRollPreviewMode.PRIVATE_TRIAL, first.mode)
            assertNull(first.jpegSave); assertNull(second.jpegSave)
            assertNotSame(first.bitmap, second.bitmap)
            assertFalse(first.bitmap.isRecycled)
            assertEquals(Bitmap.Config.ARGB_8888, first.bitmap.config)
            assertTrue(first.bitmap.colorSpace!!.isSrgb)
            assertEquals(output.width, first.bitmap.width)
            assertEquals(output.height, first.bitmap.height)
            assertTrue(first.bitmap.width <= 1024 && first.bitmap.height <= 1024)
            val one = IntArray(first.bitmap.width * first.bitmap.height)
            val two = IntArray(one.size)
            first.bitmap.getPixels(one, 0, first.bitmap.width, 0, 0, first.bitmap.width, first.bitmap.height)
            second.bitmap.getPixels(two, 0, second.bitmap.width, 0, 0, second.bitmap.width, second.bitmap.height)
            assertArrayEquals(one, two)
            assertEquals(observed.request, handle.status().get(15, TimeUnit.SECONDS).request)
            assertEquals(observed.fingerprint, handle.status().get(15, TimeUnit.SECONDS).fingerprint)
            assertEquals(before, sharedJpegCount())
            DngContentVerifier.verify(checked.fingerprint, { resolver.openInputStream(checked.uri)!! })
        } finally { first.bitmap.recycle(); second.bitmap.recycle(); handle.requestStop() }
    }

    @Test fun privateTrialUiCancellationBeforeReadbackKeepsOriginalAndAllowsExplicitRetry() = withOriginal { original ->
        val resolver = context.contentResolver
        assertEquals(1, resolver.update(original.uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null))
        val ready = PhotoRollResources(true, 0, false, 1024L * 1024 * 1024,
            PhotoRollCameraSnapshot(false, false, false, false), true, true)
        DevelopExecution.updateConditions(ready.developConditions(true))
        val renderer = PrivateTrialPhotoRollRenderer(context) { ready }
        val checked = renderer.checkOriginal(PhotoRollRepository(resolver, context.packageName), original.uri, false, CancellationSignal())
        val signal = CancellationSignal()
        val request = PhotoRollRenderRequest(checked, mode = renderer.mode, maxEdge = renderer.maxEdge,
            onStage = { if (it == PhotoRollRenderStage.TRIAL_READBACK) signal.cancel() })
        val before = sharedJpegCount()
        assertThrows(OperationCanceledException::class.java) { renderer.render(request, signal) }
        val result = renderer.render(request.copy(explicitRetry = true, onStage = {}), CancellationSignal())
        assertTrue("Private trial retry failed: $result", result is PhotoRollRenderResult.Preview)
        (result as PhotoRollRenderResult.Preview).bitmap.recycle()
        assertEquals(before, sharedJpegCount())
        DngContentVerifier.verify(checked.fingerprint, { resolver.openInputStream(checked.uri)!! })
    }

    private fun sharedJpegCount(): Int = context.contentResolver.query(
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), arrayOf(MediaStore.Images.Media._ID),
        "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=? AND ${MediaStore.Images.Media.MIME_TYPE}=?",
        arrayOf(context.packageName, "image/jpeg"), null,
    )!!.use { it.count }

    /** Programmatic callback/queue/list/renderer path, not CameraX capture or Compose interaction. */
    @Test fun syntheticCaptureHandsOffOneOriginalAndDisplaysItAsTrial() {
        check(Build.HARDWARE in setOf("goldfish", "ranchu")) { "Disposable emulator only" }
        val bytes = syntheticDng()
        val fingerprint = DngContentVerifier.identify(bytes.size.toLong(), { bytes.inputStream() })
        val jpeg = "synthetic provisional JPEG bytes".toByteArray()
        val id = "integration_${UUID.randomUUID().toString().replace("-", "")}"
        val received = CountDownLatch(1)
        val shared = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val record = AtomicReference<DngPublicationRecord?>()
        val queue = AndroidRawCaptureQueue.get(context)
        val camera = queue.newCameraOwner().apply { foreground(true) } // Reserved until both receipts are observed.
        val rows = MediaStoreDngPendingRows(context)
        val observer = RawCaptureObserver { ticket, notice ->
            try {
                when (notice) {
                    is RawCaptureNotice.Prepared -> {
                        notice.outputs.getValue(RawOutput.DNG).write(bytes)
                        notice.outputs.getValue(RawOutput.JPEG).write(jpeg)
                        queue.received(ticket, RawOutput.JPEG)
                        queue.received(ticket, RawOutput.DNG)
                    }
                    is RawCaptureNotice.Received -> if (notice.receipt.phase == CaptureReceiptPhase.FILES_RECEIVED) received.countDown()
                    is RawCaptureNotice.Shared -> { record.set(notice.record); shared.countDown() }
                    is RawCaptureNotice.Failed -> error("Synthetic queue failure: ${notice.stage}")
                }
            } catch (error: Throwable) { failure.set(error); received.countDown(); shared.countDown() }
        }
        queueObserver = observer // The production queue intentionally holds observers weakly.
        var issued: RawCaptureTicket? = null
        var cleanupRecord: DngPublicationRecord? = null
        var primaryFailure: Throwable? = null
        try {
            val ticket = queue.prepare(id, setOf(RawOutput.DNG, RawOutput.JPEG), observer)
            issued = ticket
            assertTrue("Callback receipts timed out", received.await(15, TimeUnit.SECONDS))
            assertNull(failure.get())
            assertNull(ticket.session!!.receipt()!!.originalFingerprint)
            assertEquals(1L, shared.count)
            camera.update(false)
            assertTrue("Shared original timed out", shared.await(15, TimeUnit.SECONDS))
            assertNull(failure.get())
            val done = requireNotNull(record.get())
            assertEquals(DngPublicationPhase.SHARED_ONLY, done.phase)
            assertEquals(id, done.target.captureId)
            assertEquals(fingerprint, done.fingerprint)
            assertFalse(ticket.session!!.hasUnreleasedWriters)
            assertFalse(ticket.session!!.hasUnreleasedHandoffResources)
            check(done.target.accepts(requireNotNull(rows.inspect(requireNotNull(done.uri))), false))
            DngContentVerifier.verify(fingerprint, { rows.openRead(done.target, done.uri, false) })
            cleanupRecord = done // UNKNOWN/foreign/unverified notifications can never become cleanup authority.
            assertEquals(1, rows.find(done.target).size)
            val local = File(File(context.noBackupFilesDir, "raw-captures"), id)
            assertFalse(File(local, RawOutput.DNG.fileName).exists())
            assertArrayEquals(jpeg, File(local, RawOutput.JPEG.fileName).readBytes())
            val uri = Uri.parse(requireNotNull(done.uri))
            val repository = PhotoRollRepository(context.contentResolver, context.packageName)
            assertTrue(repository.list(CancellationSignal()).items.any { it.uri == uri.toString() })
            val renderer = DngTrialRenderer(context.contentResolver) { resources }
            val original = repository.check(uri, false, { false }, renderer.maxSourceBytes)
            assertEquals(OriginalGrant.APP_OWNED, original.reference.grant)
            assertEquals(done.fingerprint, original.fingerprint)
            assertEquals(fingerprint, original.fingerprint)
            val result = renderer.render(PhotoRollRenderRequest(original, mode = PhotoRollPreviewMode.DNG_TRIAL), CancellationSignal())
            assertTrue(result is PhotoRollRenderResult.Preview)
            val preview = result as PhotoRollRenderResult.Preview
            try {
                assertEquals(PhotoRollPreviewMode.DNG_TRIAL, preview.mode)
                assertEquals(16, preview.bitmap.width)
                assertEquals(12, preview.bitmap.height)
                repository.verify(original) { false }
            } finally { preview.bitmap.recycle() }
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            try {
                camera.update(true) // Latch a stop before releasing the last foreground owner.
                issued?.let { ticket ->
                    if (!ticket.writerReleased.get()) {
                        queue.interrupt(ticket) // Only this failed/incomplete request; never close a live store directly.
                        val closed = CountDownLatch(1)
                        ticket.serial.execute(context.mainExecutor) { closed.countDown() }
                        check(closed.await(15, TimeUnit.SECONDS) && ticket.writerReleased.get()) {
                            "Synthetic writer release unconfirmed"
                        }
                    }
                    check(ticket.session?.hasUnreleasedHandoffResources != true) { "Synthetic handoff resources remain held" }
                }
                // Only a positively completed, matching row created by this test. No scan/delete on failure.
                cleanupRecord?.let { done ->
                    check(done.target.accepts(requireNotNull(rows.inspect(requireNotNull(done.uri))), false))
                    DngContentVerifier.verify(done.fingerprint, { rows.openRead(done.target, done.uri, false) })
                    check(context.contentResolver.delete(Uri.parse(done.uri), null, null) == 1)
                    check(rows.find(done.target).isEmpty())
                }
            } catch (cleanup: Throwable) {
                if (primaryFailure != null) primaryFailure.addSuppressed(cleanup) else throw cleanup
            } finally { camera.close(); queueObserver = null }
        }
    }

    @Test fun selectedContentUriProducesHonestTrialBitmapAndLeavesOriginalUnchanged() = withOriginal { original ->
        val renderer = DngTrialRenderer(context.contentResolver) { resources }
        val result = renderer.render(PhotoRollRenderRequest(original, mode = PhotoRollPreviewMode.DNG_TRIAL), CancellationSignal())
        assertTrue(result is PhotoRollRenderResult.Preview)
        val preview = result as PhotoRollRenderResult.Preview
        try {
            assertEquals(PhotoRollPreviewMode.DNG_TRIAL, preview.mode)
            assertNull(preview.jpegSave)
            assertEquals(16, preview.bitmap.width)
            assertEquals(12, preview.bitmap.height)
            assertEquals(Bitmap.Config.ARGB_8888, preview.bitmap.config)
            assertTrue(preview.bitmap.colorSpace!!.isSrgb)
            val pixels = IntArray(16 * 12)
            preview.bitmap.getPixels(pixels, 0, 16, 0, 0, 16, 12)
            assertTrue(pixels.any { (it and 0xffffff) != 0 })
            DngContentVerifier.verify(original.fingerprint, { context.contentResolver.openInputStream(original.uri)!! })
        } finally { preview.bitmap.recycle() }
    }

    @Test fun pendingOriginalCannotBecomeAnAppOwnedControllerReference() = withOriginal { original ->
        val repository = PhotoRollRepository(context.contentResolver, context.packageName)
        try {
            repository.check(original.uri, false, { false })
            fail("A pending row must not be claimed as a shared APP_OWNED original")
        } catch (failure: PhotoRollSourceFailure) { assertTrue(failure.userMessage.contains("公開済み")) }
        DngContentVerifier.verify(original.fingerprint, { context.contentResolver.openInputStream(original.uri)!! })
    }

    @Test fun cancellationDoesNotPreventTheNextRealRequest() = withOriginal { original ->
        val renderer = DngTrialRenderer(context.contentResolver) { resources }
        val request = PhotoRollRenderRequest(original, mode = PhotoRollPreviewMode.DNG_TRIAL)
        try { renderer.render(request, CancellationSignal().apply { cancel() }); fail("Expected cancellation") }
        catch (_: OperationCanceledException) { /* The next request must still work. */ }
        val result = renderer.render(request, CancellationSignal())
        assertTrue(result is PhotoRollRenderResult.Preview)
        (result as PhotoRollRenderResult.Preview).bitmap.recycle()
    }

    @Test fun unavailableResourcesAndWrongModeDoNotBecomeSuccessfulImages() = withOriginal { original ->
        val renderer = DngTrialRenderer(context.contentResolver) { resources.copy(camerasIdle = null) }
        val blocked = renderer.render(PhotoRollRenderRequest(original, mode = PhotoRollPreviewMode.DNG_TRIAL), CancellationSignal())
        assertTrue(blocked is PhotoRollRenderResult.Unavailable)
        assertTrue((blocked as PhotoRollRenderResult.Unavailable).reason.contains("カメラ"))
        val wrongMode = renderer.render(PhotoRollRenderRequest(original), CancellationSignal())
        assertTrue(wrongMode is PhotoRollRenderResult.Unavailable)
        assertTrue((wrongMode as PhotoRollRenderResult.Unavailable).reason.contains("Stage Clear"))
    }

    @Test fun selectedContentUriRunsRealR7AndLookWithExplicitTrialMode() = withOriginal { original ->
        val stages = mutableListOf<PhotoRollRenderStage>()
        val renderer = StageClearTrialRenderer(context.contentResolver) { resources }
        val request = PhotoRollRenderRequest(original, mode = renderer.mode, maxEdge = renderer.maxEdge, onStage = { stages += it })
        val result = renderer.render(request, CancellationSignal()) as PhotoRollRenderResult.Preview
        val plain = DngTrialRenderer(context.contentResolver) { resources }.render(
            PhotoRollRenderRequest(original, mode = PhotoRollPreviewMode.DNG_TRIAL), CancellationSignal(),
        ) as PhotoRollRenderResult.Preview
        try {
            assertEquals(PhotoRollPreviewMode.STAGE_CLEAR_TRIAL, result.mode)
            assertNull(result.jpegSave)
            assertEquals(listOf(PhotoRollRenderStage.DECODE, PhotoRollRenderStage.INPUTS, PhotoRollRenderStage.BASE,
                PhotoRollRenderStage.LOOK, PhotoRollRenderStage.DISPLAY), stages)
            assertEquals(16, result.bitmap.width)
            assertEquals(12, result.bitmap.height)
            assertEquals(Bitmap.Config.ARGB_8888, result.bitmap.config)
            assertTrue(result.bitmap.colorSpace!!.isSrgb)
            val looked = IntArray(16 * 12)
            val asShot = IntArray(16 * 12)
            result.bitmap.getPixels(looked, 0, 16, 0, 0, 16, 12)
            plain.bitmap.getPixels(asShot, 0, 16, 0, 0, 16, 12)
            assertFalse(looked.contentEquals(asShot))
            assertTrue(looked.any { (it and 0xffffff) != 0 })
            DngContentVerifier.verify(original.fingerprint, { context.contentResolver.openInputStream(original.uri)!! })
        } finally { result.bitmap.recycle(); plain.bitmap.recycle() }
    }

    @Test fun stageCancellationStopsLookAndAllowsNextNativeRequest() = withOriginal { original ->
        val renderer = StageClearTrialRenderer(context.contentResolver) { resources }
        val signal = CancellationSignal()
        val stages = mutableListOf<PhotoRollRenderStage>()
        val request = PhotoRollRenderRequest(original, mode = renderer.mode, maxEdge = renderer.maxEdge, onStage = {
            stages += it
            if (it == PhotoRollRenderStage.BASE) signal.cancel()
        })
        try { renderer.render(request, signal); fail("Expected stage cancellation") }
        catch (_: java.util.concurrent.CancellationException) { }
        assertEquals(PhotoRollRenderStage.BASE, stages.last())
        val result = renderer.render(request.copy(onStage = {}), CancellationSignal()) as PhotoRollRenderResult.Preview
        result.bitmap.recycle()
        DngContentVerifier.verify(original.fingerprint, { context.contentResolver.openInputStream(original.uri)!! })
    }

    @Test fun stageClearRejectsUnknownResourcesAndUnsupportedControlsBeforeDecode() = withOriginal { original ->
        var stages = 0
        val renderer = StageClearTrialRenderer(context.contentResolver) { resources }
        val request = PhotoRollRenderRequest(original, mode = renderer.mode, maxEdge = renderer.maxEdge, onStage = { stages++ })
        for (invalid in listOf(request.copy(mode = PhotoRollPreviewMode.STAGE_CLEAR), request.copy(maxEdge = 513),
            request.copy(strength = Double.NaN), request.copy(strength = 1.0), request.copy(lookVersion = "unknown"))) {
            assertTrue(renderer.render(invalid, CancellationSignal()) is PhotoRollRenderResult.Unavailable)
        }
        val blocked = StageClearTrialRenderer(context.contentResolver) { resources.copy(thermalStatus = null) }
        assertTrue(blocked.render(request, CancellationSignal()) is PhotoRollRenderResult.Unavailable)
        assertEquals(0, stages)
    }

    @Test fun maximumPortraitTrialCancelsBeforeBitmapAndNextRequestWorks() =
        withOriginal(syntheticDng(width = 1024, height = 768, orientation = 6)) { original ->
            val renderer = StageClearTrialRenderer(context.contentResolver) { resources }
            val stages = mutableListOf<PhotoRollRenderStage>()
            val request = PhotoRollRenderRequest(original, mode = renderer.mode, maxEdge = renderer.maxEdge,
                onStage = { stages += it })
            val rendered = renderer.render(request, CancellationSignal())
            assertTrue("512px trial did not complete: $rendered", rendered is PhotoRollRenderResult.Preview)
            val preview = rendered as PhotoRollRenderResult.Preview
            try {
                assertEquals(PhotoRollPreviewMode.STAGE_CLEAR_TRIAL, preview.mode)
                assertEquals(384, preview.bitmap.width)
                assertEquals(512, preview.bitmap.height)
                assertEquals(Bitmap.Config.ARGB_8888, preview.bitmap.config)
                assertTrue(preview.bitmap.colorSpace!!.isSrgb)
                assertEquals(listOf(PhotoRollRenderStage.DECODE, PhotoRollRenderStage.INPUTS,
                    PhotoRollRenderStage.BASE, PhotoRollRenderStage.LOOK, PhotoRollRenderStage.DISPLAY), stages)
            } finally { preview.bitmap.recycle() }

            // Cancel after the real R7/Look work, before display encoding can return a Bitmap.
            val signal = CancellationSignal()
            stages.clear()
            try {
                renderer.render(request.copy(onStage = {
                    stages += it
                    if (it == PhotoRollRenderStage.DISPLAY) signal.cancel()
                }), signal)
                fail("Expected display-stage cancellation")
            } catch (_: java.util.concurrent.CancellationException) { }
            assertEquals(PhotoRollRenderStage.DISPLAY, stages.last())
            val recovered = renderer.render(request.copy(maxEdge = 64, onStage = {}), CancellationSignal())
            assertTrue("Next trial did not complete: $recovered", recovered is PhotoRollRenderResult.Preview)
            val next = recovered as PhotoRollRenderResult.Preview
            try {
                assertEquals(PhotoRollPreviewMode.STAGE_CLEAR_TRIAL, next.mode)
                assertEquals(48, next.bitmap.width)
                assertEquals(64, next.bitmap.height)
                DngContentVerifier.verify(original.fingerprint, { context.contentResolver.openInputStream(original.uri)!! })
            } finally { next.bitmap.recycle() }
        }

    private fun withOriginal(bytes: ByteArray = syntheticDng(), action: (PhotoRollOriginal) -> Unit) {
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "synthetic-trial-${System.nanoTime()}.dng")
            put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Cinematic Camera Synthetic Tests/")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }) ?: error("Could not create test-only pending row")
        try {
            resolver.openOutputStream(uri, "w")!!.use { it.write(bytes) }
            val fingerprint = DngContentVerifier.identify(bytes.size.toLong(), { resolver.openInputStream(uri)!! })
            action(PhotoRollOriginal(uri, fingerprint, OriginalGrant.APP_OWNED))
        } finally { check(resolver.delete(uri, null, null) == 1) { "Test-only pending row cleanup failed" } }
    }

    /** Uncompressed Bayer DNG; default bytes stay 32x24/orientation 1. All metadata/samples are synthetic. */
    private fun syntheticDng(width: Int = 32, height: Int = 24, orientation: Int = 1): ByteArray {
        fun shorts(vararg values: Int) = ByteBuffer.allocate(values.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            .apply { values.forEach { putShort(it.toShort()) } }.array()
        fun longs(vararg values: Int) = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            .apply { values.forEach { putInt(it) } }.array()
        data class Entry(val tag: Int, val type: Int, val count: Int, val bytes: ByteArray)
        val entries = mutableListOf<Entry>()
        fun short(tag: Int, vararg values: Int) { entries += Entry(tag, 3, values.size, shorts(*values)) }
        fun long(tag: Int, value: Int) { entries += Entry(tag, 4, 1, longs(value)) }
        fun ascii(tag: Int, value: String) { entries += Entry(tag, 2, value.length + 1, (value + '\u0000').toByteArray()) }
        long(256, width); long(257, height); short(258, 16); short(259, 1); short(262, 32803)
        ascii(271, "Synthetic"); ascii(272, "Bayer fixture")
        long(273, 0); short(274, orientation); short(277, 1); long(278, height); long(279, width * height * 2)
        short(33421, 2, 2)
        entries += Entry(33422, 1, 4, byteArrayOf(0, 1, 1, 2))
        entries += Entry(50706, 1, 4, byteArrayOf(1, 4, 0, 0))
        short(50713, 1, 1); long(50714, 64); long(50717, 4095)
        entries += Entry(50721, 10, 9, longs(1, 1, 0, 1, 0, 1, 0, 1, 1, 1, 0, 1, 0, 1, 0, 1, 1, 1))
        entries += Entry(50728, 5, 3, longs(1, 1, 1, 1, 1, 1))
        short(50778, 21)
        entries.sortBy { it.tag }
        val start = 8 + 2 + 12 * entries.size + 4
        val rawOffset = start + entries.sumOf { if (it.bytes.size > 4) it.bytes.size else 0 }
        val buffer = ByteBuffer.allocate(rawOffset + width * height * 2).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put('I'.code.toByte()).put('I'.code.toByte()).putShort(42).putInt(8).putShort(entries.size.toShort())
        var extra = start
        for (entry in entries) {
            buffer.putShort(entry.tag.toShort()).putShort(entry.type.toShort()).putInt(entry.count)
            when {
                entry.tag == 273 -> buffer.putInt(rawOffset)
                entry.bytes.size <= 4 -> { buffer.put(entry.bytes); repeat(4 - entry.bytes.size) { buffer.put(0) } }
                else -> { buffer.putInt(extra); System.arraycopy(entry.bytes, 0, buffer.array(), extra, entry.bytes.size); extra += entry.bytes.size }
            }
        }
        buffer.putInt(0).position(rawOffset)
        repeat(height) { y -> repeat(width) { x -> buffer.putShort((164 + (x * 65 + y * 25) % 3500).toShort()) } }
        return buffer.array()
    }
}
