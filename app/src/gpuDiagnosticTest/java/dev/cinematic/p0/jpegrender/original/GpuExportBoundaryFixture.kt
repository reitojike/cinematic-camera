package dev.cinematic.p0.jpegrender.original

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.opengl.EGL14
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.BuildConfig
import dev.cinematic.p0.gpu.*
import dev.cinematic.p0.gpu.diag.GpuStageTap
import dev.cinematic.p0.raw.PhotoAdjustments
import dev.cinematic.p0.rawcapture.*
import dev.cinematic.p0.testing.writeSyntheticTrialDng
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Assume.assumeTrue

/** Small real-DNG factory fixture. Four 256-row bands include a two-row tail. No pixel oracle. */
internal class GpuExportBoundaryFixture(val app: Context) {
    private data class OwnedRow(val uri: Uri, val name: String)
    private val rows = mutableListOf<OwnedRow>()
    val sources = mutableListOf<SharedOriginalReference>()
    val handles = mutableListOf<AndroidManualOriginalTrialSaves.ManualOriginalTrialHandle>()
    val setting = DevelopEngineSetting(app)
    private val resolver = app.contentResolver
    private val failures = app.getSharedPreferences("gpu-export-failures", Context.MODE_PRIVATE)
    private val failureCount = failures.getLong("count", 0)
    private val failureReason = failures.getString("lastReason", null)

