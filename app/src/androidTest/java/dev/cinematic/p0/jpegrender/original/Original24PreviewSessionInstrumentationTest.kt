package dev.cinematic.p0.jpegrender.original

import android.graphics.Bitmap
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.raw.PhotoAdjustments
import dev.cinematic.p0.rawcapture.DngContentVerifier
import dev.cinematic.p0.rawcapture.OriginalGrant
import dev.cinematic.p0.rawcapture.SharedOriginalReference
import dev.cinematic.p0.testing.writeSyntheticTrialDng
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Original24PreviewSessionInstrumentationTest {
    @Test fun comparisonIsIndependentAlignedAndUnaffectedByManualControls() = fixture { input, root, source ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val session = Original24PreviewSession(source, File(root, "preview"), { Original24Resources.requireAvailable(context) }, preflight(input)) {
            input.inputStream()
        }
        val bitmaps = mutableListOf<Bitmap>()
        try {
            assertThrows(IllegalStateException::class.java) { session.comparisonBitmap() }
            val after = session.render(.2).also { bitmaps += it }
            val before = session.comparisonBitmap().also { bitmaps += it }
            assertNotSame(after, before)
            assertEquals(after.width, before.width)
            assertEquals(after.height, before.height)
            assertEquals(Bitmap.Config.ARGB_8888, before.config)
            assertTrue(before.colorSpace?.isSrgb == true)
            session.render(.8, PhotoAdjustments(80.0, .9)).also { bitmaps += it }
            val beforeAfterEdits = session.comparisonBitmap().also { bitmaps += it }
            assertNotSame(before, beforeAfterEdits)
            assertArrayEquals(pixels(before), pixels(beforeAfterEdits))
            assertFalse(session.hasUnreleasedResources)
            session.close()
            assertThrows(IllegalStateException::class.java) { session.comparisonBitmap() }
        } finally { session.close(); bitmaps.forEach { it.recycle() } }
    }

    @Test fun independentlyOwnedPreviewReusesReducedInputAndAppliesAllThreeControls() = fixture { input, root, source ->
        var sourceReads = 0
        val scratch = File(root, "preview")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val session = Original24PreviewSession(source, scratch, { Original24Resources.requireAvailable(context) }, preflight(input)) {
            sourceReads++; input.inputStream()
        }
        val bitmaps = mutableListOf<Bitmap>()
        try {
            val first = session.render(.2).also { bitmaps += it }
            assertFalse(scratch.exists())
            assertFalse(session.hasUnreleasedResources)
            assertEquals(Bitmap.Config.ARGB_8888, first.config)
            assertTrue(first.colorSpace?.isSrgb == true)
            val readsAfterDecode = sourceReads
            val second = session.render(.8).also { bitmaps += it }
            assertEquals(2, sourceReads - readsAfterDecode) // Two source fences; no snapshot/decode input read.
            assertNotSame(first, second)
            assertNotEquals(pixels(first).contentHashCode(), pixels(second).contentHashCode())
            val adjusted = session.render(.8, PhotoAdjustments(70.0, .7)).also { bitmaps += it }
            assertNotEquals(pixels(second).contentHashCode(), pixels(adjusted).contentHashCode())
            assertFalse(scratch.exists())
            DngContentVerifier.verify(source.fingerprint, { input.inputStream() })
        } finally { session.close(); bitmaps.forEach { it.recycle() } }
        assertFalse(session.hasUnreleasedResources)
    }

    @Test fun sourceChangeFailsBeforeReuseAndNeverRestartsFailedSession() = fixture { input, root, source ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val session = Original24PreviewSession(source, File(root, "preview"), { Original24Resources.requireAvailable(context) }, preflight(input)) {
            input.inputStream()
        }
        try {
            session.render(.5).recycle()
            val bytes = input.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            input.writeBytes(bytes)
            assertThrows(IOException::class.java) { session.render(.6) }
            assertThrows(IllegalStateException::class.java) { session.render(.6) }
            assertFalse(session.hasUnreleasedResources)
        } finally { session.close() }
    }

    @Test fun failedSourceCloseRemainsOwnedAndCloseRetriesExactStream() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(context.packageName == "dev.cinematic.p0.original24probe")
        val root = File.createTempFile("preview-close-", ".tmp", context.cacheDir)
        assertTrue(root.delete())
        val bytes = byteArrayOf(1, 2, 3)
        val source = SharedOriginalReference("content://example.test/preview/synthetic-close",
            DngContentVerifier.identify(3, { ByteArrayInputStream(bytes) }), OriginalGrant.USER_SELECTED)
        var failClose = true
        var closes = 0
        var opens = 0
        val stream = object : ByteArrayInputStream(bytes) {
            override fun close() { closes++; if (failClose) throw IOException("synthetic close failure"); super.close() }
        }
        val session = Original24PreviewSession(source, root, { Original24Resources.requireAvailable(context) },
            Original24PreflightGeometry(64, 48, 64, 48, 1, 64, 48)) { opens++; stream }
        assertThrows(IOException::class.java) { session.render(.5) }
        assertEquals(1, opens)
        assertTrue(session.hasUnreleasedResources)
        assertThrows(IOException::class.java) { session.close() }
        failClose = false
        session.close()
        assertTrue(closes >= 3)
        assertFalse(session.hasUnreleasedResources)
        assertFalse(root.exists())
    }

    @Test fun independentDecodeRejectsSameSizeCropAndOrientationMismatch() = fixture(cropped = true) { input, root, source ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val admitted = preflight(input)
        assertEquals(2, admitted.cropLeft)
        assertEquals(2, admitted.cropTop)
        assertEquals(60, admitted.sourceWidth)
        assertEquals(44, admitted.sourceHeight)
        // First prove the same real fixture fully decodes under its actual preflight geometry.
        val matching = Original24PreviewSession(source, File(root, "matching"),
            { Original24Resources.requireAvailable(context) }, admitted) { input.inputStream() }
        try { matching.render(.5).recycle() } finally { matching.close() }
        for ((index, wrong) in listOf(admitted.copy(cropLeft = 3), admitted.copy(orientation = 2)).withIndex()) {
            assertEquals(admitted.outputWidth, wrong.outputWidth)
            assertEquals(admitted.outputHeight, wrong.outputHeight)
            val session = Original24PreviewSession(source, File(root, "mismatch-$index"),
                { Original24Resources.requireAvailable(context) }, wrong) { input.inputStream() }
            try {
                val error = assertThrows(IOException::class.java) { session.render(.5) }
                assertTrue(error.message.orEmpty().contains("geometry differs"))
                assertFalse(session.hasUnreleasedResources)
            } finally { session.close() }
        }
    }

    private fun preflight(input: File) = ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY).use {
        Original24Preflight.inspect(it.fd, CancellationSignal())
    }

    private fun pixels(bitmap: Bitmap) = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }

    private fun fixture(cropped: Boolean = false, action: (File, File, SharedOriginalReference) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(context.packageName == "dev.cinematic.p0.original24probe")
        val root = File.createTempFile("preview-synthetic-", ".tmp", context.cacheDir)
        assertTrue(root.delete() && root.mkdir())
        val input = File(root, "input.dng")
        input.outputStream().use { writeSyntheticTrialDng(it, 64, 48, 1) }
        if (cropped) input.writeBytes(withIntegerCrop(input.readBytes()))
        val bytes = input.length()
        val source = SharedOriginalReference("content://example.test/preview/synthetic",
            DngContentVerifier.identify(bytes, { input.inputStream() }), OriginalGrant.USER_SELECTED)
        try { action(input, root, source) }
        finally { assertTrue(input.delete()); assertTrue(root.delete()) }
    }

    /** Keep original RAW/data offsets and append a new root with integral DefaultCrop tags. */
    private fun withIntegerCrop(original: ByteArray): ByteArray {
        val old = ByteBuffer.wrap(original).order(ByteOrder.LITTLE_ENDIAN)
        val count = old.getShort(8).toInt() and 0xffff
        val entries = (0 until count).map { original.copyOfRange(10 + it * 12, 22 + it * 12) }.toMutableList()
        val root = original.size
        val extra = root + 2 + (count + 2) * 12 + 4
        for ((index, tag) in listOf(50719, 50720).withIndex()) entries += ByteBuffer.allocate(12)
            .order(ByteOrder.LITTLE_ENDIAN).putShort(tag.toShort()).putShort(4).putInt(2).putInt(extra + index * 8).array()
        val output = ByteBuffer.allocate(extra + 16).order(ByteOrder.LITTLE_ENDIAN).put(original).putInt(4, root)
        output.position(root)
        output.putShort((count + 2).toShort())
        for (entry in entries.sortedBy { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff }) output.put(entry)
        output.putInt(0).putInt(2).putInt(2).putInt(60).putInt(44)
        return output.array()
    }
}
