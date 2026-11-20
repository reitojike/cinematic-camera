package dev.cinematic.p0.photoroll

import android.content.*
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.gpu.preview.GpuInputRequestDeadline
import dev.cinematic.p0.jpegrender.original.*
import dev.cinematic.p0.photoedit.*
import dev.cinematic.p0.rawcapture.*
import dev.cinematic.p0.testing.syntheticTrialDng
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

/** Real Context/base-file lookup and product input; all rows and files are private synthetic data.
 * This verifies callback selection and exact input completion, not GPU presentation or save output. */
class InitialPreviewProductIntegrationAndroidTest {
    // The same product boundary is exercised in both default-OFF and comparison-ON APKs.
    private fun halfCandidate(): InitialPreviewKind? =
        if (dev.cinematic.p0.BuildConfig.DEBUG && dev.cinematic.p0.BuildConfig.INITIAL_HALF_GUIDE)
            InitialPreviewKind.HALF_GUIDE else null

    @Test fun savedThenCacheAndRecipeMismatchSkipsJpegGuide() {
        InitialPreviewProductFixture(true).use { f ->
            f.publish()
            f.expect(InitialPreviewKind.SAVED_JPEG)
            val reads=f.provider.jpegReads.get()
            f.expect(InitialPreviewKind.SAVED_CACHE)
            assertTrue("cache still revalidates publication bytes", f.provider.jpegReads.get()>reads)
            val lookups = f.provider.jpegQueries.get()
            val opens = f.provider.jpegReads.get()
            f.expect(halfCandidate(),PhotoEditValues(.7,0.0,0.0))
            assertEquals(lookups, f.provider.jpegQueries.get())
            assertEquals(opens, f.provider.jpegReads.get())
            f.verify()
        }
    }
    @Test fun firstOpenIgnoresEmbeddedThumbnailAndNeverTouchesSavedProvider() {
        for (embedded in listOf(false, true)) InitialPreviewProductFixture(embedded).use { f ->
            f.expect(halfCandidate())
            assertEquals(0, f.provider.jpegQueries.get())
            assertEquals(0, f.provider.jpegReads.get())
            assertFalse(f.cursor.exists())
            f.verify()
        }
    }
    @Test fun changedJpegAndChangedOriginalNeverReuseOldSavedPixels() {
        InitialPreviewProductFixture(false).use { f ->
            f.publish()
            f.expect(InitialPreviewKind.SAVED_JPEG)
            f.provider.withChangedJpeg {
                f.expect(halfCandidate())
            }
            f.expect(InitialPreviewKind.SAVED_CACHE)
            f.provider.withChangedOriginal {
                val frames=mutableListOf<InitialPreviewFrame>()
                try {
                    val exact=f.open { frames.add(it) }
                    assertEquals(listOfNotNull(halfCandidate()),frames.map { it.kind })
                    assertNotEquals(f.source.fingerprint,exact.original.reference.fingerprint)
                    assertTrue(exact.input.width>0 && exact.input.height>0)
                } finally { frames.forEach { it.bitmap.recycle() } }
            }
            // Source selection cleared the old cache, even after test-owned original restoration.
            f.expect(InitialPreviewKind.SAVED_JPEG)
            f.verify()
        }
    }
    @Test fun sidecarDoesNotRecoverOrUseWarmCacheAndHonorsHalfBuildPolicy() {
        InitialPreviewProductFixture(false).use { f ->
            f.publish()
            f.expect(InitialPreviewKind.SAVED_JPEG)
            val sidecar=File(f.cursor.path+".bak")
            val bytes=byteArrayOf(9,8,7)
            sidecar.writeBytes(bytes)
            val reads=f.provider.jpegReads.get()
            f.expect(halfCandidate())
            assertEquals(reads,f.provider.jpegReads.get())
            assertArrayEquals(bytes,sidecar.readBytes())
            assertTrue(sidecar.delete())
            f.expect(InitialPreviewKind.SAVED_CACHE)
            f.verify()
        }
    }
}

