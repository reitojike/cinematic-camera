package dev.cinematic.p0.jpegrender.original

import android.content.Context
import dev.cinematic.p0.gpu.DevelopEngine
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.rawcapture.*
import dev.cinematic.p0.raw.original.OriginalStage
import dev.cinematic.p0.raw.original.OriginalStageTrace
import dev.cinematic.p0.testing.writeSyntheticTrialDng
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Ordinary GPU native-input/JPEG producer failures. Private 64x48 fixture; no shared publication. */
@RunWith(AndroidJUnit4::class)
class GpuSaveProducerSafetyAndroidTest {
    @Test fun gpuBandFailureAndCancellationDrainBeforeFreshAttemptCanSucceed() {
        for (cancel in listOf(false, true)) withFixture { f ->
            val injected = if (cancel) java.util.concurrent.CancellationException("Synthetic GPU band cancellation")
                else dev.cinematic.p0.gpu.GpuContextLostException("Synthetic band failure, not a driver loss")
            var reached = 0
            val witness = GpuExportBoundaryWitness {
                reached++
                throw injected
            }
            val failure = Original24GpuOutput.observeForDiagnostics(witness).use { f.requireFailedAttempt() }
            assertEquals("No later GPU event may run after the fault", 1, reached)
            assertTrue("Preserve the injected primary in the failure chain",
                generateSequence(failure as Throwable?) { it.cause }.any { it === injected })
            assertEquals("Fault occurs before opening a private JPEG", 0, f.outputOpens)
            assertFalse(f.output.exists())
            assertFalse(f.attempt.hasUnreleasedResources)
            assertFalse(f.scratch.exists())
            witness.assertReleased()
            f.requireRerenderRejected()
            f.verifyOriginal()
            f.requireFreshAttemptSucceeds()
        }
    }
    @Test fun unchangedMetadataCannotBypassColdSaveFingerprintBeforeOrAfterGpuJpeg() {
        for (changeAfterOutput in listOf(false, true)) withFixture { f ->
            var restore: (() -> Unit)? = null
            if (changeAfterOutput) f.afterWrite = {
                if (restore == null) restore = f.changePrivateOriginalWithoutChangingMetadata()
            } else restore = f.changePrivateOriginalWithoutChangingMetadata()
            try {
                var proof: Original24TrialJpeg? = null
                assertThrows(DngContentChanged::class.java) { proof = f.attempt.render { false } }
                assertNull("Changed source never becomes a verified JPEG result", proof)
                assertEquals(if (changeAfterOutput) 1 else 0, f.outputOpens)
                if (changeAfterOutput) {
                    assertTrue(f.writeCalls > 0)
                    assertEquals(1, f.readbackCalls)
                    assertTrue(f.outputCloseAcknowledged)
                }
                assertFalse(f.attempt.hasUnreleasedResources)
                f.requireRerenderRejected()
            } finally { restore?.invoke() }
            f.verifyOriginal()
        }
    }

    @Test fun partialOutputWriteNeverReturnsTrialResultOrAllowsAnotherRender() = withFixture { f ->
        f.failWrite = true
        f.requireFailedAttempt()
        assertTrue("Fault must be reached after native decode and row rendering", f.writeCalls > 0)
        assertTrue(f.output.length() > 0)
        assertEquals(0, f.readbackCalls)
        assertTrue(f.outputCloseAcknowledged)
        assertFalse(f.attempt.hasUnreleasedResources)
        f.requireRerenderRejected()
        f.verifyOriginal()
    }

