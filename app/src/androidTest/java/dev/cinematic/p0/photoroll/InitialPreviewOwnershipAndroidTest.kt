package dev.cinematic.p0.photoroll

import android.graphics.Bitmap
import android.graphics.Color
import dev.cinematic.p0.jpegrender.original.Original24PreflightGeometry
import dev.cinematic.p0.jpegrender.original.Original24Request
import dev.cinematic.p0.rawcapture.*
import org.junit.Assert.*
import org.junit.Test

/** Synthetic bitmaps only. These tests exercise owners, not Compose/SurfaceView drawing. */
class InitialPreviewOwnershipAndroidTest {
    private val source=SharedOriginalReference("content://synthetic/original/1",
        OriginalFingerprint(123,"0".repeat(64)),OriginalGrant.USER_SELECTED)
    private val geometry=Original24PreflightGeometry(64,32,64,32,1,64,32)
    private val request: Original24Request=Original24Request(ManualOriginalTrialRequests.identity(source),1,source)
    private val identity: InitialPreviewIdentity=InitialPreviewIdentity(source,geometry,request.recipe.schema+":"+request.recipe.payload)
    private val fingerprint=OriginalFingerprint(4,"1".repeat(64))
    private val render=ManualOriginalTrialRender(RenderedJpegRequest(request.captureId,1,source,request.recipe,
        JpegOutputDescription(64,32,1,"sRGB",4)),OriginalRasterGeometry(64,32,1,64,32,true),fingerprint,.5,0.0)
    private val record=JpegPublicationRecord(JpegPublicationTarget(request.captureId,1,"2".repeat(32)),render,
        fingerprint,7,JpegPublicationPhase.PUBLISHED,"content://media/external_primary/images/media/1")
    private fun bitmap()=Bitmap.createBitmap(64,32,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
    private fun frame()=InitialPreviewFrame(bitmap(),InitialPreviewKind.SAVED_JPEG,identity)

    @Test fun cacheOwnsPrivateCopyAndClearDoesNotRecycleUiOrProducerPixels() {
        val cache=InitialPreviewSavedCache()
        val producer=bitmap()
        var ui: Bitmap?=null
        try {
            val generation=cache.generation()
            cache.remember(identity,record,producer,generation)
            producer.eraseColor(Color.RED)
            ui=requireNotNull(cache.copy(identity,record,generation))
            assertNotSame(producer,ui)
            assertEquals(Color.GREEN,ui.getPixel(0,0))
            cache.clear()
            assertFalse(ui.isRecycled); assertFalse(producer.isRecycled)
            assertEquals(Color.GREEN,ui.getPixel(0,0))
            assertNull(cache.copy(identity,record,cache.generation()))
        } finally { cache.clear(); ui?.recycle(); producer.recycle() }
    }
    @Test fun changedSourceRecipeGeometryOrPublicationMisses() {
        val producer=bitmap()
        val keys=listOf(identity.copy(source=source.copy(fingerprint=source.fingerprint.copy(sha256="3".repeat(64)))),
            identity.copy(canonicalRecipe="changed-recipe"),
            identity.copy(geometry=Original24PreflightGeometry(64,32,32,32,1,32,32,1,0)))
        try {
            for(key in keys) {
                val cache=InitialPreviewSavedCache()
                try {
                    val generation=cache.generation()
                    cache.remember(identity,record,producer,generation)
                    assertNull(cache.copy(key,record,generation))
                    assertNull(cache.copy(identity,record,generation))
                } finally { cache.clear() }
            }
            val cache=InitialPreviewSavedCache()
            try {
                val generation=cache.generation()
                cache.remember(identity,record,producer,generation)
                assertNull(cache.copy(identity,record.copy(revision=8),generation))
            } finally { cache.clear() }
        } finally { producer.recycle() }
    }
    @Test fun selectionInvalidatesPreviousPixelsAndTrimRejectsLateRemember() {
        val cache=InitialPreviewSavedCache()
        val producer=bitmap()
        try {
            val generation=cache.generation()
            cache.remember(identity,record,producer,generation)
            cache.select(identity.copy(canonicalRecipe="another"),generation)
            assertNull(cache.copy(identity,record,generation))
            cache.clear()
            cache.remember(identity,record,producer,generation)
            assertNull(cache.copy(identity,record,cache.generation()))
            assertNull(cache.copy(identity,record,generation))
            assertFalse(producer.isRecycled)
        } finally { cache.clear(); producer.recycle() }
    }
    @Test fun oversizedBitmapIsNeverRetainedByCache() {
        val cache=InitialPreviewSavedCache()
        val large=Bitmap.createBitmap(1025,1024,Bitmap.Config.ARGB_8888)
        try {
            assertTrue(large.allocationByteCount>InitialPreviewJpeg.MAX_BITMAP_BYTES)
            cache.remember(identity,record,large,cache.generation())
            assertNull(cache.copy(identity,record,cache.generation()))
            assertFalse(large.isRecycled)
        } finally { cache.clear(); large.recycle() }
    }
    @Test fun exactWinningBeforeQueuedDeliveryRecyclesUnpublishedGuide() {
        val queue=ArrayDeque<() -> Unit>()
        var exact=false
        var publications=0
        val handoff=InitialPreviewHandoff({ queue.addLast(it); true },{ !exact },{ publications++ })
        val frame=frame()
        handoff.offer(frame)
        assertFalse(frame.bitmap.isRecycled)
        exact=true
        queue.removeFirst().invoke()
        assertTrue(frame.bitmap.isRecycled); assertEquals(0,publications)
        handoff.close()
    }
    @Test fun closeBeforeOrAfterQueueingRecyclesOnlyUnpublishedPixels() {
        val queue=ArrayDeque<() -> Unit>()
        var publications=0
        val handoff=InitialPreviewHandoff({ queue.addLast(it); true },{ true },{ publications++ })
        val queued=frame()
        handoff.offer(queued); handoff.close(); queue.removeFirst().invoke()
        assertTrue(queued.bitmap.isRecycled)
        val late=frame()
        handoff.offer(late)
        assertTrue(late.bitmap.isRecycled)
        assertEquals(0,queue.size); assertEquals(0,publications)
    }
    @Test fun refusedPostRecyclesAndSuccessfulPublishTransfersOwnership() {
        val rejected=frame()
        InitialPreviewHandoff({false},{true},{fail("Unexpected publish")}).offer(rejected)
        assertTrue(rejected.bitmap.isRecycled)
        val queue=ArrayDeque<() -> Unit>()
        var published: InitialPreviewFrame?=null
        val handoff=InitialPreviewHandoff({ queue.addLast(it); true },{true},{published=it})
        val accepted=frame()
        try {
            handoff.offer(accepted); queue.removeFirst().invoke()
            assertSame(accepted,published)
            handoff.close()
            assertFalse(accepted.bitmap.isRecycled)
            assertEquals(Color.GREEN,accepted.bitmap.getPixel(0,0))
        } finally { handoff.close(); accepted.bitmap.recycle() }
    }
    @Test fun oldSessionCallbackCannotPublishIntoTheNewSession() {
        val queue=ArrayDeque<() -> Unit>()
        val published=mutableListOf<InitialPreviewFrame>()
        var currentSession=1
        val old=InitialPreviewHandoff({ queue.addLast(it); true },{currentSession==1},{published.add(it)})
        val stale=frame()
        old.offer(stale)
        old.close(); currentSession=2
        val next=InitialPreviewHandoff({ queue.addLast(it); true },{currentSession==2},{published.add(it)})
        val fresh=frame()
        try {
            next.offer(fresh)
            queue.removeFirst().invoke(); queue.removeFirst().invoke()
            assertTrue(stale.bitmap.isRecycled)
            assertEquals(listOf(fresh),published)
            next.close()
            assertFalse(fresh.bitmap.isRecycled)
        } finally { old.close(); next.close(); fresh.bitmap.recycle() }
    }
}