/** Reusable by a separate Surface/UI test. No Reader mock or production seam. */
internal class InitialPreviewProductFixture(embedded: Boolean) : AutoCloseable {
    private val target=InstrumentationRegistry.getInstrumentation().targetContext
    val root=File.createTempFile("initial-product-private-",".tmp",target.cacheDir.canonicalFile)
    val app: Context
    val provider: Provider
    val renderer: ManualTrialPhotoRollRenderer
    val repository: PhotoRollRepository
    val source: SharedOriginalReference
    val geometry: Original24PreflightGeometry
    val request: Original24Request
    val record: JpegPublicationRecord
    private val evidence=linkedMapOf<File,ByteArray>()
    val cursor: File
    init {
        requireGpuInputSafetyFixture()
        check(root.delete() && root.mkdir())
        val raw=syntheticTrialDng(256,192,1)
        val thumbnail=jpeg(16,8)
        val original=if(embedded) appendThumbnail(raw,thumbnail) else raw
        provider=Provider(target,root,original,jpeg(256,192))
        val resolver=ContentResolver.wrap(provider)
        app=object: ContextWrapper(target) {
            override fun getApplicationContext(): Context=this
            override fun getCacheDir(): File=root
            override fun getNoBackupFilesDir(): File=root
            override fun getContentResolver(): ContentResolver=resolver
        }
        // Initialize the real queue while no fabricated publication exists. No save operation.
        AndroidAutoDevelopQueue.get(app).manualOriginalIo { Unit }.get(60,TimeUnit.SECONDS)
        val ready=PhotoRollResources(true,0,false,2L*1024*1024*1024,
            PhotoRollCameraSnapshot(false,false,false,false),true,true)
        DevelopExecution.updateConditions(ready.developConditions(true))
        renderer=ManualTrialPhotoRollRenderer(app,{ready})
        repository=PhotoRollRepository(resolver,target.packageName)
        source=SharedOriginalReference(provider.uri(1).toString(),provider.originalFingerprint,OriginalGrant.APP_OWNED)
        geometry=ParcelFileDescriptor.open(provider.original,ParcelFileDescriptor.MODE_READ_ONLY).use {
            Original24Preflight.inspect(it.fd,CancellationSignal())
        }
        request=Original24Request(ManualOriginalTrialRequests.identity(source),1,source)
        val fingerprint=provider.jpegFingerprint
        val output=JpegOutputDescription(256,192,1,"sRGB",fingerprint.bytes)
        val render=ManualOriginalTrialRender(RenderedJpegRequest(request.captureId,1,source,request.recipe,output),
            OriginalRasterGeometry(256,192,1,256,192,true),fingerprint,.5,0.0)
        record=JpegPublicationRecord(JpegPublicationTarget(request.captureId,1,"1".repeat(32)),render,
            fingerprint,7,JpegPublicationPhase.PUBLISHED,provider.uri(2).toString())
        provider.record=record
        cursor=File(root,"manual-original24-requests/${request.captureId}/manual-request.bin")
    }
    fun publish() {
        val bytes=ManualOriginalTrialRequestCodec.encode(request)
        val directory="raw-captures/${request.captureId}/renders/1"
        evidence[cursor]=bytes
        evidence[File(root,"$directory/manual-request.bin")]=bytes
        evidence[File(root,"$directory/jpeg-publication.bin")]=JpegPublicationCodec.encode(record)
        evidence.forEach { (file,value) -> check(file.parentFile.isDirectory || file.parentFile.mkdirs()); file.writeBytes(value) }
    }
    fun open(values: PhotoEditValues=PhotoAdjustmentPolicy.defaults,
        onGuide: (InitialPreviewFrame)->Unit): ManualTrialPhotoRollRenderer.GpuInput =
        renderer.openGpu(repository,provider.uri(1),false,CancellationSignal(),
            GpuInputRequestDeadline(nowMillis=SystemClock::elapsedRealtime),{ assertNull(it) },onGuide) { values }