    @Test fun failedOutputCloseRetainsOwnerUntilThatSameCloseIsAcknowledged() = withFixture { f ->
        f.failClose = true
        f.requireFailedAttempt()
        assertTrue(f.writeCalls > 0)
        assertEquals(0, f.readbackCalls)
        assertTrue(f.closeCalls > 0)
        assertFalse(f.outputCloseAcknowledged)
        assertTrue(f.attempt.hasUnreleasedResources)
        f.requireRerenderRejected()
        assertThrows(IOException::class.java) { f.attempt.close() }
        assertFalse(f.outputCloseAcknowledged)
        assertTrue(f.attempt.hasUnreleasedResources)
        f.failClose = false
        f.attempt.close()
        assertTrue(f.outputCloseAcknowledged)
        assertFalse(f.attempt.hasUnreleasedResources)
        f.requireRerenderRejected()
        f.verifyOriginal()
    }

    @Test fun sameLengthDecodableReadbackChangeNeverBecomesTrialResult() = withFixture { f ->
        f.alterReadback = true
        f.requireFailedAttempt()
        assertEquals(1, f.readbackCalls)
        assertTrue("Changed readback must remain a decodable JPEG of the same length", f.alteredReadbackInspected)
        assertTrue(f.outputCloseAcknowledged)
        assertFalse(f.attempt.hasUnreleasedResources)
        f.requireRerenderRejected()
        f.verifyOriginal()
    }

    @Test fun stopAfterOutputStartsCannotReturnTrialResultOrRestartTheAttempt() = withFixture { f ->
        f.afterWrite = { f.attempt.requestStop() }
        f.requireFailedAttempt()
        assertTrue("Cancellation must occur after actual output bytes", f.writeCalls > 0)
        assertTrue(f.output.length() > 0)
        assertEquals(0, f.readbackCalls)
        assertTrue(f.outputCloseAcknowledged)
        assertFalse(f.attempt.hasUnreleasedResources)
        f.requireRerenderRejected()
        f.verifyOriginal()
    }

    @Test fun gpuPackingReportFailureCannotPublishOrRestartTheAttempt() {
        val diagnosticFailure = IOException("Synthetic GPU row packing report failure")
        val trace = OriginalStageTrace { stage, _, _ -> if (stage == OriginalStage.BITMAP_PACK) throw diagnosticFailure }
        withFixture(trace) { f ->
            assertSame(diagnosticFailure, f.requireFailedAttempt())
            assertEquals(0, f.outputOpens)
            assertFalse(f.attempt.hasUnreleasedResources)
            f.requireRerenderRejected()
        }
    }

    @Test fun failedReportingCannotHideThePrivateWriteFailure() {
        var unobserved: Throwable? = null
        withFixture { f ->
            f.failWrite = true
            unobserved = f.requireFailedAttempt()
            assertNotNull(f.writeFailure)
        }
        val diagnosticFailure = IOException("Synthetic codec report failure")
        val trace = OriginalStageTrace { stage, _, complete ->
            if (stage == OriginalStage.JPEG_ENCODE && !complete) throw diagnosticFailure
        }
        withFixture(trace) { f ->
            f.failWrite = true
            val actual = f.requireFailedAttempt()
            // Bitmap.compress may convert the stream exception into false; preserve the producer's
            // normal codec failure rather than assuming the platform propagates that stream object.
            assertNotNull(f.writeFailure)
            assertEquals(requireNotNull(unobserved).javaClass, actual.javaClass)
            assertEquals(requireNotNull(unobserved).message, actual.message)
            assertTrue(actual.suppressed.contains(diagnosticFailure))
            assertTrue(f.outputCloseAcknowledged)
            assertFalse(f.attempt.hasUnreleasedResources)
        }
    }

