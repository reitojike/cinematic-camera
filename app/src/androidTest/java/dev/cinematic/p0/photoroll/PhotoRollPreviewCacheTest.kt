package dev.cinematic.p0.photoroll

import android.graphics.Bitmap
import android.graphics.Color
import android.content.ComponentCallbacks2
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.cinematic.p0.raw.PhotoAdjustments
import dev.cinematic.p0.rawcapture.OriginalFingerprint
import dev.cinematic.p0.rawcapture.OriginalGrant
import dev.cinematic.p0.rawcapture.SharedOriginalReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic pixels only. No provider, native decoder, camera or shared publication. */
@RunWith(AndroidJUnit4::class)
class PhotoRollPreviewCacheTest {
    private val key = PhotoRollPreviewKey(SharedOriginalReference("content://example.test/photo/1",
        OriginalFingerprint(3, "a".repeat(64)), OriginalGrant.USER_SELECTED), .5, PhotoAdjustments.NONE,
        "stage-clear-v1", 1024, PhotoRollPreviewMode.MANUAL_TRIAL)

    @Suppress("DEPRECATION")
    @Test fun activityRecreationRetainsPixelsWhileMemoryPressureEvictsThem() {
        val input = Bitmap.createBitmap(8, 6, Bitmap.Config.ARGB_8888).also { it.eraseColor(Color.BLUE) }
        lateinit var retained: PhotoRollPreviewCache
        try {
            ActivityScenario.launch(PhotoRollActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    retained = ViewModelProvider(activity)[PhotoRollPreviewCache::class.java]
                    retained.remember(retained.begin(key, false), input, null, null)
                    activity.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
                    requireNotNull(retained.copy(retained.begin(key, false))).use {
                        assertEquals(Color.BLUE, it.after.getPixel(0, 0))
                    }
                }
                scenario.recreate()
                scenario.onActivity { activity ->
                    val restored = ViewModelProvider(activity)[PhotoRollPreviewCache::class.java]
                    assertSame(retained, restored)
                    requireNotNull(restored.copy(restored.begin(key, false))).use {
                        assertEquals(Color.BLUE, it.after.getPixel(0, 0))
                    }
                    activity.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
                    assertNull(restored.copy(restored.begin(key, false)))
                }
            }
            assertNull(retained.copy(retained.begin(key, false)))
        } finally { input.recycle() }
    }

    @Test fun copiesOutliveUiRecyclingAndCannotMutateRetainedPixels() {
        val cache = PhotoRollPreviewCache()
        val input = Bitmap.createBitmap(8, 6, Bitmap.Config.ARGB_8888).also { it.eraseColor(Color.RED) }
        try {
            val ticket = cache.begin(key, false)
            cache.remember(ticket, input, null, null)
            input.recycle()
            val first = requireNotNull(cache.copy(ticket))
            assertEquals(Color.RED, first.after.getPixel(0, 0))
            first.close()
            val second = requireNotNull(cache.copy(cache.begin(key, false)))
            assertEquals(Color.RED, second.after.getPixel(0, 0))
            second.close()
        } finally { if (!input.isRecycled) input.recycle(); cache.clear() }
    }

    @Test fun memoryClearAndExplicitRetryFenceLateInsertion() {
        val cache = PhotoRollPreviewCache()
        val input = Bitmap.createBitmap(8, 6, Bitmap.Config.ARGB_8888)
        try {
            val old = cache.begin(key, false)
            cache.clear()
            cache.remember(old, input, null, null)
            assertNull(cache.copy(old))
            val current = cache.begin(key, false)
            assertNull(cache.copy(current))
            cache.remember(current, input, null, null)
            val retry = cache.begin(key, true)
            assertNull(cache.copy(retry))
            assertNull(cache.copy(current))
        } finally { input.recycle(); cache.clear() }
    }

    @Test fun oneBoundedPairFitsButOriginalSizedBitmapIsNeverAdmitted() {
        val cache = PhotoRollPreviewCache()
        val after = Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888)
        val before = Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888)
        val oversized = Bitmap.createBitmap(1025, 1, Bitmap.Config.ARGB_8888)
        try {
            val ticket = cache.begin(key, false)
            cache.remember(ticket, after, before, "synthetic before")
            requireNotNull(cache.copy(ticket)).use { assertTrue(it.bytes <= PhotoRollPreviewCache.MAX_BYTES) }
            val different = cache.begin(key.copy(strength = .6), false)
            cache.remember(different, oversized, null, null)
            assertNull(cache.copy(different))
            assertNull(cache.copy(ticket))
        } finally { after.recycle(); before.recycle(); oversized.recycle(); cache.clear() }
    }
}
