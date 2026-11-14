package dev.cinematic.p0.jpegrender.original

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.opengl.EGL14
import android.provider.MediaStore
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.gpu.DevelopEngine
import dev.cinematic.p0.gpu.DevelopEngineSetting
import dev.cinematic.p0.gpu.EglContexts
import dev.cinematic.p0.gpu.GlCapabilities
import dev.cinematic.p0.gpu.GlThread
import dev.cinematic.p0.gpu.GpuAvailability
import dev.cinematic.p0.gpu.GpuContextLostException
import dev.cinematic.p0.raw.PhotoAdjustments
import dev.cinematic.p0.rawcapture.*
import dev.cinematic.p0.testing.writeSyntheticTrialDng
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #289: synthetic 12MP factory/export safety, never a physical-device or pixel-quality gate.
 * The exception injection is NOT a real driver loss. Current EGL witnesses route selection;
 * owner/workspace observations do not expose or prove the private GPU texture-ledger balance.
 * Cancellation before a render proof and UI/FGS retry are deliberately separate coverage.
 */
@RunWith(AndroidJUnit4::class)
class GpuFullSizeSaveSafetyAndroidTest {
    @Test fun pseudoContextLossRequiresExplicitRetryAndKeepsFrozenGpuAt12Mp() = withFixture { fixture ->
        val save = fixture.prepare(.45, PhotoAdjustments(20.0, .3))
        val request = save.request
        val beforeCount = fixture.failureCount()
        check(beforeCount in 0 until Long.MAX_VALUE) { "Failure counter must permit one observable increment" }
        val injected = GpuWitness(fixture, save, injectAt = INJECT_AT_GL_CHECKPOINT)

        val failed = save.advance(ALLOWED, stop = injected::stop).get(SAVE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        assertEquals(ManualOriginalTrialSavePhase.GPU_FAILED, failed.phase)
        assertEquals(ManualOriginalTrialSaveReason.OPERATION_FAILED, failed.reason)
        assertTrue("A failed image producer must not publish a URI", failed.uri == null)
        assertEquals(GpuSaveFailure(GpuSaveFailureReason.CONTEXT_LOST, 1), failed.gpuFailure)
        assertTrue("The one-shot GL-side pseudo exception must be reached", injected.injected.get())
        assertEquals(INJECT_AT_GL_CHECKPOINT, injected.checkpoints.get())
        assertTrue("GPU entry must observe the source snapshot and full RGB16 raster", injected.sourceReady.get())
        assertEquals(DevelopEngine.GPU, fixture.setting.current())
        fixture.assertFailedProducerClosed(save)
        fixture.assertOriginalUnchanged()
        fixture.assertFailureRecord(beforeCount + 1, GpuSaveFailureReason.CONTEXT_LOST.code)
        val intent = requireNotNull(fixture.intent(save).read(ManualOriginalTrialRequestCodec.MAX_BYTES))
        assertTrue("A retry must retain the exact durable intent",
            intent.contentEquals(ManualOriginalTrialRequestCodec.encode(request)))

        // A normal advance may inspect/release the owner, but must not silently render again.
        val ordinary = GpuWitness(fixture, save)
        val unchanged = save.advance(ALLOWED, stop = ordinary::stop).get(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        assertTrue("Normal advance must keep the same retryable failure", failed == unchanged)
        assertEquals(0, ordinary.checkpoints.get())
        fixture.assertFailedProducerClosed(save)
        fixture.assertFailureRecord(beforeCount + 1, GpuSaveFailureReason.CONTEXT_LOST.code)
        assertTrue("Normal advance must not replace the durable intent",
            intent.contentEquals(requireNotNull(fixture.intent(save).read(ManualOriginalTrialRequestCodec.MAX_BYTES))))
        fixture.assertOriginalUnchanged()

        // Change only the next-save preference. This same generation must still retry on GPU.
        check(fixture.app.getSharedPreferences("capture-probe", Context.MODE_PRIVATE).edit().putString(ENGINE_KEY, "CPU").commit())
        assertEquals(DevelopEngine.GPU, fixture.setting.current())
        val retry = GpuWitness(fixture, save)
        val published = save.advance(ALLOWED, retryGpuFailure = true, stop = retry::stop)
            .get(SAVE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        assertTrue("Explicit retry must keep the original immutable request", request == save.request)
        assertEquals(GpuSaveFailure(GpuSaveFailureReason.CONTEXT_LOST, 1), published.gpuFailure)
        fixture.verifyPublication(save, published, retry)
        fixture.assertFailureRecord(beforeCount + 1, GpuSaveFailureReason.CONTEXT_LOST.code)
        assertEquals(DevelopEngine.GPU, fixture.setting.current())
        assertTrue("Successful retry must not erase or replace the durable intent",
            intent.contentEquals(requireNotNull(fixture.intent(save).read(ManualOriginalTrialRequestCodec.MAX_BYTES))))
        Log.i(TAG, "case=pseudo_context_loss pixels=12000000 injection=current_egl_callback " +
            "checkpoint=$INJECT_AT_GL_CHECKPOINT reason=gpu_context_lost ownerCount=1 " +
            "aggregateDelta=1 automaticRetry=false explicitRetry=published resourcesClosed=true")
    }

    @Test fun fiveConsecutiveGpuWritesUseNewGenerationsAndReleaseEachOwnerAt12Mp() = withFixture { fixture ->
        val beforeCount = fixture.failureCount()
        val beforeReason = fixture.failureReason()
        val generations = mutableSetOf<Long>()
        val recipes = mutableSetOf<RenderRecipeSnapshot>()
        val publications = mutableSetOf<Uri>()
        var previous: AndroidManualOriginalTrialSaves.ManualOriginalTrialHandle? = null
        repeat(5) { index ->
            // Every nonzero strength and pair of accepted edits is distinct. No published-handle
            // reconciliation is counted as a write, and every iteration must enter a real GL save.
            val strength = .25 + index * .1
            val edits = PhotoAdjustments(-20.0 + index * 10.0, -.2 + index * .1)
            val save = fixture.prepare(strength, edits)
            previous?.let {
                assertNotSame(it, save)
                assertFalse("The previous owner must be closed before a new generation", it.hasUnreleasedResources)
                assertEquals(it.request.generation + 1, save.request.generation)
            }
            assertTrue("Every write needs a distinct generation", generations.add(save.request.generation))
            assertTrue("Every write needs a distinct recipe", recipes.add(save.request.recipe))
            val witness = GpuWitness(fixture, save)
            val published = save.advance(ALLOWED, stop = witness::stop).get(SAVE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            assertNull(published.gpuFailure)
            val record = fixture.verifyPublication(save, published, witness)
            assertTrue("Every generation must publish its own JPEG", publications.add(Uri.parse(requireNotNull(record.uri))))
            fixture.assertFailureRecord(beforeCount, beforeReason)
            assertEquals(DevelopEngine.GPU, fixture.setting.current())
            previous = save
            Log.i(TAG, "case=consecutive_write pixels=12000000 completed=${index + 1} " +
                "newGeneration=true gpuObserved=true publicationVerified=true originalUnchanged=true resourcesClosed=true")
        }
        assertEquals(5, generations.size)
        assertEquals(5, recipes.size)
        assertEquals(5, publications.size)
        assertTrue("All five producer/publication owners must be closed", fixture.handles.all { !it.hasUnreleasedResources })
    }

    @Test fun optIn24MpGpuWritePublishesOriginalSizeAndReleasesOwners() {
        check(InstrumentationRegistry.getArguments().getString("gpuExport24") == "true") { "24MP execution requires gpuExport24=true" }
        withFixture(width = 6000, height = 4000) { fixture ->
            val beforeCount = fixture.failureCount()
            val beforeReason = fixture.failureReason()
            val save = fixture.prepare(.45, PhotoAdjustments(20.0, .3))
            val witness = GpuWitness(fixture, save)
            val published = save.advance(ALLOWED, stop = witness::stop)
                .get(SAVE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            assertNull(published.gpuFailure)
            fixture.verifyPublication(save, published, witness)
            fixture.assertFailureRecord(beforeCount, beforeReason)
            assertEquals(DevelopEngine.GPU, fixture.setting.current())
            Log.i(TAG, "case=single_24mp_write pixels=24000000 gpuObserved=true " +
                "publicationVerified=true originalUnchanged=true resourcesClosed=true")
        }
    }

    /** Count callbacks with a current EGL context, not rows, bands, passes, or elapsed time. */
    private class GpuWitness(
        private val fixture: Fixture,
        private val save: AndroidManualOriginalTrialSaves.ManualOriginalTrialHandle,
        private val injectAt: Int? = null,
    ) {
        val checkpoints = AtomicInteger()
        val sourceReady = AtomicBoolean()
        val injected = AtomicBoolean()

        fun stop(): Boolean {
            if (fixture.stopped.get()) return true
            if (EGL14.eglGetCurrentContext() != EGL14.EGL_NO_CONTEXT) {
                val checkpoint = checkpoints.incrementAndGet()
                if (checkpoint == 1) {
                    val scratch = fixture.scratch(save)
                    val snapshot = File(scratch, "source-snapshot.dng")
                    val ready = snapshot.isFile && snapshot.length() == fixture.source.fingerprint.bytes &&
                        File(scratch, "source-linear.rgb16").let {
                            it.isFile && it.length() == fixture.width.toLong() * fixture.height * 6
                        }
                    if (ready) {
                        DngContentVerifier.verify(fixture.source.fingerprint, { snapshot.inputStream() }, fixture.stopped::get)
                        sourceReady.set(true)
                    }
                }
                // Delayed, bounded pseudo failure exercises a started producer. Fence polling
                // also invokes stop: this number deliberately claims no exact band boundary.
                if (checkpoint == injectAt && injected.compareAndSet(false, true))
                    throw GpuContextLostException("Synthetic export pseudo context loss")
            }
            // The post-GL classification/cleanup checks must see false, not cancellation.
            return false
        }
    }

    private class Fixture(val app: Context, val width: Int, val height: Int) {
        val resolver: ContentResolver = app.contentResolver
        val setting = DevelopEngineSetting(app)
        val stopped = AtomicBoolean()
        val handles = mutableListOf<AndroidManualOriginalTrialSaves.ManualOriginalTrialHandle>()
        val outputs = mutableListOf<OwnedRow>()
        val sourceName = "synthetic_gpu_export_safety_${UUID.randomUUID()}.dng"
        var sourceUri: Uri? = null
        lateinit var source: SharedOriginalReference
        private val failures = app.getSharedPreferences("gpu-export-failures", Context.MODE_PRIVATE)

        fun createSource() {
            val uri = requireNotNull(resolver.insert(
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, sourceName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/x-adobe-dng")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Cinematic Camera Synthetic Tests/")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                })).also { sourceUri = it }
            // Streaming fixture generation avoids a second full-size source byte array.
            val bytes = requireNotNull(resolver.openOutputStream(uri, "w")).use {
                writeSyntheticTrialDng(it, width, height, 1)
            }
            assertTrue("Synthetic source must respect factory admission", bytes in 1..64L * 1024 * 1024)
            assertEquals(1, resolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null))
            val fingerprint = DngContentVerifier.identify(bytes, { requireNotNull(resolver.openInputStream(uri)) })
            source = SharedOriginalReference(uri.toString(), fingerprint, OriginalGrant.APP_OWNED)
        }

        fun prepare(strength: Double, edits: PhotoAdjustments): AndroidManualOriginalTrialSaves.ManualOriginalTrialHandle {
            val save = AndroidManualOriginalTrialSaves.prepare(app, source, strength, ALLOWED, edits, stopped::get)
                .get(PREPARE_TIMEOUT_SECONDS, TimeUnit.SECONDS).also(handles::add)
            assertTrue("Prepared source must remain the synthetic original", source == save.request.source && save.matches(source))
            assertEquals(strength, save.appliedStrength, 0.0)
            assertEquals(edits, save.appliedAdjustments)
            assertEquals(ManualOriginalTrialRender.recipe(strength, edits), save.request.recipe)
            assertEquals(ManualOriginalTrialSavePhase.READY, save.state.value.phase)
            assertEquals(width, save.geometry.rawWidth); assertEquals(height, save.geometry.rawHeight)
            assertEquals(width, save.geometry.sourceWidth); assertEquals(height, save.geometry.sourceHeight)
            assertEquals(width, save.geometry.outputWidth); assertEquals(height, save.geometry.outputHeight)
            assertEquals(1, save.geometry.orientation)
            assertFalse("Preparation must not create a JPEG", File(directory(save), "output.jpg").exists())
            assertTrue("A new generation must not already have a publication", journal(save).read() == null)
            assertFalse(save.hasUnreleasedResources)
            return save
        }

        fun directory(save: AndroidManualOriginalTrialSaves.ManualOriginalTrialHandle) =
            RenderedJpegFiles(File(app.noBackupFilesDir, "raw-captures"), save.request.captureId,
                save.request.generation).generationRoot()
        fun scratch(save: AndroidManualOriginalTrialSaves.ManualOriginalTrialHandle) = File(directory(save), "original-work")
        private fun storage(save: AndroidManualOriginalTrialSaves.ManualOriginalTrialHandle, file: CaptureJournalFile) =
            AndroidAtomicJournalStorage(requireNotNull(directory(save).parentFile), save.request.generation.toString(), file)
        fun intent(save: AndroidManualOriginalTrialSaves.ManualOriginalTrialHandle) = storage(save, CaptureJournalFile.MANUAL_REQUEST)
        fun journal(save: AndroidManualOriginalTrialSaves.ManualOriginalTrialHandle) =
            JpegPublicationJournal(save.request.captureId, save.request.generation, storage(save, CaptureJournalFile.JPEG_PUBLICATION))

        fun assertOriginalUnchanged() {
            val verified = DngContentVerifier.verify(source.fingerprint,
                { requireNotNull(resolver.openInputStream(requireNotNull(sourceUri))) })
            assertEquals(source.fingerprint, verified)
            requireOwnedPublishedRow(resolver, OwnedRow(requireNotNull(sourceUri), sourceName), app.packageName)
        }

        fun assertFailedProducerClosed(save: AndroidManualOriginalTrialSaves.ManualOriginalTrialHandle) {
            assertFalse("The failed producer must release all observable owner resources", save.hasUnreleasedResources)
            assertFalse("The failed producer must remove its snapshot/RGB16 workspace", scratch(save).exists())
            assertFalse("A pre-JPEG GPU failure must not retain an output", File(directory(save), "output.jpg").exists())
            assertTrue("A pre-JPEG GPU failure must not reach publication", journal(save).read() == null)
        }

        fun failureCount() = failures.getLong("count", 0)
        fun failureReason(): String? = failures.getString("lastReason", null)
        fun assertFailureRecord(count: Long, reason: String?) {
            // Only observe the real AndroidGpuSaveFailures store. Do not seed/reset the count.
            assertEquals(count, failureCount())
            assertEquals(reason, failureReason())
        }

        fun verifyPublication(save: AndroidManualOriginalTrialSaves.ManualOriginalTrialHandle,
            state: ManualOriginalTrialSaveState, witness: GpuWitness): JpegPublicationRecord {
            assertEquals(ManualOriginalTrialSavePhase.PUBLISHED, state.phase)
            assertTrue("Every completed write must actually enter GPU with current EGL", witness.checkpoints.get() > 0)
            assertTrue("Every write must rebuild the snapshot and complete RGB16 source", witness.sourceReady.get())
            assertFalse("Producer and publisher resources must be closed", save.hasUnreleasedResources)
            assertFalse("Successful write must remove its snapshot/RGB16 workspace", scratch(save).exists())
            val uri = Uri.parse(requireNotNull(state.uri))
            assertTrue("JPEG publication must not replace the source row", uri != sourceUri)
            val record = requireNotNull(journal(save).read())
            outputs += OwnedRow(uri, record.target.displayName)
            assertEquals(JpegPublicationPhase.PUBLISHED, record.phase)
            assertTrue("Publication journal and owner must agree", record.uri == state.uri)
            val proof = record.render as ManualOriginalTrialRender
            assertTrue("Render proof must identify this source", proof.request.source == source)
            assertTrue("Render proof must identify this generation", proof.request.captureId == save.request.captureId)
            assertEquals(save.request.generation, proof.request.generation)
            assertEquals(save.request.recipe, proof.request.recipe)
            assertEquals(save.appliedStrength, proof.appliedStrength, 0.0)
            assertEquals(save.appliedAdjustments, proof.adjustments)
            assertEquals(OriginalRasterGeometry(width, height, 1, width, height, true), proof.geometry)
            requireOwnedPublishedRow(resolver, outputs.last(), app.packageName)
            val inspection = AndroidJpegInspector().inspect(
                { requireNotNull(resolver.openInputStream(uri)) }, record.fingerprint.bytes) { false }
            assertEquals(record.fingerprint, inspection.fingerprint)
            assertEquals(proof.request.output, inspection.output)
            assertEquals(width, inspection.output.width); assertEquals(height, inspection.output.height)
            assertEquals(1, inspection.output.orientation)
            assertEquals("sRGB", inspection.output.color)
            // Decode the actual JPEG at a bounded sample size, not a CPU look or pixel oracle.
            val decoded = requireNotNull(resolver.openInputStream(uri)).use {
                requireNotNull(BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 8 }))
            }
            try {
                assertEquals(width / 8, decoded.width); assertEquals(height / 8, decoded.height)
                assertTrue(requireNotNull(decoded.colorSpace).isSrgb)
            } finally { decoded.recycle() }
            assertOriginalUnchanged()
            return record
        }
    }

