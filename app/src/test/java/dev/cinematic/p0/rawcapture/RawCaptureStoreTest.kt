package dev.cinematic.p0.rawcapture

import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RawCaptureStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val pair = setOf(RawOutput.DNG, RawOutput.JPEG)
    private val dng = byteArrayOf(1, 4, 9, 16)
    private val jpeg = byteArrayOf(2, 5, 8)

    @Test fun jpegFirstAndDuplicateReceiptKeepBothOriginalByteSequences() {
        val fixture = fixture()
        fixture.store.create(pair)
        val raw = fixture.store.openOutput(0, RawOutput.DNG)
        val preview = fixture.store.openOutput(0, RawOutput.JPEG)
        raw.write(dng)
        preview.write(jpeg)
        preview.close()
        assertEquals(1L, fixture.store.received(0, preview).revision)
        assertThrows(CaptureJournalConflict::class.java) { fixture.store.received(0, preview) }
        assertEquals(1L, fixture.store.received(1, preview).revision)
        raw.close()
        val receipt = fixture.store.received(1, raw)
        assertEquals(CaptureReceiptPhase.FILES_RECEIVED, receipt.phase)
        assertEquals(mapOf(RawOutput.DNG to 4L, RawOutput.JPEG to 3L), receipt.receivedBytes)
        fixture.assertBytes(RawOutput.DNG, dng)
        fixture.assertBytes(RawOutput.JPEG, jpeg)
        assertEquals(2, fixture.directory.listFiles()!!.size) // Memory journal backend is separate.
    }

    @Test fun partialSuccessInterruptionAndLateReceiptPreserveSuccessfulFiles() {
        val fixture = fixture()
        fixture.store.create(pair)
        val raw = fixture.store.openOutput(0, RawOutput.DNG)
        val preview = fixture.store.openOutput(0, RawOutput.JPEG)
        raw.write(dng)
        raw.close()
        fixture.store.received(0, raw)
        fixture.store.interrupt(1)
        preview.write(jpeg) // Interrupt does not close or release another writer.
        preview.close()
        val receipt = fixture.store.received(2, preview)
        assertEquals(CaptureReceiptPhase.INTERRUPTED, receipt.phase)
        assertEquals(pair, receipt.receivedBytes.keys)
        assertThrows(IOException::class.java) { fixture.store.openOutput(3, RawOutput.JPEG) }
        fixture.assertBytes(RawOutput.DNG, dng)
        fixture.assertBytes(RawOutput.JPEG, jpeg)
    }

    @Test fun rawOnlyDoesNotCreateOrRequireJpeg() {
        val fixture = fixture()
        fixture.store.create(setOf(RawOutput.DNG))
        assertThrows(IllegalArgumentException::class.java) { fixture.store.openOutput(0, RawOutput.JPEG) }
        val raw = fixture.store.openOutput(0, RawOutput.DNG)
        raw.write(dng)
        raw.close()
        assertEquals(CaptureReceiptPhase.FILES_RECEIVED, fixture.store.received(0, raw).phase)
        assertFalse(fixture.file(RawOutput.JPEG).exists())
    }

    @Test fun openWriterAndEmptyOrForeignOutputCannotBecomeReceipts() {
        val fixture = fixture()
        fixture.store.create(pair)
        val raw = fixture.store.openOutput(0, RawOutput.DNG)
        raw.write(dng)
        assertThrows(IOException::class.java) { fixture.store.received(0, raw) }
        assertThrows(IOException::class.java) { fixture.store.recoverAfterProcessDeath() }
        val other = fixture("other")
        other.store.create(pair)
        raw.close()
        assertThrows(IllegalArgumentException::class.java) { other.store.received(0, raw) }
        val empty = fixture.store.openOutput(0, RawOutput.JPEG)
        empty.close()
        assertThrows(IOException::class.java) { fixture.store.received(0, empty) }
        assertTrue(fixture.store.readReceipt()!!.receivedBytes.isEmpty())
        fixture.assertBytes(RawOutput.DNG, dng)
        assertTrue(fixture.file(RawOutput.JPEG).exists())
    }

    @Test fun writeFlushAndCloseFailuresNeverBecomeReceiptsEvenAfterRepeatedClose() {
        for (fault in listOf("write", "flush", "close")) {
            val fixture = fixture(fault, fault)
            fixture.store.create(pair)
            val raw = fixture.store.openOutput(0, RawOutput.DNG)
            when (fault) {
                "write" -> assertThrows(IOException::class.java) { raw.write(dng) }
                "flush" -> {
                    raw.write(dng)
                    assertThrows(IOException::class.java) { raw.flush() }
                }
                "close" -> {
                    raw.write(dng)
                    assertThrows(IOException::class.java) { raw.close() }
                }
            }
            raw.close()
            raw.close()
            assertThrows(IOException::class.java) { fixture.store.received(0, raw) }
            assertThrows(IOException::class.java) { raw.write(7) }
            assertTrue(fixture.store.readReceipt()!!.receivedBytes.isEmpty())
            fixture.assertBytes(RawOutput.DNG, if (fault == "write") dng.copyOf(2) else dng)
        }
    }

    @Test fun failedRawPreservesPartialBytesAndSuccessfulJpegIndependently() {
        val fixture = fixture("partial-write", "write")
        fixture.store.create(pair)
        val raw = fixture.store.openOutput(0, RawOutput.DNG)
        assertThrows(IOException::class.java) { raw.write(dng) }
        raw.close()
        val preview = fixture.store.openOutput(0, RawOutput.JPEG)
        preview.write(jpeg)
        preview.close()
        fixture.store.received(0, preview)
        fixture.store.interrupt(1)
        fixture.assertBytes(RawOutput.DNG, dng.copyOf(2))
        fixture.assertBytes(RawOutput.JPEG, jpeg)
        assertEquals(mapOf(RawOutput.JPEG to 3L), fixture.store.readReceipt()!!.receivedBytes)
    }

    @Test fun openFailureKeepsExistingSuccessfulOtherFormat() {
        val fixture = fixture("open-failure", "open")
        fixture.store.create(pair)
        val preview = fixture.store.openOutput(0, RawOutput.JPEG)
        preview.write(jpeg)
        preview.close()
        fixture.store.received(0, preview)
        assertThrows(IOException::class.java) { fixture.store.openOutput(1, RawOutput.DNG) }
        fixture.store.interrupt(1)
        fixture.assertBytes(RawOutput.JPEG, jpeg)
        assertEquals(setOf(RawOutput.JPEG), fixture.store.readReceipt()!!.receivedBytes.keys)
    }

    @Test fun receiptCommitFailureKeepsClosedFileAndCanRetryWithoutReopeningIt() {
        val fixture = fixture()
        fixture.store.create(pair)
        val raw = fixture.store.openOutput(0, RawOutput.DNG)
        raw.write(dng)
        raw.close()
        fixture.storage.fault = "before"
        assertThrows(IOException::class.java) { fixture.store.received(0, raw) }
        fixture.assertBytes(RawOutput.DNG, dng)
        assertTrue(fixture.store.readReceipt()!!.receivedBytes.isEmpty())
        fixture.storage.fault = "none"
        assertEquals(1L, fixture.store.received(0, raw).revision)
        assertThrows(IOException::class.java) { fixture.store.openOutput(1, RawOutput.DNG) }
        fixture.assertBytes(RawOutput.DNG, dng)
    }

    @Test fun lostCommitAcknowledgementIsRecoveredByReadingNotRecreatingTheFile() {
        val fixture = fixture()
        fixture.store.create(pair)
        val raw = fixture.store.openOutput(0, RawOutput.DNG)
        raw.write(dng)
        raw.close()
        fixture.storage.fault = "after"
        assertThrows(IOException::class.java) { fixture.store.received(0, raw) }
        fixture.storage.fault = "none"
        assertEquals(1L, fixture.store.readReceipt()!!.revision)
        assertThrows(CaptureJournalConflict::class.java) { fixture.store.received(0, raw) }
        assertEquals(1L, fixture.store.received(1, raw).revision)
        fixture.assertBytes(RawOutput.DNG, dng)
    }

    @Test fun restartPreservesUnrecordedClosedFileWithoutInferringCompletion() {
        val fixture = fixture()
        fixture.store.create(pair)
        val raw = fixture.store.openOutput(0, RawOutput.DNG)
        val preview = fixture.store.openOutput(0, RawOutput.JPEG)
        raw.write(dng)
        raw.close()
        preview.write(jpeg)
        preview.close()
        fixture.store.received(0, preview)
        val restarted = RawCaptureStore(fixture.root, fixture.id, fixture.storage)
        assertThrows(IOException::class.java) { restarted.openOutput(1, RawOutput.DNG) }
        val recovered = restarted.recoverAfterProcessDeath()!!
        assertEquals(CaptureReceiptPhase.INTERRUPTED, recovered.phase)
        assertEquals(mapOf(RawOutput.JPEG to 3L), recovered.receivedBytes)
        assertThrows(IOException::class.java) { restarted.openOutput(2, RawOutput.DNG) }
        fixture.assertBytes(RawOutput.DNG, dng)
        fixture.assertBytes(RawOutput.JPEG, jpeg)
    }

    @Test fun completeReceiptSurvivesRestartButMissingOrChangedRecordedFileStopsRecovery() {
        for (change in listOf("none", "missing", "shorter")) {
            val fixture = fixture(change)
            fixture.store.create(setOf(RawOutput.DNG))
            val raw = fixture.store.openOutput(0, RawOutput.DNG)
            raw.write(dng)
            raw.close()
            fixture.store.received(0, raw)
            val originalJournal = fixture.storage.contents!!.copyOf()
            when (change) {
                "missing" -> assertTrue(fixture.file(RawOutput.DNG).delete())
                "shorter" -> fixture.file(RawOutput.DNG).writeBytes(byteArrayOf(1))
            }
            val restarted = RawCaptureStore(fixture.root, fixture.id, fixture.storage)
            if (change == "none") {
                assertEquals(CaptureReceiptPhase.FILES_RECEIVED, restarted.recoverAfterProcessDeath()!!.phase)
            } else {
                assertThrows(IOException::class.java) { restarted.recoverAfterProcessDeath() }
                assertArrayEquals(originalJournal, fixture.storage.contents)
                assertFalse(fixture.file(RawOutput.JPEG).exists())
            }
        }
    }

    @Test fun orphanDirectoryExistingOutputAndOldCaptureAreNeverReusedOrModified() {
        val fixture = fixture()
        assertTrue(fixture.directory.mkdir()) // Death after directory creation, before journal commit.
        assertThrows(IOException::class.java) { fixture.store.create(pair) }
        assertNull(fixture.storage.contents)
        assertThrows(IOException::class.java) { fixture.store.recoverAfterProcessDeath() }
        val next = fixture("next")
        next.store.create(pair)
        next.file(RawOutput.DNG).writeBytes(dng) // Unconfirmed file from a previous writer.
        assertThrows(IOException::class.java) { next.store.openOutput(0, RawOutput.DNG) }
        next.assertBytes(RawOutput.DNG, dng)
        assertTrue(fixture.directory.exists())
        assertEquals(0, fixture.directory.listFiles()!!.size)
    }

    @Test fun journalCreationFailureRetainsDirectoryAndDoesNotReuseCaptureId() {
        val fixture = fixture()
        fixture.storage.fault = "before"
        assertThrows(IOException::class.java) { fixture.store.create(pair) }
        assertTrue(fixture.directory.exists())
        fixture.storage.fault = "none"
        assertThrows(IOException::class.java) { fixture.store.create(pair) }
        assertThrows(IOException::class.java) { fixture.store.readReceipt() }
        assertNull(fixture.storage.contents)
    }

    @Test fun lostCreationAcknowledgementDoesNotPermitNewWritersEvenWhenReceiptExists() {
        val fixture = fixture()
        fixture.storage.fault = "after"
        assertThrows(IOException::class.java) { fixture.store.create(pair) }
        fixture.storage.fault = "none"
        assertEquals(CaptureReceiptPhase.CAPTURING, fixture.store.readReceipt()!!.phase)
        assertThrows(IOException::class.java) { fixture.store.openOutput(0, RawOutput.DNG) }
        assertEquals(CaptureReceiptPhase.INTERRUPTED, fixture.store.recoverAfterProcessDeath()!!.phase)
        assertEquals(0, fixture.directory.listFiles()!!.size)
    }

    @Test fun invalidIdFormatsStaleRevisionAndCorruptJournalDoNotCreateMedia() {
        val fixture = fixture()
        assertThrows(IllegalArgumentException::class.java) { RawCaptureStore(fixture.root, "../outside", fixture.storage) }
        assertThrows(IllegalArgumentException::class.java) { fixture.store.create(setOf(RawOutput.JPEG)) }
        assertFalse(fixture.directory.exists())
        fixture.store.create(pair)
        assertThrows(CaptureJournalConflict::class.java) { fixture.store.openOutput(4, RawOutput.DNG) }
        fixture.storage.contents = byteArrayOf(0, 1)
        assertThrows(IOException::class.java) { fixture.store.openOutput(0, RawOutput.DNG) }
        assertEquals(0, fixture.directory.listFiles()!!.size)
    }

    @Test fun mediaSymlinkCannotRedirectWritesOrRecordedFileChecks() {
        val fixture = fixture()
        fixture.store.create(pair)
        val outside = temporary.newFile("unrelated-data").also { it.writeBytes(jpeg) }
        try { Files.createSymbolicLink(fixture.file(RawOutput.DNG).toPath(), outside.toPath()) }
        catch (e: IOException) {
            if (System.getProperty("os.name").orEmpty().startsWith("Windows")) assumeNoException("Windows symlink permission unavailable", e)
            else throw e
        }
        assertThrows(IOException::class.java) { fixture.store.openOutput(0, RawOutput.DNG) }
        assertThrows(IOException::class.java) { fixture.store.readReceipt() }
        assertArrayEquals(jpeg, outside.readBytes())
    }

    @Test fun originalFingerprintPersistsThroughLateJpegAndRecreation() {
        val fixture = fixture()
        fixture.store.create(pair)
        val raw = fixture.store.openOutput(0, RawOutput.DNG)
        raw.write(dng)
        raw.close()
        fixture.store.received(0, raw)
        val verified = fixture.store.verifyOriginal(1)
        val expected = OriginalFingerprint(4, sha256(dng))
        assertEquals(expected, verified.originalFingerprint)
        val preview = fixture.store.openOutput(2, RawOutput.JPEG)
        preview.write(jpeg)
        preview.close()
        assertEquals(expected, fixture.store.received(2, preview).originalFingerprint)
        val restarted = RawCaptureStore(fixture.root, fixture.id, fixture.storage)
        assertEquals(expected, restarted.recoverAfterProcessDeath()!!.originalFingerprint)
        fixture.assertBytes(RawOutput.DNG, dng)
        fixture.assertBytes(RawOutput.JPEG, jpeg)
    }

    @Test fun verificationRequiresRecordedDngAndStopsBeforeCommittingOnCancelOrStaleRevision() {
        val fixture = fixture()
        fixture.store.create(pair)
        assertThrows(IOException::class.java) { fixture.store.verifyOriginal(0) }
        val raw = fixture.store.openOutput(0, RawOutput.DNG)
        raw.write(dng)
        raw.close()
        fixture.store.received(0, raw)
        val prior = fixture.storage.contents!!.copyOf()
        assertThrows(CaptureJournalConflict::class.java) { fixture.store.verifyOriginal(0) }
        assertThrows(DngVerificationStopped::class.java) { fixture.store.verifyOriginal(1) { true } }
        assertArrayEquals(prior, fixture.storage.contents)
        fixture.assertBytes(RawOutput.DNG, dng)
    }

    @Test fun fingerprintCommitFailuresKeepFilesAndResolveByReadingActualJournal() {
        for (fault in listOf("before", "after")) {
            val fixture = fixture(fault)
            fixture.store.create(setOf(RawOutput.DNG))
            val raw = fixture.store.openOutput(0, RawOutput.DNG)
            raw.write(dng)
            raw.close()
            fixture.store.received(0, raw)
            fixture.storage.fault = fault
            assertThrows(IOException::class.java) { fixture.store.verifyOriginal(1) }
            fixture.storage.fault = "none"
            val observed = fixture.store.readReceipt()!!
            if (fault == "before") assertNull(observed.originalFingerprint)
            else assertEquals(OriginalFingerprint(4, sha256(dng)), observed.originalFingerprint)
            val verified = fixture.store.verifyOriginal(observed.revision)
            assertEquals(2L, verified.revision)
            assertEquals(OriginalFingerprint(4, sha256(dng)), verified.originalFingerprint)
            fixture.assertBytes(RawOutput.DNG, dng)
        }
    }

    @Test fun sameSizeChangeCannotReplacePreviouslyVerifiedOriginalFingerprint() {
        val fixture = fixture()
        fixture.store.create(setOf(RawOutput.DNG))
        val raw = fixture.store.openOutput(0, RawOutput.DNG)
        raw.write(dng)
        raw.close()
        fixture.store.received(0, raw)
        fixture.store.verifyOriginal(1)
        val prior = fixture.storage.contents!!.copyOf()
        val changed = dng.copyOf().also { it[0] = 99 }
        fixture.file(RawOutput.DNG).writeBytes(changed)
        assertThrows(DngContentChanged::class.java) { fixture.store.verifyOriginal(2) }
        assertArrayEquals(prior, fixture.storage.contents)
        fixture.assertBytes(RawOutput.DNG, changed)
    }

    @Test fun hashingDoesNotHoldStoreLockAndStaleHashResultCannotCommit() {
        val fixture = fixture()
        fixture.store.create(pair)
        val raw = fixture.store.openOutput(0, RawOutput.DNG)
        val preview = fixture.store.openOutput(0, RawOutput.JPEG)
        raw.write(dng)
        raw.close()
        preview.write(jpeg)
        preview.close()
        fixture.store.received(0, raw)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = AtomicBoolean(true)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val hashing = executor.submit(Callable {
                fixture.store.verifyOriginal(1) {
                    if (first.compareAndSet(true, false)) {
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                    }
                    false
                }
            })
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val callback = executor.submit(Callable { fixture.store.received(1, preview) })
            assertEquals(2L, callback.get(5, TimeUnit.SECONDS).revision)
            release.countDown()
            val failure = assertThrows(ExecutionException::class.java) { hashing.get(5, TimeUnit.SECONDS) }
            assertTrue(failure.cause is CaptureJournalConflict)
            assertNull(fixture.store.readReceipt()!!.originalFingerprint)
        } finally { release.countDown(); executor.shutdownNow() }
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun fixture(id: String = "capture-1", fault: String = "none"): Fixture {
        val root = temporary.newFolder("root-$id")
        val storage = MemoryStorage()
        val open: (File) -> OutputStream = { file ->
            if (fault == "open" && file.name == RawOutput.DNG.fileName) throw IOException("Injected open failure")
            val stream = Files.newOutputStream(file.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
            object : FilterOutputStream(stream) {
                private var closeFailedOnce = false
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    if (fault == "write" && file.name == RawOutput.DNG.fileName) {
                        out.write(bytes, offset, minOf(length, 2))
                        throw IOException("Injected partial write failure")
                    }
                    out.write(bytes, offset, length)
                }
                override fun flush() {
                    out.flush()
                    if (fault == "flush") throw IOException("Injected flush failure")
                }
                override fun close() {
                    if (fault == "close" && !closeFailedOnce) {
                        closeFailedOnce = true
                        throw IOException("Injected failure before stream close")
                    }
                    out.close()
                }
            }
        }
        val store = if (fault == "none") RawCaptureStore(root, id, storage) else RawCaptureStore(root, id, storage, open)
        return Fixture(root, id, storage, store)
    }

    private class Fixture(val root: File, val id: String, val storage: MemoryStorage, val store: RawCaptureStore) {
        val directory get() = File(root, id)
        fun file(output: RawOutput) = File(directory, output.fileName)
        fun assertBytes(output: RawOutput, expected: ByteArray) = assertArrayEquals(expected, file(output).readBytes())
    }

    private class MemoryStorage : AtomicJournalStorage {
        var contents: ByteArray? = null
        var fault = "none"
        override fun read(maxBytes: Int) = contents?.copyOf()
        override fun replace(contents: ByteArray) {
            if (fault == "before") throw IOException("Injected commit failure")
            this.contents = contents.copyOf()
            if (fault == "after") throw IOException("Injected lost acknowledgement")
        }
    }
}
