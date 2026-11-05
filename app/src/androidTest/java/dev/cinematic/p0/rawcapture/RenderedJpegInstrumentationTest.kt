package dev.cinematic.p0.rawcapture

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import androidx.test.filters.SdkSuppress
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Explicitly selected tests on a disposable emulator. Creates/deletes only its own synthetic rows. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class RenderedJpegInstrumentationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Before fun requireIsolatedEmulator() {
        check(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        check(Build.HARDWARE in setOf("goldfish", "ranchu") && Build.VERSION.SDK_INT >= 29)
    }

    private fun syntheticJpeg(): ByteArray {
        val bitmap = Bitmap.createBitmap(16, 12, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.rgb(100, 60, 40))
            return ByteArrayOutputStream().also { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }.toByteArray()
        } finally { bitmap.recycle() }
    }
    private fun fingerprint(bytes: ByteArray) = OriginalFingerprint(bytes.size.toLong(),
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })

    @Test fun knownLengthAndSharedSnapshotMatchNativeInspectionWithoutExposingMutableSource() {
        val original = syntheticJpeg()
        val source = original.clone()
        val snapshot = EncodedJpegSnapshot.read({ ByteArrayInputStream(source) }, source.size.toLong()) { false }
        source.fill(0)
        val inspector = AndroidJpegInspector()
        val expected = inspector.inspect({ ByteArrayInputStream(original) }) { false }
        assertEquals(expected, inspector.inspect({ ByteArrayInputStream(original) }, original.size.toLong()) { false })
        assertEquals(expected, inspector.inspectSnapshot(snapshot) { false })
        val bitmap = ImageDecoder.decodeBitmap(snapshot.source())
        try { assertEquals(16, bitmap.width); assertEquals(12, bitmap.height) } finally { bitmap.recycle() }
        assertArrayEquals(original, snapshot.openRead().use { it.readBytes() })
        listOf(original.size.toLong() - 1, original.size.toLong() + 1).forEach { length ->
            assertThrows(IOException::class.java) { inspector.inspect({ ByteArrayInputStream(original) }, length) { false } }
        }
        for (invalid in listOf(original.copyOf(original.size - 2), byteArrayOf(-1, -40, -1, -39))) {
            assertThrows(IOException::class.java) { inspector.inspect({ ByteArrayInputStream(invalid) }, invalid.size.toLong()) { false } }
        }
    }

    @Test fun nativeInspectorAcceptsCompleteSrgbAndRejectsTruncationCorruptionAndExifRotation() {
        val jpeg = syntheticJpeg()
        assertEquals(ExifInterface.ORIENTATION_UNDEFINED, ExifInterface(ByteArrayInputStream(jpeg))
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, -1))
        val inspector = AndroidJpegInspector()
        fun inspect(bytes: ByteArray) = inspector.inspect({ ByteArrayInputStream(bytes) }, { false })
        val observed = inspect(jpeg)
        assertEquals(JpegOutputDescription(16, 12, 1, "sRGB", jpeg.size.toLong()), observed.output)
        assertEquals(fingerprint(jpeg), observed.fingerprint)
        for (invalid in listOf(jpeg.copyOf(jpeg.size - 2), byteArrayOf(-1, -40, -1, -39), "not JPEG".toByteArray())) {
            assertThrows(IOException::class.java) { inspect(invalid) }
        }
        val file = File.createTempFile("jpeg-inspection-", ".jpg", context.cacheDir)
        try {
            file.writeBytes(jpeg)
            ExifInterface(file).apply { setAttribute(ExifInterface.TAG_ORIENTATION, "1"); saveAttributes() }
            assertEquals(1, inspect(file.readBytes()).output.orientation)
            for (orientation in 2..8) {
                ExifInterface(file).apply { setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString()); saveAttributes() }
                assertThrows(IOException::class.java) { inspect(file.readBytes()) }
                val rotated = file.readBytes()
                assertThrows(IOException::class.java) { inspector.inspect({ ByteArrayInputStream(rotated) }, rotated.size.toLong()) { false } }
            }
        } finally { check(file.delete()) }
        assertThrows(IOException::class.java) { inspector.inspect({ ByteArrayInputStream(jpeg) }, { true }) }
    }

    @Test fun nativePendingPublicationReconcilesLostAcknowledgementAndKeepsOriginalAndPrivateJpeg() {
        val id = "jpeg_smoke_${UUID.randomUUID().toString().replace("-", "")}"
        val original = "synthetic source bytes, not a decodable DNG".toByteArray()
        val jpeg = syntheticJpeg()
        val nativeOriginal = MediaStoreDngPendingRows(context)
        val originalTarget = DngPublicationTarget(id, UUID.randomUUID().toString().replace("-", ""))
        val originalUri = requireNotNull(nativeOriginal.insert(originalTarget))
        var jpegUri: String? = null
        val root = File(context.noBackupFilesDir, "jpeg-publication-smoke")
        val files = RenderedJpegFiles(root, id, 1)
        fun journal() = JpegPublicationJournal(id, 1, AndroidAtomicJournalStorage(
            requireNotNull(files.generationRoot().parentFile), "1", CaptureJournalFile.JPEG_PUBLICATION))
        try {
            nativeOriginal.openOutput(originalTarget, originalUri).use { it.write(original) }
            nativeOriginal.publish(originalTarget, originalUri)
            files.createOutput().use { it.write(jpeg) }
            files.sealCompleted()
            val render = CompletedRender(RenderedJpegRequest(id, 1,
                SharedOriginalReference(originalUri, fingerprint(original), OriginalGrant.APP_OWNED),
                RenderRecipeSnapshot("synthetic-v1", "synthetic-only"),
                JpegOutputDescription(16, 12, 1, "sRGB", jpeg.size.toLong())),
                "synthetic-renderer-v1", RenderedResultKind.COMPLETE_STAGE_CLEAR,
                RenderedFaceState.VERIFIED_NONE, RenderedExposureState.SUPPORTED)
            val input = object : RenderedJpegInput {
                override fun openJpeg() = files.openRead()
                override fun openOriginal(reference: SharedOriginalReference): InputStream =
                    requireNotNull(context.contentResolver.openInputStream(Uri.parse(reference.uri)))
            }
            val rows = MediaStoreJpegRows(context)
            val lostAck = object : RenderedJpegRows by rows {
                override fun publish(target: JpegPublicationTarget, uri: String) {
                    rows.publish(target, uri)
                    throw IOException("Synthetic acknowledgement loss")
                }
            }
            fun publisher(provider: RenderedJpegRows) = RecoverablePhotoPublisher(journal(), provider, render,
                setOf(RendererPublicationContract("synthetic-renderer-v1", "synthetic-v1")), input, AndroidJpegInspector(), { true })
            val acknowledgement = assertThrows(IOException::class.java) { publisher(lostAck).advance() }
            assertEquals("Synthetic acknowledgement loss", acknowledgement.message)
            val interrupted = journal().read()!!
            jpegUri = interrupted.uri
            assertEquals(JpegPublicationPhase.PUBLISHING, interrupted.phase)
            assertFalse(rows.inspect(jpegUri!!)!!.pending!!)
            val done = publisher(rows).advance()
            assertEquals(JpegPublicationPhase.PUBLISHED, done.phase)
            assertEquals(jpegUri, done.uri)
            assertEquals(1, rows.find(done.target).size)
            DngContentVerifier.verify(fingerprint(original), { input.openOriginal(render.request.source) })
            assertArrayEquals(jpeg, files.openRead().use { it.readBytes() })
            check(context.contentResolver.delete(Uri.parse(jpegUri), null, null) == 1)
            jpegUri = null
            assertThrows(IOException::class.java) { publisher(rows).advance() }
            assertEquals(0, rows.find(done.target).size)
            assertEquals(JpegPublicationPhase.PUBLISHED, journal().read()!!.phase)
        } finally {
            // Only IDs returned by these test insertions are eligible; no gallery enumeration/delete.
            jpegUri?.let { context.contentResolver.delete(Uri.parse(it), null, null) }
            context.contentResolver.delete(Uri.parse(originalUri), null, null)
            File(root, id).deleteRecursively() // Synthetic files beneath this run's random ID only.
        }
    }

    @Test fun androidFactoryRequiresClosedProducerAndRetainedPublisher() {
        val id = "jpeg_factory_${UUID.randomUUID().toString().replace("-", "")}"
        val operation = AndroidRenderedJpegOperation.get(context, id, 1)
        val duplicate = AndroidRenderedJpegOperation.get(context, id, 1)
        assertSame(operation, duplicate)
        val jpeg = syntheticJpeg()
        val render = CompletedRender(RenderedJpegRequest(id, 1,
            SharedOriginalReference("content://test/original/1", fingerprint(byteArrayOf(1)), OriginalGrant.APP_OWNED),
            RenderRecipeSnapshot("synthetic-v1", "synthetic-only"),
            JpegOutputDescription(16, 12, 1, "sRGB", jpeg.size.toLong())),
            "synthetic-renderer-v1", RenderedResultKind.COMPLETE_STAGE_CLEAR,
            RenderedFaceState.VERIFIED_NONE, RenderedExposureState.SUPPORTED)
        val directory = File(context.noBackupFilesDir, "raw-captures/$id")
        try {
            val writer = operation.createOutput()
            writer.write(jpeg)
            assertThrows(IOException::class.java) { operation.publisher(render, setOf(RendererPublicationContract("synthetic-renderer-v1", "synthetic-v1")), { true }) }
            assertThrows(IOException::class.java) { duplicate.createOutput() }
            assertThrows(IOException::class.java) { duplicate.publisher(render, setOf(RendererPublicationContract("synthetic-renderer-v1", "synthetic-v1")), { true }) }
            writer.close()
            operation.publisher(render, setOf(RendererPublicationContract("synthetic-renderer-v1", "synthetic-v1")), { true })
            assertThrows(IOException::class.java) { operation.publisher(render, setOf(RendererPublicationContract("synthetic-renderer-v1", "synthetic-v1")), { true }) }
            assertThrows(IOException::class.java) { operation.createOutput() }
        } finally { directory.deleteRecursively() }
    }
}
