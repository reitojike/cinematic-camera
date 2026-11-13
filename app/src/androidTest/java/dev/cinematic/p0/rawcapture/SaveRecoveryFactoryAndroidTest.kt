package dev.cinematic.p0.rawcapture

import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.jpegrender.original.Original24PreflightGeometry
import dev.cinematic.p0.jpegrender.original.Original24Request
import dev.cinematic.p0.raw.PhotoAdjustments
import dev.cinematic.p0.testing.writeSyntheticTrialDng
import java.io.File
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Emulator-only synthetic source and app-private records. No renderer/FGS/UI or host kill is claimed.
 * Invocation: isolated=true saveRecovery=true. Retain all fixture history, never delete held records. */
class SaveRecoveryFactoryAndroidTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private val geometry = Original24PreflightGeometry(256, 192, 256, 192, 1, 256, 192)
    private val allowed = AutoDevelopConditions(true, false, false, false, true, true, true, true, previewMemoryAvailable = true)

    @Test fun explicitRequestUpdatesPreparedIndexAndOldOwnerCannotStart() {
        val old = heldFixture()
        val intent = storage(old.request, CaptureJournalFile.MANUAL_REQUEST).read(ManualOriginalTrialRequestCodec.MAX_BYTES)
        val next = old.requestExplicitRecovery { false }.get(30, TimeUnit.SECONDS)
        assertEquals(old.request.generation + 1, next.request.generation)
        assertEquals(old.request.source, next.request.source)
        assertEquals(old.request.recipe, next.request.recipe)
        assertTrue(next.isExplicitRecoveryRequest)
        assertSame(next, AndroidManualOriginalTrialSaves.knownPrepared(old.request.source))
        assertSame(next, old.requestExplicitRecovery { false }.get(30, TimeUnit.SECONDS))
        assertSame(next, AndroidManualOriginalTrialSaves.restorePreparedGpu(app, old.request.source, geometry) { false }
            .get(30, TimeUnit.SECONDS))
        assertEquals(ManualOriginalTrialSavePhase.READY, next.state.value.phase)
        assertThrows(ExecutionException::class.java) { old.advance(allowed) { false }.get(30, TimeUnit.SECONDS) }
        assertArrayEquals(intent, storage(old.request, CaptureJournalFile.MANUAL_REQUEST).read(ManualOriginalTrialRequestCodec.MAX_BYTES))
        assertNull(storage(next.request, CaptureJournalFile.MANUAL_REQUEST).read(ManualOriginalTrialRequestCodec.MAX_BYTES))
        assertNull(storage(next.request, CaptureJournalFile.MANUAL_ATTEMPT).read(ManualTrialAttemptCodec.MAX_BYTES))
        assertFalse(next.hasUnreleasedResources)
        // Edit-only metadata must not supersede an accepted READY recovery before its first intent.
        assertThrows(ExecutionException::class.java) {
            AndroidManualOriginalTrialSaves.prepareGpu(app, old.request.source, geometry, .75, allowed, PhotoAdjustments.NONE) { false }
                .get(30, TimeUnit.SECONDS)
        }
        val restored = AndroidManualOriginalTrialSaves.restorePreparedGpu(app, old.request.source, geometry) { false }
            .get(30, TimeUnit.SECONDS)
        assertSame(next, restored)
        assertTrue(requireNotNull(restored).isExplicitRecoveryRequest)
        assertEquals(.5, restored.appliedStrength, 0.0)
        verifyOriginal(old.request)
    }

    @Test fun durablePublicReceiptBeforeSeedRejectsSeparateRequest() {
        val old = heldFixture()
        val request = old.request
        val fingerprint = OriginalFingerprint(4, "a".repeat(64))
        val render = ManualOriginalTrialRender(RenderedJpegRequest(request.captureId, request.generation, request.source,
            request.recipe, JpegOutputDescription(256, 192, 1, "sRGB", 4)),
            OriginalRasterGeometry(256, 192, 1, 256, 192, true), fingerprint, request.strength, 0.0)
        val record = JpegPublicationRecord(JpegPublicationTarget(request.captureId, request.generation, "b".repeat(32)),
            render, fingerprint, 3, JpegPublicationPhase.UNKNOWN, "content://media/external_primary/images/media/123456789")
        storage(request, CaptureJournalFile.JPEG_PUBLICATION).replace(JpegPublicationCodec.encode(record))
        JpegReconciliationReceipt(storage(request, CaptureJournalFile.JPEG_RECONCILIATION)).commit(
            JpegReconciliationObservation(record, JpegReconciliationFinding.PUBLIC_BYTES_MATCH, record.uri)) {}
        assertThrows(ExecutionException::class.java) { old.requestExplicitRecovery { false }.get(30, TimeUnit.SECONDS) }
        assertNull(storage(request, CaptureJournalFile.EXPLICIT_RECOVERY_EXPORT).read(SaveRecoveryCodec.MAX_BYTES))
        assertNull(storage(request, CaptureJournalFile.JPEG_RECOVERY).read(JpegPublicationCodec.MAX_BYTES))
        verifyOriginal(request)
    }

    private fun heldFixture(): AndroidManualOriginalTrialSaves.ManualOriginalTrialHandle {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("isolated") == "true" && args.getString("saveRecovery") == "true")
        assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish") && app.packageName == "dev.cinematic.p0")
        val resolver = app.contentResolver
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "recovery_${UUID.randomUUID()}.dng")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/x-adobe-dng")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Cinematic Camera Synthetic Tests/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }))
        val bytes = requireNotNull(resolver.openOutputStream(uri, "w")).use { writeSyntheticTrialDng(it, 256, 192, 1) }
        assertEquals(1, resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null))
        val source = SharedOriginalReference(uri.toString(), DngContentVerifier.identify(bytes,
            { requireNotNull(resolver.openInputStream(uri)) }), OriginalGrant.APP_OWNED)
        DevelopExecution.updateConditions(allowed)
        val old = AndroidManualOriginalTrialSaves.prepareGpu(app, source, geometry, .5, allowed, PhotoAdjustments.NONE) { false }
            .get(30, TimeUnit.SECONDS)
        storage(old.request, CaptureJournalFile.MANUAL_REQUEST).replace(ManualOriginalTrialRequestCodec.encode(old.request))
        assertEquals(ManualOriginalTrialSavePhase.HOLD, old.advance(allowed) { false }.get(30, TimeUnit.SECONDS).phase)
        return old
    }
    private fun storage(request: Original24Request, kind: CaptureJournalFile): AtomicJournalStorage {
        val directory = RenderedJpegFiles(File(app.noBackupFilesDir, "raw-captures"), request.captureId, request.generation).generationRoot()
        return AndroidAtomicJournalStorage(requireNotNull(directory.parentFile), request.generation.toString(), kind)
    }
    private fun verifyOriginal(request: Original24Request) = assertEquals(request.source.fingerprint,
        DngContentVerifier.verify(request.source.fingerprint,
            { requireNotNull(app.contentResolver.openInputStream(Uri.parse(request.source.uri))) }))
}
