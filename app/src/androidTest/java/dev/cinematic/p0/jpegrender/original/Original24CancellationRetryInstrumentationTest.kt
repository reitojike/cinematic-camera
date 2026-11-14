package dev.cinematic.p0.jpegrender.original

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.opengl.EGL14
import android.os.Build
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.gpu.*
import dev.cinematic.p0.raw.PhotoAdjustments
import dev.cinematic.p0.rawcapture.*
import dev.cinematic.p0.testing.writeSyntheticTrialDng
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** #345: actual factory cancellation and same-generation explicit retry. Small synthetic DNG,
 * real GPU producer and JPEG publication; no pixel oracle, performance or device-quality gate.
 * The CPU checkpoint is identified from its real renderer stack; no product test hook is added.
 */
@RunWith(AndroidJUnit4::class)
class Original24CancellationRetryInstrumentationTest {
    @get:org.junit.Rule val isolatedUi = dev.cinematic.p0.photoroll.SyntheticFixtureRule()

    @Test fun normalGpuSaveCancellationRequiresExplicitRetryBeforePublication() = runCase(DevelopEngine.GPU, ordinary = true)
    @Test fun normalGpuSaveCancellationAfterJpegEntryRemainsHeld() = runCase(DevelopEngine.GPU, afterJpegEntry = true, ordinary = true)
    @Test fun normalGpuConfirmedCancellationCanPublishChangedRecipeWithoutRetryingOldSave() =
        runCase(DevelopEngine.GPU, ordinary = true, newGeneration = true)
    @Test fun normalGpuConfirmedCancellationCanChangeRecipeThroughOrdinaryUi() =
        runCase(DevelopEngine.GPU, ordinary = true, newGeneration = true, editThroughUi = true)

