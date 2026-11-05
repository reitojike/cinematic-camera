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
                assertNotNull(cancelledLease.comparisonCopy(24, 32))
                assertThrows(DngVerificationStopped::class.java) { f.read(record, shouldStop = { true }) }
                assertNotNull(cancelledLease.comparisonCopy(24, 32))
                val resumed = DecodeCounter()
                f.read(record, resumed).close()
                assertEquals("Normal cancellation with confirmed close preserves the verified entry", 0, resumed.loads)

                val recovered = f.ready(.75, session)
                f.read(recovered).close()
                val corruptLease = requireNotNull(PrivateTrialReuseRegistry.forRequest(recovered.request))
                assertNotNull(corruptLease.comparisonCopy(24, 32))
                // This test's private output only. The generated DNG is never changed here.
                f.output(recovered).writeBytes("synthetic corrupt JPEG".toByteArray())
                assertThrows(java.io.IOException::class.java) { f.read(recovered) }
                assertNull(corruptLease.comparisonCopy(24, 32))
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
                assertNotNull(lease.comparisonCopy(24, 32))
                PrivateTrialReuseRegistry.invalidateAll() // Same entry point used by lifecycle/capture invalidation.
                assertNull(lease.comparisonCopy(24, 32))
                assertNull(PrivateTrialReuseRegistry.forRequest(record.request))

                val next = PrivateTrialReuseRegistry.openSession()
                try {
                    val restored = f.ready(.75, next)
                    f.read(restored).close()
                    val previous = requireNotNull(PrivateTrialReuseRegistry.forRequest(restored.request))
                    assertNotNull(previous.comparisonCopy(24, 32))
                    val changedSource = restored.request.source.copy(grant = OriginalGrant.USER_SELECTED)
                    val changed = restored.request.copy(source = changedSource,
                        captureId = ManualDevelopRequests.identity(changedSource))
                    PrivateTrialReuseRegistry.bind(next, changed)
                    assertNull(previous.comparisonCopy(24, 32))
                    assertNull(PrivateTrialReuseRegistry.forRequest(changed)?.comparisonCopy(24, 32))
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
                assertNotNull(lease.comparisonCopy(24, 32))
                f.changeOwnSyntheticSource()
                val counter = DecodeCounter()
                assertThrows(DngContentChanged::class.java) { f.read(record, counter) }
                assertEquals(0, counter.loads)
                assertNull(lease.comparisonCopy(24, 32))
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
        fun ready(strength: Double, session: PrivateTrialReuseSession?): AutoDevelopRecord {
            val handle = AndroidAutoDevelopQueue.preparePrivateTrial(context, source,
                JpegGenerationRecipe.trial(strength), reuseSession = session).get(60, TimeUnit.SECONDS)
            latest = handle
            var status = handle.status().get(60, TimeUnit.SECONDS)
            if (status.phase in setOf(PrivateTrialPhase.PREPARED, PrivateTrialPhase.RUNNING))
                status = handle.start(allowed).get(60, TimeUnit.SECONDS)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
            while (status.phase in setOf(PrivateTrialPhase.PREPARED, PrivateTrialPhase.RUNNING) && System.nanoTime() < deadline) {
                Thread.sleep(20)
                status = handle.status().get(60, TimeUnit.SECONDS)
            }
            assertEquals(PrivateTrialPhase.READY, status.phase)
            return requireNotNull(AutoDevelopJournal(handle.request.id, handle.request.generation,
                AndroidAtomicJournalStorage(File(directory, "renders"), handle.request.generation.toString(),
                    CaptureJournalFile.AUTO_DEVELOP)).read())
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
        fun output(record: AutoDevelopRecord): File {
            val attempt = requireNotNull(record.attemptId)
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
