package dev.cinematic.p0.rawcapture

import dev.cinematic.p0.jpegrender.original.Original24PreflightGeometry
import dev.cinematic.p0.jpegrender.original.Original24Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class InitialPreviewSavedJpegTest {
    @get:Rule val temporary = TemporaryFolder()
    private val source = SharedOriginalReference("content://synthetic/original/1",
        OriginalFingerprint(123, "0".repeat(64)), OriginalGrant.USER_SELECTED)
    private val geometry = Original24PreflightGeometry(64,32,64,32,1,64,32)
    private val request = Original24Request(ManualOriginalTrialRequests.identity(source), 1, source)
    // Metadata/TOCTOU tests use a fake inspector. Real JPEG header/pixel tests are Android-only.
    private val jpeg = byteArrayOf(0xff.toByte(),0xd8.toByte(),0xff.toByte(),0xd9.toByte())
    private val fingerprint = OriginalFingerprint(jpeg.size.toLong(), MessageDigest.getInstance("SHA-256")
        .digest(jpeg).joinToString("") { "%02x".format(it) })
    private val output = JpegOutputDescription(64,32,1,"sRGB",jpeg.size.toLong())
    private val render = ManualOriginalTrialRender(RenderedJpegRequest(request.captureId,1,source,request.recipe,output),
        OriginalRasterGeometry(64,32,1,64,32,true),fingerprint,.5,0.0)
    private val record = JpegPublicationRecord(JpegPublicationTarget(request.captureId,1,"1".repeat(32)),
        render,fingerprint,7,JpegPublicationPhase.PUBLISHED,"content://media/external_primary/images/media/1")
    private inner class Reader : InitialPreviewSavedJpeg.Reader {
        var cursorBytes: ByteArray? = ManualOriginalTrialRequestCodec.encode(request)
        var intentBytes: ByteArray? = cursorBytes
        var journalBytes: ByteArray? = JpegPublicationCodec.encode(record)
        var data = jpeg
        var row: DngPendingRow? = DngPendingRow(requireNotNull(record.uri),record.target.displayName,
            record.target.volume,record.target.relativePath,record.target.mimeType,true,false,false)
        var opened = 0
        var afterRead: () -> Unit = {}
        override fun cursor() = cursorBytes
        override fun intent(generation: Long) = intentBytes
        override fun publication(generation: Long) = journalBytes
        override fun inspect(uri: String) = row
        override fun open(record: JpegPublicationRecord) = data.inputStream().also { opened++; afterRead() }
    }
    private fun read(reader: Reader, expected: Original24PreflightGeometry = geometry,
        stop: () -> Boolean = { false }) = InitialPreviewSavedJpeg.read(source,expected,request.recipe,reader,stop) {
            JpegInspection(output,it.fingerprint)
        }

    @Test fun publishedMatchingSnapshotReturnsBoundEvidence() {
        val reader = Reader()
        val found = requireNotNull(read(reader))
        assertEquals(record,found.publication); assertEquals(request,found.request)
        assertEquals(fingerprint,found.encoded.fingerprint); assertEquals(1,reader.opened)
    }
    @Test fun missingCursorDoesNotReadAnyLaterRecordOrTouchMediaStore() {
        val reader = object : InitialPreviewSavedJpeg.Reader {
            override fun cursor(): ByteArray? = null
            override fun intent(generation: Long): ByteArray? = error("unexpected intent")
            override fun publication(generation: Long): ByteArray? = error("unexpected publication")
            override fun inspect(uri: String): DngPendingRow? = error("unexpected MediaStore inspection")
            override fun open(record: JpegPublicationRecord): java.io.InputStream = error("unexpected JPEG read")
        }
        assertNull(InitialPreviewSavedJpeg.read(source, geometry, request.recipe, reader, { false },
            beforeSnapshot = { error("unexpected JPEG admission") }) {
            error("unexpected JPEG inspection")
        })
    }
    @Test fun savedCandidateRequiresAdmissionBeforeProviderOpenAndPropagatesFailure() {
        val reader = Reader()
        val failure = java.io.IOException("test admission failure")
        val attempt = runCatching {
            InitialPreviewSavedJpeg.read(source, geometry, request.recipe, reader, { false },
                beforeSnapshot = { throw failure }) { error("unexpected JPEG inspection") }
        }
        assertSame(failure, attempt.exceptionOrNull())
        assertEquals(0, reader.opened)
        var admitted = false
        reader.afterRead = { assertTrue("admission happens before JPEG open", admitted) }
        assertNotNull(InitialPreviewSavedJpeg.read(source, geometry, request.recipe, reader, { false },
            beforeSnapshot = { admitted = true }) { JpegInspection(output, it.fingerprint) })
        assertEquals(1, reader.opened)
    }
    @Test fun pendingForeignGenerationAndMissingIntentNeverReadJpeg() {
        val reader = Reader()
        reader.journalBytes = JpegPublicationCodec.encode(record.copy(phase=JpegPublicationPhase.PUBLISHING))
        assertNull(read(reader))
        reader.journalBytes = JpegPublicationCodec.encode(record)
        reader.intentBytes = ManualOriginalTrialRequestCodec.encode(request.copy(generation=2))
        assertNull(read(reader)); reader.intentBytes = null; assertNull(read(reader))
        assertEquals(0,reader.opened)
    }
    @Test fun sourceRecipeAndGeometryMismatchNeverReadJpeg() {
        val reader = Reader()
        reader.cursorBytes = ManualOriginalTrialRequestCodec.encode(request.copy(strength=.6))
        assertNull(read(reader))
        val foreign = source.copy(fingerprint=source.fingerprint.copy(sha256="2".repeat(64)))
        reader.cursorBytes = ManualOriginalTrialRequestCodec.encode(request.copy(source=foreign,
            captureId=ManualOriginalTrialRequests.identity(foreign)))
        assertNull(read(reader))
        reader.cursorBytes = ManualOriginalTrialRequestCodec.encode(request)
        assertNull(read(reader,Original24PreflightGeometry(64,32,32,32,1,32,32)))
        assertEquals(0,reader.opened)
    }
    @Test fun snapshotMutationAndChangedCursorOrPublicationAreMisses() {
        val damaged = Reader().apply { data=byteArrayOf(1,2,3,4) }
        assertNull(read(damaged))
        val changed = Reader().apply { afterRead={cursorBytes=ManualOriginalTrialRequestCodec.encode(request.copy(generation=2))} }
        assertNull(read(changed))
        val publication = Reader().apply { afterRead={journalBytes=JpegPublicationCodec.encode(record.copy(revision=8))} }
        assertNull(read(publication))
    }
    @Test fun rowChangesOrUnknownContractNeverBecomeDisplayable() {
        val rowChanged=Reader().apply { afterRead={row=row?.copy(trashed=true)} }
        assertNull(read(rowChanged))
        val reader=Reader().apply { row=row?.copy(ownedByApp=false) }
        assertNull(read(reader)); assertEquals(0,reader.opened)
        // Unsupported version cannot pass even if the rest is a familiar checksum-protected record.
        val unknown=Reader().apply { journalBytes=journalBytes!!.clone().also { it[7]=4 } }
        assertNull(read(unknown)); assertEquals(0,unknown.opened)
    }
    @Test fun cancellationIsNotHiddenAsAnUnavailableCandidate() {
        val reader=Reader()
        assertTrue(runCatching { read(reader,stop={true}) }.exceptionOrNull() is CancellationException)
        var stopped=false
        reader.afterRead={stopped=true}
        assertTrue(runCatching { read(reader,stop={stopped}) }.exceptionOrNull() is CancellationException)
    }
    @Test fun baseReadNeverRecoversSidecarsAndRejectsOversize() {
        val root=temporary.newFolder("private")
        val directory=File(root,"a").apply { mkdir() }
        val file=File(directory,"journal.bin").apply { writeBytes(byteArrayOf(1,2,3)) }
        assertArrayEquals(byteArrayOf(1,2,3),InitialPreviewSavedJpeg.readBase(root,"a/journal.bin",3))
        assertNull(InitialPreviewSavedJpeg.readBase(root,"a/journal.bin",2))
        for (suffix in listOf(".new",".bak")) {
            val sidecar=File(file.path+suffix).apply { writeBytes(byteArrayOf(4)) }
            assertNull(InitialPreviewSavedJpeg.readBase(root,"a/journal.bin",3))
            assertArrayEquals(byteArrayOf(1,2,3),file.readBytes())
            assertArrayEquals(byteArrayOf(4),sidecar.readBytes())
            assertTrue(sidecar.delete())
        }
        assertNull(InitialPreviewSavedJpeg.readBase(root,"a/missing.bin",3))
        val outside = File(root.parentFile, "outside.bin").apply { writeBytes(byteArrayOf(7)) }
        assertNull(InitialPreviewSavedJpeg.readBase(root, "../outside.bin", 3))
        assertNull(InitialPreviewSavedJpeg.readBase(root, outside.absolutePath, 3))
        assertArrayEquals(byteArrayOf(7), outside.readBytes())
    }
}
