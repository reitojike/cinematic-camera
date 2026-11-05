package dev.cinematic.p0.rawcapture

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.jpegrender.JpegFrame
import dev.cinematic.p0.jpegrender.JpegGenerationRecipe
import dev.cinematic.p0.jpegrender.JpegGenerationRequest
import dev.cinematic.p0.testing.writeSyntheticTrialDng
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real queue/codec/RAW decode with generated pixels only. No camera capture or performance claim. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class PrivateTrialReuseInstrumentationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val allowed = AutoDevelopConditions(true, false, false, false, true, true, true, true)

    @Before fun isolatedOnly() {
        check(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        PrivateTrialReuseRegistry.invalidateAll()
        DevelopExecution.updateConditions(allowed)
    }

    @Test fun strengthChangeReusesComparisonWithoutDecodeAndMatchesUncachedBytesAndPixels() {
        val session = PrivateTrialReuseRegistry.openSession()
        try {
            Fixture().use { f ->
                val first = f.ready(.5, session)
                val firstCounter = DecodeCounter()
                val firstBefore = f.read(first, firstCounter).use { pixels(requireNotNull(it.comparisonBitmap)) }
                assertEquals(1, firstCounter.loads)

                val changed = f.ready(.75, session)
                val cachedBytes = f.output(changed).readBytes()
                val hit = DecodeCounter()
                val cached = f.read(changed, hit).use {
                    pixels(it.bitmap) to pixels(requireNotNull(it.comparisonBitmap))
                }
                assertEquals("A comparison hit must never create/load another native frame", 0, hit.factories)
                assertEquals(0, hit.loads)
                assertEquals(firstBefore, cached.second)

                // Return through another generation, then render .75 without any session binding.
                f.ready(.5, null)
                val uncached = f.ready(.75, null)
                val miss = DecodeCounter()
                val uncachedPixels = f.read(uncached, miss).use {
                    pixels(it.bitmap) to pixels(requireNotNull(it.comparisonBitmap))
                }
                assertEquals(1, miss.loads)
                assertTrue(uncached.request.generation > changed.request.generation)
                assertArrayEquals(cachedBytes, f.output(uncached).readBytes())
                assertEquals(cached, uncachedPixels)
                f.assertOriginalAndUnpublished(uncached)
            }
        } finally { session.close() }
    }

    @Test fun cachedComparisonCannotBypassFreshJpegValidationOrCancellation() {
        val session = PrivateTrialReuseRegistry.openSession()
        try {
            Fixture().use { f ->
                val record = f.ready(.5, session)
                f.read(record).close()
                val cancelledLease = requireNotNull(PrivateTrialReuseRegistry.forRequest(record.request))
                assertNotNull(record.comparisonCopy(cancelledLease))
                assertThrows(java.util.concurrent.CancellationException::class.java) { f.read(record, shouldStop = { true }) }
                assertNotNull(record.comparisonCopy(cancelledLease))
                val resumed = DecodeCounter()
                f.read(record, resumed).close()
                assertEquals("Normal cancellation with confirmed close preserves the verified entry", 0, resumed.loads)

                val recovered = f.ready(.75, session)
                f.read(recovered).close()
                val corruptLease = requireNotNull(PrivateTrialReuseRegistry.forRequest(recovered.request))
                assertNotNull(recovered.comparisonCopy(corruptLease))
                // This test's private output only. The generated DNG is never changed here.
                f.output(recovered).writeBytes("synthetic corrupt JPEG".toByteArray())
                assertThrows(java.io.IOException::class.java) { f.read(recovered) }
                assertNull(recovered.comparisonCopy(corruptLease))
                f.assertOriginalAndUnpublished(recovered)
            }
        } finally { session.close() }
    }

    @Test fun globalInvalidationAndSourceGrantChangesDoNotRetainComparisonPixels() {
        val session = PrivateTrialReuseRegistry.openSession()
        try {
            Fixture().use { f ->
                val record = f.ready(.5, session)
                f.read(record).close()
                val lease = requireNotNull(PrivateTrialReuseRegistry.forRequest(record.request))
                assertNotNull(record.comparisonCopy(lease))
                PrivateTrialReuseRegistry.invalidateAll() // Same entry point used by lifecycle/capture invalidation.
                assertNull(record.comparisonCopy(lease))
                assertNull(PrivateTrialReuseRegistry.forRequest(record.request))

                val next = PrivateTrialReuseRegistry.openSession()
                try {
                    val restored = f.ready(.75, next)
                    f.read(restored).close()
                    val previous = requireNotNull(PrivateTrialReuseRegistry.forRequest(restored.request))
                    assertNotNull(restored.comparisonCopy(previous))
                    val changedSource = restored.request.source.copy(grant = OriginalGrant.USER_SELECTED)
                    val changed = restored.request.copy(source = changedSource,
                        captureId = ManualDevelopRequests.identity(changedSource))
                    PrivateTrialReuseRegistry.bind(next, changed)
                    assertNull(restored.comparisonCopy(previous))
                    assertNull(restored.comparisonCopy(PrivateTrialReuseRegistry.forRequest(changed)))
                    f.assertOriginalAndUnpublished(record)
                } finally { next.close() }
            }
        } finally { session.close() }
    }

    @Test fun freshSourceValidationRejectsUpdatedSyntheticBytesBeforeComparisonReuse() {
        val session = PrivateTrialReuseRegistry.openSession()
        try {
            Fixture().use { f ->
                val record = f.ready(.5, session)
                f.read(record).close()
                val lease = requireNotNull(PrivateTrialReuseRegistry.forRequest(record.request))
                assertNotNull(record.comparisonCopy(lease))
                f.changeOwnSyntheticSource()
                val counter = DecodeCounter()
                assertThrows(DngContentChanged::class.java) { f.read(record, counter) }
                assertEquals(0, counter.loads)
                assertNull(record.comparisonCopy(lease))
            }
        } finally { session.close() }
    }

    @Test fun comparisonCloseFailureDropsTheCandidateAndDoesNotCommitReusablePixels() {
        val session = PrivateTrialReuseRegistry.openSession()
        try {
            Fixture().use { f ->
                val record = f.ready(.5, session)
                val counter = DecodeCounter().apply { closeFailures = 1 }
                f.read(record, counter).use { assertNull(it.comparisonBitmap) }
                assertEquals(1, counter.loads)
                assertNull(PrivateTrialReuseRegistry.forRequest(record.request))
                f.assertOriginalAndUnpublished(record)
            }
        } finally { session.close() }
    }

    @Test fun runnerPreverificationFailureInvalidatesAnExistingVerifiedEntryBeforeHolding() {
        val session = PrivateTrialReuseRegistry.openSession()
        try {
            Fixture().use { f ->
                val first = f.ready(.5, session)
                f.read(first).close()
                val pending = f.prepared(.75, session)
                val lease = requireNotNull(PrivateTrialReuseRegistry.forRequest(pending.request))
                assertNotNull(first.comparisonCopy(lease))
                f.changeOwnSyntheticSource()
                val backend = f.backend(pending.request)
                val runner = f.runner(pending.request, backend)
                val held = runner.advance()
                assertEquals(AutoRunStatus.HELD, held.status)
                assertEquals(AutoJobReason.SOURCE_CHANGED, requireNotNull(held.record).reason)
                assertNull(held.record?.attemptId)
                assertNull(first.comparisonCopy(lease))
                assertNull(PrivateTrialReuseRegistry.forRequest(pending.request))
                assertFalse(runner.hasUnreleasedResources)
                assertFalse(backend.hasUnreleasedResources)
            }
        } finally { session.close() }
    }

    @Test fun runnerIndependentFinalInspectionFailureInvalidatesTheJustCommittedFoundation() {
        val session = PrivateTrialReuseRegistry.openSession()
        try {
            Fixture().use { f ->
                val pending = f.prepared(.5, session)
                val actual = f.backend(pending.request)
                var sawCommittedFoundation = false
                val fault = object : AutoDevelopBackend by actual {
                    override fun inspect(request: AutoDevelopRequest, attemptId: String,
                        shouldStop: () -> Boolean): JpegInspection {
                        val lease = requireNotNull(PrivateTrialReuseRegistry.forRequest(request))
                        sawCommittedFoundation = lease.load(JpegGenerationRequest(request.captureId,
                            request.generation, request.source, JpegGenerationRecipe.trial(.5))) != null
                        check(sawCommittedFoundation) { "Inject only after the attempt committed a verified foundation" }
                        // Corrupt this fixture's closed output after attempt completion, before independent inspection.
                        f.output(attemptId).writeBytes("synthetic final inspection fault".toByteArray())
                        return actual.inspect(request, attemptId, shouldStop)
                    }
                }
                val runner = f.runner(pending.request, fault)
                val held = runner.advance()
                assertTrue(sawCommittedFoundation)
                assertEquals(AutoRunStatus.HELD, held.status)
                assertEquals(AutoJobReason.INVALID_OUTPUT, requireNotNull(held.record).reason)
                assertNull(PrivateTrialReuseRegistry.forRequest(pending.request))
                assertFalse(runner.hasUnreleasedResources)
                assertFalse(actual.hasUnreleasedResources)
                f.assertOriginalAndUnpublished(requireNotNull(held.record))
            }
        } finally { session.close() }
    }

    @Test fun actualInspectionAndEveryPrivateReadbackStopKeepVerifiedPixelsAfterPositiveClose() {
        val session = PrivateTrialReuseRegistry.openSession()
        try {
            Fixture().use { f ->
                val record = f.ready(.5, session)
                f.read(record).close()
                val lease = requireNotNull(PrivateTrialReuseRegistry.forRequest(record.request))
                val before = requireNotNull(record.comparisonCopy(lease))
                val backend = f.backend(record.request)
                var inspectionChecks = 0
                backend.inspect(record.request, requireNotNull(record.attemptId)) { inspectionChecks++; false }
                assertTrue(inspectionChecks > 0)
                // Interrupt each actual snapshot/EXIF/codec/post-codec checkpoint, including stop=true at entry.
                for (stopAt in 1..inspectionChecks) {
                    var checks = 0
                    assertThrows(java.util.concurrent.CancellationException::class.java) {
                        backend.inspect(record.request, requireNotNull(record.attemptId)) { ++checks >= stopAt }
                    }
                    assertFalse(backend.hasUnreleasedResources)
                    assertArrayEquals(before, record.comparisonCopy(lease))
                }
                var readbackChecks = 0
                f.read(record, shouldStop = { readbackChecks++; false }).close()
                assertTrue(readbackChecks > inspectionChecks)
                // Include the later comparison, post-original-hash and pre-commit checkpoints, not only entry.
                for (stopAt in 1..readbackChecks) {
                    var checks = 0
                    assertThrows(java.util.concurrent.CancellationException::class.java) {
                        f.read(record, shouldStop = { ++checks >= stopAt })
                    }
                    assertArrayEquals(before, record.comparisonCopy(lease))
                }
                f.assertOriginalAndUnpublished(record)
            }
        } finally { session.close() }
    }

    @Test fun actualCorruptJpegRemainsAnIoFailureWhenStopIsRequestedAfterItsLastPoll() {
        val session = PrivateTrialReuseRegistry.openSession()
        try {
            Fixture().use { f ->
                val record = f.ready(.5, session)
                f.read(record).close()
                val lease = requireNotNull(PrivateTrialReuseRegistry.forRequest(record.request))
                assertNotNull(record.comparisonCopy(lease))
                f.output(record).writeBytes("synthetic corrupt JPEG without markers".toByteArray())
                val backend = f.backend(record.request)
                var checks = 0
                var stopRequested = false
                // Small-file snapshot: entry, read, EOF, close, then first inspection poll. Model a stop
                // racing immediately after that last poll and before the actual marker validation fails.
                assertThrows(java.io.IOException::class.java) {
                    backend.inspect(record.request, requireNotNull(record.attemptId)) {
                        val sampled = stopRequested
                        if (++checks == 5) stopRequested = true
                        sampled
                    }
                }
                assertTrue(stopRequested)
                assertEquals(5, checks)
                assertFalse(backend.hasUnreleasedResources)
                assertNull(record.comparisonCopy(lease))
                assertNull(PrivateTrialReuseRegistry.forRequest(record.request))
                f.assertOriginalAndUnpublished(record)
            }
        } finally { session.close() }
    }

    /** Tiny native inputs may be half-sized before physical orientation. Use only inspected output geometry. */
    private fun AutoDevelopRecord.comparisonCopy(lease: PrivateTrialReuseLease?): IntArray? {
        val output = requireNotNull(observed).output
        return lease?.comparisonCopy(output.width, output.height)
    }

    private class DecodeCounter { var factories = 0; var loads = 0; var closeFailures = 0 }

    private inner class Fixture : AutoCloseable {
        private val resolver = context.contentResolver
        private val uri = requireNotNull(resolver.insert(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "synthetic_reuse_${UUID.randomUUID()}.dng")
                put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Cinematic Camera Synthetic Tests/")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }))
        val source: SharedOriginalReference
        private val root = File(context.noBackupFilesDir, "raw-captures").canonicalFile
        private val directory: File
        private var latest: PrivateTrialHandle? = null
        private var cleanupFingerprint: OriginalFingerprint? = null
        init {
            try {
                val bytes = resolver.openOutputStream(uri, "w")!!.use { writeSyntheticTrialDng(it, 32, 24, 6) }
                val fingerprint = DngContentVerifier.identify(bytes, { resolver.openInputStream(uri)!! })
                check(resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) == 1)
                source = SharedOriginalReference(uri.toString(), fingerprint, OriginalGrant.APP_OWNED)
                cleanupFingerprint = fingerprint
                directory = File(root, ManualDevelopRequests.identity(source)).canonicalFile
                check(directory.parentFile == root && !directory.exists())
            } catch (failure: Throwable) {
                try { resolver.delete(uri, null, null) } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                throw failure
            }
        }
        fun prepared(strength: Double, session: PrivateTrialReuseSession?): AutoDevelopRecord {
            val handle = AndroidAutoDevelopQueue.preparePrivateTrial(context, source,
                JpegGenerationRecipe.trial(strength), reuseSession = session).get(60, TimeUnit.SECONDS)
            latest = handle
            return requireNotNull(journal(handle.request.generation).read())
        }
        fun ready(strength: Double, session: PrivateTrialReuseSession?): AutoDevelopRecord {
            prepared(strength, session)
            val handle = requireNotNull(latest)
            var status = handle.status().get(60, TimeUnit.SECONDS)
            if (status.phase in setOf(PrivateTrialPhase.PREPARED, PrivateTrialPhase.RUNNING))
                status = handle.start(allowed).get(60, TimeUnit.SECONDS)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
            while (status.phase in setOf(PrivateTrialPhase.PREPARED, PrivateTrialPhase.RUNNING) && System.nanoTime() < deadline) {
                Thread.sleep(20)
                status = handle.status().get(60, TimeUnit.SECONDS)
            }
            assertEquals(PrivateTrialPhase.READY, status.phase)
            return requireNotNull(journal(handle.request.generation).read())
        }
        private fun journal(generation: Long) = AutoDevelopJournal(ManualDevelopRequests.identity(source), generation,
            AndroidAtomicJournalStorage(File(directory, "renders"), generation.toString(), CaptureJournalFile.AUTO_DEVELOP))
        fun backend(request: AutoDevelopRequest) = AndroidAutoDevelopBackend(context, request,
            AutoRendererCatalog(listOf(BoundedJpegRendererAdapter.privateTrial(PrivateTrialReuseRegistry::forRequest))))
        fun runner(request: AutoDevelopRequest, backend: AutoDevelopBackend): AutoDevelopRunner {
            // The queue's prepared job is disabled and has no start intent. This isolated fault harness alone
            // advances it, synchronously, under the same coordinator; it never constructs a publication owner.
            assertEquals(PrivateTrialPhase.PREPARED, latest!!.status().get(60, TimeUnit.SECONDS).phase)
            return AutoDevelopRunner(journal(request.generation), DevelopExecution.coordinator, backend,
                interactive = true, observations = { allowed }, allowPublication = { false }) { true }
        }
        fun read(record: AutoDevelopRecord, counter: DecodeCounter = DecodeCounter(),
            shouldStop: () -> Boolean = { false }): PrivateTrialPreview {
            // The real backend is tested directly solely to observe comparison-frame factory/load counts.
            // Its reads still hold the same process-wide admission slot and use the queue's actual JPEG.
            val backend = AndroidAutoDevelopBackend(context, record.request,
                AutoRendererCatalog(listOf(BoundedJpegRendererAdapter.privateTrial(PrivateTrialReuseRegistry::forRequest)))) { io, source ->
                counter.factories++
                val actual = AndroidOwnedJpegFrames(io, source)
                object : OwnedJpegFrameSource {
                    override val contract get() = actual.contract
                    override fun load(source: SharedOriginalReference, longEdge: Int, shouldStop: () -> Boolean): JpegFrame {
                        counter.loads++
                        return actual.load(source, longEdge, shouldStop)
                    }
                    override fun requestStop() = actual.requestStop()
                    override fun close() {
                        actual.close()
                        if (counter.closeFailures > 0) {
                            counter.closeFailures--
                            throw java.io.IOException("Synthetic comparison close failure after actual FD closure")
                        }
                    }
                }
            }
            val slot = requireNotNull(DevelopExecution.tryStartObserved(allowed))
            try { return backend.privatePreview(record, shouldStop) }
            finally {
                backend.releaseResources()
                check(!backend.hasUnreleasedResources)
                check(DevelopExecution.completeAfterResourcesClosed(slot))
            }
        }
        fun output(record: AutoDevelopRecord): File = output(requireNotNull(record.attemptId))
        fun output(attempt: String): File {
            check(attempt.matches(Regex("[0-9a-f]{32}")))
            return File(directory, "auto-attempts/$attempt/renders/1/output.jpg").canonicalFile.also {
                check(it.path.startsWith(directory.path + File.separator) && it.isFile)
            }
        }
        fun assertOriginalAndUnpublished(record: AutoDevelopRecord) {
            DngContentVerifier.verify(source.fingerprint, { resolver.openInputStream(uri)!! })
            assertNull(AndroidAtomicJournalStorage(File(directory, "renders"), record.request.generation.toString(),
                CaptureJournalFile.JPEG_PUBLICATION).read(JpegPublicationCodec.MAX_BYTES))
        }
        fun changeOwnSyntheticSource() {
            // Fault injection changes only the generated URI owned by this fixture, never a user source.
            resolver.openOutputStream(uri, "wa")!!.use { it.write(7) }
            cleanupFingerprint = resolver.openInputStream(uri)!!.use { input ->
                val bytes = input.readBytes()
                DngContentVerifier.identify(bytes.size.toLong(), { bytes.inputStream() })
            }
            assertNotEquals(source.fingerprint, cleanupFingerprint)
        }
        override fun close() {
            latest?.let { previous ->
                val recipe = requireNotNull(JpegGenerationRecipe.fromTrialSnapshot(previous.request.recipe))
                val fenced = AndroidAutoDevelopQueue.preparePrivateTrial(context, source, recipe).get(60, TimeUnit.SECONDS)
                fenced.requestStop()
                fenced.status().get(60, TimeUnit.SECONDS)
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
            var slot = DevelopExecution.tryStartObserved(allowed)
            while (slot == null && System.nanoTime() < deadline) {
                Thread.sleep(20)
                slot = DevelopExecution.tryStartObserved(allowed)
            }
            check(slot != null) { "Keep synthetic artifacts until all native/FD owners acknowledge close" }
            check(DevelopExecution.completeAfterResourcesClosed(slot))
            DngContentVerifier.verify(requireNotNull(cleanupFingerprint), { resolver.openInputStream(uri)!! })
            check(resolver.delete(uri, null, null) == 1)
            check(directory.canonicalFile.parentFile == root)
            if (directory.exists()) check(directory.deleteRecursively())
        }
    }

    private fun pixels(bitmap: Bitmap): String {
        assertFalse(bitmap.isRecycled)
        assertEquals(Bitmap.Config.ARGB_8888, bitmap.config)
        assertTrue(bitmap.colorSpace!!.isSrgb)
        val digest = MessageDigest.getInstance("SHA-256")
        val row = IntArray(bitmap.width)
        val bytes = ByteBuffer.allocate(bitmap.width * 4)
        repeat(bitmap.height) { y ->
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            bytes.clear(); row.forEach { bytes.putInt(it) }; digest.update(bytes.array())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