    private data class OwnedRow(val uri: Uri, val name: String)

    private fun withFixture(width: Int = WIDTH, height: Int = HEIGHT, run: (Fixture) -> Unit) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        requireGpuSaveSafetyFixture()
        val capabilities = GlThread().use { thread -> thread.call {
            EglContexts(thread, app.assets).use { GlCapabilities.inspect(it) }
        } }
        assertTrue("GPU capabilities missing: ${capabilities.missing}", capabilities.supported)
        assertTrue("The source width requires matching texture support", capabilities.maxTextureSize >= width)
        val preferences = app.getSharedPreferences("capture-probe", Context.MODE_PRIVATE)
        val hadEngine = preferences.contains(ENGINE_KEY)
        val previousEngine = preferences.getString(ENGINE_KEY, null)
        val fixture = Fixture(app, width, height)
        var success = false
        var failure: Throwable? = null
        try {
            assertFalse(fixture.setting.visible)
            assertFalse(fixture.setting.select(DevelopEngine.CPU, GpuAvailability.Available))
            assertEquals(DevelopEngine.GPU, fixture.setting.current())
            DevelopExecution.updateConditions(ALLOWED)
            fixture.createSource()
            run(fixture)
            // Same serial queue drain is a resource-close boundary; Future cancellation is not.
            AndroidManualOriginalTrialSaves.discardPreview(app).get(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            assertTrue(fixture.handles.all { !it.hasUnreleasedResources })
            fixture.assertOriginalUnchanged()
            success = true
        } catch (error: Throwable) { failure = error; throw error }
        finally {
            fixture.stopped.set(true)
            var terminal = failure
            fun attempt(action: () -> Unit) {
                try { action() } catch (error: Throwable) {
                    val first = terminal
                    if (first == null) terminal = error
                    else if (first !== error) first.addSuppressed(error)
                }
            }
            if (!success) {
                fixture.handles.forEach { handle -> attempt { handle.requestStop() } }
                attempt {
                    AndroidManualOriginalTrialSaves.discardPreview(app).get(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                }
                // Preserve exact synthetic rows/private evidence on every failed or uncertain run.
            }
            attempt {
                val editor = preferences.edit()
                if (hadEngine) editor.putString(ENGINE_KEY, previousEngine) else editor.remove(ENGINE_KEY)
                check(editor.commit()) { "Restore exact prior develop-engine preference" }
                check(preferences.contains(ENGINE_KEY) == hadEngine && preferences.getString(ENGINE_KEY, null) == previousEngine)
            }
            if (success && terminal == null) attempt {
                val source = OwnedRow(requireNotNull(fixture.sourceUri), fixture.sourceName)
                val rows = fixture.outputs + source
                assertEquals("Cleanup targets must be distinct", rows.size, rows.map { it.uri }.toSet().size)
                // Validate every exact owned row before deleting any. No collection cleanup.
                rows.forEach { requireOwnedPublishedRow(fixture.resolver, it, app.packageName) }
                rows.forEach { row ->
                    requireOwnedPublishedRow(fixture.resolver, row, app.packageName)
                    check(fixture.resolver.delete(row.uri,
                        "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.IS_PENDING}=0",
                        arrayOf(app.packageName, row.name)) == 1)
                }
            }
            if (failure == null) terminal?.let { throw it }
        }
    }

    companion object {
        private const val TAG = "GpuExportSafety"
        private const val ENGINE_KEY = "develop/engine"
        private const val WIDTH = 4000
        private const val HEIGHT = 3000
        private const val INJECT_AT_GL_CHECKPOINT = 1024
        // Safety bounds only, not speed targets or performance measurements.
        private const val PREPARE_TIMEOUT_SECONDS = 120L
        private const val SAVE_TIMEOUT_SECONDS = 600L
        private const val CLOSE_TIMEOUT_SECONDS = 60L
        private val ALLOWED = AutoDevelopConditions(true, false, false, false, true, true, true, true,
            previewMemoryAvailable = true)

        private fun requireOwnedPublishedRow(resolver: ContentResolver, row: OwnedRow, owner: String) {
            require(row.uri.scheme == "content" && row.uri.authority == "media" && ContentUris.parseId(row.uri) > 0)
            val columns = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.OWNER_PACKAGE_NAME, MediaStore.MediaColumns.IS_PENDING)
            requireNotNull(resolver.query(row.uri, columns, null, null, null)).use { cursor ->
                check(cursor.count == 1 && cursor.moveToFirst())
                check(cursor.getLong(0) == ContentUris.parseId(row.uri) && cursor.getString(1) == row.name &&
                    cursor.getString(2) == owner && !cursor.isNull(3) && cursor.getInt(3) == 0)
            }
        }
    }
}