    fun createSource(): SharedOriginalReference {
        val seed = sources.size
        val name = "synthetic_gpu_band_safety_${UUID.randomUUID()}.dng"
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/x-adobe-dng")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Cinematic Camera Synthetic Tests/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }))
        val bytes = requireNotNull(resolver.openOutputStream(uri, "w")).use {
            writeSyntheticTrialDng(it, WIDTH, HEIGHT, 1) { x, y -> 164 + (x * 65 + y * 25 + seed * 97) % 3500 }
        }
        assertEquals(1, resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null))
        rows += OwnedRow(uri, name)
        return SharedOriginalReference(uri.toString(), DngContentVerifier.identify(bytes,
            { requireNotNull(resolver.openInputStream(uri)) }), OriginalGrant.APP_OWNED).also(sources::add)
    }

    fun prepare(source: SharedOriginalReference = sources.single()): AndroidManualOriginalTrialSaves.ManualOriginalTrialHandle =
        AndroidManualOriginalTrialSaves.prepare(app, source, .65, ALLOWED, PhotoAdjustments(20.0, .3))
            .get(60, TimeUnit.SECONDS).also { save ->
                handles += save
                assertEquals(ManualOriginalTrialSavePhase.READY, save.state.value.phase)
                assertEquals(source, save.request.source)
                assertEquals(Original24PreflightGeometry(WIDTH, HEIGHT, WIDTH, HEIGHT, 1, WIDTH, HEIGHT), save.geometry)
                assertFalse(save.hasUnreleasedResources)
            }

    fun directory(save: AndroidManualOriginalTrialSaves.ManualOriginalTrialHandle) =
        RenderedJpegFiles(File(app.noBackupFilesDir, "raw-captures"), save.request.captureId, save.request.generation).generationRoot()
    private fun storage(save: AndroidManualOriginalTrialSaves.ManualOriginalTrialHandle, kind: CaptureJournalFile) =
        AndroidAtomicJournalStorage(requireNotNull(directory(save).parentFile), save.request.generation.toString(), kind)
    fun intent(save: AndroidManualOriginalTrialSaves.ManualOriginalTrialHandle) = storage(save, CaptureJournalFile.MANUAL_REQUEST)
    fun journal(save: AndroidManualOriginalTrialSaves.ManualOriginalTrialHandle) =
        JpegPublicationJournal(save.request.captureId, save.request.generation, storage(save, CaptureJournalFile.JPEG_PUBLICATION))

    fun assertWorkingSource(save: AndroidManualOriginalTrialSaves.ManualOriginalTrialHandle) {
        val work = File(directory(save), "original-work")
        val source = save.request.source
        val snapshot = File(work, "source-snapshot.dng")
        assertEquals(source.fingerprint.bytes, snapshot.length())
        assertEquals(WIDTH.toLong() * HEIGHT * 6, File(work, "source-linear.rgb16").length())
        assertEquals(source.fingerprint, DngContentVerifier.verify(source.fingerprint, { snapshot.inputStream() }))
        assertFalse("Named GPU boundary must precede JPEG output-open", File(directory(save), "output.jpg").exists())
        assertNull(journal(save).read())
    }

    fun assertClosed(save: AndroidManualOriginalTrialSaves.ManualOriginalTrialHandle, unpublished: Boolean = false) {
        assertFalse("Producer/publication owner must confirm cleanup", save.hasUnreleasedResources)
        assertFalse("Snapshot and RGB16 workspace must be removed", File(directory(save), "original-work").exists())
        if (unpublished) {
            assertFalse(File(directory(save), "output.jpg").exists())
            assertNull(journal(save).read())
        }
    }

    fun verifyPublication(save: AndroidManualOriginalTrialSaves.ManualOriginalTrialHandle) {
        assertEquals(ManualOriginalTrialSavePhase.PUBLISHED, save.state.value.phase)
        assertClosed(save)
        val record = requireNotNull(journal(save).read())
        val uri = Uri.parse(requireNotNull(record.uri))
        val row = OwnedRow(uri, record.target.displayName)
        if (row !in rows) rows += row
        assertEquals(JpegPublicationPhase.PUBLISHED, record.phase)
        assertEquals(record.uri, save.state.value.uri)
        val proof = record.render as ManualOriginalTrialRender
        assertEquals(save.request.captureId, proof.request.captureId)
        assertEquals(save.request.generation, proof.request.generation)
        assertEquals(save.request.source, proof.request.source)
        assertEquals(save.request.recipe, proof.request.recipe)
        assertEquals(save.appliedStrength, proof.appliedStrength, 0.0)
        assertEquals(save.appliedAdjustments, proof.adjustments)
        assertEquals(OriginalRasterGeometry(WIDTH, HEIGHT, 1, WIDTH, HEIGHT, true), proof.geometry)
        assertNotEquals(Uri.parse(save.request.source.uri), uri)
        requireOwned(row)
        val inspection = AndroidJpegInspector().inspect(
            { requireNotNull(resolver.openInputStream(uri)) }, record.fingerprint.bytes) { false }
        assertEquals(record.fingerprint, inspection.fingerprint)
        assertEquals(proof.request.output, inspection.output)
        assertEquals(WIDTH, inspection.output.width); assertEquals(HEIGHT, inspection.output.height)
        assertEquals(1, inspection.output.orientation); assertEquals("sRGB", inspection.output.color)
        verifyOriginals()
    }

    fun verifyOriginals() {
        sources.forEach { source -> assertEquals(source.fingerprint, DngContentVerifier.verify(source.fingerprint,
            { requireNotNull(resolver.openInputStream(Uri.parse(source.uri))) })) }
    }
    fun assertNoGpuFailureRecorded() {
        assertEquals(failureCount, failures.getLong("count", 0))
        assertEquals(failureReason, failures.getString("lastReason", null))
    }
    /** Only this fixture's synthetic sources/handles. Stream output survives a non-debuggable APK. */
    fun reportDiagnostics(stage: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun emit(label: String, value: String) {
            instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "\nGpuExportBoundaryEvidence stage=$stage $label=$value\n")
            })
        }
        emit("sources", sources.joinToString { "${it.uri}:${it.fingerprint}" })
        handles.distinct().forEach { save ->
            val key = "${save.request.captureId}:${save.request.generation}"
            emit("handle[$key]", "request=${save.request} state=${save.state.value} held=${save.hasUnreleasedResources}")
            fun record(name: String, read: () -> ByteArray?) {
                val bytes = try { read() } catch (error: Exception) {
                    emit("$key/$name", "READ_FAILED:${error.javaClass.name}:${error.message}")
                    return
                }
                emit("$key/$name", bytes?.let { Base64.encodeToString(it, Base64.NO_WRAP) } ?: "ABSENT")
            }
            // Preserve exact bounded binary records, including malformed bytes, rather than only toString.
            record("manualRequestBase64") { intent(save).read(ManualOriginalTrialRequestCodec.MAX_BYTES) }
            record("jpegPublicationBase64") { storage(save, CaptureJournalFile.JPEG_PUBLICATION).read(JpegPublicationCodec.MAX_BYTES) }
        }
    }

    fun reportFailure(stage: String, primary: Throwable) {
        try { reportDiagnostics(stage) }
        catch (reporting: Throwable) { if (reporting !== primary) primary.addSuppressed(reporting) }
    }

    private fun requireOwned(row: OwnedRow) {
        require(row.uri.scheme == "content" && row.uri.authority == "media" && ContentUris.parseId(row.uri) > 0)
        requireNotNull(resolver.query(row.uri, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.OWNER_PACKAGE_NAME, MediaStore.MediaColumns.IS_PENDING), null, null, null)).use {
            check(it.count == 1 && it.moveToFirst() && it.getLong(0) == ContentUris.parseId(row.uri) &&
                it.getString(1) == row.name && it.getString(2) == app.packageName && !it.isNull(3) && it.getInt(3) == 0)
        }
    }
    private fun cleanVerifiedRows() {
        assertEquals(rows.size, rows.map { it.uri }.toSet().size)
        verifyOriginals()
        rows.forEach(::requireOwned)
        rows.forEach { row ->
            requireOwned(row)
            check(resolver.delete(row.uri,
                "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.IS_PENDING}=0",
                arrayOf(app.packageName, row.name)) == 1)
        }
    }

    companion object {
        const val WIDTH = 512
        const val HEIGHT = 770
        private const val ENGINE_KEY = "develop/engine"
        val ALLOWED = AutoDevelopConditions(true, false, false, false, true, true, true, true, previewMemoryAvailable = true)
        fun run(body: (GpuExportBoundaryFixture) -> Unit) {
            val target = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            val args = InstrumentationRegistry.getArguments()
            assumeTrue(BuildConfig.BUILD_TYPE == "gpuDiagnostic" && target.packageName == "dev.cinematic.p0.gpudiagnostic")
            assumeTrue(args.getString("isolated") == "true" && args.getString("gpuExportBoundaries") == "true")
            assumeTrue("Physical devices are excluded", Build.HARDWARE in setOf("ranchu", "goldfish"))
            val capabilities = GlThread().use { thread -> thread.call {
                EglContexts(thread, target.assets).use { GlCapabilities.inspect(it) }
            } }
            assertTrue("Required GPU capabilities: ${capabilities.missing}", capabilities.supported)
            val prefs = target.getSharedPreferences("capture-probe", Context.MODE_PRIVATE)
            val hadEngine = prefs.contains(ENGINE_KEY)
            val oldEngine = prefs.getString(ENGINE_KEY, null)
            val fixture = GpuExportBoundaryFixture(target)
            var success = false
            var failure: Throwable? = null
            try {
                assertTrue(fixture.setting.select(DevelopEngine.GPU, GpuAvailability.Available))
                DevelopExecution.updateConditions(ALLOWED)
                fixture.createSource()
                body(fixture)
                fixture.verifyOriginals()
                fixture.assertNoGpuFailureRecorded()
                fixture.reportDiagnostics("success-before-cleanup")
                success = true
            } catch (error: Throwable) {
                failure = error
                fixture.reportFailure("failure-before-fixture-cleanup", error)
                throw error
            }
            finally {
                try {
                    fixture.handles.forEach { it.requestStop() }
                    AndroidManualOriginalTrialSaves.discardPreview(target).get(60, TimeUnit.SECONDS)
                    fixture.handles.forEach { fixture.assertClosed(it) }
                    // Failure/uncertain runs keep exact synthetic rows and journals for inspection.
                    if (success) fixture.cleanVerifiedRows()
                } catch (cleanup: Throwable) {
                    if (failure == null) throw cleanup
                    if (cleanup !== failure) failure.addSuppressed(cleanup)
                } finally {
                    val editor = prefs.edit()
                    if (hadEngine) editor.putString(ENGINE_KEY, oldEngine) else editor.remove(ENGINE_KEY)
                    check(editor.commit())
                    check(prefs.contains(ENGINE_KEY) == hadEngine && prefs.getString(ENGINE_KEY, null) == oldEngine)
                }
            }
        }
    }
}

