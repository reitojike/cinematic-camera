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
    private val context get() = dev.cinematic.p0.jpegrender.original.isolatedGpuSaveSafetyApp()
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

    private fun sharedJpegCount(): Int = context.contentResolver.query(
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), arrayOf(MediaStore.Images.Media._ID),
        "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=? AND ${MediaStore.Images.Media.MIME_TYPE}=?",
        arrayOf(context.packageName, "image/jpeg"), null,
    )!!.use { it.count }

    /** Programmatic callback/queue/list/source-handoff path, not CameraX capture or Compose interaction. */
    @Test fun syntheticCaptureHandsOffOneOriginalAndPreservesItsFingerprint() {
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
            val original = repository.check(uri, false, { false }, 64L * 1024 * 1024)
            assertEquals(OriginalGrant.APP_OWNED, original.reference.grant)
            assertEquals(done.fingerprint, original.fingerprint)
            assertEquals(fingerprint, original.fingerprint)
            repository.verify(original) { false }
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

    @Test fun pendingOriginalCannotBecomeAnAppOwnedControllerReference() = withOriginal { original ->
        val repository = PhotoRollRepository(context.contentResolver, context.packageName)
        try {
            repository.check(original.uri, false, { false })
            fail("A pending row must not be claimed as a shared APP_OWNED original")
        } catch (failure: PhotoRollSourceFailure) { assertTrue(failure.userMessage.contains("公開済み")) }
        DngContentVerifier.verify(original.fingerprint, { context.contentResolver.openInputStream(original.uri)!! })
    }

    /** Uncompressed Bayer DNG; default bytes stay 32x24/orientation 1. All metadata/samples are synthetic. */
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
