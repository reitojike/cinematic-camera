package dev.cinematic.p0.jpegrender.original

import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.raw.original.NativeOriginalRaster
import dev.cinematic.p0.testing.writeSyntheticTrialDng
import java.io.File
import java.io.IOException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Unadopted native boundary only. Private synthetic files, no UI, photos or publication. */
@RunWith(AndroidJUnit4::class)
class HalfPreviewNativeAndroidTest {
    @Test fun halfCandidateIsDistinctWhileFullResolutionRemainsUnchanged() = withFiles { source, output ->
        val before = source.readBytes()
        ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { input ->
            for (half in listOf(true, false)) {
                output.writeBytes(byteArrayOf())
                ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_READ_WRITE).use { target ->
                    val report = JSONObject(if (half) NativeOriginalRaster.decodeHalfPreview(input.fd, target.fd, CancellationSignal())
                        else NativeOriginalRaster.decode24(input.fd, target.fd, CancellationSignal()))
                    assertEquals(64, report.getInt("sourceWidth"))
                    assertEquals(48, report.getInt("sourceHeight"))
                    assertEquals(6, report.getInt("orientation"))
                    assertEquals(if (half) 24 else 48, report.getInt("width"))
                    assertEquals(if (half) 32 else 64, report.getInt("height"))
                    assertEquals(if (half) 1 else 0, report.getInt("halfSize"))
                    assertEquals(if (half) "HALF_PREVIEW_CANDIDATE_NOT_SAVE_INPUT" else "PRELIMINARY_ORIGINAL_NO_FACE_NO_VERIFIED_EV", report.getString("scope"))
                    assertEquals(report.getLong("pixelsWritten") * 6, output.length())
                }
            }
        }
        assertArrayEquals(before, source.readBytes())
    }

    @Test fun cancellationAndOccupiedTargetCannotChangeOriginalOrOutput() = withFiles { source, output ->
        val before = source.readBytes()
        ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { input ->
            ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_READ_WRITE).use { target ->
                assertThrows(IOException::class.java) {
                    NativeOriginalRaster.decodeHalfPreview(input.fd, target.fd, CancellationSignal().apply { cancel() })
                }
            }
            assertEquals(0L, output.length())
            val sentinel = byteArrayOf(1, 2, 3)
            output.writeBytes(sentinel)
            ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_READ_WRITE).use { target ->
                assertThrows(IOException::class.java) {
                    NativeOriginalRaster.decodeHalfPreview(input.fd, target.fd, CancellationSignal())
                }
            }
            assertArrayEquals(sentinel, output.readBytes())
            assertThrows(IOException::class.java) {
                NativeOriginalRaster.decodeHalfPreview(input.fd, input.fd, CancellationSignal())
            }
        }
        assertArrayEquals(before, source.readBytes())
    }

    private fun withFiles(action: (File, File) -> Unit) {
        requireGpuInputSafetyFixture()
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val source = File.createTempFile("half-native-synthetic-", ".dng", cache)
        val output = File.createTempFile("half-native-output-", ".rgb16", cache)
        try {
            source.outputStream().use { writeSyntheticTrialDng(it, 64, 48, 6) }
            action(source, output)
        } finally {
            assertTrue(source.delete())
            assertTrue(output.delete())
        }
    }
}
