package dev.cinematic.p0.rawcapture

import android.graphics.Bitmap
import android.graphics.Color
import android.system.Os
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.jpegrender.original.Original24PreflightGeometry
import dev.cinematic.p0.jpegrender.original.Original24Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

/** In-memory generated JPEG + journals only. No shared rows, user original, Activity, or save owner. */
class InitialPreviewSavedJpegAndroidTest {
    private fun jpeg(): ByteArray {
        val bitmap=Bitmap.createBitmap(64,32,Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.GREEN)
            return ByteArrayOutputStream().also { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG,95,it)) }.toByteArray()
        } finally { bitmap.recycle() }
    }
    @Test fun trustedRootAliasReadsJournalButRejectsDescendantRedirectAndSidecars() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val privateRoot = File.createTempFile("saved-guide-alias-", ".test", target.cacheDir.canonicalFile)
        assertTrue(privateRoot.delete() && privateRoot.mkdir())
        val root = File(privateRoot, "real").apply { check(mkdir()) }
        val alias = File(privateRoot, "alias")
        val directory = File(root, "request").apply { check(mkdir()) }
        val journal = File(directory, "manual-request.bin")
        val outside = File(privateRoot, "outside").apply { check(mkdir()) }
        val foreign = File(outside, "manual-request.bin")
        val data = byteArrayOf(1, 2, 3)
        val path = "request/manual-request.bin"
        val redirected = File(root, "redirected")
        val linkedLeaf = File(directory, "linked.bin")
        try {
            Os.symlink(root.path, alias.path)
            assertNotEquals(alias.absoluteFile, alias.canonicalFile)
            assertEquals(root, alias.canonicalFile)
            // Cold miss through the alias is still a no-op.
            assertNull(InitialPreviewSavedJpeg.readBase(alias, path, 3))
            journal.writeBytes(data)
            foreign.writeBytes(data)
            assertArrayEquals(data, InitialPreviewSavedJpeg.readBase(root, path, 3))
            assertArrayEquals(data, InitialPreviewSavedJpeg.readBase(alias, path, 3))

            Os.symlink(outside.path, redirected.path)
            Os.symlink(foreign.path, linkedLeaf.path)
            assertNull(InitialPreviewSavedJpeg.readBase(alias, "redirected/manual-request.bin", 3))
            assertNull(InitialPreviewSavedJpeg.readBase(alias, "request/linked.bin", 3))
            assertNull(InitialPreviewSavedJpeg.readBase(alias, "../outside/manual-request.bin", 3))
            assertNull(InitialPreviewSavedJpeg.readBase(alias, foreign.absolutePath, 3))
            assertArrayEquals(data, foreign.readBytes())

            for (suffix in listOf(".bak", ".new")) {
                val sidecar = File(journal.path + suffix).apply { writeBytes(byteArrayOf(4)) }
                assertNull(InitialPreviewSavedJpeg.readBase(alias, path, 3))
                assertArrayEquals(data, journal.readBytes())
                assertArrayEquals(byteArrayOf(4), sidecar.readBytes())
                assertTrue(sidecar.delete())
            }
        } finally {
            // Never recurse through a test-owned symlink.
            linkedLeaf.delete()
            redirected.delete()
            alias.delete()
            check(privateRoot.deleteRecursively())
        }
    }

    @Test fun publishedSnapshotIsInspectedWithoutReopeningTheJpeg() {
        check(jpeg(), expected=true, wrongHeader=false)
    }
    @Test fun savedRecordCannotRelabelJpegHeaderGeometry() {
        check(jpeg(), expected=false, wrongHeader=true)
    }
    private fun check(bytes: ByteArray, expected: Boolean, wrongHeader: Boolean) {
        val source=SharedOriginalReference("content://synthetic/original/1",OriginalFingerprint(123,"0".repeat(64)),OriginalGrant.USER_SELECTED)
        val request=Original24Request(ManualOriginalTrialRequests.identity(source),1,source)
        val width=if(wrongHeader) 32 else 64
        val geometry=Original24PreflightGeometry(64,32,width,32,1,width,32)
        val fingerprint=OriginalFingerprint(bytes.size.toLong(),MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) })
        val output=JpegOutputDescription(width,32,1,"sRGB",bytes.size.toLong())
        val render=ManualOriginalTrialRender(RenderedJpegRequest(request.captureId,1,source,request.recipe,output),
            OriginalRasterGeometry(width,32,1,width,32,true),fingerprint,.5,0.0)
        val record=JpegPublicationRecord(JpegPublicationTarget(request.captureId,1,"1".repeat(32)),render,fingerprint,
            7,JpegPublicationPhase.PUBLISHED,"content://media/external_primary/images/media/1")
        val requestBytes=ManualOriginalTrialRequestCodec.encode(request)
        val journal=JpegPublicationCodec.encode(record)
        var opens=0
        val reader=object: InitialPreviewSavedJpeg.Reader {
            override fun cursor()=requestBytes.clone()
            override fun intent(generation: Long)=requestBytes.clone()
            override fun publication(generation: Long)=journal.clone()
            override fun inspect(uri: String)=DngPendingRow(requireNotNull(record.uri),record.target.displayName,
                record.target.volume,record.target.relativePath,record.target.mimeType,true,false,false)
            override fun open(record: JpegPublicationRecord)=bytes.inputStream().also { opens++ }
        }
        val result=InitialPreviewSavedJpeg.read(source,geometry,request.recipe,reader,{false}) {
            AndroidJpegInspector().inspectSnapshot(it) { false }
        }
        assertEquals(expected,result != null)
        assertEquals(1,opens)
        assertArrayEquals(requestBytes,reader.cursor())
        assertArrayEquals(journal,reader.publication(1))
    }
}
