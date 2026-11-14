package dev.cinematic.p0.jpegrender.original

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.opengl.EGL14
import dev.cinematic.p0.gpu.*
import dev.cinematic.p0.gpu.diag.GpuStageTap
import android.os.CancellationSignal
import android.os.SystemClock
import dev.cinematic.p0.photoroll.*
import dev.cinematic.p0.gpu.preview.GpuInputRequestDeadline
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.gpu.DevelopEngine
import dev.cinematic.p0.gpu.DevelopEngineSetting
import dev.cinematic.p0.gpu.EglContexts
import dev.cinematic.p0.gpu.GlCapabilities
import dev.cinematic.p0.gpu.GlThread
import dev.cinematic.p0.gpu.GpuAvailability
import dev.cinematic.p0.raw.PhotoAdjustments
import dev.cinematic.p0.raw.R7PreviewInputs
import dev.cinematic.p0.rawcapture.*
import dev.cinematic.p0.testing.writeSyntheticTrialDng
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*

import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic factory/publication wiring only. Pixel acceptance stays in the GPU engine suites. */
@RunWith(AndroidJUnit4::class)
class GpuPreviewPublicationSafetyAndroidTest {
    @Test fun over1024GpuPreviewThenExplicitSaveKeepsRecipeAndOnePublicJpeg() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        requireGpuSaveSafetyFixture()
        val capabilities = GlThread().use { thread -> thread.call {
            EglContexts(thread, app.assets).use { GlCapabilities.inspect(it) }
        } }
        assertTrue("GPU capabilities missing: ${capabilities.missing}", capabilities.supported)
        val setting = DevelopEngineSetting(app)
        assertFalse(setting.visible)
        val preferences = app.getSharedPreferences("capture-probe", Context.MODE_PRIVATE)
        val previousSetting = preferences.getString("develop/engine", null)
        val resolver = app.contentResolver
        val sourceName = "synthetic_original24_gpu_save_${UUID.randomUUID()}.dng"
        var sourceUri: Uri? = null
        var outputUri: Uri? = null
        var outputName: String? = null
        var handle: AndroidManualOriginalTrialSaves.ManualOriginalTrialHandle? = null
        val stopped = AtomicBoolean(false)
        val switchedToCpu = AtomicBoolean(false)
        val observedGpu = AtomicBoolean(false)
        val settingLock = Any()
        var success = false
        var failure: Throwable? = null
        try {
            assertFalse(setting.select(DevelopEngine.CPU, GpuAvailability.Available))
            assertEquals(DevelopEngine.GPU, setting.current())
            val originalUri = requireNotNull(resolver.insert(
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, sourceName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/x-adobe-dng")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Cinematic Camera Synthetic Tests/")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                })).also { sourceUri = it }
            val sourceBytes = requireNotNull(resolver.openOutputStream(originalUri, "w")).use {
                writeSyntheticTrialDng(it, 1152, 768, 6)
            }
            assertEquals(1, resolver.update(originalUri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null))
            val fingerprint = DngContentVerifier.identify(sourceBytes,
                { requireNotNull(resolver.openInputStream(originalUri)) })
            val source = SharedOriginalReference(originalUri.toString(), fingerprint, OriginalGrant.APP_OWNED)
            val allowed = AutoDevelopConditions(true, false, false, false, true, true, true, true,
                previewMemoryAvailable = true)
            val strength = .45
            val edits = PhotoAdjustments(20.0, .3)
            DevelopExecution.updateConditions(allowed)
            val save = AndroidManualOriginalTrialSaves.prepare(app, source, strength, allowed, edits, stopped::get)
                .get(30, TimeUnit.SECONDS).also { handle = it }
            assertEquals(source, save.request.source)
            assertEquals(strength, save.appliedStrength, 0.0)
            assertEquals(edits, save.appliedAdjustments)
            assertEquals(ManualOriginalTrialRender.recipe(strength, edits), save.request.recipe)
            assertTrue(save.matches(source))
            assertEquals(ManualOriginalTrialSavePhase.READY, save.state.value.phase)
            assertEquals(1152, save.geometry.rawWidth); assertEquals(768, save.geometry.rawHeight)
            assertEquals(1152, save.geometry.sourceWidth); assertEquals(768, save.geometry.sourceHeight)
            assertEquals(6, save.geometry.orientation)
            assertEquals(768, save.geometry.outputWidth); assertEquals(1152, save.geometry.outputHeight)

            // The original-size save and preview use the same bounded AS_SHOT analysis input.
            // Check base exposure plus manual EV without a CPU look or JPEG similarity threshold.
            val ready = PhotoRollResources(true, 0, false, 2L * 1024 * 1024 * 1024,
                PhotoRollCameraSnapshot(false, false, false, false), true, true)
            val input = GpuPhotoRollInputs.open(app, { ready }, PhotoRollRepository(resolver, app.packageName),
                originalUri, false, 64L * 1024 * 1024, CancellationSignal(),
                GpuInputRequestDeadline(nowMillis = SystemClock::elapsedRealtime), {}) { stopped.get() }
                .get(30, TimeUnit.SECONDS)
            assertEquals(source, input.original.reference)
            assertEquals(save.geometry, input.geometry)
            assertSame(save, AndroidManualOriginalTrialSaves.prepareGpu(app, source, input.geometry, strength,
                allowed, edits, stopped::get).get(30, TimeUnit.SECONDS))
            assertSame(save, AndroidManualOriginalTrialSaves.knownPrepared(source))
            val expectedEv = R7PreviewInputs.prepare(R7PreviewInputs.AsShotInput(input.image,
                R7PreviewInputs.Source.LIBRAW_PRELIMINARY_AS_SHOT), emptyList()).exposure.externalBaseEv + edits.exposureEv
            val directory = RenderedJpegFiles(File(app.noBackupFilesDir, "raw-captures"),
                save.request.captureId, save.request.generation).generationRoot()
            val scratch = File(directory, "original-work")
            val snapshot = File(scratch, "source-snapshot.dng")
            val journal = JpegPublicationJournal(save.request.captureId, save.request.generation,
                AndroidAtomicJournalStorage(requireNotNull(directory.parentFile), save.request.generation.toString(),
                    CaptureJournalFile.JPEG_PUBLICATION))
            assertFalse("Preparation cannot generate the saved JPEG", File(directory, "output.jpg").exists())
            assertNull(journal.read())

            assertEquals(682, input.image.width)
            assertEquals(1024, input.image.height)
            GlThread().use { thread -> thread.call {
                EglContexts(thread, app.assets).use { egl ->
                    val ledger = GpuMemoryLedger(GpuMemoryLedger.PREVIEW_BUDGET)
                    DefaultStageClearGpu(egl, ledger, GpuFrameMode.FULL_RECOMPUTE).use { engine ->
                        engine.openPhoto(input.image) { false }.use { photo ->
                            val displayed = engine.renderForTest(photo, GpuRenderParams(strength, edits), GpuStageTap.Point.DISPLAY8)
                            assertEquals(682, displayed.width)
                            assertEquals(1024, displayed.height)
                        }
                    }
                    assertEquals(0L, ledger.allocatedBytes)
                    assertEquals(0, ledger.textureCount)
                }
            } }
            assertFalse("Rendering a preview cannot create a saved JPEG", File(directory, "output.jpg").exists())
            assertNull("Rendering a preview cannot create publication authority", journal.read())
            val published = save.advance(allowed) {
                // The snapshot exists only after Backend.render has frozen beginExport(). Use
                // the existing stop callback, not a new product hook or a timing-dependent sleep.
                synchronized(settingLock) {
                    if (!stopped.get() && !switchedToCpu.get() && snapshot.isFile) {
                        check(preferences.edit().putString("develop/engine", "CPU").commit())
                        switchedToCpu.set(true)
                    }
                }
                // Only the real GPU producer invokes this save's callback with a current EGL
                // context. A CPU fallback would publish a JPEG but fail this routing witness.
                if (switchedToCpu.get() && EGL14.eglGetCurrentContext() != EGL14.EGL_NO_CONTEXT)
                    observedGpu.set(true)
                stopped.get()
            }.get(120, TimeUnit.SECONDS)
            assertEquals("Save result: $published", ManualOriginalTrialSavePhase.PUBLISHED, published.phase)
            assertTrue("Setting changed after the save snapshot", switchedToCpu.get())
            assertEquals(DevelopEngine.GPU, setting.current())
            assertTrue("The frozen GPU save must actually run with a current EGL context", observedGpu.get())
            assertFalse(save.hasUnreleasedResources)
            assertFalse("The producer must release its snapshot/raster workspace", scratch.exists())
            val jpegUri = Uri.parse(requireNotNull(published.uri)).also { outputUri = it }
            assertNotEquals(originalUri, jpegUri)
            val record = requireNotNull(journal.read())
            outputName = record.target.displayName
            assertEquals(JpegPublicationPhase.PUBLISHED, record.phase)
            assertEquals(published.uri, record.uri)
            val proof = record.render as ManualOriginalTrialRender
            assertEquals(source, proof.request.source)
            assertEquals(save.request.recipe, proof.request.recipe)
            assertEquals(strength, proof.appliedStrength, 0.0)
            assertEquals(edits, proof.adjustments)
            assertEquals(expectedEv, proof.externalEv, 0.0)
            assertEquals(OriginalRasterGeometry(1152, 768, 6, 768, 1152, true), proof.geometry)
            val inspection = AndroidJpegInspector().inspect(
                { requireNotNull(resolver.openInputStream(jpegUri)) }, record.fingerprint.bytes) { false }
            assertEquals(record.fingerprint, inspection.fingerprint)
            assertEquals(proof.request.output, inspection.output)
            assertEquals(768, inspection.output.width); assertEquals(1152, inspection.output.height)
            assertEquals(1, inspection.output.orientation)
            assertEquals("sRGB", inspection.output.color)
            val decoded = requireNotNull(resolver.openInputStream(jpegUri)).use {
                requireNotNull(BitmapFactory.decodeStream(it))
            }
            try {
                assertEquals(768, decoded.width); assertEquals(1152, decoded.height)
                assertTrue(requireNotNull(decoded.colorSpace).isSrgb)
            } finally { decoded.recycle() }

            val reopened = save.advance(allowed, stop = stopped::get).get(30, TimeUnit.SECONDS)
            assertEquals(ManualOriginalTrialSavePhase.PUBLISHED, reopened.phase)
            assertEquals(published.uri, reopened.uri)
            assertEquals(record, journal.read())
            assertEquals(record.fingerprint, DngContentVerifier.identify(record.fingerprint.bytes,
                { requireNotNull(resolver.openInputStream(jpegUri)) }))
            assertEquals(fingerprint, DngContentVerifier.verify(fingerprint,
                { requireNotNull(resolver.openInputStream(originalUri)) }))
            assertFalse(save.hasUnreleasedResources)
            AndroidManualOriginalTrialSaves.discardPreview(app).get(30, TimeUnit.SECONDS)
            success = true
        } catch (error: Throwable) { failure = error; throw error }
        finally {
            // Prevent any late callback from changing the setting after restoration. A cancelled
            // future is not a resource-close receipt; drain the same queue before touching rows.
            synchronized(settingLock) { stopped.set(true) }
            try {
                if (!success) {
                    handle?.requestStop()
                    AndroidManualOriginalTrialSaves.discardPreview(app).get(30, TimeUnit.SECONDS)
                    // Retain synthetic rows and private evidence after every failed/uncertain run.
                } else {
                    val originalUri = requireNotNull(sourceUri)
                    val jpegUri = requireNotNull(outputUri)
                    val jpegName = requireNotNull(outputName)
                    // Verify both exact owned rows before deleting either; no collection cleanup.
                    requireOwnedPublishedRow(resolver, originalUri, sourceName, app.packageName)
                    requireOwnedPublishedRow(resolver, jpegUri, jpegName, app.packageName)
                    deleteExactOwnedRow(resolver, jpegUri, jpegName, app.packageName)
                    deleteExactOwnedRow(resolver, originalUri, sourceName, app.packageName)
                }
            } catch (cleanup: Throwable) {
                if (failure == null) throw cleanup
                if (cleanup !== failure) failure.addSuppressed(cleanup)
            } finally {
                val editor = preferences.edit()
                if (previousSetting == null) editor.remove("develop/engine")
                else editor.putString("develop/engine", previousSetting)
                check(editor.commit()) { "Restore original develop-engine preference" }
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
