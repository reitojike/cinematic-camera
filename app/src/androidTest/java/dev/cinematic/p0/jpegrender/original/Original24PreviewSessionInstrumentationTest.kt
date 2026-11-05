package dev.cinematic.p0.jpegrender.original

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.raw.PhotoAdjustments
import dev.cinematic.p0.rawcapture.DngContentVerifier
import dev.cinematic.p0.rawcapture.AutoReadOnlyDescriptor
import dev.cinematic.p0.rawcapture.AutoRenderIo
import dev.cinematic.p0.rawcapture.OriginalGrant
import dev.cinematic.p0.rawcapture.SharedOriginalReference
import dev.cinematic.p0.testing.writeSyntheticTrialDng
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Original24PreviewSessionInstrumentationTest {
    @Test fun over1024SmoothLightDngPreviewAndActualJpegKeepManualRecipe() =
        fixture(width = 1152, height = 768, sample = ::smoothLightSample) { input, root, source ->
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val strength = .75
            val edits = PhotoAdjustments(70.0, .6)
            val session = Original24PreviewSession(source, File(root, "preview"),
                { Original24Resources.requireAvailable(context) }, preflight(input)) { input.inputStream() }
            val output = File(root, "output.jpg")
            val io = object : AutoRenderIo {
                override fun openOriginal() = input.inputStream()
                override fun openOriginalDescriptor(): AutoReadOnlyDescriptor = error("Use verified immutable snapshot")
                override fun openOutput() = Files.newOutputStream(output.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                override fun openOutputRead() = output.inputStream()
            }
            val attempt = Original24Attempt(context, Original24Request("TRIAL24_preview_parity", 1, source,
                strength, edits), io, File(root, "original"))
            var preview: Bitmap? = null
            var jpeg: Bitmap? = null
            var scaled: Bitmap? = null
            var failure: Throwable? = null
            try {
                val neutral = session.render(.5)
                val neutralEv = try { requireNotNull(session.previewExternalEv) } finally { neutral.recycle() }
                preview = session.render(strength, edits)
                assertEquals(neutralEv + edits.exposureEv, requireNotNull(session.previewExternalEv), 1e-6)
                val result = attempt.render { false }
                assertEquals(requireNotNull(session.previewExternalEv), result.externalEv, 1e-6)
                assertEquals(strength, result.request.strength, 0.0)
                assertEquals(edits, result.request.adjustments)
                assertEquals(1152, result.output.width); assertEquals(768, result.output.height)
                assertEquals(1024, preview.width)
                jpeg = requireNotNull(BitmapFactory.decodeFile(output.path))
                scaled = Bitmap.createScaledBitmap(jpeg, preview.width, preview.height, true)
                var sum = 0.0; var count = 0L; var maximum = 0.0; var visiblyDifferent = 0L
                val buckets = IntArray(1001)
                repeat(preview.height) { y -> repeat(preview.width) { x ->
                    val a = preview.getPixel(x, y); val b = scaled.getPixel(x, y)
                    for (shift in 0..16 step 8) {
                        val delta = abs(((a ushr shift) and 255) - ((b ushr shift) and 255)) / 255.0
                        sum += delta; count++; maximum = maxOf(maximum, delta)
                        if (delta > .05) visiblyDifferent++
                        buckets[(delta * 1000).toInt().coerceAtMost(1000)]++
                    }
                } }
                var seen = 0L; var p99 = 0.0
                for (i in buckets.indices) { seen += buckets[i]; if (seen >= count * .99) { p99 = i / 1000.0; break } }
                println("actual JPEG/preview 1152x768 -> ${preview.width}x${preview.height}: ev=${result.externalEv}, " +
                    "sRGB mean=${sum / count}, p99=$p99, max=$maximum, over5percent=${visiblyDifferent.toDouble() / count}")
                // Smooth light sources only: abrupt high-frequency patterns require a separate quality decision.
                assertTrue("mean=${sum / count}", sum / count < .005)
                assertTrue("p99=$p99", p99 < .02)
                assertTrue("max=$maximum", maximum < .06)
                assertTrue("over5percent=$visiblyDifferent/$count", visiblyDifferent.toDouble() / count < .001)
                DngContentVerifier.verify(source.fingerprint, { input.inputStream() })
                assertFalse(session.hasUnreleasedResources)
                assertFalse(attempt.hasUnreleasedResources)
                assertTrue(output.delete())
            } catch (error: Throwable) {
                failure = error; throw error
            } finally {
                fun close(action: () -> Unit) { try { action() } catch (error: Throwable) {
                    if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error)
                } }
                close { scaled?.recycle() }; close { jpeg?.recycle() }; close { preview?.recycle() }
                close { session.close() }; close { attempt.close() }
                failure?.let { throw it }
            }
        }

    @Test fun comparisonIsIndependentAlignedAndUnaffectedByManualControls() = fixture { input, root, source ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val session = Original24PreviewSession(source, File(root, "preview"), { Original24Resources.requireAvailable(context) }, preflight(input)) {
            input.inputStream()
        }
        val bitmaps = mutableListOf<Bitmap>()
        try {
            assertThrows(IllegalStateException::class.java) { session.comparisonBitmap() }
            val after = session.render(.2).also { bitmaps += it }
            val before = requireNotNull(session.comparisonBitmap()).also { bitmaps += it }
            assertNotSame(after, before)
            assertEquals(after.width, before.width)
            assertEquals(after.height, before.height)
            assertEquals(Bitmap.Config.ARGB_8888, before.config)
            assertTrue(before.colorSpace?.isSrgb == true)
            session.render(.8, PhotoAdjustments(80.0, .9)).also { bitmaps += it }
            val beforeAfterEdits = requireNotNull(session.comparisonBitmap()).also { bitmaps += it }
            assertNotSame(before, beforeAfterEdits)
            assertArrayEquals(pixels(before), pixels(beforeAfterEdits))
            assertFalse(session.hasUnreleasedResources)
            session.close()
            assertNull(session.previewExternalEv)
            assertThrows(IllegalStateException::class.java) { session.comparisonBitmap() }
        } finally { session.close(); bitmaps.forEach { it.recycle() } }
    }

    @Test fun comparisonAllocationOomPreservesVerifiedAfterAndReusableSession() = fixture { input, root, source ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var reads = 0
        val session = Original24PreviewSession(source, File(root, "preview"),
            { Original24Resources.requireAvailable(context) }, preflight(input)) { reads++; input.inputStream() }
        val bitmaps = mutableListOf<Bitmap>()
        try {
            val after = session.render(.5).also { bitmaps += it }
            val afterPixels = pixels(after)
            val beforeReads = reads
            assertNull(session.comparisonBitmap(createBitmap = { _, _ -> throw OutOfMemoryError("synthetic bitmap allocation") }))
            assertEquals(2, reads - beforeReads) // Both source fences still complete after the optional OOM.
            assertFalse(after.isRecycled)
            assertArrayEquals(afterPixels, pixels(after))
            assertFalse(session.hasUnreleasedResources)
            requireNotNull(session.comparisonBitmap()).also { bitmaps += it }
            session.render(.7).also { bitmaps += it }
        } finally { session.close(); bitmaps.forEach { it.recycle() } }
    }

    @Test fun optionalComparisonOomDoesNotMaskSourceCancelCloseAdmissionOrIoFailure() {
        for (mode in listOf("source", "cancel", "close", "admission", "io")) fixture { input, root, source ->
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            var failClose = false
            var failAdmission = false
            var cancel = false
            var created = false
            val session = Original24PreviewSession(source, File(root, "preview"), {
                Original24Resources.requireAvailable(context)
                if (failAdmission) throw IOException("synthetic resource admission failure")
            }, preflight(input)) {
                object : FilterInputStream(input.inputStream()) {
                    override fun close() { if (failClose) throw IOException("synthetic postcheck close failure"); super.close() }
                }
            }
            try {
                session.render(.5).recycle()
                failAdmission = mode == "admission"
                assertThrows(IOException::class.java) {
                    session.comparisonBitmap(createBitmap = { _, _ ->
                        created = true
                        when (mode) {
                            "source" -> input.writeBytes(input.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
                            "cancel" -> cancel = true
                            "close" -> failClose = true
                            "io" -> throw IOException("synthetic bitmap IO failure")
                        }
                        throw OutOfMemoryError("synthetic bitmap allocation")
                    }, shouldStop = { cancel })
                }
                assertEquals(mode != "admission", created)
                assertEquals(mode == "close", session.hasUnreleasedResources)
                assertThrows(IllegalStateException::class.java) { session.render(.6) }
            } finally {
                failClose = false
                session.close()
            }
            assertFalse(session.hasUnreleasedResources)
        }
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
            val firstEv = requireNotNull(session.previewExternalEv)
            assertEquals(1, session.previewFieldBuilds)
            assertTrue(session.previewFieldBytes > 0)
            assertFalse(scratch.exists())
            assertFalse(session.hasUnreleasedResources)
            assertEquals(Bitmap.Config.ARGB_8888, first.config)
            assertTrue(first.colorSpace?.isSrgb == true)
            val readsAfterDecode = sourceReads
            val second = session.render(.8).also { bitmaps += it }
            assertEquals(firstEv, requireNotNull(session.previewExternalEv), 0.0)
            assertEquals(1, session.previewFieldBuilds) // Strength alone retains the same fields.
            assertEquals(2, sourceReads - readsAfterDecode) // Two source fences; no snapshot/decode input read.
            assertNotSame(first, second)
            assertNotEquals(pixels(first).contentHashCode(), pixels(second).contentHashCode())
            val adjusted = session.render(.8, PhotoAdjustments(70.0, .7)).also { bitmaps += it }
            assertEquals(firstEv + .7, requireNotNull(session.previewExternalEv), 1e-6)
            assertEquals(2, session.previewFieldBuilds) // Warmth and EV replace the R7 base and fields.
            assertNotEquals(pixels(second).contentHashCode(), pixels(adjusted).contentHashCode())
            session.render(.8, PhotoAdjustments(70.0, 1.0)).also { bitmaps += it }
            assertEquals(firstEv + 1.0, requireNotNull(session.previewExternalEv), 1e-6)
            assertEquals(3, session.previewFieldBuilds) // EV-only changes also invalidate the fields.
            assertFalse(scratch.exists())
            DngContentVerifier.verify(source.fingerprint, { input.inputStream() })
        } finally { session.close(); bitmaps.forEach { it.recycle() } }
        assertFalse(session.hasUnreleasedResources)
        assertEquals(0, session.previewFieldBytes)
    }

    @Test fun sourceChangeFailsBeforeReuseAndNeverRestartsFailedSession() = fixture { input, root, source ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val session = Original24PreviewSession(source, File(root, "preview"), { Original24Resources.requireAvailable(context) }, preflight(input)) {
            input.inputStream()
        }
        try {
            session.render(.5).recycle()
            assertNotNull(session.previewExternalEv)
            val bytes = input.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            input.writeBytes(bytes)
            assertThrows(IOException::class.java) { session.render(.6) }
            assertEquals(0, session.previewFieldBytes)
            assertNull(session.previewExternalEv)
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

    private fun smoothLightSample(x: Int, y: Int): Int {
        val xx = (x + .5) / 1152; val yy = (y + .5) / 768
        fun spot(cx: Double, cy: Double, spread: Double) =
            exp(-((xx - cx).pow(2) + (yy - cy).pow(2)) / spread)
        val warm = 1850 * spot(.24, .32, .00042)
        val cool = 1700 * spot(.72, .47, .00072)
        val base = 410 + 1100 * xx + 340 * yy
        val channel = if (y % 2 == 0 && x % 2 == 0) 0 else if (y % 2 == 1 && x % 2 == 1) 2 else 1
        val scale = when (channel) { 0 -> 1.08; 1 -> .94; else -> .8 }
        val warmGain = when (channel) { 0 -> 1.45; 1 -> .78; else -> .28 }
        val coolGain = when (channel) { 0 -> .43; 1 -> .92; else -> 1.48 }
        return (base * scale + warm * warmGain + cool * coolGain).toInt().coerceIn(64, 4095)
    }

    private fun fixture(cropped: Boolean = false, width: Int = 64, height: Int = 48,
        sample: ((Int, Int) -> Int)? = null,
        action: (File, File, SharedOriginalReference) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(context.packageName == "dev.cinematic.p0.original24probe")
        val root = File.createTempFile("preview-synthetic-", ".tmp", context.cacheDir)
        assertTrue(root.delete() && root.mkdir())
        val input = File(root, "input.dng")
        input.outputStream().use {
            if (sample == null) writeSyntheticTrialDng(it, width, height, 1)
            else writeSyntheticTrialDng(it, width, height, 1, sample)
        }
        if (cropped) input.writeBytes(withIntegerCrop(input.readBytes()))
        val bytes = input.length()
        val source = SharedOriginalReference("content://example.test/preview/synthetic",
            DngContentVerifier.identify(bytes, { input.inputStream() }), OriginalGrant.USER_SELECTED)
        var failure: Throwable? = null
        try { action(input, root, source) }
        catch (error: Throwable) { failure = error; throw error }
        finally { if (failure == null) { assertTrue(input.delete()); assertTrue(root.delete()) } }
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
