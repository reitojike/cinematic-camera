package dev.cinematic.p0.raw

import android.graphics.Bitmap
import android.graphics.Color
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.rawcapture.OriginalFingerprint
import dev.cinematic.p0.testing.syntheticTrialDng
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

/** Only generated DNG/JPEG bytes in a UUID test-owned temporary file. No Activity or photos. */
class NativeEmbeddedJpegAndroidTest {
    @Test fun extractsIdenticalJpegWithAllExplicitOrientationsAndPreservesDescriptor() {
        for (orientation in 1..8) {
            val (dng, jpeg) = fixture(orientation)
            withSource(dng) { file, descriptor ->
                Os.lseek(descriptor.fileDescriptor, 7, OsConstants.SEEK_SET)
                val result = NativeEmbeddedJpeg.extract(descriptor.fd, fingerprint(dng), CancellationSignal())
                assertEquals(NativeEmbeddedJpegResult.Status.FOUND, result.status)
                assertArrayEquals(jpeg, result.jpeg)
                assertEquals(16, result.width); assertEquals(8, result.height)
                assertEquals(orientation, result.orientation)
                assertEquals(NativeEmbeddedJpegResult.OrientationEvidence.THUMBNAIL, result.evidence)
                assertEquals(7L, Os.lseek(descriptor.fileDescriptor, 0, OsConstants.SEEK_CUR))
                assertArrayEquals(dng, file.readBytes())
            }
        }
    }

    @Test fun unknownOrientationIsNotReturnedAsDisplayableJpeg() {
        val (dng, _) = fixture(0)
        withSource(dng) { _, descriptor ->
            val result = NativeEmbeddedJpeg.extract(descriptor.fd, fingerprint(dng), CancellationSignal())
            assertEquals(NativeEmbeddedJpegResult.Status.UNSUPPORTED, result.status)
            assertNull(result.jpeg)
        }
    }

    @Test fun fingerprintMismatchIsAnErrorBeforeParsingAndNextCallCanSucceed() {
        val (dng, _) = fixture(1)
        withSource(dng) { _, descriptor ->
            val wrong = fingerprint(dng).copy(sha256 = "0".repeat(64))
            val failure = runCatching { NativeEmbeddedJpeg.extract(descriptor.fd, wrong, CancellationSignal()) }.exceptionOrNull()
            assertTrue(failure is IOException); assertEquals("DNG_CHANGED", failure?.message)
            assertEquals(NativeEmbeddedJpegResult.Status.FOUND,
                NativeEmbeddedJpeg.extract(descriptor.fd, fingerprint(dng), CancellationSignal()).status)
        }
    }

