package dev.cinematic.p0.rawcapture

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
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

/** Historical class name retained for test selection; now tests fresh readback, not CPU cache reuse.
 * Isolated legacy queue/codec/RAW decode with generated pixels only. No product enrollment. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class PrivateTrialReuseInstrumentationTest {
    private val legacyHolder = lazy { LegacyCpuFixture.create() }
    private val legacy by legacyHolder
    @org.junit.After fun closeLegacyFixture() { if (legacyHolder.isInitialized()) legacy.close() }
    private val context get() = legacy.app
    private val allowed = AutoDevelopConditions(true, false, false, false, true, true, true, true)

    @Before fun isolatedOnly() {
        check(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        legacy.execution.updateConditions(allowed)
    }

    @Test fun freshSourceValidationRejectsUpdatedSyntheticBytesBeforeComparisonDecode() {
            Fixture().use { f ->
                val record = f.ready(.5)
                f.read(record).close()
                f.changeOwnSyntheticSource()
                val counter = DecodeCounter()
                assertThrows(DngContentChanged::class.java) { f.read(record, counter) }
                assertEquals(0, counter.loads)
            }
    }

    @Test fun actualInspectionAndEveryPrivateReadbackStopReleaseBeforeFreshRetry() {
            Fixture().use { f ->
                val record = f.ready(.5)
                val baseline = f.read(record).use { pixels(it.bitmap) }
                val backend = f.backend(record.request)
                var inspectionChecks = 0
                backend.inspect(record.request, requireNotNull(record.attemptId)) { inspectionChecks++; false }
                backend.releaseResources()
                assertTrue(inspectionChecks > 0)
                // Interrupt each actual snapshot/EXIF/codec/post-codec checkpoint, including stop=true at entry.
                for (stopAt in 1..inspectionChecks) {
                    var checks = 0
                    assertThrows(java.util.concurrent.CancellationException::class.java) {
                        backend.inspect(record.request, requireNotNull(record.attemptId)) { ++checks >= stopAt }
                    }
                    backend.releaseResources()
                    assertFalse(backend.hasUnreleasedResources)
                    backend.inspect(record.request, requireNotNull(record.attemptId)) { false }
                    backend.releaseResources()
                }
                var readbackChecks = 0
                f.read(record, shouldStop = { readbackChecks++; false }).close()
                assertTrue(readbackChecks > inspectionChecks)
                // Include comparison and post-original-hash checkpoints, not only entry.
                for (stopAt in 1..readbackChecks) {
                    var checks = 0
                    assertThrows(java.util.concurrent.CancellationException::class.java) {
                        f.read(record, shouldStop = { ++checks >= stopAt })
                    }
                    f.read(record).use { assertEquals(baseline, pixels(it.bitmap)) }
                }
                f.assertOriginalAndUnpublished(record)
            }
    }

    @Test fun actualCorruptJpegRemainsAnIoFailureWhenStopIsRequestedAfterItsLastPoll() {
            Fixture().use { f ->
                val record = f.ready(.5)
                f.read(record).close()
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
                backend.releaseResources()
                    assertFalse(backend.hasUnreleasedResources)
                f.assertOriginalAndUnpublished(record)
            }
    }

    @Test fun runnerRejectsAnotherValidJpegWithoutPublishing() {
            Fixture().use { f ->
                val pending = f.prepared(.5)
                val actual = f.backend(pending.request)
                var replacedWithValidJpeg = false
                val fault = object : AutoDevelopBackend by actual {
                    override fun inspect(request: AutoDevelopRequest, attemptId: String,
                        shouldStop: () -> Boolean): JpegInspection {
                        f.replaceWithDifferentValidJpeg(attemptId)
                        replacedWithValidJpeg = true
                        return actual.inspect(request, attemptId, shouldStop)
                    }
                }
                val runner = f.runner(pending.request, fault)
                val held = runner.advance()
                assertTrue(replacedWithValidJpeg)
                assertEquals(AutoRunStatus.HELD, held.status)
                assertEquals(AutoJobReason.INVALID_OUTPUT, requireNotNull(held.record).reason)
                assertFalse(runner.hasUnreleasedResources)
                assertFalse(actual.hasUnreleasedResources)
                f.assertOriginalAndUnpublished(requireNotNull(held.record))
            }
    }

    @Test fun runnerRejectsForeignAttemptAndSameGeometryLengthJpegWithAnotherHash() {
            Fixture().use { f ->
                val pending = f.prepared(.5)
                val actual = f.backend(pending.request)
                var checkedValidReplacement = false
                val fault = object : AutoDevelopBackend by actual {
                    override fun inspect(request: AutoDevelopRequest, attemptId: String,
                        shouldStop: () -> Boolean): JpegInspection {
                        val foreign = if (attemptId == "a".repeat(32)) "b".repeat(32) else "a".repeat(32)
                        val rejected = assertThrows(java.io.IOException::class.java) { actual.inspect(request, foreign) { false } }
                        assertEquals("Private inspection belongs to another attempt", rejected.message)
                        f.replaceWithSameGeometryAndLengthJpeg(attemptId)
                        checkedValidReplacement = true
                        return actual.inspect(request, attemptId, shouldStop)
                    }
                }
                val runner = f.runner(pending.request, fault)
                val held = runner.advance()
                assertTrue(checkedValidReplacement)
                assertEquals(AutoRunStatus.HELD, held.status)
                assertEquals(AutoJobReason.INVALID_OUTPUT, requireNotNull(held.record).reason)
                assertFalse(runner.hasUnreleasedResources)
                assertFalse(actual.hasUnreleasedResources)
                f.assertOriginalAndUnpublished(requireNotNull(held.record))
            }
    }
    @Test fun comparisonCloseFailureHoldsAdmissionUntilExplicitRelease() {
        Fixture().use { f ->
            val record = f.ready(.5)
            val counter = DecodeCounter().apply { failClose = true }
            val backend = f.readBackend(record, counter)
            val slot = f.acquireReadSlot()
            var released = false
            var primary: Throwable? = null
            try {
                assertThrows(java.io.IOException::class.java) { backend.privatePreview(record) { false } }
                assertEquals(1, counter.loads)
                assertTrue(counter.closeCalls > 0)
                assertTrue(backend.hasUnreleasedResources)
                assertThrows(java.io.IOException::class.java) { backend.releaseResources() }
                assertTrue(backend.hasUnreleasedResources)
                assertNull(legacy.execution.tryStartObserved(allowed))
                counter.failClose = false
                backend.releaseResources()
                assertFalse(backend.hasUnreleasedResources)
                assertTrue(legacy.execution.completeAfterResourcesClosed(slot))
                released = true
                f.read(record).close()
                f.assertOriginalAndUnpublished(record)
            } catch (failure: Throwable) { primary = failure; throw failure }
            finally {
                counter.failClose = false
                dev.cinematic.p0.jpegrender.original.preserveSafetyCleanup(primary, {
                    backend.releaseResources()
                    check(!backend.hasUnreleasedResources)
                    if (!released) check(legacy.execution.completeAfterResourcesClosed(slot))
                })
            }
        }
    }
    @Test fun unknownObservationDuringFreshReadbackStopsOnlyThatAttemptAndPreservesOriginal() {
        Fixture().use { f ->
            val record = f.ready(.5)
            val baseline = f.read(record).use { pixels(it.bitmap) }
            val counter = DecodeCounter().apply {
                onLoad = { legacy.execution.updateConditions(AutoDevelopConditions()) }
            }
            val backend = f.readBackend(record, counter)
            val slot = f.acquireReadSlot()
            val callerStop = false
            var released = false
            var primary: Throwable? = null
            try {
                assertFalse(legacy.execution.shouldStop(slot))
                assertThrows(java.util.concurrent.CancellationException::class.java) {
                    backend.privatePreview(record) {
                        callerStop || legacy.execution.shouldStop(slot)
                    }.use { fail("Unknown observations must cancel the active readback") }
                }
                assertFalse(callerStop)
                assertEquals(1, counter.loads)
                assertTrue(counter.closeCalls > 0)
                assertTrue(legacy.execution.shouldStop(slot))
                backend.releaseResources()
                assertFalse(backend.hasUnreleasedResources)
                assertTrue(legacy.execution.completeAfterResourcesClosed(slot))
                released = true
                legacy.execution.updateConditions(allowed)
                f.read(record).use { assertEquals(baseline, pixels(it.bitmap)) }
                f.assertOriginalAndUnpublished(record)
            } catch (failure: Throwable) { primary = failure; throw failure }
            finally {
                dev.cinematic.p0.jpegrender.original.preserveSafetyCleanup(primary, {
                    backend.releaseResources()
                    check(!backend.hasUnreleasedResources)
                    if (!released) check(legacy.execution.completeAfterResourcesClosed(slot))
                }, { legacy.execution.updateConditions(allowed) })
            }
        }
    }

    private class DecodeCounter {
        var factories = 0; var loads = 0; var failClose = false; var closeCalls = 0
        var onLoad: () -> Unit = {}
    }

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
        fun prepared(strength: Double): AutoDevelopRecord {
            val handle = legacy.preparePrivateTrial( source,
                JpegGenerationRecipe.trial(strength)).get(60, TimeUnit.SECONDS)
            latest = handle
            return requireNotNull(journal(handle.request.generation).read())
        }
        fun ready(strength: Double): AutoDevelopRecord {
            prepared(strength)
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
            AutoRendererCatalog(listOf(BoundedJpegRendererAdapter.privateTrial())))
        fun runner(request: AutoDevelopRequest, backend: AutoDevelopBackend): AutoDevelopRunner {
            // The queue's prepared job is disabled and has no start intent. This isolated fault harness alone
            // advances it, synchronously, under the same coordinator; it never constructs a publication owner.
            assertEquals(PrivateTrialPhase.PREPARED, latest!!.status().get(60, TimeUnit.SECONDS).phase)
            return legacy.runner(journal(request.generation), backend) { allowed }
        }
        fun readBackend(record: AutoDevelopRecord, counter: DecodeCounter) = AndroidAutoDevelopBackend(context, record.request,
            AutoRendererCatalog(listOf(BoundedJpegRendererAdapter.privateTrial()))) { io, source ->
                counter.factories++
                val actual = AndroidOwnedJpegFrames(io, source)
                object : OwnedJpegFrameSource {
                    override val contract get() = actual.contract
                    override fun load(source: SharedOriginalReference, longEdge: Int, shouldStop: () -> Boolean): JpegFrame {
                        counter.loads++
                        counter.onLoad()
                        return actual.load(source, longEdge, shouldStop)
                    }
                    override fun requestStop() = actual.requestStop()
                    override fun close() {
                        counter.closeCalls++
                        if (counter.failClose) throw java.io.IOException("Synthetic unacknowledged comparison close")
                        actual.close()
                    }
                }
            }
        fun acquireReadSlot(): AutoDevelopCoordinator.Attempt {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
            var admitted = legacy.execution.tryStartObserved(allowed)
            while (admitted == null && System.nanoTime() < deadline) {
                Thread.sleep(20)
                admitted = legacy.execution.tryStartObserved(allowed)
            }
            return checkNotNull(admitted) { "Timed out waiting for isolated readback admission" }
        }
        fun read(record: AutoDevelopRecord, counter: DecodeCounter = DecodeCounter(),
            shouldStop: () -> Boolean = { false }): PrivateTrialPreview {
            val backend = readBackend(record, counter)
            val slot = acquireReadSlot()
            var primary: Throwable? = null
            var delivery: PrivateTrialPreview? = null
            try { return backend.privatePreview(record, shouldStop).also { delivery = it } }
            catch (failure: Throwable) { primary = failure; throw failure }
            finally {
                dev.cinematic.p0.jpegrender.original.preserveSafetyCleanup(primary, {
                    try {
                        backend.releaseResources()
                        check(!backend.hasUnreleasedResources)
                        check(legacy.execution.completeAfterResourcesClosed(slot))
                    } catch (cleanup: Throwable) { delivery?.close(); throw cleanup }
                })
            }
        }
        fun output(record: AutoDevelopRecord): File = output(requireNotNull(record.attemptId))
        fun output(attempt: String): File {
            check(attempt.matches(Regex("[0-9a-f]{32}")))
            return File(directory, "auto-attempts/$attempt/renders/1/output.jpg").canonicalFile.also {
                check(it.path.startsWith(directory.path + File.separator) && it.isFile)
            }
        }
        fun replaceWithDifferentValidJpeg(attempt: String) {
            val file = output(attempt)
            val original = AndroidJpegInspector().inspect({ file.inputStream() }) { false }.output
            val bitmap = Bitmap.createBitmap(if (original.width == 2) 3 else 2,
                if (original.height == 3) 4 else 3, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.rgb(35, 95, 155))
                file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
            } finally { bitmap.recycle() }
            val replacement = AndroidJpegInspector().inspect({ file.inputStream() }) { false }.output
            assertNotEquals(original, replacement) // Valid codec bytes, deliberately different real dimensions.
        }
        fun replaceWithSameGeometryAndLengthJpeg(attempt: String) {
            val file = output(attempt)
            val original = AndroidJpegInspector().inspect({ file.inputStream() }) { false }
            val bytes = file.readBytes()
            check(bytes.size >= 4 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte())
            var offset = 2
            var changed = false
            // Alter one nonzero 8-bit DQT coefficient before SOS. This changes valid decoding metadata
            // without changing stream length, frame dimensions, orientation, color definition or markers.
            while (offset + 4 <= bytes.size) {
                check(bytes[offset] == 0xff.toByte())
                val marker = bytes[offset + 1].toInt() and 0xff
                if (marker == 0xda || marker == 0xd9) break
                val length = ((bytes[offset + 2].toInt() and 0xff) shl 8) or (bytes[offset + 3].toInt() and 0xff)
                check(length >= 2 && offset.toLong() + 2 + length <= bytes.size)
                if (marker == 0xdb && length >= 67 && (bytes[offset + 4].toInt() and 0xf0) == 0) {
                    val coefficient = offset + 5
                    check((bytes[coefficient].toInt() and 0xff) != 0)
                    bytes[coefficient] = if (bytes[coefficient] == 1.toByte()) 2.toByte() else 1.toByte()
                    changed = true
                    break
                }
                offset += 2 + length
            }
            check(changed) { "Generated JPEG requires an 8-bit quantization table for this fault injection" }
            file.writeBytes(bytes)
            val replacement = AndroidJpegInspector().inspect({ file.inputStream() }) { false }
            assertEquals(original.output, replacement.output)
            assertEquals(original.fingerprint.bytes, replacement.fingerprint.bytes)
            assertNotEquals(original.fingerprint.sha256, replacement.fingerprint.sha256)
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
                val fenced = legacy.preparePrivateTrial( source, recipe).get(60, TimeUnit.SECONDS)
                fenced.requestStop()
                fenced.status().get(60, TimeUnit.SECONDS)
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
            var slot = legacy.execution.tryStartObserved(allowed)
            while (slot == null && System.nanoTime() < deadline) {
                Thread.sleep(20)
                slot = legacy.execution.tryStartObserved(allowed)
            }
            check(slot != null) { "Keep synthetic artifacts until all native/FD owners acknowledge close" }
            check(legacy.execution.completeAfterResourcesClosed(slot))
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
