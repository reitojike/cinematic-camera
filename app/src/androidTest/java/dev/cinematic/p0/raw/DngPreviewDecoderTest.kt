package dev.cinematic.p0.raw

import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DngPreviewDecoderTest {
    @Test fun decodesSyntheticBayerIntoBoundedLinearRgbWithoutChangingSource() = fixture().use { file ->
        val before = digest(file.file)
        val full = native(file.file, 1024)
        assertEquals(16, full.width)
        assertEquals(12, full.height)
        assertEquals(32, full.sourceWidth)
        assertEquals(24, full.sourceHeight)
        assertEquals(full.width * full.height * 3, full.pixels.size)
        assertTrue(full.pixels.all { it.isFinite() && it in 0f..1f })
        assertTrue(full.pixels.any { it > .01f })
        val small = native(file.file, 8)
        assertEquals(8, small.width)
        assertEquals(6, small.height)
        // Each 2x2 block of the half-size result is averaged in linear light.
        for (y in 0 until 6) for (x in 0 until 8) for (c in 0..2) {
            var sum = 0f
            for (dy in 0..1) for (dx in 0..1) sum += full.pixels[((y * 2 + dy) * 16 + x * 2 + dx) * 3 + c]
            assertEquals(sum / 4f, small.pixels[(y * 8 + x) * 3 + c], 1e-6f)
        }
        assertArrayEquals(before, digest(file.file))
    }

    @Test fun appliesOrientationBeforeReportingOutputSize() = fixture(orientation = 6).use { file ->
        val result = native(file.file, 8)
        assertEquals(6, result.width)
        assertEquals(8, result.height)
    }

    @Test fun rejectsNonDngAndHugeDimensionsAndRecoversForNextRequest() {
        fixture(validDng = false).use { assertError("DNG_UNSUPPORTED") { native(it.file, 8) } }
        fixture(cameraWb = false).use { assertError("DNG_UNSUPPORTED") { native(it.file, 8) } }
        fixture(width = 4000, height = 4001).use { assertError("DNG_LIMIT") { native(it.file, 8) } }
        fixture().use { assertTrue(native(it.file, 8).pixels.isNotEmpty()) }
    }

    @Test fun cancellationAndInvalidLimitReleaseResources() = fixture().use { file ->
        val cancelled = CancellationSignal().apply { cancel() }
        assertError("DNG_CANCELLED") { native(file.file, 8, cancelled) }
        assertError("DNG_UNSUPPORTED") { native(file.file, 1025) }
        assertTrue(native(file.file, 8).pixels.isNotEmpty())
    }

    @Test fun rejectsNonSeekableSourceWithoutCopyingIt() {
        val pipe = ParcelFileDescriptor.createPipe()
        try {
            assertError("DNG_SOURCE") { NativeDngDecoder.decode(pipe[0].fd, 8, CancellationSignal()) }
        } finally { pipe.forEach { it.close() } }
    }

    @Test fun rejectsEncodedByteLimitBeforeAllocatingSnapshot() = fixture().use { file ->
        RandomAccessFile(file.file, "rw").use { it.setLength(64L * 1024 * 1024 + 1) }
        assertError("DNG_LIMIT") { native(file.file, 8) }
    }

    @Test fun developsOneSynthetic12MpFrameAtPreviewSize() = fixture(width = 4000, height = 3000).use { file ->
        val start = SystemClock.elapsedRealtimeNanos()
        val result = native(file.file, 1024)
        val elapsedMs = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
        assertEquals(1024, result.width)
        assertEquals(768, result.height)
        assertTrue(result.pixels.all { it.isFinite() })
        val highWater = File("/proc/self/status").useLines { lines -> lines.firstOrNull { it.startsWith("VmHWM:") } }
        // Synthetic process-wide cumulative high water, NOT native-only peak or Pixel performance.
        println("Synthetic12MP preview ms=$elapsedMs process $highWater")
    }

    private fun native(file: File, edge: Int, signal: CancellationSignal = CancellationSignal()): NativeDngPixels =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use {
            NativeDngDecoder.decode(it.fd, edge, signal)
        }

    private fun assertError(code: String, action: () -> Any) {
        try { action(); fail("Expected decoder failure") }
        catch (error: IOException) { assertEquals(code, error.message) }
    }

    private fun digest(file: File): ByteArray = MessageDigest.getInstance("SHA-256").digest(file.readBytes())

    private class Fixture(val file: File) : AutoCloseable {
        override fun close() { check(file.delete()) }
    }

    /** Generated non-photographic TIFF/DNG. No real EXIF, image, device identity or external fixture. */
    private fun fixture(
        orientation: Int = 1, validDng: Boolean = true, width: Int = 32, height: Int = 24,
        widthTag: Int = width, heightTag: Int = height, cameraWb: Boolean = true,
    ): Fixture {
        fun shorts(vararg values: Int) = ByteBuffer.allocate(values.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            .apply { values.forEach { putShort(it.toShort()) } }.array()
        fun longs(vararg values: Int) = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            .apply { values.forEach { putInt(it) } }.array()
        data class Entry(val tag: Int, val type: Int, val count: Int, val bytes: ByteArray)
        val entries = mutableListOf<Entry>()
        fun short(tag: Int, vararg value: Int) { entries += Entry(tag, 3, value.size, shorts(*value)) }
        fun long(tag: Int, value: Int) { entries += Entry(tag, 4, 1, longs(value)) }
        fun ascii(tag: Int, value: String) { entries += Entry(tag, 2, value.length + 1, (value + '\u0000').toByteArray()) }
        long(256, widthTag); long(257, heightTag); short(258, 16); short(259, 1); short(262, 32803)
        ascii(271, "Synthetic"); ascii(272, "Bayer fixture")
        long(273, 0); short(274, orientation); short(277, 1); long(278, height); long(279, width * height * 2)
        short(33421, 2, 2)
        entries += Entry(33422, 1, 4, byteArrayOf(0, 1, 1, 2))
        if (validDng) entries += Entry(50706, 1, 4, byteArrayOf(1, 4, 0, 0))
        short(50713, 1, 1); long(50714, 64); long(50717, 4095)
        // XYZ to camera RGB; a simple, invertible synthetic calibration.
        entries += Entry(50721, 10, 9, longs(1, 1, 0, 1, 0, 1, 0, 1, 1, 1, 0, 1, 0, 1, 0, 1, 1, 1))
        if (cameraWb) entries += Entry(50728, 5, 3, longs(1, 1, 1, 1, 1, 1))
        short(50778, 21)
        entries.sortBy { it.tag }
        val start = 8 + 2 + 12 * entries.size + 4
        val rawOffset = start + entries.sumOf { if (it.bytes.size > 4) it.bytes.size else 0 }
        val buffer = ByteBuffer.allocate(rawOffset + width * height * 2).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put('I'.code.toByte()).put('I'.code.toByte()).putShort(42).putInt(8).putShort(entries.size.toShort())
        var extra = start
        for (entry in entries) {
            buffer.putShort(entry.tag.toShort()).putShort(entry.type.toShort()).putInt(entry.count)
            when {
                entry.tag == 273 -> buffer.putInt(rawOffset)
                entry.bytes.size <= 4 -> { buffer.put(entry.bytes); repeat(4 - entry.bytes.size) { buffer.put(0) } }
                else -> { buffer.putInt(extra); System.arraycopy(entry.bytes, 0, buffer.array(), extra, entry.bytes.size); extra += entry.bytes.size }
            }
        }
        buffer.putInt(0)
        buffer.position(rawOffset)
        repeat(height) { y -> repeat(width) { x -> buffer.putShort((164 + (x * 65 + y * 25) % 3500).toShort()) } }
        val file = File.createTempFile("synthetic-dng-", ".bin", InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
        file.writeBytes(buffer.array())
        return Fixture(file)
    }
}
