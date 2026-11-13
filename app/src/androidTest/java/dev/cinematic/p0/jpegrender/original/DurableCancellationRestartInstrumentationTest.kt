package dev.cinematic.p0.jpegrender.original

import android.content.ContentValues
import android.net.Uri
import android.opengl.EGL14
import android.os.Build
import android.os.Process
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.photoroll.ConfirmedCancellationEditProbe
import dev.cinematic.p0.photoedit.AndroidPhotoEditRecipes
import dev.cinematic.p0.photoedit.PhotoEditValues
import dev.cinematic.p0.raw.PhotoAdjustments
import dev.cinematic.p0.rawcapture.*
import dev.cinematic.p0.testing.writeSyntheticTrialDng
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Two-invocation protocol. The host force-stops between seed/resume, and must record PID absence.
 * Every run has its own synthetic source/checkpoint. Never delete failed evidence or held journals.
 * Opt-in: isolated=true durableCancellation=true durableCase=<unique alphanumeric id>.
 */
@RunWith(AndroidJUnit4::class)
class DurableCancellationRestartInstrumentationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext
    private fun checkpoint(): File {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("isolated") == "true" && args.getString("durableCancellation") == "true")
        assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish") && app.packageName == "dev.cinematic.p0")
        val id = requireNotNull(args.getString("durableCase"))
        require(id.matches(Regex("[a-zA-Z0-9_-]{1,40}")))
        return File(app.filesDir, "durable-cancel-test-$id")
    }

    @Test fun seedClean() = seed("clean")
    @Test fun seedAfterJpeg() = seed("jpeg")
    @Test fun seedLegacyIntent() = seed("legacy")
    @Test fun seedStartedAwaitHostKill() = seed("started")
    @Test fun seedRetryStartedAwaitHostKill() = seed("retrystarted")

    private fun seed(kind: String) {
        val dir = checkpoint()
        check(!dir.exists()) { "Use a fresh case id; never overwrite prior evidence" }
        check(dir.mkdirs())
        val resolver = app.contentResolver
        val name = "cancel_${dir.name.removePrefix("durable-cancel-test-")}.dng"
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
        DevelopExecution.updateConditions(ALLOWED)
        val handle = AndroidManualOriginalTrialSaves.prepare(app, source, .65, ALLOWED, PhotoAdjustments(20.0, .3))
            .get(60, TimeUnit.SECONDS)
        val request = handle.request
        File(dir, "request.bin").writeBytes(ManualOriginalTrialRequestCodec.encode(request))
        File(dir, "name.txt").writeText(name)
        File(dir, "pid.txt").writeText(Process.myPid().toString())
        File(dir, "kind.txt").writeText(kind)
        // A newer edit-only draft must not silently supersede the cancelled request on cold open.
        AndroidPhotoEditRecipes(app).save(source, PhotoEditValues(.35, 0.0, 0.0)).get(30, TimeUnit.SECONDS)
        if (kind == "legacy") {
            storage(request, CaptureJournalFile.MANUAL_REQUEST).replace(ManualOriginalTrialRequestCodec.encode(request))
            assertNull(storage(request, CaptureJournalFile.MANUAL_ATTEMPT).read(ManualTrialAttemptCodec.MAX_BYTES))
        } else {
            val gpuSeen = AtomicBoolean(false)
            val output = File(generation(request), "output.jpg")
            val result = handle.advance(ALLOWED) {
                if (kind == "started") {
                    val ledger = storage(request, CaptureJournalFile.MANUAL_ATTEMPT).read(ManualTrialAttemptCodec.MAX_BYTES)
                    if (ledger != null && ManualTrialAttemptCodec.decode(ledger).phase == ManualTrialAttemptPhase.STARTED) {
                        File(dir, "armed.txt").writeText("STARTED pid=${Process.myPid()}")
                        check(CountDownLatch(1).await(120, TimeUnit.SECONDS)) { "Host must kill the process at STARTED" }
                    }
                    false
                } else {
                    if (EGL14.eglGetCurrentContext() != EGL14.EGL_NO_CONTEXT) gpuSeen.set(true)
                    if (kind == "jpeg") output.exists() else gpuSeen.get()
                }
            }.get(180, TimeUnit.SECONDS)
            assertTrue(gpuSeen.get())
            assertEquals(if (kind in setOf("clean", "retrystarted")) ManualOriginalTrialSavePhase.CANCELLED else ManualOriginalTrialSavePhase.HOLD, result.phase)
            assertFalse(handle.hasUnreleasedResources)
            val record = ManualTrialAttemptCodec.decode(requireNotNull(storage(request, CaptureJournalFile.MANUAL_ATTEMPT).read(ManualTrialAttemptCodec.MAX_BYTES)))
            assertEquals(if (kind in setOf("clean", "retrystarted")) ManualTrialAttemptPhase.CANCELLED_CLEAN else ManualTrialAttemptPhase.STARTED, record.phase)
            assertFalse(File(generation(request), "original-work").exists())
            assertFalse(output.exists())
            if (kind == "retrystarted") {
                assertEquals(1L, record.attempt)
                File(dir, "clean-before-retry.bin").writeBytes(ManualTrialAttemptCodec.encode(record))
                verifyOriginal(request)
                assertNull(publication(request))
                handle.advance(ALLOWED, retryCancellation = true) {
                    val latest = ManualTrialAttemptCodec.decode(requireNotNull(storage(request,
                        CaptureJournalFile.MANUAL_ATTEMPT).read(ManualTrialAttemptCodec.MAX_BYTES)))
                    if (latest.attempt == 2L && latest.phase == ManualTrialAttemptPhase.STARTED) {
                        assertEquals(request, latest.request)
                        assertFalse(handle.hasUnreleasedResources)
                        assertFalse(output.exists())
                        File(dir, "armed.txt").writeText("STARTED pid=${Process.myPid()} attempt=2")
                        check(CountDownLatch(1).await(120, TimeUnit.SECONDS)) { "Host must kill after retry proof invalidation" }
                    }
                    false
                }.get(180, TimeUnit.SECONDS)
                fail("Host must kill the retry before any producer starts")
            }
        }
        verifyOriginal(request)
        assertNull(publication(request))
        File(dir, "seed-ok.txt").writeText("$kind pid=${Process.myPid()}")
    }

    @Test fun resumeSameRecipe() = resume("same")
    @Test fun resumeSameRecipeThroughColdUi() = resume("uisame")
    @Test fun resumeChangedRecipeThroughColdUi() = resume("ui")
    @Test fun resumeHeld() = resume("hold")

    private fun resume(mode: String) {
        val dir = checkpoint()
        val request = ManualOriginalTrialRequestCodec.decode(File(dir, "request.bin").readBytes())
        assertNotEquals("A new process, not Activity recreation, is required", File(dir, "pid.txt").readText().toInt(), Process.myPid())
        assertNull(AndroidManualOriginalTrialSaves.knownPrepared(request.source))
        val oldIntent = storage(request, CaptureJournalFile.MANUAL_REQUEST).read(ManualOriginalTrialRequestCodec.MAX_BYTES)
        val oldLedger = storage(request, CaptureJournalFile.MANUAL_ATTEMPT).read(ManualTrialAttemptCodec.MAX_BYTES)
        verifyOriginal(request)
        if (File(dir, "kind.txt").readText() == "retrystarted") {
            val clean = ManualTrialAttemptCodec.decode(File(dir, "clean-before-retry.bin").readBytes())
            val started = ManualTrialAttemptCodec.decode(requireNotNull(oldLedger))
            assertEquals(clean.copy(attempt = 2, phase = ManualTrialAttemptPhase.STARTED), started)
        }
        DevelopExecution.updateConditions(ALLOWED)
        if (mode == "ui" || mode == "uisame") {
            val next = if (mode == "ui") ConfirmedCancellationEditProbe.publishCold(app, request.source, File(dir, "name.txt").readText(), request)
                else ConfirmedCancellationEditProbe.retryCold(app, request.source, File(dir, "name.txt").readText(), request)
            assertEquals(request.generation + if (mode == "ui") 1 else 0, next.request.generation)
            assertEquals(ManualOriginalTrialSavePhase.PUBLISHED, next.state.value.phase)
            if (mode == "ui") {
                assertNotEquals(request.recipe, next.request.recipe)
                assertArrayEquals(oldLedger, storage(request, CaptureJournalFile.MANUAL_ATTEMPT).read(ManualTrialAttemptCodec.MAX_BYTES))
                assertNull(publication(request))
            } else {
                assertEquals(request, next.request)
                assertEquals(2L, ManualTrialAttemptCodec.decode(requireNotNull(storage(request,
                    CaptureJournalFile.MANUAL_ATTEMPT).read(ManualTrialAttemptCodec.MAX_BYTES))).attempt)
            }
            verifyPublication(next.request)
        } else {
            val restored = AndroidManualOriginalTrialSaves.prepare(app, request.source, request.strength, ALLOWED, request.adjustments)
                .get(60, TimeUnit.SECONDS)
            assertEquals(request, restored.request)
            val expected = if (mode == "same") ManualOriginalTrialSavePhase.CANCELLED else ManualOriginalTrialSavePhase.HOLD
            assertEquals(expected, restored.state.value.phase)
            // Ordinary advance after restart must not implicitly consume the clean proof.
            assertEquals(expected, restored.advance(ALLOWED) { false }.get(30, TimeUnit.SECONDS).phase)
            assertArrayEquals(oldLedger, storage(request, CaptureJournalFile.MANUAL_ATTEMPT).read(ManualTrialAttemptCodec.MAX_BYTES))
            if (mode == "same") {
                assertEquals(ManualOriginalTrialSavePhase.PUBLISHED,
                    restored.advance(ALLOWED, retryCancellation = true) { false }.get(180, TimeUnit.SECONDS).phase)
                val started = ManualTrialAttemptCodec.decode(requireNotNull(storage(request, CaptureJournalFile.MANUAL_ATTEMPT).read(ManualTrialAttemptCodec.MAX_BYTES)))
                assertEquals(2L, started.attempt)
                assertEquals(ManualTrialAttemptPhase.STARTED, started.phase)
                val journal = verifyPublication(request)
                assertEquals(ManualOriginalTrialSavePhase.PUBLISHED, restored.advance(ALLOWED) { false }.get(30, TimeUnit.SECONDS).phase)
                assertEquals(journal, publication(request))
            } else {
                assertEquals(ManualOriginalTrialSavePhase.HOLD,
                    restored.advance(ALLOWED, retryCancellation = true) { false }.get(30, TimeUnit.SECONDS).phase)
                assertThrows(java.util.concurrent.ExecutionException::class.java) {
                    AndroidManualOriginalTrialSaves.prepare(app, request.source, .35, ALLOWED).get(30, TimeUnit.SECONDS)
                }
                assertNull(publication(request))
                assertArrayEquals(oldLedger, storage(request, CaptureJournalFile.MANUAL_ATTEMPT).read(ManualTrialAttemptCodec.MAX_BYTES))
            }
            assertFalse(restored.hasUnreleasedResources)
        }
        assertArrayEquals(oldIntent, storage(request, CaptureJournalFile.MANUAL_REQUEST).read(ManualOriginalTrialRequestCodec.MAX_BYTES))
        verifyOriginal(request)
        File(dir, "resume-ok.txt").writeText("$mode oldPid=${File(dir, "pid.txt").readText()} newPid=${Process.myPid()}")
        // Keep this test's source, outputs and journals as cross-process evidence; no recovery by deletion.
    }

    private fun verifyOriginal(request: Original24Request) = assertEquals(request.source.fingerprint,
        DngContentVerifier.verify(request.source.fingerprint, { requireNotNull(app.contentResolver.openInputStream(Uri.parse(request.source.uri))) }))
    private fun verifyPublication(request: Original24Request): JpegPublicationRecord {
        val record = requireNotNull(publication(request))
        assertEquals(JpegPublicationPhase.PUBLISHED, record.phase)
        assertEquals(request.recipe, record.render.request.recipe)
        assertEquals(request.source, record.render.request.source)
        val inspection = AndroidJpegInspector().inspect({ requireNotNull(app.contentResolver.openInputStream(Uri.parse(record.uri))) }, record.fingerprint.bytes) { false }
        assertEquals(record.fingerprint, inspection.fingerprint)
        return record
    }
    private fun generation(request: Original24Request) = RenderedJpegFiles(File(app.noBackupFilesDir, "raw-captures"), request.captureId, request.generation).generationRoot()
    private fun storage(request: Original24Request, kind: CaptureJournalFile): AtomicJournalStorage =
        AndroidAtomicJournalStorage(requireNotNull(generation(request).parentFile), request.generation.toString(), kind)
    private fun publication(request: Original24Request) = JpegPublicationJournal(request.captureId, request.generation,
        storage(request, CaptureJournalFile.JPEG_PUBLICATION)).read()
    companion object {
        private val ALLOWED = AutoDevelopConditions(true, false, false, false, true, true, true, true, previewMemoryAvailable = true)
    }
}
