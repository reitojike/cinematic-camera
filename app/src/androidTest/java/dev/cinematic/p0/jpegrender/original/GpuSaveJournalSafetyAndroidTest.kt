package dev.cinematic.p0.jpegrender.original

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import dev.cinematic.p0.gpu.DevelopEngine
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.raw.PhotoAdjustments
import dev.cinematic.p0.raw.original.OriginalStage
import dev.cinematic.p0.raw.original.OriginalStageTrace
import dev.cinematic.p0.rawcapture.*
import dev.cinematic.p0.testing.writeSyntheticTrialDng
import java.io.File
import java.io.FilterOutputStream
import java.util.UUID
import org.junit.Assert.*

import org.junit.Test
import org.junit.runner.RunWith

/** Real decode/render, owner, journal and MediaStore publication; no production test hook.
 * Factory/admission/FGS/UI wiring remains covered separately, not simulated by this adapter. */
@RunWith(AndroidJUnit4::class)
class GpuSaveJournalSafetyAndroidTest {
    @Test fun gpuSaveKeepsStageOrderPublicationAndEveryJournalRevisionAcrossRetries() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        requireGpuSaveSafetyFixture()
        // Cover rotated/manual and unrotated/full-strength recipes without CPU worker comparisons.
        verifyCase(app, orientation = 6, strength = .45, edits = PhotoAdjustments(20.0, .3), generations = listOf(1))
        verifyCase(app, orientation = 1, strength = 1.0, edits = PhotoAdjustments.NONE, generations = listOf(1))
    }

    private data class Observation(
        val jpeg: ByteArray,
        val stages: List<OriginalStage>,
        val events: List<String>,
        val records: List<JpegPublicationRecord>,
    )

    private fun verifyCase(app: Context, orientation: Int, strength: Double, edits: PhotoAdjustments, generations: List<Int>) {
        val resolver = app.contentResolver
        val sourceName = "synthetic_save_parity_${UUID.randomUUID()}.dng"
        val sourceUri = requireNotNull(resolver.insert(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, sourceName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/x-adobe-dng")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Cinematic Camera Synthetic Tests/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }))
        val root = File(app.noBackupFilesDir, "synthetic-save-parity-${UUID.randomUUID()}")
        check(root.mkdir())
        val outputs = mutableListOf<JpegPublicationRecord>()
        var success = false
        try {
            val sourceBytes = requireNotNull(resolver.openOutputStream(sourceUri, "w")).use {
                writeSyntheticTrialDng(it, 64, 48, orientation)
            }
            assertEquals(1, resolver.update(sourceUri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null))
            val source = SharedOriginalReference(sourceUri.toString(), DngContentVerifier.identify(sourceBytes,
                { requireNotNull(resolver.openInputStream(sourceUri)) }), OriginalGrant.APP_OWNED)
            val observations = generations.map { generation ->
                val request = Original24Request(ManualOriginalTrialRequests.identity(source), generation.toLong(), source, strength, edits)
                runArm(app, File(root, "generation-$generation"), request).also { outputs += it.records.last() }
            }
            assertEquals(source.fingerprint, DngContentVerifier.verify(source.fingerprint,
                { requireNotNull(resolver.openInputStream(sourceUri)) }))
            android.util.Log.i("GpuSaveJournalSafety", "PASS synthetic=64x48 orientation=$orientation strength=$strength " +
                "generations=${generations.joinToString(",")} jpegBytes=${observations[0].jpeg.size} " +
                "sha256=${observations[0].records.last().fingerprint.sha256} journalStates=7 retries=2")
            success = true
        } finally {
            // On any failed/uncertain outcome retain only this test's synthetic evidence for inspection.
            if (success) {
                val rows = MediaStoreJpegRows(app)
                outputs.forEach { record ->
                    assertTrue(record.target.accepts(requireNotNull(rows.inspect(requireNotNull(record.uri))), false))
                }
                requireOwnedSource(app, sourceUri, sourceName)
                outputs.forEach { record ->
                    assertEquals(1, resolver.delete(Uri.parse(requireNotNull(record.uri)),
                        "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.IS_PENDING}=0",
                        arrayOf(app.packageName, record.target.displayName)))
                }
                assertEquals(1, resolver.delete(sourceUri,
                    "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.IS_PENDING}=0",
                    arrayOf(app.packageName, sourceName)))
                assertTrue(root.deleteRecursively())
            }
        }
    }

    private fun runArm(app: Context, root: File, request: Original24Request): Observation {
        check(root.mkdir())
        val events = mutableListOf<String>()
        val stages = mutableListOf<OriginalStage>()
        val records = mutableListOf<JpegPublicationRecord>()
        fun trackedStorage(kind: CaptureJournalFile, onCommit: (ByteArray) -> Unit): AtomicJournalStorage {
            val delegate = AndroidAtomicJournalStorage(root, request.captureId, kind)
            return object : AtomicJournalStorage by delegate {
                override fun replace(contents: ByteArray) {
                    delegate.replace(contents)
                    assertArrayEquals(contents, delegate.read(CaptureReceiptCodec.MAX_BYTES))
                    onCommit(contents)
                }
            }
        }
        val intentStorage = trackedStorage(CaptureJournalFile.MANUAL_REQUEST) {
            assertArrayEquals(ManualOriginalTrialRequestCodec.encode(request), it)
            events += "INTENT"
        }
        val journalStorage = trackedStorage(CaptureJournalFile.JPEG_PUBLICATION) {
            val record = JpegPublicationCodec.decode(it)
            assertEquals(request.captureId, record.target.captureId)
            assertEquals(request.generation, record.target.generation)
            assertEquals(request.captureId, record.render.request.captureId)
            assertEquals(request.generation, record.render.request.generation)
            assertEquals(request.source, record.render.request.source)
            assertEquals(request.recipe, record.render.request.recipe)
            records += record
            events += "JOURNAL_${record.phase}"
        }
        val journal = JpegPublicationJournal(request.captureId, request.generation, journalStorage)
        val files = RenderedJpegFiles(root, request.captureId, request.generation)
        val sourceUri = Uri.parse(request.source.uri)
        fun sourceInput() = requireNotNull(app.contentResolver.openInputStream(sourceUri))
        val io = object : AutoRenderIo {
            override fun openOriginal() = sourceInput()
            override fun openOriginalDescriptor(): AutoReadOnlyDescriptor = error("Use verified snapshot")
            override fun openOutput() = files.createOutput()
            override fun openOutputRead() = files.openRead()
        }
        val scratch = File(root, "scratch")
        var attempt: Original24Attempt? = null
        var publication: RecoverablePhotoPublisher? = null
        var renderCount = 0
        var publisherCount = 0
        val delegateRows = MediaStoreJpegRows(app)
        val rows = object : RenderedJpegRows by delegateRows {
            override fun insert(target: JpegPublicationTarget): String? {
                assertEquals(JpegPublicationPhase.INSERTING, journal.read()?.phase)
                assertFalse(requireNotNull(attempt).hasUnreleasedResources)
                events += "ROW_INSERT"
                return delegateRows.insert(target)
            }
            override fun openOutput(target: JpegPublicationTarget, uri: String): java.io.OutputStream {
                assertEquals(JpegPublicationPhase.COPYING, journal.read()?.phase)
                events += "ROW_WRITE"
                return object : FilterOutputStream(delegateRows.openOutput(target, uri)) {
                    private var closed = false
                    override fun write(bytes: ByteArray, offset: Int, length: Int) = out.write(bytes, offset, length)
                    override fun close() {
                        if (!closed) { super.close(); closed = true; events += "ROW_CLOSE" }
                    }
                }
            }
            override fun publish(target: JpegPublicationTarget, uri: String) {
                assertEquals(JpegPublicationPhase.PUBLISHING, journal.read()?.phase)
                assertFalse(requireNotNull(attempt).hasUnreleasedResources)
                delegateRows.publish(target, uri)
                events += "ROW_PUBLISH"
            }
        }
        val backend = object : ManualOriginalTrialSaveBackend {
            override val hasUnreleasedResources get() = attempt?.hasUnreleasedResources == true
            override fun readPublication() = journal.read()
            override fun render(stop: () -> Boolean): ManualOriginalTrialRender {
                renderCount++
                assertArrayEquals(ManualOriginalTrialRequestCodec.encode(request), intentStorage.read(ManualOriginalTrialRequestCodec.MAX_BYTES))
                events += "RENDER_ENTER"
                val engine = Original24Attempt(app, request, io, scratch,
                    trace = OriginalStageTrace { stage, _, complete -> assertTrue(complete); stages += stage },
                    developEngine = DevelopEngine.GPU).also { attempt = it }
                val result = engine.use { it.render(stop) }
                assertFalse(engine.hasUnreleasedResources)
                assertFalse(scratch.exists())
                events += "RENDER_CLOSED"
                return ManualOriginalTrialRender(RenderedJpegRequest(request.captureId, request.generation, request.source,
                    request.recipe, result.output), result.geometry, result.fingerprint, request.strength, result.externalEv, request.adjustments)
            }
            override fun publisher(render: ManualOriginalTrialRender): ManualOriginalTrialPublication {
                publisherCount++
                assertFalse(hasUnreleasedResources)
                files.sealCompleted()
                events += "PUBLISHER"
                val owner = RecoverablePhotoPublisher(journal, rows, render, setOf(ManualOriginalTrialRender.CONTRACT),
                    object : RenderedJpegInput {
                        override fun openJpeg() = files.openRead()
                        override fun openOriginal(reference: SharedOriginalReference): java.io.InputStream {
                            assertEquals(request.source, reference)
                            return sourceInput()
                        }
                    }, AndroidJpegInspector()) { true }.also { publication = it }
                return object : ManualOriginalTrialPublication {
                    override val hasUnreleasedResources get() = owner.hasUnreleasedResources
                    override fun advance(stop: () -> Boolean) = owner.advance(stop)
                    override fun releaseResources() = owner.releaseResources()
                }
            }
            override fun releaseResources() { attempt?.close() }
        }
        val owner = ManualOriginalTrialSaveOwner(request, backend, intentStorage) { true }
        try {
            val result = owner.advance()
            assertEquals("Save failed: ${owner.lastFailure?.javaClass?.simpleName}", ManualOriginalTrialSavePhase.PUBLISHED, result.phase)
            assertFalse(owner.hasUnreleasedResources)
            val record = requireNotNull(journal.read())
            assertEquals(record.uri, result.uri)
            assertEquals(request.source, record.render.request.source)
            assertEquals(request.recipe, record.render.request.recipe)
            assertEquals(request.captureId, record.target.captureId)
            assertEquals(request.generation, record.target.generation)
            assertEquals(JpegPublicationPhase.entries.filter { it != JpegPublicationPhase.UNKNOWN }, records.map { it.phase })
            assertEquals((0L..6L).toList(), records.map { it.revision })
            assertEquals(1, records.map { it.target }.distinct().size)
            assertEquals(listOf(null, null) + List(5) { record.uri }, records.map { it.uri })
            assertEquals(listOf("INTENT", "RENDER_ENTER", "RENDER_CLOSED", "PUBLISHER",
                "JOURNAL_PREPARED", "JOURNAL_INSERTING", "ROW_INSERT", "JOURNAL_ROW_RECORDED",
                "JOURNAL_COPYING", "ROW_WRITE", "ROW_CLOSE", "JOURNAL_BYTES_COMPLETE",
                "JOURNAL_PUBLISHING", "ROW_PUBLISH", "JOURNAL_PUBLISHED"), events)
            assertTrue(stages.containsAll(listOf(OriginalStage.JPEG_ENCODE, OriginalStage.JPEG_INSPECT,
                OriginalStage.SOURCE_VERIFY_AFTER, OriginalStage.RESOURCE_CLOSE)))
            assertTrue(stages.indexOf(OriginalStage.JPEG_ENCODE) < stages.indexOf(OriginalStage.JPEG_INSPECT))
            assertTrue(stages.indexOf(OriginalStage.JPEG_INSPECT) < stages.indexOf(OriginalStage.SOURCE_VERIFY_AFTER))
            assertTrue(stages.indexOf(OriginalStage.SOURCE_VERIFY_AFTER) < stages.indexOf(OriginalStage.RESOURCE_CLOSE))
            assertFalse(OriginalStage.R7_COLOR_FUSED in stages)
            val jpeg = requireNotNull(app.contentResolver.openInputStream(Uri.parse(requireNotNull(record.uri)))).use { it.readBytes() }
            val inspection = AndroidJpegInspector().inspect({ jpeg.inputStream() }, jpeg.size.toLong()) { false }
            assertEquals(record.fingerprint, inspection.fingerprint)
            assertEquals(record.render.request.output, inspection.output)
            assertEquals(1, inspection.output.orientation)
            val persisted = requireNotNull(journalStorage.read(JpegPublicationCodec.MAX_BYTES))
            val eventsBeforeRetry = events.toList()
            val stagesBeforeRetry = stages.toList()
            repeat(2) {
                assertEquals(result, owner.advance())
                assertEquals(record, journal.read())
                assertArrayEquals(persisted, journalStorage.read(JpegPublicationCodec.MAX_BYTES))
                assertEquals(eventsBeforeRetry, events)
                assertEquals(stagesBeforeRetry, stages)
                assertEquals(1, renderCount); assertEquals(1, publisherCount)
                assertFalse(owner.hasUnreleasedResources)
            }
            assertEquals(request.source.fingerprint, DngContentVerifier.verify(request.source.fingerprint, ::sourceInput))
            return Observation(jpeg, stages.toList(), events.toList(), records.toList())
        } finally {
            owner.releaseResources()
            assertFalse(owner.hasUnreleasedResources)
            assertFalse(publication?.hasUnreleasedResources == true)
        }
    }

    private fun requireOwnedSource(app: Context, uri: Uri, name: String) {
        requireNotNull(app.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.OWNER_PACKAGE_NAME, MediaStore.MediaColumns.IS_PENDING), null, null, null)).use {
            check(it.count == 1 && it.moveToFirst())
            check(it.getString(0) == name && it.getString(1) == app.packageName && !it.isNull(2) && it.getInt(2) == 0)
        }
    }
}