    @Test fun unknownScratchFileKeepsGpuAttemptOwnedUntilExplicitRemovalAndClose() {
        lateinit var owned: Fixture
        var reached = false
        val trace = object : OriginalStageTrace {
            override fun started(stage: OriginalStage) {
                if (stage == OriginalStage.BITMAP_PACK && !reached) {
                    reached = true
                    File(owned.scratch, "owned-by-test.tmp").writeText("keep")
                    owned.attempt.requestStop()
                }
            }
            override fun finished(stage: OriginalStage, elapsedNanos: Long, completed: Boolean) = Unit
        }
        withFixture(trace) { f ->
            owned = f
            val blocker = File(f.scratch, "owned-by-test.tmp")
            var primary: Throwable? = null
            try {
                assertThrows(java.util.concurrent.CancellationException::class.java) { f.attempt.render { false } }
                assertTrue("GPU-selected attempt must reach the pack entry before the injected stop; drawing has not begun", reached)
                assertTrue(f.attempt.hasUnreleasedResources)
                assertEquals("keep", blocker.readText())
                assertThrows(IllegalStateException::class.java) { f.attempt.close() }
                assertTrue(f.attempt.hasUnreleasedResources)
                assertTrue(blocker.delete()) // Only the test owns this synthetic obstruction.
                f.attempt.close()
                assertFalse(f.attempt.hasUnreleasedResources)
                assertFalse(f.scratch.exists())
                f.verifyOriginal()
            } catch (error: Throwable) { primary = error; throw error }
            finally {
                preserveSafetyCleanup(primary,
                    { if (blocker.exists()) { assertEquals("keep", blocker.readText()); check(blocker.delete()) } },
                    { f.attempt.close() })
            }
        }
    }

    private fun withFixture(trace: OriginalStageTrace? = null, action: (Fixture) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        requireGpuSaveSafetyFixture()
        val f = Fixture(context, trace)
        var failure: Throwable? = null
        try { action(f) } catch (error: Throwable) { failure = error; throw error }
        finally {
            preserveSafetyCleanup(failure,
                { f.failClose = false; f.attempt.close() },
                { assertFalse(f.attempt.hasUnreleasedResources) },
                { f.verifyOriginal() })
        }
    }

