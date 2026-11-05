package dev.cinematic.rawprobe

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.FilterOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.math.pow
import kotlin.math.roundToInt
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Tiny generated images only. Codec/transport experiment, never a FULL or Stage Clear acceptance. */
@RunWith(AndroidJUnit4::class)
class BaselineSmallTest {
    private fun directory(): File {
        val args = InstrumentationRegistry.getArguments()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(args.getString("rawProbe") == "true")
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        check(context.packageName in setOf("dev.cinematic.rawprobe", "dev.cinematic.rawprobe.largeheap"))
        return Files.createTempDirectory(context.cacheDir.canonicalFile.toPath(), "baseline-small-").toFile()
    }

    private fun source(dir: File, bytes: ByteArray = smallGeometryDng()): File =
        File.createTempFile("source-", ".dng", dir).apply { writeBytes(bytes) }

    private data class Pixels(val geometry: JSONObject, val rgb: ByteArray, val argb: IntArray)

    private fun pixels(file: File, signal: CancellationSignal = CancellationSignal(), stage: (String) -> Unit = {}): Pixels {
        val before = BaselineJpegAttempt.digest(file)
        val allocation = BaselineAllocation(stage)
        try {
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                Os.lseek(fd.fileDescriptor, 7, OsConstants.SEEK_SET)
                val frame = NativeBaselineProbe.decode(fd.fd, signal, allocation)
                assertEquals(7L, Os.lseek(fd.fileDescriptor, 0, OsConstants.SEEK_CUR))
                assertSame(allocation.bitmap, frame.bitmap)
                val geometry = BaselineJpegAttempt.validateFrame(frame)
                val w = frame.bitmap.width; val h = frame.bitmap.height
                val argb = IntArray(w * h)
                frame.bitmap.getPixels(argb, 0, w, 0, 0, w, h)
                val linear = ByteBuffer.wrap(frame.rgb16).order(ByteOrder.LITTLE_ENDIAN)
                for (pixel in argb) {
                    assertEquals(255, pixel ushr 24)
                    for (shift in intArrayOf(16, 8, 0)) {
                        val v = (linear.short.toInt() and 65535) / 65535.0
                        val encoded = if (v <= .0031308) 12.92 * v else 1.055 * v.pow(1.0 / 2.4) - .055
                        assertEquals((encoded * 255).roundToInt().coerceIn(0, 255), (pixel ushr shift) and 255)
                    }
                }
                assertEquals(0, linear.remaining())
                Pixels(geometry, frame.rgb16.copyOf(), argb)
            }
        } finally {
            allocation.close()
            assertNull(allocation.bitmap)
            assertEquals(before, BaselineJpegAttempt.digest(file))
        }
    }

    private fun failure(block: () -> Unit): Throwable {
        try { block() } catch (expected: Exception) { return expected }
        throw AssertionError("Expected failure")
    }

    @Test fun allPixelsMatchIndependentPreCropAndEightOrientations() {
        val dir = directory()
        val original = pixels(source(dir))
        assertEquals("[12,10,40,28]", original.geometry.getJSONArray("crop").toString())
        val trimmed = pixels(source(dir, smallGeometryDng(40, 28, active = intArrayOf(0, 0, 28, 40),
            origin = intArrayOf(0, 0), crop = intArrayOf(40, 28), pixelOffsetX = 12, pixelOffsetY = 10)))
        assertArrayEquals("Every RGB16 sample of independently trimmed source", trimmed.rgb, original.rgb)
        val w = 40; val h = 28
        for (orientation in 1..8) {
            val rotated = pixels(source(dir, smallGeometryDng(orientation = orientation)))
            val rw = if (orientation >= 5) h else w
            assertEquals(rw, rotated.geometry.getInt("width"))
            assertEquals(if (orientation >= 5) w else h, rotated.geometry.getInt("height"))
            for (y in 0 until h) for (x in 0 until w) {
                val (dx, dy) = when (orientation) {
                    1 -> x to y; 2 -> w - 1 - x to y; 3 -> w - 1 - x to h - 1 - y
                    4 -> x to h - 1 - y; 5 -> y to x; 6 -> h - 1 - y to x
                    7 -> h - 1 - y to w - 1 - x; else -> y to w - 1 - x
                }
                for (b in 0 until 6) assertEquals("orientation=$orientation pixel=$x,$y byte=$b",
                    original.rgb[(y * w + x) * 6 + b], rotated.rgb[(dy * rw + dx) * 6 + b])
            }
        }
        val odd = pixels(source(dir, smallGeometryDng(65, 49, active = intArrayOf(4, 4, 45, 61),
            origin = intArrayOf(4, 4), crop = intArrayOf(49, 33))))
        assertEquals(49 * 33, odd.argb.size)
        assertEquals(49 * 33L, odd.geometry.getLong("packedPixels"))
    }

    @Test fun rejectsAmbiguousAndOversizedGeometryBeforeAllocation() {
        val dir = directory()
        val bad = listOf("missing-active", "fractional-crop", "duplicate-active", "user-crop")
            .map { smallGeometryDng(malformed = it) } + listOf(
                smallGeometryDng(crop = intArrayOf(70, 28)),
                smallGeometryDng(65, 65),
            )
        for (bytes in bad) {
            val allocation = BaselineAllocation(stage = { check(it != "process") })
            val file = source(dir, bytes)
            try {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    assertEquals("BASELINE_GEOMETRY", failure { NativeBaselineProbe.decode(fd.fd, CancellationSignal(), allocation) }.message)
                }
                assertNull(allocation.bitmap)
            } finally { allocation.close() }
        }
        val oversized = source(dir, ByteArray(128 * 1024 + 1))
        assertTrue(failure { BaselineJpegAttempt(oversized, File(dir, "too-large.jpg"), CancellationSignal()).execute() } is IOException)
        assertFalse(File(dir, "too-large.jpg").exists())
    }

    @Test fun baselineJpegIsClosedReadBackAndOriginalUnchanged() {
        val dir = directory()
        for (orientation in listOf(1, 6)) {
            val file = source(dir, smallGeometryDng(orientation = orientation))
            val before = BaselineJpegAttempt.digest(file)
            val output = File(dir, "orientation-$orientation.jpg")
            val attempt = BaselineJpegAttempt(file, output, CancellationSignal())
            val result = attempt.execute()
            assertTrue(attempt.released)
            assertSame(result, attempt.receipt)
            assertEquals(before, BaselineJpegAttempt.digest(file))
            assertEquals(BaselineJpegAttempt.sha256(output.readBytes()), result.getString("outputSha256"))
            assertEquals(if (orientation == 1) 40 else 28, result.getInt("width"))
            assertEquals(if (orientation == 1) 28 else 40, result.getInt("height"))
            assertEquals(1, result.getInt("pixelOrientation"))
            assertEquals(0, result.getInt("exifReaderOrientation"))
            assertEquals("NOT_RUN", result.getString("face"))
            assertEquals("NOT_RUN", result.getString("exposure"))
            assertEquals("DIAGNOSTIC_BASELINE_NO_R7_NO_STAGE_CLEAR", result.getString("scope"))
        }
    }

    @Test fun rejectsJpegWithAdditionalExifRotation() {
        val dir = directory()
        val attempt = BaselineJpegAttempt(source(dir), File(dir, "double-rotation.jpg"), CancellationSignal(), encode = { image, sink ->
            val bytes = ByteArrayOutputStream().also { check(image.compress(Bitmap.CompressFormat.JPEG, 95, it)) }.toByteArray()
            // Independent minimal EXIF orientation 6, used only to prove that a second transform fails.
            val app1 = ByteBuffer.allocate(36).order(ByteOrder.LITTLE_ENDIAN)
            app1.put(0xff.toByte()).put(0xe1.toByte()).put(0).put(34)
            app1.put(byteArrayOf(69, 120, 105, 102, 0, 0)).putShort(0x4949).putShort(42).putInt(8)
            app1.putShort(1).putShort(274).putShort(3).putInt(1).putShort(6).putShort(0).putInt(0)
            sink.write(bytes, 0, 2); sink.write(app1.array()); sink.write(bytes, 2, bytes.size - 2)
            true
        })
        assertEquals("JPEG geometry/color mismatch", failure { attempt.execute() }.message)
        assertNull(attempt.receipt); assertTrue(attempt.released)
    }

    @Test fun writeFlushCloseAndCodecFailuresCannotCreateSuccess() {
        val dir = directory()
        val file = source(dir)
        val before = BaselineJpegAttempt.digest(file)
        for (mode in listOf("partial-write", "drop-write", "mutate-write", "flush", "close", "wrap")) {
            var closes = 0
            val output = File(dir, "$mode.jpg")
            val attempt = BaselineJpegAttempt(file, output, CancellationSignal(), wrap = { base ->
                if (mode == "wrap") throw IOException("wrap injected")
                object : FilterOutputStream(base) {
                    var position = 0
                    override fun write(b: ByteArray, offset: Int, length: Int) {
                        when (mode) {
                            "partial-write" -> { out.write(b, offset, minOf(5, length)); throw IOException("write injected") }
                            "drop-write" -> Unit
                            "mutate-write" -> {
                                val copy = b.copyOfRange(offset, offset + length)
                                // JFIF density byte: keep length, codec validity and all image pixels intact.
                                if (14 in position until position + length) copy[14 - position] = (copy[14 - position].toInt() xor 1).toByte()
                                out.write(copy); position += length
                            }
                            else -> out.write(b, offset, length)
                        }
                    }
                    override fun flush() { if (mode == "flush") throw IOException("flush injected"); out.flush() }
                    override fun close() {
                        closes++
                        if (mode == "close" && closes == 1) throw IOException("close injected")
                        out.close()
                    }
                }
            })
            failure { attempt.execute() }
            assertNull(mode, attempt.receipt)
            assertEquals(before, BaselineJpegAttempt.digest(file))
            if (mode == "close") {
                assertFalse(attempt.released)
                assertEquals(1, closes)
                attempt.closePendingWriter()
                assertEquals(2, closes)
            } else if (mode != "wrap") assertEquals(1, closes)
            assertTrue(mode, attempt.released)
            assertNull(attempt.receipt)
            failure { attempt.execute() }
            assertTrue("Preserve failed private partial file", output.exists())
            if (mode == "mutate-write") {
                val decoded = BitmapFactory.decodeFile(output.path)
                assertNotNull("Changed JPEG still passes codec", decoded)
                decoded!!.recycle()
            }
        }
        val codec = BaselineJpegAttempt(file, File(dir, "codec.jpg"), CancellationSignal(), encode = { _, _ -> false })
        assertEquals("JPEG encoder failed", failure { codec.execute() }.message)
        assertNull(codec.receipt); assertTrue(codec.released)
        val existing = File(dir, "existing.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val duplicate = BaselineJpegAttempt(file, existing, CancellationSignal())
        failure { duplicate.execute() }
        assertArrayEquals(byteArrayOf(1, 2, 3), existing.readBytes())
        assertNull(duplicate.receipt); assertTrue(duplicate.released)
    }

    @Test fun closeFailuresRetainReaderAndDescriptorUntilAcknowledged() {
        val dir = directory()
        val file = source(dir)
        for (at in listOf("source", "readback", "descriptor")) {
            var closes = 0
            val output = File(dir, "$at-close.jpg")
            val attempt = BaselineJpegAttempt(file, output, CancellationSignal(), openReader = { target ->
                val base = Files.newInputStream(target.toPath())
                if ((at == "source" && target == file) || (at == "readback" && target == output)) object : FilterInputStream(base) {
                    override fun close() { closes++; if (closes == 1) throw IOException("reader close injected"); super.close() }
                } else base
            }, closeDescriptor = { fd ->
                if (at == "descriptor") { closes++; if (closes == 1) throw IOException("descriptor close injected") }
                fd.close()
            })
            assertEquals(if (at == "descriptor") "descriptor close injected" else "reader close injected", failure { attempt.execute() }.message)
            assertEquals(1, closes)
            assertFalse(attempt.released); assertNull(attempt.receipt)
            if (at == "descriptor") attempt.closePendingDescriptor() else attempt.closePendingReader()
            assertEquals(2, closes)
            assertTrue(attempt.released); assertNull(attempt.receipt)
            failure { attempt.execute() }
        }
    }

    @Test fun cancellationBeforeDecodeDuringPackAndWriteReleasesOwners() {
        val dir = directory()
        val file = source(dir)
        val before = BaselineJpegAttempt.digest(file)
        for (at in listOf("before", "process", "pack_row", "write")) {
            val signal = CancellationSignal()
            var hit = at == "before"
            if (hit) signal.cancel()
            var rows = 0
            val attempt = BaselineJpegAttempt(file, File(dir, "$at.jpg"), signal, stage = { name ->
                if (name == at && (name != "pack_row" || ++rows == 3)) { hit = true; signal.cancel() }
            }, wrap = { base -> object : FilterOutputStream(base) {
                override fun write(b: ByteArray, offset: Int, length: Int) {
                    out.write(b, offset, length)
                    if (at == "write") { hit = true; signal.cancel() }
                }
            } })
            val error = failure { attempt.execute() }
            assertTrue("Reached requested cancellation $at, actual=${error.message}", hit)
            if (at in listOf("process", "pack_row")) assertEquals("DNG_CANCELLED", error.message)
            assertNull(attempt.receipt); assertTrue(attempt.released)
            assertEquals(before, BaselineJpegAttempt.digest(file))
        }
        // A fresh attempt still succeeds after cancellations; no global codec/native ownership remains.
        assertTrue(BaselineJpegAttempt(file, File(dir, "recovery.jpg"), CancellationSignal()).execute().getInt("bytes") > 0)
    }

    @Test fun cannotPromoteResizedHalfSizeOrIncompletePixels() {
        val dir = directory()
        val p = pixels(source(dir))
        val bitmap = Bitmap.createBitmap(40, 28, Bitmap.Config.ARGB_8888)
        val larger = Bitmap.createBitmap(80, 56, Bitmap.Config.ARGB_8888)
        try {
            for (key in listOf("halfSize", "resized", "packedPixels")) {
                val g = JSONObject(p.geometry.toString()).put(key, when (key) { "halfSize" -> 1; "resized" -> true; else -> 1 })
                failure { BaselineJpegAttempt.validateFrame(BaselineFrame(bitmap, g.toString(), p.rgb)) }
            }
            failure { BaselineJpegAttempt.validateFrame(BaselineFrame(larger, p.geometry.toString(), p.rgb)) }
        } finally { bitmap.recycle(); larger.recycle() }
    }
}