    fun expect(kind: InitialPreviewKind?, values: PhotoEditValues=PhotoAdjustmentPolicy.defaults) {
        val frames=mutableListOf<InitialPreviewFrame>()
        try {
            val exact=open(values) { frames.add(it) }
            assertEquals(listOfNotNull(kind),frames.map { it.kind })
            assertEquals(source,exact.original.reference)
            assertEquals(geometry,exact.geometry)
            assertTrue(exact.input.width>0 && exact.input.height>0)
        } finally { frames.forEach { if(!it.bitmap.isRecycled) it.bitmap.recycle() } }
    }
    fun verify() {
        provider.verify()
        evidence.forEach { (file,bytes) -> assertArrayEquals(bytes,file.readBytes()) }
        assertTrue("all provider descriptors closed",provider.readers.none { it.fileDescriptor.valid() })
    }
    override fun close() {
        renderer.discardTransientInputs()
        AndroidManualOriginalTrialSaves.discardPreview(app).get(30,TimeUnit.SECONDS)
        GpuPhotoRollRetiredOwners.releaseBeforeOpen()
        verify()
        check(root.canonicalFile==root.absoluteFile && root.parentFile.canonicalFile==target.cacheDir.canonicalFile)
        check(root.name.startsWith("initial-product-private-"))
        check(root.deleteRecursively())
    }
    internal class Provider(context: Context,root: File,raw: ByteArray,jpeg: ByteArray): ContentProvider() {
        val original=File(root,"synthetic.dng").apply { writeBytes(raw) }
        private val saved=File(root,"synthetic.jpg").apply { writeBytes(jpeg) }
        val originalFingerprint=DngContentVerifier.identify(original.length(),{original.inputStream()})
        val jpegFingerprint=DngContentVerifier.identify(saved.length(),{saved.inputStream()})
        lateinit var record: JpegPublicationRecord
        val jpegQueries=AtomicInteger(); val jpegReads=AtomicInteger(); val writes=AtomicInteger()
        val readers=CopyOnWriteArrayList<ParcelFileDescriptor>()
        private val owner=context.packageName
        init { attachInfo(context,ProviderInfo().apply { authority="media"; exported=false }) }
        fun uri(id: Int): Uri=ContentUris.withAppendedId(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),id.toLong())
        fun verify() {
            DngContentVerifier.verify(originalFingerprint,{original.inputStream()})
            DngContentVerifier.verify(jpegFingerprint,{saved.inputStream()})
            assertEquals(0,writes.get())
        }
        fun withChangedJpeg(action: ()->Unit) {
            val bytes=saved.readBytes()
            val changed=bytes.clone().also { it[it.size/2]=(it[it.size/2].toInt() xor 1).toByte() }
            saved.writeBytes(changed)
            try { action(); assertArrayEquals(changed,saved.readBytes()) }
            finally { saved.writeBytes(bytes) }
        }
        fun withChangedOriginal(action: ()->Unit) {
            val bytes=original.readBytes()
            val changed=bytes.clone()
            val buffer=ByteBuffer.wrap(changed).order(ByteOrder.LITTLE_ENDIAN)
            val count=buffer.getShort(8).toInt() and 0xffff
            val strip=(0 until count).map { 10+it*12 }.first { (buffer.getShort(it).toInt() and 0xffff)==273 }
            val offset=buffer.getInt(strip+8)
            changed[offset]=(changed[offset].toInt() xor 1).toByte()
            original.writeBytes(changed)
            try { action(); assertArrayEquals(changed,original.readBytes()) }
            finally { original.writeBytes(bytes) }
        }
        override fun onCreate()=true
        override fun getType(uri: Uri)=if(uri==uri(1)) "image/x-adobe-dng" else "image/jpeg"
        override fun query(uri: Uri,projection: Array<out String>?,selection: String?,args: Array<out String>?,sort: String?): Cursor {
            val id=ContentUris.parseId(uri).toInt(); check(id in 1..2)
            if(id==2) jpegQueries.incrementAndGet()
            val file=if(id==1) original else saved
            val columns=checkNotNull(projection)
            return MatrixCursor(columns).apply { addRow(Array<Any?>(columns.size) { when(columns[it]) {
                MediaStore.MediaColumns._ID -> id
                OpenableColumns.DISPLAY_NAME -> if(id==1) "SYNTHETIC.dng" else record.target.displayName
                OpenableColumns.SIZE -> file.length()
                MediaStore.MediaColumns.MIME_TYPE -> getType(uri)
                MediaStore.MediaColumns.VOLUME_NAME -> MediaStore.VOLUME_EXTERNAL_PRIMARY
                MediaStore.MediaColumns.RELATIVE_PATH -> if(id==2) record.target.relativePath else "DCIM/"
                MediaStore.MediaColumns.OWNER_PACKAGE_NAME -> owner
                MediaStore.MediaColumns.DATE_MODIFIED -> 100L
                MediaStore.MediaColumns.IS_PENDING,MediaStore.MediaColumns.IS_TRASHED -> 0
                else -> null
            } }) }
        }
        override fun openFile(uri: Uri,mode: String): ParcelFileDescriptor {
            check(mode=="r")
            val id=ContentUris.parseId(uri).toInt(); check(id in 1..2)
            if(id==2) jpegReads.incrementAndGet()
            return ParcelFileDescriptor.open(if(id==1) original else saved,ParcelFileDescriptor.MODE_READ_ONLY).also { readers+=it }
        }
        override fun insert(uri: Uri,values: ContentValues?): Uri? { writes.incrementAndGet(); error("No shared writes") }
        override fun update(uri: Uri,values: ContentValues?,selection: String?,args: Array<out String>?): Int { writes.incrementAndGet(); error("No shared writes") }
        override fun delete(uri: Uri,selection: String?,args: Array<out String>?): Int { writes.incrementAndGet(); error("No shared writes") }
    }
    companion object {
        private fun jpeg(width: Int,height: Int): ByteArray {
            val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
            try { bitmap.eraseColor(Color.GREEN); return ByteArrayOutputStream().also {
                check(bitmap.compress(Bitmap.CompressFormat.JPEG,95,it)) }.toByteArray() }
            finally { bitmap.recycle() }
        }
        private fun appendThumbnail(raw: ByteArray,jpeg: ByteArray): ByteArray {
            val buffer=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            val count=buffer.getShort(8).toInt() and 0xffff
            buffer.putInt(8+2+count*12,raw.size)
            val entries=listOf(254 to 1,256 to 16,257 to 8,258 to 8,259 to 6,262 to 6,
                273 to (raw.size+2+11*12+4),274 to 1,277 to 3,278 to 8,279 to jpeg.size)
            val ifd=ByteBuffer.allocate(2+entries.size*12+4).order(ByteOrder.LITTLE_ENDIAN)
            ifd.putShort(entries.size.toShort())
            entries.forEach { (tag,value) ->
                val short=tag in setOf(258,259,262,274,277)
                ifd.putShort(tag.toShort()).putShort(if(short) 3 else 4).putInt(1)
                if(short) ifd.putShort(value.toShort()).putShort(0) else ifd.putInt(value)
            }
            ifd.putInt(0)
            return raw+ifd.array()+jpeg
        }
    }
}
