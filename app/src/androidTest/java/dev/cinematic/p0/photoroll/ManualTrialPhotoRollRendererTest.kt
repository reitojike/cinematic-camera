package dev.cinematic.p0.photoroll

import android.graphics.Bitmap
import android.net.Uri
import android.os.CancellationSignal
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.cinematic.p0.rawcapture.OriginalFingerprint
import dev.cinematic.p0.rawcapture.OriginalGrant
import dev.cinematic.p0.rawcapture.SharedOriginalReference
import dev.cinematic.p0.raw.PhotoAdjustments
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManualTrialPhotoRollRendererTest {
    private val original = PhotoRollOriginal(Uri.parse("content://test.example/original/1"),
        OriginalFingerprint(16, "1".repeat(64)), OriginalGrant.USER_SELECTED)

    @Test fun previewTransfersPixelsAndBindsTheSameManualStrengthWithoutGenerating() {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val adjustments = PhotoAdjustments(20.0, .3)
        var previewCalls = 0
        var savePreparations = 0
        var saveCalls = 0
        val preview = object : PhotoRollRenderer {
            override val mode = PhotoRollPreviewMode.PRIVATE_TRIAL
            override fun render(request: PhotoRollRenderRequest, cancellation: CancellationSignal): PhotoRollRenderResult {
                previewCalls++
                assertEquals(PhotoRollPreviewMode.PRIVATE_TRIAL, request.mode)
                return PhotoRollRenderResult.Preview(bitmap, mode, trialStrength = request.strength,
                    trialAdjustments = request.adjustments)
            }
        }
        val renderer = ManualTrialPhotoRollRenderer(preview) { source, strength, values ->
            savePreparations++
            assertEquals(original.reference, source.reference)
            assertEquals(adjustments, values)
            object : PhotoRollJpegSaveOwner {
                override val snapshots = MutableStateFlow(PhotoRollSaveSnapshot(PhotoRollSavePhase.READY))
                override val snapshot get() = snapshots.value
                override val width = 0
                override val height = 0
                override val appliedStrength = strength
                override val appliedAdjustments = values
                override fun matches(source: SharedOriginalReference, mode: PhotoRollPreviewMode) =
                    source == original.reference && mode == PhotoRollPreviewMode.MANUAL_TRIAL
                override fun advance(shouldStop: () -> Boolean): PhotoRollSaveSnapshot {
                    saveCalls++
                    return snapshot
                }
            }
        }
        val result = renderer.render(PhotoRollRenderRequest(original, strength = .37, adjustments = adjustments,
            mode = PhotoRollPreviewMode.MANUAL_TRIAL), CancellationSignal()) as PhotoRollRenderResult.Preview
        try {
            assertSame(bitmap, result.bitmap)
            assertEquals(PhotoRollPreviewMode.MANUAL_TRIAL, result.mode)
            assertEquals(.37, result.appliedStrength!!, 0.0)
            assertEquals(.37, result.jpegSave!!.appliedStrength!!, 0.0)
            assertEquals(adjustments, result.appliedAdjustments)
            assertEquals(adjustments, result.jpegSave!!.appliedAdjustments)
            assertEquals(1, previewCalls)
            assertEquals(1, savePreparations)
            assertEquals(0, saveCalls)
        } finally { result.recycleDisplayPixels() }
    }

    @Test fun mismatchedOwnerDoesNotEscapeAndReleasesPreviewPixels() {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val before = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val preview = object : PhotoRollRenderer {
            override val mode = PhotoRollPreviewMode.PRIVATE_TRIAL
            override fun render(request: PhotoRollRenderRequest, cancellation: CancellationSignal) =
                PhotoRollRenderResult.Preview(bitmap, mode, trialStrength = request.strength,
                    trialAdjustments = request.adjustments, comparisonBitmap = before)
        }
        val renderer = ManualTrialPhotoRollRenderer(preview) { _, _, _ -> object : PhotoRollJpegSaveOwner {
            override val snapshots = MutableStateFlow(PhotoRollSaveSnapshot(PhotoRollSavePhase.READY))
            override val snapshot get() = snapshots.value
            override val width = 0
            override val height = 0
            override val appliedStrength = .5
            override val appliedAdjustments = PhotoAdjustments()
            override fun matches(source: SharedOriginalReference, mode: PhotoRollPreviewMode) = true
            override fun advance(shouldStop: () -> Boolean) = snapshot
        } }
        assertThrows(PhotoRollSourceFailure::class.java) {
            renderer.render(PhotoRollRenderRequest(original, strength = .37,
                mode = PhotoRollPreviewMode.MANUAL_TRIAL), CancellationSignal())
        }
        assertTrue(bitmap.isRecycled)
        assertTrue(before.isRecycled)
    }

    @Test fun failedPreparationReleasesBothDisplayCopies() {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val before = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val preview = object : PhotoRollRenderer {
            override val mode = PhotoRollPreviewMode.PRIVATE_TRIAL
            override fun render(request: PhotoRollRenderRequest, cancellation: CancellationSignal) =
                PhotoRollRenderResult.Preview(bitmap, mode, comparisonBitmap = before,
                    trialStrength = request.strength, trialAdjustments = request.adjustments)
        }
        val renderer = ManualTrialPhotoRollRenderer(preview) { _, _, _ ->
            throw PhotoRollSourceFailure("Preparation failed")
        }
        assertThrows(PhotoRollSourceFailure::class.java) {
            renderer.render(PhotoRollRenderRequest(original, mode = PhotoRollPreviewMode.MANUAL_TRIAL), CancellationSignal())
        }
        assertTrue(bitmap.isRecycled)
        assertTrue(before.isRecycled)
    }
}
