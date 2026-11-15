package dev.cinematic.p0.jpegrender.original

import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.gpu.GpuPreviewInputSize
import dev.cinematic.p0.rawcapture.DngContentVerifier
import dev.cinematic.p0.rawcapture.OriginalGrant
import dev.cinematic.p0.rawcapture.SharedOriginalReference
import dev.cinematic.p0.testing.writeSyntheticTrialDng
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Private synthetic inputs only. No EGL, shared publication, camera, physical device or product adoption.
 * This is a geometry/ownership test, not a speed or visual equivalence measurement. */
@RunWith(AndroidJUnit4::class)
class HalfPreviewBoundaryAndroidTest {
    @Test fun halfCandidateDecodesTwentyFourMegapixelsAndReleasesTransientOwners() {
        check(InstrumentationRegistry.getArguments().getString("halfPreview24Mp") == "true") {
            "Explicit halfPreview24Mp=true required for the 24MP native allocation"
        }
        // 6000x4000 -> 3000x2000; scale 1024/3000, floor(2000*1024/3000)=682.
        verifyCase(6000, 4000, 1, 1024, 682)
    }

    @Test fun halfCandidateFitsBelowAtAndAboveTheLongEdgeBoundary() {
        // Deliberately independent expected sizes; do not calculate the oracle with fit().
        // Half widths 1023/1024 remain unchanged; 1025 scales the 512 height down to 511.
        verifyCase(2046, 1024, 1, 1023, 512)
        verifyCase(2048, 1024, 1, 1024, 512)
        verifyCase(2050, 1024, 1, 1024, 511)
    }

    @Test fun oddExtentsAndAllOrientationsPreserveCeilHalfGeometry() {
        // ceil(65/2)=33, ceil(49/2)=25. EXIF 5..8 exchange axes, including mirrors.
        for (orientation in 1..8) {
            verifyCase(65, 49, orientation, if (orientation >= 5) 25 else 33,
                if (orientation >= 5) 33 else 25)
        }
        // 2051x1537 -> 1026x769 -> floor each axis at scale 1024/1026.
        verifyCase(2051, 1537, 1, 1024, 767)
        verifyCase(2051, 1537, 6, 767, 1024)
        // Square input is limited by 786432 pixels, not by the 1024 long edge.
        // floor(sqrt(786432))=886, both axes; checks the second independent GPU bound.
        verifyCase(2051, 2051, 1, 886, 886)
    }

    private fun verifyCase(width: Int, height: Int, orientation: Int, expectedWidth: Int, expectedHeight: Int) {
        requireGpuInputSafetyFixture()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File.createTempFile("half-boundary-synthetic-", ".tmp", context.cacheDir)
        assertTrue(root.delete() && root.mkdir())
        val input = File(root, "input.dng")
        // Stream rows: even the 24MP fixture never creates a second full Java byte array.
        input.outputStream().buffered().use { writeSyntheticTrialDng(it, width, height, orientation) }
        val source = SharedOriginalReference("content://example.test/half-boundary/synthetic",
            DngContentVerifier.identify(input.length(), { input.inputStream() }), OriginalGrant.USER_SELECTED)
        val geometry = ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY).use {
            Original24Preflight.inspect(it.fd, CancellationSignal())
        }
        assertEquals(width, geometry.sourceWidth)
        assertEquals(height, geometry.sourceHeight)
        assertEquals(orientation, geometry.orientation)
        val scratch = File(root, "candidate-owner")
        var succeeded = false
        try {
            Original24PreviewSession(source, scratch, {}, geometry,
                rasterMode = PreviewRasterMode.HALF_CANDIDATE) { input.inputStream() }.use { session ->
                val image = session.loadGpuAsShotInput()
                assertEquals("$width x $height orientation $orientation width", expectedWidth, image.width)
                assertEquals("$width x $height orientation $orientation height", expectedHeight, image.height)
                assertTrue(maxOf(image.width, image.height) <= 1024)
                assertTrue(image.width.toLong() * image.height <= 786432L)
                GpuPreviewInputSize.checkSupported(image.width, image.height)
                repeat(image.pixelCount * 3) { index ->
                    if (!image[index].isFinite()) fail("Non-finite half candidate channel $index")
                }
                assertFalse("Transient owners must close before returning", session.hasUnreleasedResources)
                assertFalse("Private snapshot and raster directory must be removed", scratch.exists())
            }
            assertFalse(scratch.exists())
            DngContentVerifier.verify(source.fingerprint, { input.inputStream() })
            println("HALF_BOUNDARY $width x $height orientation=$orientation -> $expectedWidth x $expectedHeight: finite, source unchanged, owners closed")
            succeeded = true
        } finally {
            // Keep failed evidence, and never recursively delete an owner whose close is unconfirmed.
            if (succeeded) {
                assertTrue(input.delete())
                assertTrue(root.delete())
            }
        }
    }
}