    @Test fun cancellationAndLengthMismatchDoNotBecomeSuccessfulMisses() {
        val (dng, _) = fixture(1)
        withSource(dng) { _, descriptor ->
            val stopped = CancellationSignal().apply { cancel() }
            assertTrue(runCatching { NativeEmbeddedJpeg.extract(descriptor.fd, fingerprint(dng), stopped) }
                .exceptionOrNull() is OperationCanceledException)
            val failure = runCatching { NativeEmbeddedJpeg.extract(descriptor.fd,
                fingerprint(dng).copy(bytes = dng.size.toLong() + 1), CancellationSignal()) }.exceptionOrNull()
            assertTrue(failure is IOException); assertEquals("DNG_CHANGED", failure?.message)
            assertTrue(runCatching { NativeEmbeddedJpeg.extract(descriptor.fd,
                fingerprint(dng).copy(bytes = 64L * 1024 * 1024 + 1), CancellationSignal()) }
                .exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test fun noThumbnailRemainsAbsentAndNonSeekableInputFails() {
        val dng = syntheticTrialDng().copyOf(4096)
        withSource(dng) { _, descriptor ->
            assertEquals(NativeEmbeddedJpegResult.Status.ABSENT,
                NativeEmbeddedJpeg.extract(descriptor.fd, fingerprint(dng), CancellationSignal()).status)
        }
        withSource(dng) { file, _ ->
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE).use { writable ->
                val error = runCatching { NativeEmbeddedJpeg.extract(writable.fd, fingerprint(dng), CancellationSignal()) }
                    .exceptionOrNull()
                assertTrue(error is IOException); assertEquals("DNG_SOURCE", error?.message)
            }
        }
        val pipes = ParcelFileDescriptor.createPipe()
        try {
            assertTrue(runCatching { NativeEmbeddedJpeg.extract(pipes[0].fd, fingerprint(dng), CancellationSignal()) }
                .exceptionOrNull() is IOException)
        } finally { pipes.forEach { it.close() } }
    }

    /** Encoded hostile counts/offsets only: every input stays 4 KiB, not a large allocation test. */
    @Test fun malformedIfdCountsAndThumbnailRangesNeverBecomeDisplayable() {
        val (valid, jpeg) = fixture(1)
        val thumbnailIfd = syntheticTrialDng().size
        val cases = listOf<Pair<String, (ByteBuffer) -> Unit>>(
            "root IFD outside snapshot" to { it.putInt(4, -1); Unit },
            "root IFD entry count exceeds snapshot" to { it.putShort(8, 0xffff.toShort()); Unit },
            "thumbnail offset outside snapshot" to {
                it.putInt(thumbnailIfd + 2 + 6 * 12 + 8, -1); Unit
            },
            "thumbnail byte count exceeds snapshot and JPEG cap" to {
                it.putInt(thumbnailIfd + 2 + 10 * 12 + 8, Int.MAX_VALUE); Unit
            }
        )
        for ((name, mutate) in cases) {
            val malformed = valid.copyOf()
            mutate(ByteBuffer.wrap(malformed).order(ByteOrder.LITTLE_ENDIAN))
            assertEquals(4096, malformed.size)
            withSource(malformed) { file, descriptor ->
                val result = NativeEmbeddedJpeg.extract(descriptor.fd, fingerprint(malformed), CancellationSignal())
                assertNotEquals(name, NativeEmbeddedJpegResult.Status.FOUND, result.status)
                assertNull(name, result.jpeg)
                assertArrayEquals(name, malformed, file.readBytes())
            }
            // A malformed source must not poison native state for a subsequent valid call.
            withSource(valid) { _, descriptor ->
                val recovered = NativeEmbeddedJpeg.extract(descriptor.fd, fingerprint(valid), CancellationSignal())
                assertEquals(name, NativeEmbeddedJpegResult.Status.FOUND, recovered.status)
                assertArrayEquals(name, jpeg, recovered.jpeg)
            }
        }
    }

    private fun fingerprint(bytes: ByteArray) = OriginalFingerprint(bytes.size.toLong(),
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })

    private fun withSource(bytes: ByteArray, run: (File, ParcelFileDescriptor) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("embedded-jni-", ".dng", context.cacheDir)
        try {
            file.writeBytes(bytes)
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { run(file, it) }
        } finally { check(file.delete()) }
    }

    private fun fixture(orientation: Int): Pair<ByteArray, ByteArray> {
        val jpeg = ByteArrayOutputStream().use { out ->
            val bitmap = Bitmap.createBitmap(16, 8, Bitmap.Config.ARGB_8888)
            try { bitmap.eraseColor(Color.rgb(120, 80, 40)); check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)) }
            finally { bitmap.recycle() }
            out.toByteArray()
        }
        val raw = syntheticTrialDng()
        val rawIfd = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val count = rawIfd.getShort(8).toInt() and 0xffff
        rawIfd.putInt(8 + 2 + count * 12, raw.size)
        val entries = listOf(254 to 1, 256 to 16, 257 to 8, 258 to 8, 259 to 6, 262 to 6,
            273 to (raw.size + 2 + 11 * 12 + 4), 274 to orientation, 277 to 3, 278 to 8, 279 to jpeg.size)
        val ifd = ByteBuffer.allocate(2 + entries.size * 12 + 4).order(ByteOrder.LITTLE_ENDIAN)
        ifd.putShort(entries.size.toShort())
        for ((tag, value) in entries) {
            val short = tag in setOf(258, 259, 262, 274, 277)
            ifd.putShort(tag.toShort()).putShort(if (short) 3 else 4).putInt(1)
            if (short) ifd.putShort(value.toShort()).putShort(0) else ifd.putInt(value)
        }
        ifd.putInt(0)
        val encoded = raw + ifd.array() + jpeg
        return encoded.copyOf(maxOf(4096, encoded.size)) to jpeg
    }
}
