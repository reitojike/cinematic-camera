package dev.cinematic.p0.photoroll

import android.graphics.Bitmap
import android.util.LruCache
import androidx.lifecycle.ViewModel

/** One <=1024px sRGB display pair, at most 8 MiB. No raw arrays, FD, activity, save owner or grant.
 * ViewModel survives rotation; process death intentionally discards pixels. The caller rechecks the
 * source and recreates the durable save handle before using a hit. Returned pixels are independent. */
internal class PhotoRollPreviewCache : ViewModel() {
    internal class Pixels(val after: Bitmap, val before: Bitmap?, val label: String?) : AutoCloseable {
        val bytes get() = after.allocationByteCount.toLong() + (before?.allocationByteCount ?: 0)
        override fun close() { after.recycle(); if (before !== after) before?.recycle() }
    }
    private val retention = PhotoRollPreviewRetention()
    private val cache = object : LruCache<PhotoRollPreviewKey, Pixels>(MAX_BYTES) {
        override fun sizeOf(key: PhotoRollPreviewKey, value: Pixels) = value.bytes.toInt()
        override fun entryRemoved(evicted: Boolean, key: PhotoRollPreviewKey, oldValue: Pixels, newValue: Pixels?) = oldValue.close()
    }
    private var currentKey: PhotoRollPreviewKey? = null

    @Synchronized fun begin(key: PhotoRollPreviewKey, explicitRetry: Boolean): PhotoRollPreviewRetention.Ticket {
        if (explicitRetry || currentKey != key) clear()
        currentKey = key
        return retention.begin(key)
    }

    @Synchronized fun copy(ticket: PhotoRollPreviewRetention.Ticket): Pixels? {
        if (!retention.accepts(ticket)) return null
        val pixels = cache.get(ticket.key) ?: return null
        return copyPixels(pixels.after, pixels.before, pixels.label).also { if (it == null) clear() }
    }

    @Synchronized fun remember(ticket: PhotoRollPreviewRetention.Ticket, after: Bitmap, before: Bitmap?, label: String?) {
        if (!retention.accepts(ticket)) return
        if (!valid(after) || before?.let { !valid(it) || it === after || it.width != after.width || it.height != after.height } == true) return
        val bytes = after.allocationByteCount.toLong() + (before?.allocationByteCount ?: 0)
        if (bytes > MAX_BYTES) return
        // Evict the prior copy before allocating another pair; the displayed pixels remain UI-owned.
        cache.evictAll()
        val copied = copyPixels(after, before, label) ?: return
        if (copied.bytes > MAX_BYTES) copied.close() else cache.put(ticket.key, copied)
    }

    @Synchronized fun clear() { retention.clear(); currentKey = null; cache.evictAll() }
    override fun onCleared() = clear()

    private fun valid(bitmap: Bitmap) = !bitmap.isRecycled && bitmap.config == Bitmap.Config.ARGB_8888 &&
        bitmap.width in 1..1024 && bitmap.height in 1..1024 && bitmap.colorSpace?.isSrgb == true

    private fun copyPixels(after: Bitmap, before: Bitmap?, label: String?): Pixels? {
        var copiedAfter: Bitmap? = null
        var copiedBefore: Bitmap? = null
        return try {
            copiedAfter = after.copy(Bitmap.Config.ARGB_8888, false) ?: return null
            copiedBefore = before?.copy(Bitmap.Config.ARGB_8888, false)
            if (before != null && copiedBefore == null) { copiedAfter.recycle(); null }
            else Pixels(copiedAfter, copiedBefore, label)
        } catch (failure: Throwable) {
            copiedAfter?.recycle(); copiedBefore?.recycle()
            if (failure is OutOfMemoryError) null else throw failure
        }
    }
    companion object { const val MAX_BYTES = 8 * 1024 * 1024 }
}
