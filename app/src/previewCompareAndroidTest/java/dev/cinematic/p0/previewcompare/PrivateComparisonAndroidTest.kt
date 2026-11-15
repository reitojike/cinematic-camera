package dev.cinematic.p0.previewcompare

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
        check(InstrumentationRegistry.getArguments().getString("privateCompareSynthetic") == "true")
    }
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun fixture(): File {
        check(BuildConfig.PRIVATE_PREVIEW_COMPARE)
        return File(app.cacheDir, "comparison-synthetic-source.dng").also {
            it.outputStream().use { out -> writeSyntheticTrialDng(out, 1024, 768, 1) }
        }
    }
    private fun assertNoPrivateCopies() {
        assertTrue(app.cacheDir.listFiles().orEmpty().none { it.name.startsWith("private-compare-") })
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
                assertEquals(1024, it.ahd.width)
                assertEquals(768, it.ahd.height)
                assertEquals(it.ahd.width, it.ppg.width)
                assertEquals(it.ahd.height, it.ppg.height)
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
                    if (phase.startsWith("PPG")) { source.appendBytes(byteArrayOf(1)); changed = true }
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
                    if (phase.startsWith("PPG")) {
                        val directory = app.cacheDir.listFiles()!!.single { it.name.startsWith("private-compare-") }
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
    @Test fun unavailableSourceLeavesNoPrivateCopy() {
        val source = File(app.cacheDir, "missing-synthetic-source.dng")
        assertFalse(source.exists())
        assertThrows(java.io.FileNotFoundException::class.java) {
            PrivatePhotoComparison(app).compare(Uri.fromFile(source), ComparisonStop(), 0.0) {}
        }
        assertNoPrivateCopies()
    }
}
