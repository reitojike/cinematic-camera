package dev.cinematic.p0.gpu

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.jpegrender.original.Original24Attempt
import dev.cinematic.p0.jpegrender.original.Original24Request
import dev.cinematic.p0.photoroll.TrialDisplayPixels
import dev.cinematic.p0.raw.LinearSrgbImage
import dev.cinematic.p0.rawcapture.AutoReadOnlyDescriptor
import dev.cinematic.p0.rawcapture.AutoRenderIo
import dev.cinematic.p0.rawcapture.OriginalFingerprint
import dev.cinematic.p0.rawcapture.OriginalGrant
import dev.cinematic.p0.rawcapture.SharedOriginalReference
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/** #282: test the existing encoders, without copying or changing either formula. */
@RunWith(AndroidJUnit4::class)
class StageClearFinishEncodingTest {
    @Test fun originalChannelAndDisplayByteAgreeAt4096UnitSamples() {
        compareEncoders(FloatArray(4096) { it / 4095f }, width = 64)
    }

    @Test fun originalChannelAndDisplayByteAgreeAtClipAndTransferBoundaries() {
        compareEncoders(floatArrayOf(
            -Float.MAX_VALUE, -1f, -0f, 0f, Float.MIN_VALUE,
            Math.nextDown(0.0031308f), 0.0031308f, Math.nextUp(0.0031308f),
            Math.nextDown(1f), 1f, Math.nextUp(1f), Float.MAX_VALUE,
        ), width = 12)
    }

    private fun compareEncoders(values: FloatArray, width: Int) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val image = LinearSrgbImage(width, values.size / width, FloatArray(values.size * 3) { values[it / 3] })
        val display = TrialDisplayPixels.encode(image, maxOf(image.width, image.height)) { false }
        // Valid synthetic metadata only. No URI, file, renderer or output is opened.
        val source = SharedOriginalReference("content://synthetic/encoding-only",
            OriginalFingerprint(1L, "0".repeat(64)), OriginalGrant.APP_OWNED)
        val io = object : AutoRenderIo {
            override fun openOriginal(): InputStream = error("Encoding must not read an original")
            override fun openOriginalDescriptor(): AutoReadOnlyDescriptor = error("Encoding must not open a descriptor")
            override fun openOutput(): OutputStream = error("Encoding must not create an output")
            override fun openOutputRead(): InputStream = error("Encoding must not read an output")
        }
        // A private-method test avoids adding a production/test hook to #286's file.
        val channel = Original24Attempt::class.java.getDeclaredMethod("channel", Float::class.javaPrimitiveType!!)
            .apply { isAccessible = true }
        Original24Attempt(context, Original24Request("synthetic_encoding", 1L, source), io,
            File(context.cacheDir, "unused-encoding-test")).use { attempt ->
            values.forEachIndexed { index, value ->
                val encoded = channel.invoke(attempt, value) as Int
                for (shift in listOf(0, 8, 16)) {
                    assertEquals("sample=$index channel=$shift", encoded, (display[index] ushr shift) and 255)
                }
                assertEquals(255, (display[index] ushr 24) and 255)
            }
            assertFalse(attempt.hasUnreleasedResources)
        }
    }
}
