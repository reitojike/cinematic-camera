package dev.cinematic.p0.halfcompare

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.BuildConfig
import dev.cinematic.p0.testing.writeSyntheticTrialDng
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CancellationException

@RunWith(AndroidJUnit4::class)
class PrivateComparisonAndroidTest {
    @org.junit.Before fun emulatorOnly() {
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        check(InstrumentationRegistry.getArguments().getString("privateHalfSynthetic") == "true")
    }
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun fixture(): File {
        check(BuildConfig.PRIVATE_HALF_COMPARE)
        return File(app.cacheDir, "comparison-synthetic-source.dng").also {
            it.outputStream().use { out -> writeSyntheticTrialDng(out, 2048, 1536, 1) }
        }
    }
    private fun assertNoPrivateCopies() {
        assertTrue(app.cacheDir.listFiles().orEmpty().none { it.name.startsWith("private-half-compare-") })
    }
    @Test fun staleGenerationCannotPublish() {
        val gate = CompareGeneration()
        val first = gate.next()
        assertTrue(gate.accepts(first))
        val second = gate.next()
        assertFalse(gate.accepts(first))
        assertTrue(gate.accepts(second))
    }
    @Test fun cancellationDoesNotChangeOriginal() {
        val source = fixture()
        val original = source.readBytes()
        try {
            val stop = ComparisonStop().apply { cancel() }
            assertThrows(CancellationException::class.java) {
                PrivatePhotoComparison(app).compare(Uri.fromFile(source), stop, 0.0) {}
            }
            assertArrayEquals(original, source.readBytes())
            assertNoPrivateCopies()
        } finally { assertTrue(source.delete()) }
    }
    @Test fun syntheticPairIsBoundedAndOriginalUnchanged() {
        val source = fixture()
        val original = source.readBytes()
        try {
            PrivatePhotoComparison(app).compare(Uri.fromFile(source), ComparisonStop(), 0.0) {}.use {
                assertEquals(1024, it.full.width)
                assertEquals(768, it.full.height)
                assertEquals(it.full.width, it.half.width)
                assertEquals(it.full.height, it.half.height)
            }
            assertArrayEquals(original, source.readBytes())
            assertNoPrivateCopies()
        } finally { assertTrue(source.delete()) }
    }
    @Test fun changedOriginalRejectsResultAndCleansCopies() {
        val source = fixture()
        try {
            var changed = false
            assertThrows(dev.cinematic.p0.rawcapture.DngContentChanged::class.java) {
                PrivatePhotoComparison(app).compare(Uri.fromFile(source), ComparisonStop(), 0.0) { phase ->
                    if (phase.startsWith("HALF")) { source.appendBytes(byteArrayOf(1)); changed = true }
                }
            }
            assertTrue(changed)
            assertNoPrivateCopies()
        } finally { assertTrue(source.delete()) }
    }
    @Test fun cleanupFailureBlocksRetryWithoutDeletingUnknownFiles() {
        val source = fixture()
        val original = source.readBytes()
        var extra: File? = null
        try {
            val failure = assertThrows(IllegalStateException::class.java) {
                PrivatePhotoComparison(app).compare(Uri.fromFile(source), ComparisonStop(), 0.0) { phase ->
                    if (phase.startsWith("HALF")) {
                        val directory = app.cacheDir.listFiles()!!.single { it.name.startsWith("private-half-compare-") }
                        extra = File(directory, "test-owned-cleanup-obstacle").also { it.writeText("synthetic") }
                    }
                }
            }
            assertEquals("COMPARE_CLEANUP", failure.message)
            assertNotNull(extra)
            assertTrue(PrivatePhotoComparison.cleanupBlocked)
            assertTrue(extra!!.exists())
            val retry = assertThrows(IllegalStateException::class.java) {
                PrivatePhotoComparison(app).compare(Uri.fromFile(source), ComparisonStop(), 0.0) {}
            }
            assertEquals("COMPARE_CLEANUP", retry.message)
            assertTrue(extra!!.exists())
            assertArrayEquals(original, source.readBytes())
        } finally {
            // Test-only obstacle, not an unresolved raster owner. Clear only what this test created.
            extra?.let { assertTrue(it.delete()); assertTrue(it.parentFile!!.delete()) }
            PrivatePhotoComparison::class.java.getDeclaredField("cleanupBlocked").apply { isAccessible = true }.setBoolean(null, false)
            assertTrue(source.delete())
        }
        assertNoPrivateCopies()
    }
    @Test fun providerCloseFailureBlocksRetryAndRetainsPrivateCopy() {
        val source = fixture()
        val raw = source.inputStream()
        var closes = 0
        try {
            val comparator = PrivatePhotoComparison(app, openRead = {
                object : java.io.FilterInputStream(raw) {
                    override fun close() { closes++; throw java.io.IOException("synthetic close failure") }
                }
            })
            assertThrows(java.io.IOException::class.java) {
                comparator.compare(Uri.fromFile(source), ComparisonStop(), 0.0) {}
            }
            assertTrue(closes >= 2)
            assertTrue(PrivatePhotoComparison.cleanupBlocked)
            val work = app.cacheDir.listFiles()!!.single { it.name.startsWith("private-half-compare-") }
            assertTrue(File(work, "source.dng").exists())
            assertThrows(IllegalStateException::class.java) {
                comparator.compare(Uri.fromFile(source), ComparisonStop(), 0.0) {}
            }
        } finally {
            raw.close()
            app.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("private-half-compare-") }.forEach {
                assertTrue(File(it, "source.dng").delete()); assertTrue(it.delete())
            }
            PrivatePhotoComparison::class.java.getDeclaredField("cleanupBlocked").apply { isAccessible = true }.setBoolean(null, false)
            assertTrue(source.delete())
        }
    }
    @Test fun pipeSourceIsRejectedWithoutBlockingOnRead() {
        val pipe = android.os.ParcelFileDescriptor.createPipe()
        try {
            val error = assertThrows(IllegalStateException::class.java) {
                PrivatePhotoComparison(app, openDescriptor = { _, _ -> pipe[0] })
                    .compare(Uri.parse("content://example.test/synthetic-pipe"), ComparisonStop(), 0.0) {}
            }
            assertEquals("COMPARE_LOCAL_FILE_REQUIRED", error.message)
            assertNoPrivateCopies()
        } finally { pipe[0].close(); pipe[1].close() }
    }
    @Test fun cancellationIsForwardedToProviderOpen() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val cancelled = java.util.concurrent.CountDownLatch(1)
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val stop = ComparisonStop()
        try {
            val result = executor.submit<Boolean> {
                assertThrows(android.os.OperationCanceledException::class.java) {
                    PrivatePhotoComparison(app, openDescriptor = { _, signal ->
                        signal.setOnCancelListener { cancelled.countDown() }
                        entered.countDown()
                        check(cancelled.await(10, java.util.concurrent.TimeUnit.SECONDS))
                        signal.throwIfCanceled()
                        error("Cancelled provider must not return a descriptor")
                    }).compare(Uri.parse("content://example.test/synthetic-open"), stop, 0.0) {}
                }
                true
            }
            assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
            stop.cancel()
            assertTrue(result.get(10, java.util.concurrent.TimeUnit.SECONDS))
            assertNoPrivateCopies()
        } finally { stop.cancel(); executor.shutdownNow() }
    }
    @Test fun smallInputIsRejectedWithoutUpsamplingOrChangingOriginal() {
        val source = File(app.cacheDir, "comparison-small-source.dng")
        source.outputStream().use { out -> writeSyntheticTrialDng(out, 1024, 768, 1) }
        val original = source.readBytes()
        try {
            val error = assertThrows(IllegalStateException::class.java) {
                PrivatePhotoComparison(app).compare(Uri.fromFile(source), ComparisonStop(), 0.0) {}
            }
            assertEquals("COMPARE_SIZE", error.message)
            assertArrayEquals(original, source.readBytes())
            assertNoPrivateCopies()
        } finally { assertTrue(source.delete()) }
    }
    @Test fun unavailableSourceLeavesNoPrivateCopy() {
        val source = File(app.cacheDir, "missing-synthetic-source.dng")
        assertFalse(source.exists())
        assertThrows(java.io.FileNotFoundException::class.java) {
            PrivatePhotoComparison(app).compare(Uri.fromFile(source), ComparisonStop(), 0.0) {}
        }
        assertNoPrivateCopies()
    }
}