    private class Fixture(private val context: Context, trace: OriginalStageTrace?) {
        private val root = File(context.noBackupFilesDir, "original24-failure-${UUID.randomUUID()}")
            .also { check(it.mkdir()) }
        private val original = File(root, "synthetic.dng")
        val output = File(root, "output.jpg")
        private val originalFingerprint = original.outputStream().use { writeSyntheticTrialDng(it, 64, 48, 1) }
            .let { DngContentVerifier.identify(it, { original.inputStream() }) }
        private val source = SharedOriginalReference("content://example.test/original24/failure", originalFingerprint,
            OriginalGrant.USER_SELECTED)
        var failWrite = false
        var failClose = false
        var alterReadback = false
        var afterWrite: () -> Unit = {}
        var outputOpens = 0
        var writeCalls = 0
        var writeFailure: IOException? = null
        var closeCalls = 0
        var readbackCalls = 0
        var outputCloseAcknowledged = false
        var alteredReadbackInspected = false
        private val io = object : AutoRenderIo {
            override fun openOriginal(): InputStream = original.inputStream()
            override fun openOriginalDescriptor(): AutoReadOnlyDescriptor =
                error("Native input must use its verified immutable snapshot")
            override fun openOutput(): OutputStream {
                outputOpens++
                val delegate = Files.newOutputStream(output.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                return object : OutputStream() {
                    override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
                    override fun write(bytes: ByteArray, offset: Int, count: Int) {
                        if (count == 0) return
                        writeCalls++
                        if (failWrite) {
                            delegate.write(bytes, offset, 1)
                            throw IOException("Injected private JPEG write failure").also { writeFailure = it }
                        }
                        delegate.write(bytes, offset, count)
                        afterWrite()
                    }
                    override fun flush() = delegate.flush()
                    override fun close() {
                        closeCalls++
                        if (failClose) throw IOException("Injected private JPEG close failure")
                        delegate.close()
                        outputCloseAcknowledged = true
                    }
                }
            }
            override fun openOutputRead(): InputStream {
                readbackCalls++
                check(outputCloseAcknowledged)
                if (!alterReadback) return output.inputStream()
                val encoded = output.readBytes()
                val changed = alterQuantization(encoded)
                assertEquals(encoded.size, changed.size)
                val inspected = AndroidJpegInspector().inspect({ ByteArrayInputStream(changed) }, encoded.size.toLong()) { false }
                val actual = DngContentVerifier.identify(encoded.size.toLong(), { ByteArrayInputStream(encoded) })
                assertEquals(64, inspected.output.width)
                assertEquals(48, inspected.output.height)
                assertNotEquals(actual, inspected.fingerprint)
                alteredReadbackInspected = true
                return ByteArrayInputStream(changed)
            }
        }
        val scratch = File(root, "scratch")
        val attempt = Original24Attempt(context, Original24Request("TRIAL24_synthetic_failure", 1, source), io,
            scratch, trace, developEngine = DevelopEngine.GPU)

        fun requireFreshAttemptSucceeds() {
            val freshScratch = File(root, "fresh-scratch")
            val fresh = Original24Attempt(context, Original24Request("TRIAL24_synthetic_failure", 2, source),
                io, freshScratch, developEngine = DevelopEngine.GPU)
            fresh.use {
                val result = it.render { false }
                assertEquals(source, result.request.source)
                assertEquals(2L, result.request.generation)
                assertEquals(64, result.output.width)
                assertEquals(48, result.output.height)
                assertEquals(result.fingerprint,
                    AndroidJpegInspector().inspect({ output.inputStream() }, output.length()) { false }.fingerprint)
            }
            assertFalse(fresh.hasUnreleasedResources)
            assertFalse(freshScratch.exists())
            assertTrue(outputCloseAcknowledged)
            verifyOriginal()
        }
        fun changePrivateOriginalWithoutChangingMetadata(): () -> Unit {
            val bytes = original.readBytes()
            val modified = original.lastModified()
            original.writeBytes(bytes.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
            check(original.setLastModified(modified))
            assertEquals(bytes.size.toLong(), original.length())
            assertEquals(modified, original.lastModified())
            return { original.writeBytes(bytes); check(original.setLastModified(modified)) }
        }

        fun requireFailedAttempt(): Throwable {
            var result: Original24TrialJpeg? = null
            val failure = assertThrows(Exception::class.java) { result = attempt.render { false } }
            assertNull("A failed producer cannot provide evidence to construct a publication proof", result)
            verifyOriginal()
            return failure
        }

        fun requireRerenderRejected() {
            val opens = outputOpens
            val writes = writeCalls
            val reads = readbackCalls
            assertThrows(IllegalStateException::class.java) { attempt.render { false } }
            assertEquals(opens, outputOpens)
            assertEquals(writes, writeCalls)
            assertEquals(reads, readbackCalls)
        }

        fun verifyOriginal() { assertEquals(originalFingerprint, DngContentVerifier.verify(originalFingerprint, { original.inputStream() })) }
    }

    companion object {
        /** Alter a legal 8-bit quantization coefficient, keeping markers, geometry and byte length. */
        private fun alterQuantization(encoded: ByteArray): ByteArray {
            val changed = encoded.clone()
            var offset = 2 // SOI
            while (offset + 4 < changed.size) {
                check(changed[offset] == 0xff.toByte())
                val marker = changed[offset + 1].toInt() and 255
                check(marker != 0xda && marker != 0xd9) { "JPEG quantization table missing" }
                val length = ((changed[offset + 2].toInt() and 255) shl 8) or (changed[offset + 3].toInt() and 255)
                check(length >= 2 && offset + 2 + length <= changed.size)
                if (marker == 0xdb && (changed[offset + 4].toInt() and 0xf0) == 0) {
                    val coefficient = changed[offset + 5].toInt() and 255
                    check(coefficient in 1..255)
                    changed[offset + 5] = (if (coefficient == 255) 254 else coefficient + 1).toByte()
                    return changed
                }
                offset += 2 + length
            }
            error("JPEG quantization table missing")
        }
    }
}
