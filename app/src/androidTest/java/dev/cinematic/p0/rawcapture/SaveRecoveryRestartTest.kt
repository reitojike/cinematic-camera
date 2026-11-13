package dev.cinematic.p0.rawcapture

import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.jpegrender.original.Original24PreflightGeometry
import dev.cinematic.p0.jpegrender.original.Original24Request
import dev.cinematic.p0.photoedit.AndroidPhotoEditRecipes
import dev.cinematic.p0.photoedit.PhotoEditValues
import dev.cinematic.p0.photoroll.SaveRecoveryColdUiProbe
import dev.cinematic.p0.raw.PhotoAdjustments
import dev.cinematic.p0.testing.writeSyntheticTrialDng
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Real process-death harness, emulator opt-in only. Publisher seams use synthetic completed JPEGs,
 * not a GPU-render kill claim. B uses the real factory except the explicitly seeded consent seam.
 * Host must kill while armed, prove PID absence, and invoke resume in a different process. */
class SaveRecoveryRestartTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val geometry = Original24PreflightGeometry(256, 192, 256, 192, 1, 256, 192)
    private val allowed = AutoDevelopConditions(true, false, false, false, true, true, true, true, previewMemoryAvailable = true)
    private val kind get() = requireNotNull(args.getString("recoveryBoundary"))
    private fun dir(): File {
        assumeTrue(args.getString("isolated") == "true" && args.getString("saveRecoveryRestart") == "true")
        check(Build.HARDWARE in setOf("ranchu", "goldfish") && app.packageName == "dev.cinematic.p0")
        val id = requireNotNull(args.getString("recoveryCase"))
        require(id.matches(Regex("[a-zA-Z0-9_-]{1,40}")))
        require(kind in boundaries)
        return File(app.filesDir, "save-recovery-test-$id")
    }
    private fun write(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { it.write(bytes); it.fd.sync() }
    }
    private fun gate(boundary: String) {
        if (boundary != kind) return
        val d = dir()
        write(File(d, "armed.txt"), "$boundary ${Process.myPid()}".toByteArray())
        check(CountDownLatch(1).await(180, TimeUnit.SECONDS)) { "Host kill did not arrive" }
        error("Kill gate cannot return")
    }

    @Test fun seedAwaitHostKill() {
        val d = dir()
        check(!d.exists()) { "Fresh case id required; preserve all prior evidence" }
        check(d.mkdirs())
        val name = "recovery_${d.name}.dng"
        val resolver = app.contentResolver
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/x-adobe-dng")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Cinematic Camera Synthetic Tests/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }))
        val bytes = requireNotNull(resolver.openOutputStream(uri, "w")).use { writeSyntheticTrialDng(it, 256, 192, 1) }
        assertEquals(1, resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null))
        val source = SharedOriginalReference(uri.toString(), DngContentVerifier.identify(bytes,
            { requireNotNull(resolver.openInputStream(uri)) }), OriginalGrant.APP_OWNED)
        DevelopExecution.updateConditions(allowed)
        val handle = AndroidManualOriginalTrialSaves.prepareGpu(app, source, geometry, .65, allowed, PhotoAdjustments.NONE) { false }
            .get(60, TimeUnit.SECONDS)
        val request = handle.request
        write(File(d, "request.bin"), ManualOriginalTrialRequestCodec.encode(request))
        write(File(d, "name.txt"), name.toByteArray())
        write(File(d, "pid.txt"), Process.myPid().toString().toByteArray())
        write(File(d, "kind.txt"), kind.toByteArray())
        AndroidPhotoEditRecipes(app).save(source, PhotoEditValues(.35, 0.0, 0.0)).get(30, TimeUnit.SECONDS)
        val intent = ManualOriginalTrialRequestCodec.encode(request)
        storage(request, CaptureJournalFile.MANUAL_REQUEST).replace(intent)
        write(File(d, "old-intent.bin"), intent)
        gate("intent")
        if (kind.startsWith("b-")) {
            seedB(handle)
            error("B gate not reached")
        }
        val bitmap = Bitmap.createBitmap(256, 192, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xff446688.toInt())
        try { FileOutputStream(File(d, "completed.jpg")).use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it)); it.fd.sync()
        } } finally { bitmap.recycle() }
        gate("private")
        val render = render(request)
        val target = JpegPublicationTarget(request.captureId, request.generation, UUID.randomUUID().toString().replace("-", ""))
        val rows = MediaStoreJpegRows(app)
        if (kind.startsWith("unknown-")) {
            val rowUri = requireNotNull(rows.insert(target))
            val jpeg = File(d, "completed.jpg").readBytes()
            val size = when (kind) { "unknown-empty" -> 0; "unknown-partial" -> jpeg.size / 2; else -> jpeg.size }
            rows.openOutput(target, rowUri).use { it.write(jpeg, 0, size) }
            if (kind == "unknown-public") rows.publish(target, rowUri)
            val record = JpegPublicationRecord(target, render, render.outputFingerprint, 7, JpegPublicationPhase.UNKNOWN, rowUri)
            val encoded = JpegPublicationCodec.encode(record)
            storage(request, CaptureJournalFile.JPEG_PUBLICATION).replace(encoded)
            write(File(d, "old-publication.bin"), encoded)
            write(File(d, "old-row.bin"), rows.openRead(target, rowUri, kind != "unknown-public").use { it.readBytes() })
            gate(kind)
        }
        val backing = storage(request, CaptureJournalFile.JPEG_PUBLICATION)
        val journal = JpegPublicationJournal(request.captureId, request.generation, object : AtomicJournalStorage {
            override fun read(maxBytes: Int) = backing.read(maxBytes)
            override fun replace(contents: ByteArray) {
                backing.replace(contents)
                when (JpegPublicationCodec.decode(contents).phase) {
                    JpegPublicationPhase.PREPARED -> gate("journal")
                    JpegPublicationPhase.INSERTING -> gate("insert-before")
                    JpegPublicationPhase.PUBLISHED -> gate("final-ack")
                    else -> Unit
                }
            }
        }) { target.token }
        val hookedRows = object : RenderedJpegRows by rows {
            override fun insert(target: JpegPublicationTarget): String? = rows.insert(target).also { gate("insert") }
            override fun openOutput(target: JpegPublicationTarget, uri: String): OutputStream {
                val out = rows.openOutput(target, uri)
                return object : OutputStream() {
                    override fun write(b: Int) { out.write(b); gate("copy") }
                    override fun write(b: ByteArray, off: Int, len: Int) {
                        val first = maxOf(1, len / 2)
                        out.write(b, off, first); out.flush(); gate("copy")
                        out.write(b, off + first, len - first)
                    }
                    override fun close() { out.close(); gate("close") }
                }
            }
            override fun publish(target: JpegPublicationTarget, uri: String) {
                gate("publish"); rows.publish(target, uri); gate("ack")
            }
        }
        publisher(request, render, journal, hookedRows).advance()
        error("Selected boundary was not reached")
    }

    private fun seedB(old: AndroidManualOriginalTrialSaves.ManualOriginalTrialHandle) {
        val request = old.request
        assertEquals(ManualOriginalTrialSavePhase.HOLD, old.advance(allowed) { false }.get(30, TimeUnit.SECONDS).phase)
        if (kind == "b-consent") {
            // Seed the durable consent seam explicitly: no production hook or claim of UI consent.
            val intent = File(dir(), "old-intent.bin").readBytes()
            val value = ExplicitRecoveryExport(UUID.randomUUID().toString().replace("-", ""), request,
                SaveRecoveryCodec.digest(intent).joinToString("") { "%02x".format(it) }, null,
                request.copy(generation = request.generation + 1),
                "GPU:${ManualOriginalTrialRender.CONTRACT.renderer}:${ManualTrialAttemptCodec.EXECUTION_VERSION}")
            ExplicitRecoveryExports(storage(request, CaptureJournalFile.EXPLICIT_RECOVERY_EXPORT)).accept(value) {}
            gate(kind)
        }
        val first = old.requestExplicitRecovery { false }
        val duplicate = old.requestExplicitRecovery { false }
        val next = first.get(30, TimeUnit.SECONDS)
        assertSame(next, duplicate.get(30, TimeUnit.SECONDS))
        assertThrows(java.util.concurrent.ExecutionException::class.java) { old.advance(allowed) { false }.get(30, TimeUnit.SECONDS) }
        gate("b-cursor")
        next.advance(allowed) {
            if (storage(next.request, CaptureJournalFile.MANUAL_REQUEST).read(ManualOriginalTrialRequestCodec.MAX_BYTES) != null)
                gate("b-intent")
            if (android.opengl.EGL14.eglGetCurrentContext() != android.opengl.EGL14.EGL_NO_CONTEXT) gate("b-gpu-start")
            false
        }.get(200, TimeUnit.SECONDS)
    }

    @Test fun resumeAfterHostKill() {
        val d = dir()
        assertEquals(kind, File(d, "kind.txt").readText())
        val request = ManualOriginalTrialRequestCodec.decode(File(d, "request.bin").readBytes())
        assertNotEquals(File(d, "pid.txt").readText().toInt(), Process.myPid())
        assertNull(AndroidManualOriginalTrialSaves.knownPrepared(request.source))
        verifyOriginal(request)
        DevelopExecution.updateConditions(allowed)
        val mode = args.getString("recoveryResume") ?: "backend"
        if (kind.startsWith("b-") || kind in setOf("intent", "private") ||
            (kind.startsWith("unknown-") && mode != "backend")) {
            if (mode == "ui" || mode == "fgs") {
                SaveRecoveryColdUiProbe.check(app, request, File(d, "name.txt").readText(),
                    kind, publishExplicitly = mode == "fgs")
            } else resumeBOrHeld(request)
        } else {
            val original = JpegPublicationJournal(request.captureId, request.generation, storage(request, CaptureJournalFile.JPEG_PUBLICATION))
            val before = requireNotNull(original.read())
            val rows = MediaStoreJpegRows(app)
            val candidatesBefore = rows.find(before.target)
            if (kind.startsWith("unknown-")) {
                val reader = ReconciledJpegPublication(original,
                    JpegPublicationJournal(request.captureId, request.generation, storage(request, CaptureJournalFile.JPEG_RECOVERY)),
                    JpegReconciliationReceipt(storage(request, CaptureJournalFile.JPEG_RECONCILIATION)), rows, DngIoResources()) { true }
                val effective = requireNotNull(reader.reconcile(before.render, setOf(ManualOriginalTrialRender.CONTRACT)) { false })
                if (kind in setOf("unknown-empty", "unknown-partial")) {
                    assertEquals(JpegPublicationPhase.UNKNOWN, effective.phase)
                    assertNull(reader.recovery.read())
                    assertArrayEquals(File(d, "old-row.bin").readBytes(),
                        rows.openRead(before.target, requireNotNull(before.uri), true).use { it.readBytes() })
                } else {
                    assertEquals(JpegPublicationPhase.PUBLISHED, publisher(request, before.render, reader.recovery, rows).advance().phase)
                }
                assertArrayEquals(File(d, "old-publication.bin").readBytes(),
                    storage(request, CaptureJournalFile.JPEG_PUBLICATION).read(JpegPublicationCodec.MAX_BYTES))
            } else {
                assertEquals(if (kind == "insert-before") JpegPublicationPhase.UNKNOWN else JpegPublicationPhase.PUBLISHED,
                    publisher(request, before.render, original, rows).advance().phase)
            }
            val after = rows.find(before.target)
            assertEquals(if (kind == "insert-before") 0 else 1, after.size)
            if (candidatesBefore.isNotEmpty()) assertEquals(candidatesBefore.single().uri, after.single().uri)
        }
        assertArrayEquals(File(d, "old-intent.bin").readBytes(), storage(request, CaptureJournalFile.MANUAL_REQUEST).read(ManualOriginalTrialRequestCodec.MAX_BYTES))
        verifyOriginal(request)
        write(File(d, "resume-ok.txt"), "$kind $mode old=${File(d, "pid.txt").readText()} new=${Process.myPid()}".toByteArray())
    }

    private fun resumeBOrHeld(request: Original24Request) {
        val restored = requireNotNull(AndroidManualOriginalTrialSaves.restorePreparedGpu(app, request.source, geometry) { false }.get(30, TimeUnit.SECONDS))
        if (kind == "b-consent") {
            val saved = requireNotNull(ExplicitRecoveryExports(storage(request, CaptureJournalFile.EXPLICIT_RECOVERY_EXPORT)).read())
            val next = restored.requestExplicitRecovery { false }.get(30, TimeUnit.SECONDS)
            assertEquals(saved.request, next.request)
            assertSame(next, restored.requestExplicitRecovery { false }.get(30, TimeUnit.SECONDS))
            assertEquals(ManualOriginalTrialSavePhase.READY, next.state.value.phase)
            assertNull(storage(next.request, CaptureJournalFile.MANUAL_REQUEST).read(ManualOriginalTrialRequestCodec.MAX_BYTES))
        } else if (kind == "b-cursor") {
            assertEquals(request.generation + 1, restored.request.generation)
            assertTrue(restored.isExplicitRecoveryRequest)
            assertEquals(request.strength, restored.appliedStrength, 0.0)
            assertEquals(ManualOriginalTrialSavePhase.READY, restored.recheckPublication(allowed) { false }.get(30, TimeUnit.SECONDS).phase)
            assertNull(storage(restored.request, CaptureJournalFile.MANUAL_REQUEST).read(ManualOriginalTrialRequestCodec.MAX_BYTES))
        } else {
            assertEquals(ManualOriginalTrialSavePhase.HOLD, restored.state.value.phase)
            assertEquals(ManualOriginalTrialSavePhase.HOLD, restored.recheckPublication(allowed) { false }.get(30, TimeUnit.SECONDS).phase)
            assertNull(storage(restored.request, CaptureJournalFile.JPEG_PUBLICATION).read(JpegPublicationCodec.MAX_BYTES))
        }
        assertFalse(restored.hasUnreleasedResources)
    }

    private fun render(request: Original24Request): ManualOriginalTrialRender {
        val file = File(dir(), "completed.jpg")
        val inspected = AndroidJpegInspector().inspect({ file.inputStream() }, file.length()) { false }
        return ManualOriginalTrialRender(RenderedJpegRequest(request.captureId, request.generation, request.source,
            request.recipe, inspected.output), OriginalRasterGeometry(256, 192, 1, 256, 192, true),
            inspected.fingerprint, request.strength, 0.0)
    }
    private fun publisher(request: Original24Request, render: JpegPublicationRender, journal: JpegPublicationJournal,
        rows: RenderedJpegRows) = RecoverablePhotoPublisher(journal, rows, render, setOf(ManualOriginalTrialRender.CONTRACT),
        object : RenderedJpegInput {
            override fun openJpeg() = File(dir(), "completed.jpg").inputStream()
            override fun openOriginal(reference: SharedOriginalReference) = requireNotNull(app.contentResolver.openInputStream(Uri.parse(reference.uri)))
        }, AndroidJpegInspector()) { request.source == render.request.source }
    private fun verifyOriginal(request: Original24Request) = assertEquals(request.source.fingerprint,
        DngContentVerifier.verify(request.source.fingerprint, { requireNotNull(app.contentResolver.openInputStream(Uri.parse(request.source.uri))) }))
    private fun storage(request: Original24Request, kind: CaptureJournalFile): AtomicJournalStorage {
        val root = RenderedJpegFiles(File(app.noBackupFilesDir, "raw-captures"), request.captureId, request.generation).generationRoot()
        return AndroidAtomicJournalStorage(requireNotNull(root.parentFile), request.generation.toString(), kind)
    }
    companion object {
        val boundaries = setOf("intent", "private", "journal", "insert-before", "insert", "copy", "close", "publish", "ack", "final-ack",
            "unknown-empty", "unknown-partial", "unknown-complete", "unknown-public", "b-consent", "b-cursor", "b-intent", "b-gpu-start")
    }
}
