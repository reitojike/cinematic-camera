package dev.cinematic.p0.photoroll

import android.graphics.Bitmap
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.jpegrender.original.Original24Preflight
import dev.cinematic.p0.jpegrender.original.Original24PreviewSession
import dev.cinematic.p0.jpegrender.original.Original24Resources
import dev.cinematic.p0.raw.PhotoAdjustments
import dev.cinematic.p0.rawcapture.DngContentVerifier
import dev.cinematic.p0.rawcapture.OriginalGrant
import dev.cinematic.p0.rawcapture.SharedOriginalReference
import dev.cinematic.p0.testing.writeSyntheticTrialDng
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Existing session regression only: no production cache or pipeline changes. */
class PreviewCacheEquivalenceAndroidTest {
    @Test fun retainedInputAndBaseMatchColdSessionsForAllControls() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        check(context.packageName == "dev.cinematic.p0.original24probe")
        val root = File.createTempFile("cache-equivalence-", ".tmp", context.cacheDir)
        check(root.delete() && root.mkdir())
        val input = File(root, "synthetic.dng")
        input.outputStream().use { writeSyntheticTrialDng(it, 64, 48, 1) }
        val source = SharedOriginalReference("content://example.test/cache/synthetic",
            DngContentVerifier.identify(input.length(), { input.inputStream() }), OriginalGrant.USER_SELECTED)
        val geometry = ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY).use {
            Original24Preflight.inspect(it.fd, CancellationSignal())
        }
        fun session(name: String) = Original24PreviewSession(source, File(root, name),
            { Original24Resources.requireAvailable(context) }, geometry, { input.inputStream() })
        val retained = session("retained")
        var succeeded = false
        try {
            retained.render(.2).recycle()
            val settings = listOf(.8 to PhotoAdjustments.NONE,
                .8 to PhotoAdjustments(70.0, .7), .8 to PhotoAdjustments(70.0, 1.0),
                .5 to PhotoAdjustments.NONE, .2 to PhotoAdjustments(-60.0, -.7))
            for ((index, setting) in settings.withIndex()) {
                val (strength, adjustments) = setting
                val actual = retained.render(strength, adjustments)
                try {
                    val cold = session("cold-$index")
                    try {
                        val expected = cold.render(strength, adjustments)
                        try {
                            assertEquals(expected.width, actual.width)
                            assertEquals(expected.height, actual.height)
                            val expectedPixels = pixels(expected)
                            val actualPixels = pixels(actual)
                            var maxDifference = 0
                            for (i in expectedPixels.indices) for (shift in listOf(0, 8, 16, 24)) {
                                maxDifference = maxOf(maxDifference, kotlin.math.abs(
                                    ((expectedPixels[i] ushr shift) and 255) - ((actualPixels[i] ushr shift) and 255)))
                            }
                            assertEquals("Synthetic setting $index max 8bit difference", 0, maxDifference)
                            assertArrayEquals(expectedPixels, actualPixels)
                        } finally { expected.recycle() }
                    } finally { cold.close(); assertFalse(cold.hasUnreleasedResources) }
                } finally { actual.recycle() }
                assertTrue(retained.previewFieldBytes <= 435692)
                DngContentVerifier.verify(source.fingerprint, { input.inputStream() })
            }
            succeeded = true
        } finally {
            retained.close()
            assertFalse(retained.hasUnreleasedResources)
            assertEquals(0, retained.previewFieldBytes)
            if (succeeded) { assertTrue(input.delete()); assertTrue(root.delete()) }
        }
    }

    private fun pixels(bitmap: Bitmap) = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }
}