/** Renderer callbacks name actual band events. They never infer positions from stop-call counts. */
internal class GpuExportBoundaryWitness(
    private val onEvent: (Event) -> Unit = {},
) : Original24GpuOutput.DiagnosticObserver {
    data class Event(val stage: String, val complete: Boolean, val origin: Int, val rows: Int,
        val balance: Original24GpuOutput.TextureBalance)
    val events = CopyOnWriteArrayList<Event>()
    val released = CopyOnWriteArrayList<Original24GpuOutput.TextureBalance>()
    private val thread = AtomicReference<Thread>()
    private fun record(event: Event) {
        check(EGL14.eglGetCurrentContext() != EGL14.EGL_NO_CONTEXT)
        check(thread.get() === Thread.currentThread() || thread.compareAndSet(null, Thread.currentThread()))
        events += event
        onEvent(event)
    }
    override fun insideBand(point: GpuStageTap.Point, origin: Int, rows: Int, balance: Original24GpuOutput.TextureBalance) =
        record(Event(point.name, false, origin, rows, balance))
    override fun bandComplete(boundary: String, origin: Int, rows: Int, balance: Original24GpuOutput.TextureBalance) =
        record(Event(boundary, true, origin, rows, balance))
    override fun rendererReleased(balance: Original24GpuOutput.TextureBalance) { released += balance }
    fun assertReleased() {
        assertTrue("Real GPU band processing must occur", events.isNotEmpty())
        assertTrue("Live textures must be observed", events.any { it.balance.bytes > 0 && it.balance.textures > 0 })
        assertEquals("One attempt per witness", 1, released.size)
        assertEquals(0L, released.single().bytes)
        assertEquals(0, released.single().textures)
        assertTrue(released.single().peakBytes > 0)
        assertFalse("Factory must join its GL thread before returning", requireNotNull(thread.get()).isAlive)
    }
}
