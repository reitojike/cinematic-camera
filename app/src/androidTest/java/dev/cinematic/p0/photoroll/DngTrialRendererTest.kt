package dev.cinematic.p0.photoroll

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.rawcapture.DngContentVerifier
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Generated non-photo input in a test-owned pending row; never publishes or captures an image. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class DngTrialRendererTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val resources = PhotoRollResources(true, 0, false, PhotoRollResources.REQUIRED_AVAILABLE_BYTES)

    @Test fun selectedContentUriProducesHonestTrialBitmapAndLeavesOriginalUnchanged() = withOriginal { original ->
        val renderer = DngTrialRenderer(context.contentResolver) { resources }
        val result = renderer.render(PhotoRollRenderRequest(original, mode = PhotoRollPreviewMode.DNG_TRIAL), CancellationSignal())
        assertTrue(result is PhotoRollRenderResult.Preview)
        val preview = result as PhotoRollRenderResult.Preview
        try {
            assertEquals(PhotoRollPreviewMode.DNG_TRIAL, preview.mode)
            assertEquals(16, preview.bitmap.width)
            assertEquals(12, preview.bitmap.height)
            assertEquals(Bitmap.Config.ARGB_8888, preview.bitmap.config)
            assertTrue(preview.bitmap.colorSpace!!.isSrgb)
            val pixels = IntArray(16 * 12)
            preview.bitmap.getPixels(pixels, 0, 16, 0, 0, 16, 12)
            assertTrue(pixels.any { (it and 0xffffff) != 0 })
            DngContentVerifier.verify(original.fingerprint, { context.contentResolver.openInputStream(original.uri)!! })
        } finally { preview.bitmap.recycle() }
    }

    @Test fun cancellationDoesNotPreventTheNextRealRequest() = withOriginal { original ->
        val renderer = DngTrialRenderer(context.contentResolver) { resources }
        val request = PhotoRollRenderRequest(original, mode = PhotoRollPreviewMode.DNG_TRIAL)
        try { renderer.render(request, CancellationSignal().apply { cancel() }); fail("Expected cancellation") }
        catch (_: OperationCanceledException) { /* The next request must still work. */ }
        val result = renderer.render(request, CancellationSignal())
        assertTrue(result is PhotoRollRenderResult.Preview)
        (result as PhotoRollRenderResult.Preview).bitmap.recycle()
    }

    @Test fun unavailableResourcesAndWrongModeDoNotBecomeSuccessfulImages() = withOriginal { original ->
        val renderer = DngTrialRenderer(context.contentResolver) { resources.copy(camerasIdle = null) }
        val blocked = renderer.render(PhotoRollRenderRequest(original, mode = PhotoRollPreviewMode.DNG_TRIAL), CancellationSignal())
        assertTrue(blocked is PhotoRollRenderResult.Unavailable)
        assertTrue((blocked as PhotoRollRenderResult.Unavailable).reason.contains("カメラ"))
        val wrongMode = renderer.render(PhotoRollRenderRequest(original), CancellationSignal())
        assertTrue(wrongMode is PhotoRollRenderResult.Unavailable)
        assertTrue((wrongMode as PhotoRollRenderResult.Unavailable).reason.contains("Stage Clear"))
    }

    private fun withOriginal(action: (PhotoRollOriginal) -> Unit) {
        val resolver = context.contentResolver
        val bytes = syntheticDng()
        val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "synthetic-trial-${System.nanoTime()}.dng")
            put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Cinematic Camera Synthetic Tests/")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }) ?: error("Could not create test-only pending row")
        try {
            resolver.openOutputStream(uri, "w")!!.use { it.write(bytes) }
            val fingerprint = DngContentVerifier.identify(bytes.size.toLong(), { resolver.openInputStream(uri)!! })
            action(PhotoRollOriginal(uri, fingerprint))
        } finally { check(resolver.delete(uri, null, null) == 1) { "Test-only pending row cleanup failed" } }
    }

    /** Minimal 32x24 uncompressed Bayer DNG; all identity/calibration and samples are synthetic. */
    private fun syntheticDng(): ByteArray {
        fun shorts(vararg values: Int) = ByteBuffer.allocate(values.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            .apply { values.forEach { putShort(it.toShort()) } }.array()
        fun longs(vararg values: Int) = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            .apply { values.forEach { putInt(it) } }.array()
        data class Entry(val tag: Int, val type: Int, val count: Int, val bytes: ByteArray)
        val entries = mutableListOf<Entry>()
        fun short(tag: Int, vararg values: Int) { entries += Entry(tag, 3, values.size, shorts(*values)) }
        fun long(tag: Int, value: Int) { entries += Entry(tag, 4, 1, longs(value)) }
        fun ascii(tag: Int, value: String) { entries += Entry(tag, 2, value.length + 1, (value + '\u0000').toByteArray()) }
        long(256, 32); long(257, 24); short(258, 16); short(259, 1); short(262, 32803)
        ascii(271, "Synthetic"); ascii(272, "Bayer fixture")
        long(273, 0); short(274, 1); short(277, 1); long(278, 24); long(279, 32 * 24 * 2)
        short(33421, 2, 2)
        entries += Entry(33422, 1, 4, byteArrayOf(0, 1, 1, 2))
        entries += Entry(50706, 1, 4, byteArrayOf(1, 4, 0, 0))
        short(50713, 1, 1); long(50714, 64); long(50717, 4095)
        entries += Entry(50721, 10, 9, longs(1, 1, 0, 1, 0, 1, 0, 1, 1, 1, 0, 1, 0, 1, 0, 1, 1, 1))
        entries += Entry(50728, 5, 3, longs(1, 1, 1, 1, 1, 1))
        short(50778, 21)
        entries.sortBy { it.tag }
        val start = 8 + 2 + 12 * entries.size + 4
        val rawOffset = start + entries.sumOf { if (it.bytes.size > 4) it.bytes.size else 0 }
        val buffer = ByteBuffer.allocate(rawOffset + 32 * 24 * 2).order(ByteOrder.LITTLE_ENDIAN)
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
        buffer.putInt(0).position(rawOffset)
        repeat(24) { y -> repeat(32) { x -> buffer.putShort((164 + (x * 65 + y * 25) % 3500).toShort()) } }
        return buffer.array()
    }
}