    private fun runCase(engine: DevelopEngine, afterJpegEntry: Boolean = false, ordinary: Boolean = true,
        newGeneration: Boolean = false, editThroughUi: Boolean = false) {
        val installed = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("isolated") == "true" && args.getString("saveCancellationRetry") == "true")
        assumeTrue("Physical devices are excluded", Build.HARDWARE in setOf("ranchu", "goldfish"))
        // The ordinary build is fixed GPU without a setting UI.
        check(ordinary && installed.packageName == "dev.cinematic.p0")
        val app = isolatedGpuSaveSafetyApp()
        val setting = DevelopEngineSetting(app)
        if (engine == DevelopEngine.GPU) {
            val capabilities = GlThread().use { thread -> thread.call {
                EglContexts(thread, app.assets).use { GlCapabilities.inspect(it) }
            } }
            assertTrue("Required GPU capabilities: ${capabilities.missing}", capabilities.supported)
        }
        assertEquals(!ordinary, setting.visible)
        if (ordinary) assertEquals(DevelopEngine.GPU, setting.current())
        val preferences = app.getSharedPreferences("capture-probe", Context.MODE_PRIVATE)
        val hadEngine = preferences.contains(ENGINE_KEY)
        val oldEngine = preferences.getString(ENGINE_KEY, null)
        val failures = app.getSharedPreferences("gpu-export-failures", Context.MODE_PRIVATE)
        val beforeFailureCount = failures.getLong("count", 0)
        val beforeFailureReason = failures.getString("lastReason", null)
        val resolver = app.contentResolver
        val sourceName = "synthetic_cancel_retry_${UUID.randomUUID()}.dng"
        var sourceUri: Uri? = null
        var source: SharedOriginalReference? = null
        var output: OwnedRow? = null
        var handle: AndroidManualOriginalTrialSaves.ManualOriginalTrialHandle? = null
        val stopped = AtomicBoolean(false)
        var success = false
        var failure: Throwable? = null
        try {
            if (!ordinary) assertTrue(setting.select(engine, GpuAvailability.Available))
            assertEquals(engine, setting.current())
            val original = requireNotNull(resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, sourceName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/x-adobe-dng")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Cinematic Camera Synthetic Tests/")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                })).also { sourceUri = it }
            val bytes = requireNotNull(resolver.openOutputStream(original, "w")).use {
                writeSyntheticTrialDng(it, WIDTH, HEIGHT, 1)
            }
            assertEquals(1, resolver.update(original, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null))
            val fingerprint = DngContentVerifier.identify(bytes, { requireNotNull(resolver.openInputStream(original)) })
            val reference = SharedOriginalReference(original.toString(), fingerprint, OriginalGrant.APP_OWNED).also { source = it }
            DevelopExecution.updateConditions(ALLOWED)
            val save = AndroidManualOriginalTrialSaves.prepare(app, reference, .65, ALLOWED, PhotoAdjustments(20.0, .3))
                .get(60, TimeUnit.SECONDS).also { handle = it }
            val request = save.request
            val directory = RenderedJpegFiles(File(app.noBackupFilesDir, "raw-captures"), request.captureId,
                request.generation).generationRoot()
            val scratch = File(directory, "original-work")
            fun storage(kind: CaptureJournalFile) = AndroidAtomicJournalStorage(requireNotNull(directory.parentFile),
                request.generation.toString(), kind)
            val intent = storage(CaptureJournalFile.MANUAL_REQUEST)
            val journal = JpegPublicationJournal(request.captureId, request.generation, storage(CaptureJournalFile.JPEG_PUBLICATION))
            val cancelledWitness = Witness(engine)
            val jpegEntrySeen = AtomicBoolean(false)
            val cancelled = save.advance(ALLOWED) {
                val producerSeen = cancelledWitness.observe()
                // openOutput creates this exact private file before the encoder's first checkpoint.
                // Even an empty file is past the conservative JPEG-entry boundary, not retry proof.
                if (afterJpegEntry && File(directory, "output.jpg").exists()) jpegEntrySeen.set(true)
                if (if (afterJpegEntry) jpegEntrySeen.get() else producerSeen) stopped.set(true)
                stopped.get()
            }.get(180, TimeUnit.SECONDS)
            assertTrue("Cancellation must reach the selected actual image producer", cancelledWitness.seen.get())
            assertFalse("CPU path must not create a GPU context", engine == DevelopEngine.CPU && cancelledWitness.sawGpu.get())
            assertEquals(if (afterJpegEntry) ManualOriginalTrialSavePhase.HOLD else ManualOriginalTrialSavePhase.CANCELLED, cancelled.phase)
            assertEquals(if (afterJpegEntry) ManualOriginalTrialSaveReason.OPERATION_FAILED else ManualOriginalTrialSaveReason.STOPPED, cancelled.reason)
            assertNull(cancelled.uri)
            assertNull(cancelled.gpuFailure)
            assertFalse(save.hasUnreleasedResources)
            cancelledWitness.assertGpuThreadEnded()
            assertFalse("Cancelled producer must remove its snapshot/RGB16 workspace", scratch.exists())
            assertFalse("Stopped producer cleanup must remove its private failed output", File(directory, "output.jpg").exists())
            assertNull(journal.read())
            val originalIntent = requireNotNull(intent.read(ManualOriginalTrialRequestCodec.MAX_BYTES))
            assertArrayEquals(ManualOriginalTrialRequestCodec.encode(request), originalIntent)
            assertEquals(fingerprint, DngContentVerifier.verify(fingerprint, { requireNotNull(resolver.openInputStream(original)) }))

            stopped.set(false)
            val ordinaryWitness = Witness(engine)
            val ordinary = save.advance(ALLOWED) { ordinaryWitness.observe(); false }.get(30, TimeUnit.SECONDS)
            assertEquals("A normal observation cannot silently start another producer", cancelled, ordinary)
            assertFalse(ordinaryWitness.seen.get())
            assertFalse(save.hasUnreleasedResources)
            assertNull(journal.read())
            assertSame("Same source/recipe must retain this immutable owner", save,
                AndroidManualOriginalTrialSaves.prepare(app, reference, request.strength, ALLOWED, request.adjustments)
                    .get(30, TimeUnit.SECONDS))

            if (newGeneration) {
                val next = if (editThroughUi) dev.cinematic.p0.photoroll.ConfirmedCancellationEditProbe.publish(app,
                    reference, sourceName, save, isolatedUi) else AndroidManualOriginalTrialSaves.prepare(app, reference, .35, ALLOWED,
                        PhotoAdjustments(-10.0, -.2)).get(30, TimeUnit.SECONDS)
                // Closing the UI publishes foreground=false; subsequent factory-only assertions
                // use the same explicit fixture admission as this test's initial cancellation.
                DevelopExecution.updateConditions(ALLOWED)
                assertEquals(request.generation + 1, next.request.generation)
                assertEquals(reference, next.request.source)
                assertNotEquals(request.recipe, next.request.recipe)
                assertSame(next, AndroidManualOriginalTrialSaves.prepare(app, reference, next.request.strength, ALLOWED, next.request.adjustments)
                    .get(30, TimeUnit.SECONDS))
                val retired = assertThrows(java.util.concurrent.ExecutionException::class.java) {
                    save.advance(ALLOWED, retryCancellation = true) { false }.get(30, TimeUnit.SECONDS)
                }
                assertTrue(retired.cause is CaptureJournalConflict)
                val witness = Witness(engine)
                val published = if (editThroughUi) next.state.value
                    else next.advance(ALLOWED) { witness.observe(); false }.get(180, TimeUnit.SECONDS)
                assertEquals(ManualOriginalTrialSavePhase.PUBLISHED, published.phase)
                if (!editThroughUi) {
                    assertTrue(witness.seen.get())
                    witness.assertGpuThreadEnded()
                }
                assertFalse(next.hasUnreleasedResources)
                val nextDirectory = RenderedJpegFiles(File(app.noBackupFilesDir, "raw-captures"),
                    next.request.captureId, next.request.generation).generationRoot()
                val nextJournal = JpegPublicationJournal(next.request.captureId, next.request.generation,
                    AndroidAtomicJournalStorage(requireNotNull(nextDirectory.parentFile), next.request.generation.toString(),
                        CaptureJournalFile.JPEG_PUBLICATION))
                val record = requireNotNull(nextJournal.read())
                val proof = record.render as ManualOriginalTrialRender
                assertEquals(next.request.recipe, proof.request.recipe)
                assertEquals(next.request.generation, proof.request.generation)
                assertEquals(reference, proof.request.source)
                val jpeg = Uri.parse(requireNotNull(published.uri))
                output = OwnedRow(jpeg, record.target.displayName)
                requireOwnedRow(resolver, requireNotNull(output), app.packageName)
                val inspection = AndroidJpegInspector().inspect({ requireNotNull(resolver.openInputStream(jpeg)) }, record.fingerprint.bytes) { false }
                assertEquals(record.fingerprint, inspection.fingerprint)
                assertEquals(proof.request.output, inspection.output)
                assertFalse(File(nextDirectory, "original-work").exists())
                val reconciled = Witness(engine)
                assertEquals(published, next.advance(ALLOWED) { reconciled.observe(); false }.get(30, TimeUnit.SECONDS))
                assertFalse(reconciled.seen.get())
                assertNull(journal.read())
                assertArrayEquals(originalIntent, intent.read(ManualOriginalTrialRequestCodec.MAX_BYTES))
                assertEquals(fingerprint, DngContentVerifier.verify(fingerprint, { requireNotNull(resolver.openInputStream(original)) }))
                AndroidManualOriginalTrialSaves.discardPreview(app).get(30, TimeUnit.SECONDS)
                success = true
                return
            }

            if (afterJpegEntry) {
                assertTrue("Negative control must cross the real output-open boundary", jpegEntrySeen.get())
                val heldWitness = Witness(engine)
                val held = save.advance(ALLOWED, retryCancellation = true) {
                    heldWitness.observe(); false
                }.get(30, TimeUnit.SECONDS)
                assertEquals("Explicit retry cannot bypass uncertain JPEG outcome", cancelled, held)
                assertFalse(heldWitness.seen.get())
                assertFalse(save.hasUnreleasedResources)
                assertNull(journal.read())
                assertArrayEquals(originalIntent, intent.read(ManualOriginalTrialRequestCodec.MAX_BYTES))
                assertEquals(beforeFailureCount, failures.getLong("count", 0))
                assertEquals(beforeFailureReason, failures.getString("lastReason", null))
                assertEquals(fingerprint, DngContentVerifier.verify(fingerprint, { requireNotNull(resolver.openInputStream(original)) }))
                AndroidManualOriginalTrialSaves.discardPreview(app).get(30, TimeUnit.SECONDS)
                success = true
                return
            }

            val retryWitness = Witness(engine)
            val published = save.advance(ALLOWED, retryCancellation = true) {
                retryWitness.observe()
                stopped.get()
            }.get(180, TimeUnit.SECONDS)
            assertTrue("Explicit retry must execute the same selected producer", retryWitness.seen.get())
            assertFalse(engine == DevelopEngine.CPU && retryWitness.sawGpu.get())
            retryWitness.assertGpuThreadEnded()
            assertEquals(request, save.request)
            assertEquals(engine, setting.current())
            assertEquals(ManualOriginalTrialSavePhase.PUBLISHED, published.phase)
            assertNull(published.gpuFailure)
            assertFalse(save.hasUnreleasedResources)
            assertFalse(scratch.exists())
            assertArrayEquals(originalIntent, intent.read(ManualOriginalTrialRequestCodec.MAX_BYTES))
            val record = requireNotNull(journal.read())
            val jpeg = Uri.parse(requireNotNull(published.uri))
            output = OwnedRow(jpeg, record.target.displayName)
            assertNotEquals(original, jpeg)
            assertEquals(JpegPublicationPhase.PUBLISHED, record.phase)
            assertEquals(published.uri, record.uri)
            val proof = record.render as ManualOriginalTrialRender
            assertEquals(reference, proof.request.source)
            assertEquals(request.generation, proof.request.generation)
            assertEquals(request.recipe, proof.request.recipe)
            assertEquals(request.captureId, proof.request.captureId)
            requireOwnedRow(resolver, requireNotNull(output), app.packageName)
            val inspection = AndroidJpegInspector().inspect({ requireNotNull(resolver.openInputStream(jpeg)) }, record.fingerprint.bytes) { false }
            assertEquals(record.fingerprint, inspection.fingerprint)
            assertEquals(proof.request.output, inspection.output)
            assertEquals(WIDTH, inspection.output.width)
            assertEquals(HEIGHT, inspection.output.height)
            assertEquals(1, inspection.output.orientation)
            assertEquals("sRGB", inspection.output.color)

            val reconcileWitness = Witness(engine)
            val same = save.advance(ALLOWED) { reconcileWitness.observe(); false }.get(30, TimeUnit.SECONDS)
            assertEquals(published, same)
            assertEquals(record, journal.read())
            assertFalse("Published retry must reconcile rather than create a second JPEG", reconcileWitness.seen.get())
            assertEquals(beforeFailureCount, failures.getLong("count", 0))
            assertEquals(beforeFailureReason, failures.getString("lastReason", null))
            assertEquals(fingerprint, DngContentVerifier.verify(fingerprint, { requireNotNull(resolver.openInputStream(original)) }))
            AndroidManualOriginalTrialSaves.discardPreview(app).get(30, TimeUnit.SECONDS)
            assertFalse(save.hasUnreleasedResources)
            success = true
        } catch (error: Throwable) { failure = error; throw error }
        finally {
            stopped.set(true)
            preserveSafetyCleanup(failure, {
                if (!success) {
                    handle?.requestStop()
                    AndroidManualOriginalTrialSaves.discardPreview(app).get(60, TimeUnit.SECONDS)
                    // Failed/uncertain runs retain synthetic rows and journals for inspection.
                } else {
                    val original = OwnedRow(requireNotNull(sourceUri), sourceName)
                    val rows = listOfNotNull(output) + original
                    assertEquals(if (afterJpegEntry) 1 else 2, rows.size)
                    assertEquals(rows.size, rows.map { it.uri }.toSet().size)
                    val reference = requireNotNull(source)
                    assertEquals(reference.fingerprint, DngContentVerifier.verify(reference.fingerprint,
                        { requireNotNull(resolver.openInputStream(original.uri)) }))
                    rows.forEach { requireOwnedRow(resolver, it, app.packageName) }
                    rows.forEach { row ->
                        requireOwnedRow(resolver, row, app.packageName)
                        check(resolver.delete(row.uri,
                            "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.IS_PENDING}=0",
                            arrayOf(app.packageName, row.name)) == 1)
                    }
                }
            }, {
                val editor = preferences.edit()
                if (hadEngine) editor.putString(ENGINE_KEY, oldEngine) else editor.remove(ENGINE_KEY)
                check(editor.commit())
                check(preferences.contains(ENGINE_KEY) == hadEngine && preferences.getString(ENGINE_KEY, null) == oldEngine)
            })
        }
    }

    private class Witness(private val engine: DevelopEngine) {
        val seen = AtomicBoolean(false)
        val sawGpu = AtomicBoolean(false)
        private val glThread = AtomicReference<Thread>()
        fun observe(): Boolean {
            val currentGpu = EGL14.eglGetCurrentContext() != EGL14.EGL_NO_CONTEXT
            if (currentGpu) {
                sawGpu.set(true)
                glThread.compareAndSet(null, Thread.currentThread())
            }
            val selected = currentGpu
            if (selected) seen.set(true)
            return selected
        }
        fun assertGpuThreadEnded() {
            if (engine == DevelopEngine.GPU) assertFalse("GPU producer thread must be joined", requireNotNull(glThread.get()).isAlive)
        }
    }
    private data class OwnedRow(val uri: Uri, val name: String)
    private fun requireOwnedRow(resolver: ContentResolver, row: OwnedRow, owner: String) {
        require(row.uri.scheme == "content" && row.uri.authority == "media" && ContentUris.parseId(row.uri) > 0)
        requireNotNull(resolver.query(row.uri, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.OWNER_PACKAGE_NAME, MediaStore.MediaColumns.IS_PENDING), null, null, null)).use { cursor ->
            check(cursor.count == 1 && cursor.moveToFirst() && cursor.getLong(0) == ContentUris.parseId(row.uri) &&
                cursor.getString(1) == row.name && cursor.getString(2) == owner && !cursor.isNull(3) && cursor.getInt(3) == 0)
        }
    }
    companion object {
        private const val WIDTH = 256
        private const val HEIGHT = 192
        private const val ENGINE_KEY = "develop/engine"
        private val ALLOWED = AutoDevelopConditions(true, false, false, false, true, true, true, true,
            previewMemoryAvailable = true)
    }
}
