package dev.cinematic.p0.jpegrender

import android.content.ContentValues
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.raw.*
import dev.cinematic.p0.rawcapture.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Generated pixels/metadata only. Synthetic controls are NOT registered for application publication. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class JpegGenerationAndroidTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val policy = RenderRecipeSnapshot("synthetic-controls-v1", "ev=0;faces=known-none")

    @Test fun portraitDngThroughR7LookAndRealCodecAtMaximumBoundPreservesOriginal() = maximumOutput(false)

    @Test fun privateTrialKeepsUnverifiedMetadataAt1024() = maximumOutput(true)

    private fun maximumOutput(trial: Boolean) {
        check(Build.HARDWARE in setOf("goldfish", "ranchu")) { "Disposable emulator only" }
        withOriginal(syntheticDng(2048, 1536, 6)) { source ->
            val id = "jpeg_${UUID.randomUUID().toString().replace("-", "")}"
            val root = File(context.cacheDir, id)
            val files = RenderedJpegFiles(root, id, 1)
            try {
                val request = JpegGenerationRequest(id, 1, source,
                    if (trial) JpegGenerationRecipe.trial() else JpegGenerationRecipe(AndroidAsShotJpegFrames.CONTRACT, policy))
                val target = object : PrivateJpegTarget {
                    override val captureId = id
                    override val generation = 1L
                    override fun createOutput() = files.createOutput()
                    override fun openRead() = files.openRead()
                }
                val controls = object : JpegControlsProvider {
                    override val contract = policy.schema
                    override fun prepare(image: LinearSrgbImage, policy: RenderRecipeSnapshot, shouldStop: () -> Boolean) = JpegControls(
                        policy, 0.0, FloatPlane(image.width, image.height, FloatArray(image.pixelCount)),
                        FaceMask(image.width, image.height, FloatArray(image.pixelCount)), RenderedFaceState.VERIFIED_NONE, RenderedExposureState.SUPPORTED)
                }
                val frames = AndroidAsShotJpegFrames(context.contentResolver, CancellationSignal())
                val attempt = JpegGenerationAttempt(request, target, frames, if (trial) AsShotNoFaceTrialControls else controls,
                    { context.contentResolver.openInputStream(android.net.Uri.parse(it.uri))!! }, AndroidSrgbJpegEncoder, AndroidJpegInspector())
                val start = SystemClock.elapsedRealtimeNanos()
                val output = if (trial) {
                    val generated = attempt.renderTrial { false }
                    assertEquals(RenderedFaceState.NOT_RUN, generated.face)
                    assertEquals(RenderedExposureState.DIAGNOSTIC, generated.exposure)
                    assertEquals(RenderedResultKind.TRIAL_PREVIEW, generated.kind)
                    assertEquals(JpegGenerationRecipe.TRIAL_SCHEMA, generated.request.recipe.schema)
                    generated.request.output
                } else attempt.render { false }.render.request.output
                assertEquals(768, output.width)
                assertEquals(1024, output.height)
                assertEquals(1, output.orientation)
                assertEquals("sRGB", output.color)
                assertFalse(attempt.hasUnreleasedResources)
                files.sealCompleted()
                files.openRead().use { assertEquals(ExifInterface.ORIENTATION_UNDEFINED, ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, -1)) }
                files.openRead().use { assertNull(ExifInterface(it).getAttribute(ExifInterface.TAG_GPS_LATITUDE)) }
                DngContentVerifier.verify(source.fingerprint, { context.contentResolver.openInputStream(android.net.Uri.parse(source.uri))!! })
                val elapsedMs = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
                val highWater = File("/proc/self/status").useLines { lines -> lines.firstOrNull { it.startsWith("VmHWM:") } }
                println("Synthetic3MP->1024 JPEG trial=$trial ms=$elapsedMs heapMax=${Runtime.getRuntime().maxMemory()} process $highWater")
            } finally {
                // Only this test's freshly allocated private directory, never an input/user path.
                check(root.canonicalFile.parentFile == context.cacheDir.canonicalFile)
                if (root.exists()) check(root.deleteRecursively())
            }
        }
    }

    @Test fun adapterPhysicallyRotatesAsymmetricPixelsClockwiseBeforeEncoding() {
        withOriginal(syntheticDng(32, 24, 1)) { normalSource ->
            val normal = AndroidAsShotJpegFrames(context.contentResolver, CancellationSignal()).load(normalSource, 1024) { false }.image
            withOriginal(syntheticDng(32, 24, 6)) { portraitSource ->
                val portrait = AndroidAsShotJpegFrames(context.contentResolver, CancellationSignal()).load(portraitSource, 1024) { false }.image
                assertEquals(normal.height, portrait.width)
                assertEquals(normal.width, portrait.height)
                for (y in 0 until normal.height) for (x in 0 until normal.width) for (channel in 0..2) {
                    val from = (y * normal.width + x) * 3 + channel
                    val to = (x * portrait.width + normal.height - 1 - y) * 3 + channel
                    assertEquals(normal[from], portrait[to], 1e-6f)
                }
            }
        }
    }

    @Test fun encoderTransfersLinearSrgbOnceAndCancelsBeforeAnyCodecWrite() {
        val image = LinearSrgbImage(32, 16, FloatArray(32 * 16 * 3) { .18f })
        val bytes = ByteArrayOutputStream()
        assertTrue(AndroidSrgbJpegEncoder.encode(image, 100, bytes) { false })
        val bitmap = BitmapFactory.decodeByteArray(bytes.toByteArray(), 0, bytes.size())!!
        try {
            assertTrue(bitmap.colorSpace!!.isSrgb)
            val gray = (bitmap.getPixel(16, 8) shr 16) and 255
            assertTrue("sRGB gray=$gray", gray in 116..120)
        } finally { bitmap.recycle() }
        var calls = 0
        val cancelled = ByteArrayOutputStream()
        assertThrows(CancellationException::class.java) { AndroidSrgbJpegEncoder.encode(image, 95, cancelled) { ++calls > 3 } }
        assertEquals(0, cancelled.size())
        assertTrue(AndroidSrgbJpegEncoder.encode(image, 95, ByteArrayOutputStream()) { false })
    }

    private fun withOriginal(bytes: ByteArray, action: (SharedOriginalReference) -> Unit) {
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "synthetic-jpeg-${System.nanoTime()}.dng")
            put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Cinematic Camera Synthetic Tests/")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }) ?: error("Cannot create test-only pending row")
        try {
            resolver.openOutputStream(uri, "w")!!.use { it.write(bytes) }
            val fingerprint = DngContentVerifier.identify(bytes.size.toLong(), { resolver.openInputStream(uri)!! })
            action(SharedOriginalReference(uri.toString(), fingerprint, OriginalGrant.APP_OWNED))
        } finally { check(resolver.delete(uri, null, null) == 1) }
    }

    // Same generated TIFF/DNG layout as adapter tests; no real photo or device metadata.
    private fun syntheticDng(width: Int = 32, height: Int = 24, orientation: Int = 1): ByteArray {
        fun shorts(vararg values: Int) = ByteBuffer.allocate(values.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            .apply { values.forEach { putShort(it.toShort()) } }.array()
        fun longs(vararg values: Int) = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            .apply { values.forEach { putInt(it) } }.array()
        data class Entry(val tag: Int, val type: Int, val count: Int, val bytes: ByteArray)
        val entries = mutableListOf<Entry>()
        fun short(tag: Int, vararg values: Int) { entries += Entry(tag, 3, values.size, shorts(*values)) }
        fun long(tag: Int, value: Int) { entries += Entry(tag, 4, 1, longs(value)) }
        fun ascii(tag: Int, value: String) { entries += Entry(tag, 2, value.length + 1, (value + '\u0000').toByteArray()) }
        long(256, width); long(257, height); short(258, 16); short(259, 1); short(262, 32803)
        ascii(271, "Synthetic"); ascii(272, "Bayer fixture")
        long(273, 0); short(274, orientation); short(277, 1); long(278, height); long(279, width * height * 2)
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
        buffer.putInt(0).position(rawOffset)
        repeat(height) { y -> repeat(width) { x -> buffer.putShort((164 + (x * 65 + y * 25) % 3500).toShort()) } }
        return buffer.array()
    }
}
