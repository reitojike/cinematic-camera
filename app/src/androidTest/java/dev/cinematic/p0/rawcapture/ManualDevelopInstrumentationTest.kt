package dev.cinematic.p0.rawcapture

import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.jpegrender.*
import dev.cinematic.p0.raw.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Entirely synthetic. Its known-none/EV provider is never enrolled in the application singleton. */
@RunWith(AndroidJUnit4::class)
class ManualDevelopInstrumentationTest {
    private val legacyHolder = lazy { LegacyCpuFixture.create(useReuseRegistry = true) }
    private val legacy by legacyHolder
    @org.junit.After fun closeLegacyFixture() { if (legacyHolder.isInitialized()) legacy.close() }
    @Test fun privateTrialFactoryRendersAndFreshlyDisplaysWithoutAnyPublicationCapability() {
        check(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        check(Build.HARDWARE in setOf("goldfish", "ranchu") && Build.VERSION.SDK_INT >= 29)
        val context = legacy.app
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "synthetic_private_${UUID.randomUUID()}.dng")
            put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Cinematic Camera Synthetic Tests/")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        })!!
        var ownedDirectory: File? = null
        var handle: PrivateTrialHandle? = null
        var primary: Throwable? = null
        try {
            val bytes = syntheticDng(2048, 1536)
            resolver.openOutputStream(uri, "w")!!.use { it.write(bytes) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            val fp = DngContentVerifier.identify(bytes.size.toLong(), { resolver.openInputStream(uri)!! })
            val source = SharedOriginalReference(uri.toString(), fp, OriginalGrant.USER_SELECTED)
            val id = ManualDevelopRequests.identity(source)
            val root = File(context.noBackupFilesDir, "raw-captures")
            val candidate = File(root, id)
            check(!candidate.exists()) { "Refuse to reuse an existing private operation" }
            ownedDirectory = candidate
            val allowed = AutoDevelopConditions(true, false, false, false, true, true, true, true)
            legacy.execution.updateConditions(allowed)
            val trial = legacy.preparePrivateTrial( source).join().also { handle = it }
            val beforeRender = legacy.execution.reserveCapture()
            try {
                trial.start(allowed).join()
                assertEquals(PrivateTrialPhase.PREPARED, trial.status().join().phase)
            } finally { legacy.execution.releaseCapture(beforeRender) }
            val ready = trial.status().join()
            assertEquals(PrivateTrialPhase.READY, ready.phase)
            assertEquals(AutoRenderQuality.PRIVATE_TRIAL_1024, ready.request.quality)
            assertEquals(RenderedFaceState.NOT_RUN, ready.face)
            assertEquals(RenderedExposureState.DIAGNOSTIC, ready.exposure)
            val output = requireNotNull(ready.output)
            assertEquals(768, output.width); assertEquals(1024, output.height)
            val storage = AndroidAtomicJournalStorage(File(candidate, "renders"), trial.request.generation.toString(), CaptureJournalFile.AUTO_DEVELOP)
            val journal = AutoDevelopJournal(id, trial.request.generation, storage)
            val attempt = journal.read()!!.attemptId
            val delivery = trial.readPreview(allowed) { false }.join()
            val image = delivery.claim()!!
            assertEquals(768, image.bitmap.width); assertEquals(1024, image.bitmap.height)
            assertTrue(image.bitmap.colorSpace!!.isSrgb)
            assertNotEquals(android.graphics.Bitmap.Config.HARDWARE, image.bitmap.config)
            assertEquals(ready.fingerprint, image.status.fingerprint)
            image.close(); assertTrue(image.bitmap.isRecycled)
            val capture = legacy.execution.reserveCapture()
            try { assertThrows(java.util.concurrent.CompletionException::class.java) { trial.readPreview(allowed) { false }.join() } }
            finally { legacy.execution.releaseCapture(capture) }
            val unclaimed = trial.readPreview(allowed) { false }.join()
            trial.requestStop()
            assertNull(unclaimed.claim())
            val same = trial.readPreview(allowed) { false }.join().claim()!!
            assertEquals(ready.fingerprint, same.status.fingerprint)
            same.close()
            // Force future cancellation before its queued readback; rejected delivery must close.
            val whileSelecting = trial.readPreview(allowed) { false }.join()
            val entered = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            val blockedPrepare = legacy.get(context).preparePrivateTrial(id) {
                entered.countDown()
                check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                journal.read()!!.request
            }
            check(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            try {
                val whilePreparing = whileSelecting.claim()
                try { assertNull(whilePreparing) } finally { whilePreparing?.close() }
                val abandoned = trial.readPreview(allowed) { false }
                assertTrue(abandoned.cancel(false))
            } finally { release.countDown(); blockedPrepare.handle { _, _ -> Unit }.join() }
            blockedPrepare.join()
            val afterAbandon = trial.readPreview(allowed) { false }.join().claim()!!
            afterAbandon.close()
            assertEquals(attempt, journal.read()!!.attemptId)
            assertNull(AndroidAtomicJournalStorage(File(candidate, "renders"), trial.request.generation.toString(),
                CaptureJournalFile.JPEG_PUBLICATION).read(JpegPublicationCodec.MAX_BYTES))
            DngContentVerifier.verify(fp, { resolver.openInputStream(uri)!! })
            val stale = trial.readPreview(allowed) { false }.join()
            val selected = AndroidAtomicJournalStorage(root, id, CaptureJournalFile.MANUAL_REQUEST)
            val acknowledgementLost = object : AtomicJournalStorage {
                override fun read(maxBytes: Int) = selected.read(maxBytes)
                override fun replace(contents: ByteArray) { selected.replace(contents); throw java.io.IOException("Synthetic commit acknowledgement lost") }
            }
            val unknown = legacy.get(context).preparePrivateTrial(id) {
                ManualDevelopRequests(source, acknowledgementLost).prepare(JpegGenerationRecipe.trial().snapshot.copy(payload =
                    JpegGenerationRecipe.trial().snapshot.payload + ";synthetic-new-policy"))
            }
            assertThrows(java.util.concurrent.CompletionException::class.java) { unknown.join() }
            val staleClaim = stale.claim()
            try { assertNull(staleClaim) } finally { staleClaim?.close() }
            val completeRecipe = JpegGenerationRecipe(AndroidAsShotJpegFrames.CONTRACT, RenderRecipeSnapshot("synthetic-unregistered-v1", "fixed"))
            val next = legacy.prepareManual( source, completeRecipe).join()
            assertEquals(trial.request.generation + 2, next.request.generation)
            assertNull(stale.claim())
            assertThrows(java.util.concurrent.CompletionException::class.java) { trial.readPreview(allowed) { false }.join() }
            assertThrows(java.util.concurrent.CompletionException::class.java) { next.start(allowed).join() }
            assertNull(AndroidAtomicJournalStorage(File(candidate, "renders"), trial.request.generation.toString(),
                CaptureJournalFile.JPEG_PUBLICATION).read(JpegPublicationCodec.MAX_BYTES))
            DngContentVerifier.verify(fp, { resolver.openInputStream(uri)!! })
        } catch (failure: Throwable) { primary = failure; throw failure }
        finally {
            dev.cinematic.p0.jpegrender.original.preserveSafetyCleanup(primary, {
                handle?.requestStop()
                legacy.close() // Positive owner close precedes any source/journal deletion.
                if (primary == null) {
                    check(resolver.delete(uri, null, null) == 1)
                    ownedDirectory?.let { directory ->
                        check(directory.canonicalFile.parentFile == File(context.noBackupFilesDir, "raw-captures").canonicalFile)
                        if (directory.exists()) check(directory.deleteRecursively())
                    }
                }
            })
        }
    }

    @Test fun ownedNativeDngToPrivateJpegThenExplicitPublicationAndFreshConfirmation() {
        check(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        check(Build.HARDWARE in setOf("goldfish", "ranchu") && Build.VERSION.SDK_INT >= 29)
        val context = legacy.app
        val resolver = context.contentResolver
        val sourceUri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "synthetic_manual_${UUID.randomUUID()}.dng")
            put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Cinematic Camera Synthetic Tests/")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        })!!
        var resultUri: android.net.Uri? = null
        var primary: Throwable? = null
        var ownedQueue: AutoDevelopQueue? = null
        var ownedDirectory: File? = null
        try {
            val bytes = syntheticDng()
            resolver.openOutputStream(sourceUri, "w")!!.use { it.write(bytes) }
            resolver.update(sourceUri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            val fp = DngContentVerifier.identify(bytes.size.toLong(), { resolver.openInputStream(sourceUri)!! })
            val source = SharedOriginalReference(sourceUri.toString(), fp, OriginalGrant.USER_SELECTED)
            val policy = RenderRecipeSnapshot("synthetic-controls-v1", "ev=0;faces=known-none")
            val recipe = JpegGenerationRecipe(AndroidAsShotJpegFrames.CONTRACT, policy)
            val controls = object : JpegControlsProvider {
                override val contract = policy.schema
                override fun prepare(image: LinearSrgbImage, policy: RenderRecipeSnapshot, shouldStop: () -> Boolean) = JpegControls(
                    policy, 0.0, FloatPlane(image.width, image.height, FloatArray(image.pixelCount)),
                    FaceMask(image.width, image.height, FloatArray(image.pixelCount)), RenderedFaceState.VERIFIED_NONE, RenderedExposureState.SUPPORTED)
            }
            val adapter = BoundedJpegRendererAdapter(recipe, { request, io -> AndroidOwnedJpegFrames(io, request.source) },
                controls, AndroidSrgbJpegEncoder, AndroidJpegInspector())
            val catalog = AutoRendererCatalog(listOf(adapter))
            val root = File(context.noBackupFilesDir, "raw-captures")
            val id = ManualDevelopRequests.identity(source)
            ownedDirectory = File(root, id)
            val queue = AutoDevelopQueue(Executor { it.run() }, AutoDevelopCoordinator(), { request ->
                val directory = RenderedJpegFiles(root, request.captureId, request.generation).generationRoot()
                AutoDevelopJournal(request.captureId, request.generation, AndroidAtomicJournalStorage(directory.parentFile!!,
                    request.generation.toString(), CaptureJournalFile.AUTO_DEVELOP))
            }, { request -> AndroidAutoDevelopBackend(context, request, catalog) }).also { ownedQueue = it }
            val requests = ManualDevelopRequests(source, AndroidAtomicJournalStorage(root, id, CaptureJournalFile.MANUAL_REQUEST))
            val handle = queue.prepareManual(id) { requests.prepare(recipe.snapshot) }.join()
            val allowed = AutoDevelopConditions(true, false, false, false, true, true, true, true)
            assertEquals(AutoJobPhase.QUEUED, handle.status().join().phase)
            handle.start(allowed).join()
            val rendered = handle.status().join()
            assertEquals(AutoJobPhase.RENDERED, rendered.phase)
            assertEquals(OriginalGrant.USER_SELECTED, rendered.request.source.grant)
            assertEquals(AutoRenderQuality.MANUAL_BOUNDED_1024_PRELIMINARY, rendered.request.quality)
            assertEquals(RenderedFaceState.NOT_RUN, rendered.request.face)
            assertEquals(RenderedFaceState.VERIFIED_NONE, rendered.completed().face)
            assertEquals(12, rendered.completed().request.output.width)
            assertEquals(16, rendered.completed().request.output.height)
            val attemptId = rendered.attemptId
            handle.publish(allowed).join()
            assertEquals(AutoJobPhase.PUBLISHED, handle.status().join().phase)
            val checked = handle.confirmPublished(allowed) { false }.join()
            resultUri = android.net.Uri.parse(checked.uri)
            assertEquals(JpegPublicationPhase.PUBLISHED, checked.phase)
            assertEquals(attemptId, handle.status().join().attemptId)
            DngContentVerifier.verify(fp, { resolver.openInputStream(sourceUri)!! })
            val output = AndroidJpegInspector().inspect({ resolver.openInputStream(resultUri!!)!! }) { false }
            assertEquals(rendered.outputFingerprint, output.fingerprint)
            assertEquals(1, resolver.delete(resultUri!!, null, null))
            resultUri = null
            assertTrue(handle.confirmPublished(allowed) { false }.isCompletedExceptionally)
            assertEquals(AutoJobPhase.PUBLISHED, handle.status().join().phase)
            assertEquals(attemptId, handle.status().join().attemptId)
        } catch (failure: Throwable) { primary = failure; throw failure }
        finally {
            dev.cinematic.p0.jpegrender.original.preserveSafetyCleanup(primary, {
                ownedQueue?.requestStop()
                ownedQueue?.manualOriginalIo { Unit }?.get(60, java.util.concurrent.TimeUnit.SECONDS)
                legacy.close()
                if (primary == null) {
                    resultUri?.let { check(resolver.delete(it, null, null) == 1) }
                    check(resolver.delete(sourceUri, null, null) == 1)
                    ownedDirectory?.let { directory ->
                        check(directory.canonicalFile.parentFile == File(context.noBackupFilesDir, "raw-captures").canonicalFile)
                        if (directory.exists()) check(directory.deleteRecursively())
                    }
                }
            })
        }
    }

    // Generated TIFF/Bayer values only; same minimal layout as the existing decoder tests.
    private fun syntheticDng(width: Int = 32, height: Int = 24): ByteArray {
        fun shorts(vararg values: Int) = ByteBuffer.allocate(values.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            .apply { values.forEach { putShort(it.toShort()) } }.array()
        fun longs(vararg values: Int) = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            .apply { values.forEach { putInt(it) } }.array()
        data class Entry(val tag: Int, val type: Int, val count: Int, val bytes: ByteArray)
        val entries = mutableListOf<Entry>()
        fun short(tag: Int, vararg values: Int) { entries += Entry(tag, 3, values.size, shorts(*values)) }
        fun long(tag: Int, value: Int) { entries += Entry(tag, 4, 1, longs(value)) }
        fun ascii(tag: Int, value: String) { entries += Entry(tag, 2, value.length + 1, (value + '\u0000').toByteArray()) }
        long(256, width); long(257, height); short(258, 16); short(259, 1); short(262, 32803)
        ascii(271, "Synthetic"); ascii(272, "Bayer fixture")
        long(273, 0); short(274, 6); short(277, 1); long(278, height); long(279, width * height * 2)
        short(33421, 2, 2); entries += Entry(33422, 1, 4, byteArrayOf(0, 1, 1, 2))
        entries += Entry(50706, 1, 4, byteArrayOf(1, 4, 0, 0))
        short(50713, 1, 1); long(50714, 64); long(50717, 4095)
        entries += Entry(50721, 10, 9, longs(1, 1, 0, 1, 0, 1, 0, 1, 1, 1, 0, 1, 0, 1, 0, 1, 1, 1))
        entries += Entry(50728, 5, 3, longs(1, 1, 1, 1, 1, 1)); short(50778, 21)
        entries.sortBy { it.tag }
        val start = 8 + 2 + 12 * entries.size + 4
        val offset = start + entries.sumOf { if (it.bytes.size > 4) it.bytes.size else 0 }
        val buffer = ByteBuffer.allocate(offset + width * height * 2).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put('I'.code.toByte()).put('I'.code.toByte()).putShort(42).putInt(8).putShort(entries.size.toShort())
        var extra = start
        for (entry in entries) {
            buffer.putShort(entry.tag.toShort()).putShort(entry.type.toShort()).putInt(entry.count)
            when {
                entry.tag == 273 -> buffer.putInt(offset)
                entry.bytes.size <= 4 -> { buffer.put(entry.bytes); repeat(4 - entry.bytes.size) { buffer.put(0) } }
                else -> { buffer.putInt(extra); System.arraycopy(entry.bytes, 0, buffer.array(), extra, entry.bytes.size); extra += entry.bytes.size }
            }
        }
        buffer.putInt(0).position(offset)
        repeat(height) { y -> repeat(width) { x -> buffer.putShort((164 + (x * 65 + y * 25) % 3500).toShort()) } }
        return buffer.array()
    }
}
