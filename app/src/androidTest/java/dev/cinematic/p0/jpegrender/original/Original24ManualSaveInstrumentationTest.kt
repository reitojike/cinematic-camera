package dev.cinematic.p0.jpegrender.original

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.raw.PhotoAdjustments
import dev.cinematic.p0.rawcapture.*
import dev.cinematic.p0.testing.writeSyntheticTrialDng
import java.io.Closeable
import java.io.File
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real factory/publication wiring on the dedicated emulator only; every image is synthetic. */
@RunWith(AndroidJUnit4::class)
class Original24ManualSaveInstrumentationTest {
    @Test fun orientedSyntheticDngPreviewAndExplicitSaveReconcileOneVerifiedPublicJpeg() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        assumeTrue(app.packageName == "dev.cinematic.p0.original24probe")
        assumeTrue(InstrumentationRegistry.getArguments().getString("original24") == "true")
        assumeTrue("Physical devices are excluded", Build.HARDWARE in setOf("ranchu", "goldfish"))
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val resolver = app.contentResolver
        val sourceName = "synthetic_original24_manual_${UUID.randomUUID()}.dng"
        val sourceUri = requireNotNull(resolver.insert(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, sourceName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/x-adobe-dng")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Cinematic Camera Synthetic Tests/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }))
        var firstBitmap: Bitmap? = null
        var secondBitmap: Bitmap? = null
        var firstComparison: Bitmap? = null
        var secondComparison: Bitmap? = null
        var firstPreviewFuture: PreviewFutureOwner? = null
        var secondPreviewFuture: PreviewFutureOwner? = null
        var handle: AndroidManualOriginalTrialSaves.ManualOriginalTrialHandle? = null
        var failure: Throwable? = null
        var outputUri: Uri? = null
        var outputName: String? = null
        var success = false
        try {
            val sourceBytes = requireNotNull(resolver.openOutputStream(sourceUri, "w")).use {
                writeSyntheticTrialDng(it, 64, 48, 6)
            }
            assertEquals(1, resolver.update(sourceUri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null))
            val fingerprint = DngContentVerifier.identify(sourceBytes, { requireNotNull(resolver.openInputStream(sourceUri)) })
            val source = SharedOriginalReference(sourceUri.toString(), fingerprint, OriginalGrant.APP_OWNED)
            val allowed = AutoDevelopConditions(true, false, false, false, true, true, true, true)
            val edits = PhotoAdjustments(20.0, .3)
            DevelopExecution.updateConditions(allowed)
            val pendingFirst = PreviewFutureOwner(AndroidManualOriginalTrialSaves.renderPreview(app, source, .45, allowed, edits))
                .also { firstPreviewFuture = it }
            val save = pendingFirst.useDelivery { preview ->
                val save = preview.save.also { handle = it }
                firstBitmap = preview.claimBitmap()
                firstComparison = requireNotNull(preview.claimComparisonBitmap())
                assertEquals("撮影時WBのRAW基準表示（R7・ルック・手動調整なし、暫定色）", preview.comparisonDescription)
                save
            }
            val first = requireNotNull(firstBitmap)
            val before = requireNotNull(firstComparison)
            assertFalse(first.isRecycled) // Delivery release cannot recycle a claimed caller-owned bitmap.
            assertFalse(before.isRecycled)
            assertNotSame(first, before)
            assertEquals(48, first.width); assertEquals(64, first.height)
            assertEquals(first.width, before.width); assertEquals(first.height, before.height)
            assertNotEquals(Bitmap.Config.HARDWARE, first.config)
            assertNotEquals(Bitmap.Config.HARDWARE, before.config)
            assertTrue(requireNotNull(first.colorSpace).isSrgb)
            assertTrue(requireNotNull(before.colorSpace).isSrgb)
            val firstPixel = first.getPixel(0, 0)
            assertEquals(64, save.geometry.rawWidth); assertEquals(48, save.geometry.rawHeight)
            assertEquals(64, save.geometry.sourceWidth); assertEquals(48, save.geometry.sourceHeight)
            assertEquals(6, save.geometry.orientation)
            assertEquals(48, save.geometry.outputWidth); assertEquals(64, save.geometry.outputHeight)
            assertEquals(source, save.request.source)
            assertEquals(.45, save.appliedStrength, 0.0)
            assertEquals(edits, save.appliedAdjustments)
            assertEquals(ManualOriginalTrialRender.recipe(.45, edits), save.request.recipe)
            assertTrue(save.matches(source))
            assertEquals(ManualOriginalTrialSavePhase.READY, save.state.value.phase)

            val same = AndroidManualOriginalTrialSaves.prepare(app, source, .45, allowed, PhotoAdjustments(20.0, .3))
                .get(30, TimeUnit.SECONDS)
            assertSame(save, same)
            val pendingSecond = PreviewFutureOwner(AndroidManualOriginalTrialSaves.renderPreview(app, source, .45, allowed, edits))
                .also { secondPreviewFuture = it }
            pendingSecond.useDelivery { again ->
                assertSame(save, again.save)
                secondBitmap = again.claimBitmap()
                secondComparison = requireNotNull(again.claimComparisonBitmap())
                assertEquals("撮影時WBのRAW基準表示（R7・ルック・手動調整なし、暫定色）", again.comparisonDescription)
            }
            val second = requireNotNull(secondBitmap)
            val secondBefore = requireNotNull(secondComparison)
            assertNotSame(first, second)
            assertNotSame(before, secondBefore)
            assertNotSame(second, secondBefore)
            assertFalse(first.isRecycled); assertFalse(second.isRecycled)
            assertFalse(before.isRecycled); assertFalse(secondBefore.isRecycled)
            assertEquals(firstPixel, first.getPixel(0, 0))
            assertEquals(first.width, second.width); assertEquals(first.height, second.height)
            assertEquals(second.width, secondBefore.width); assertEquals(second.height, secondBefore.height)

            val directory = RenderedJpegFiles(File(app.noBackupFilesDir, "raw-captures"), save.request.captureId,
                save.request.generation).generationRoot()
            assertFalse("Preparing/previewing cannot generate the saved JPEG", File(directory, "output.jpg").exists())
            val journal = JpegPublicationJournal(save.request.captureId, save.request.generation,
                AndroidAtomicJournalStorage(requireNotNull(directory.parentFile), save.request.generation.toString(),
                    CaptureJournalFile.JPEG_PUBLICATION))
            assertNull(journal.read())
            val published = save.advance(allowed) { false }.get(120, TimeUnit.SECONDS)
            assertEquals(ManualOriginalTrialSavePhase.PUBLISHED, published.phase)
            val publishedUri = Uri.parse(requireNotNull(published.uri)).also { outputUri = it }
            assertNotEquals(sourceUri, publishedUri)
            val record = requireNotNull(journal.read())
            outputName = record.target.displayName
            assertEquals(JpegPublicationPhase.PUBLISHED, record.phase)
            assertEquals(published.uri, record.uri)
            val proof = record.render as ManualOriginalTrialRender
            assertEquals(edits, proof.adjustments)
            assertEquals(save.request.recipe, proof.request.recipe)
            assertTrue(proof.geometry.fullCoverage)
            val inspection = AndroidJpegInspector().inspect({ requireNotNull(resolver.openInputStream(publishedUri)) },
                record.fingerprint.bytes) { false }
            assertEquals(record.fingerprint, inspection.fingerprint)
            assertEquals(48, inspection.output.width); assertEquals(64, inspection.output.height)
            assertEquals(1, inspection.output.orientation)
            val decoded = requireNotNull(resolver.openInputStream(publishedUri)).use { requireNotNull(BitmapFactory.decodeStream(it)) }
            try { assertEquals(48, decoded.width); assertEquals(64, decoded.height) } finally { decoded.recycle() }
            val reopened = save.advance(allowed) { false }.get(30, TimeUnit.SECONDS)
            assertEquals(ManualOriginalTrialSavePhase.PUBLISHED, reopened.phase)
            assertEquals(published.uri, reopened.uri)
            assertEquals(record, journal.read())
            assertEquals(record.fingerprint, DngContentVerifier.identify(record.fingerprint.bytes,
                { requireNotNull(resolver.openInputStream(publishedUri)) }))
            assertEquals(fingerprint, DngContentVerifier.verify(fingerprint, { requireNotNull(resolver.openInputStream(sourceUri)) }))
            assertFalse(save.hasUnreleasedResources)
            AndroidManualOriginalTrialSaves.discardPreview(app).get(30, TimeUnit.SECONDS)
            success = true
        } catch (error: Throwable) { failure = error; throw error }
        finally {
            // A late delivery is closed by onIo after cancellation, or recovered here if completion
            // won the race. Cancellation itself never acknowledges the native/session resources.
            firstPreviewFuture?.close(); secondPreviewFuture?.close()
            firstBitmap?.recycle(); secondBitmap?.recycle()
            firstComparison?.recycle(); secondComparison?.recycle()
            if (!success) {
                handle?.requestStop()
                try { AndroidManualOriginalTrialSaves.discardPreview(app).get(30, TimeUnit.SECONDS) }
                catch (cleanup: Throwable) { if (failure == null) throw cleanup else if (cleanup !== failure) failure.addSuppressed(cleanup) }
                // Retain synthetic rows and private journal/scratch on every failed/uncertain outcome.
            } else {
                val jpegUri = requireNotNull(outputUri)
                val jpegName = requireNotNull(outputName)
                // Verify BOTH exact rows before either deletion; never enumerate a collection.
                requireOwnedPublishedRow(resolver, sourceUri, sourceName, app.packageName)
                requireOwnedPublishedRow(resolver, jpegUri, jpegName, app.packageName)
                deleteExactOwnedRow(resolver, jpegUri, jpegName, app.packageName)
                deleteExactOwnedRow(resolver, sourceUri, sourceName, app.packageName)
            }
        }
    }

    /** One owner until bounded get succeeds; then use closes every still-unclaimed Before/After. */
    private class PreviewFutureOwner(
        private val future: CompletableFuture<AndroidManualOriginalTrialSaves.ManualOriginalTrialPreview>,
    ) : Closeable {
        private var delivered = false
        fun <T> useDelivery(action: (AndroidManualOriginalTrialSaves.ManualOriginalTrialPreview) -> T): T {
            check(!delivered)
            val delivery = future.get(120, TimeUnit.SECONDS)
            delivered = true
            return delivery.use(action)
        }
        override fun close() {
            if (delivered) return
            // AutoDevelopQueue.onIo closes Closeable results when complete loses to cancellation.
            if (!future.cancel(false)) {
                try { future.getNow(null)?.close() }
                catch (_: CompletionException) { /* No successful delivery was created. */ }
                catch (_: CancellationException) { /* onIo retains responsibility for late output. */ }
            }
        }
    }

    private fun requireOwnedPublishedRow(resolver: ContentResolver, uri: Uri, name: String, owner: String) {
        require(uri.scheme == "content" && uri.authority == "media" && ContentUris.parseId(uri) > 0)
        val columns = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.OWNER_PACKAGE_NAME, MediaStore.MediaColumns.IS_PENDING)
        requireNotNull(resolver.query(uri, columns, null, null, null)).use { cursor ->
            check(cursor.count == 1 && cursor.moveToFirst())
            check(cursor.getLong(0) == ContentUris.parseId(uri) && cursor.getString(1) == name &&
                cursor.getString(2) == owner && !cursor.isNull(3) && cursor.getInt(3) == 0)
        }
    }

    private fun deleteExactOwnedRow(resolver: ContentResolver, uri: Uri, name: String, owner: String) {
        requireOwnedPublishedRow(resolver, uri, name, owner)
        check(resolver.delete(uri,
            "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.IS_PENDING}=0",
            arrayOf(owner, name)) == 1)
    }
}
