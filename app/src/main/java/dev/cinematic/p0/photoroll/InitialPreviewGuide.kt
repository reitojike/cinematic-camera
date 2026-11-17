package dev.cinematic.p0.photoroll

import android.content.Context
import android.graphics.Bitmap
import android.os.CancellationSignal
import dev.cinematic.p0.jpegrender.original.Original24PreflightGeometry
import dev.cinematic.p0.photoedit.PhotoAdjustmentPolicy
import dev.cinematic.p0.photoedit.PhotoEditValues
import dev.cinematic.p0.raw.LinearSrgbImage
import dev.cinematic.p0.jpegrender.original.Original24Resources
import dev.cinematic.p0.jpegrender.original.Original24MemoryStage
import dev.cinematic.p0.raw.NativeEmbeddedJpeg
import dev.cinematic.p0.raw.NativeEmbeddedJpegResult
import dev.cinematic.p0.rawcapture.*

/** UI receives exclusive pixels. After publication, discard references rather than recycling a
 * bitmap that Compose/RenderThread may still draw. Unpublished pixels may be recycled by caller. */
internal data class InitialPreviewFrame(val bitmap: Bitmap, val kind: InitialPreviewKind,
    val identity: InitialPreviewIdentity) {
    val label get() = when {
        kind.edited -> "保存済みJPEGの案内表示 · プレビュー準備中"
        kind == InitialPreviewKind.HALF_GUIDE -> "RAW基準の案内画像 · 調整は未反映"
        else -> "撮影時の案内画像 · 調整は未反映"
    }
}

/** One saved-JPEG-only producer. No GPU readback or unsaved edit cache. A hit is used only after
 * the current source, recipe, geometry and publication have been revalidated by read-only lookup. */
internal class InitialPreviewSavedCache {
    private var identity: InitialPreviewIdentity? = null
    private var publication: JpegPublicationRecord? = null
    private var bitmap: Bitmap? = null
    private var epoch = 0L
    @Synchronized fun generation() = epoch
    @Synchronized fun select(key: InitialPreviewIdentity, generation: Long) {
        if (generation == epoch && identity != key) clearPixels()
    }
    @Synchronized fun copy(key: InitialPreviewIdentity, record: JpegPublicationRecord, generation: Long): Bitmap? {
        if (generation != epoch) return null
        if (identity != key || publication != record) { clearPixels(); return null }
        return bitmap?.copy(Bitmap.Config.ARGB_8888, false)
    }
    @Synchronized fun remember(key: InitialPreviewIdentity, record: JpegPublicationRecord,
        value: Bitmap, generation: Long) {
        if (generation != epoch) return
        clearPixels()
        if (value.allocationByteCount > InitialPreviewJpeg.MAX_BITMAP_BYTES) return
        bitmap = value.copy(Bitmap.Config.ARGB_8888, false)
        identity = key; publication = record
    }
    @Synchronized fun clear() { epoch++; clearPixels() }
    private fun clearPixels() { bitmap?.recycle(); bitmap = null; identity = null; publication = null }
}

internal object InitialPreviewGuide {
    /** Synchronous on the existing admitted input owner. Snapshot FD belongs to exact session. */
    fun load(context: Context, original: PhotoRollOriginal, geometry: Original24PreflightGeometry,
        values: PhotoEditValues, fd: Int, signal: CancellationSignal, cache: InitialPreviewSavedCache,
        cacheGeneration: Long, half: (() -> LinearSrgbImage)? = null): InitialPreviewFrame? {
        fun admit() {
            signal.throwIfCanceled()
            Original24Resources.requireAvailable(context, Original24MemoryStage.BOUNDED_PREVIEW, checkMemory = true)
        }
        admit()
        val recipe = ManualOriginalTrialRender.recipe(values.strength, PhotoAdjustmentPolicy.adjustments(values))
        val identity = InitialPreviewIdentity(original.reference, geometry, recipe.schema + ":" + recipe.payload)
        cache.select(identity, cacheGeneration)
        val saved = InitialPreviewSavedJpeg.read(context, original.reference, geometry, recipe) { signal.isCanceled }
        if (saved != null) {
            admit()
            cache.copy(identity, saved.publication, cacheGeneration)?.let {
                return checked(it, InitialPreviewKind.SAVED_CACHE, identity, signal)
            }
            InitialPreviewJpeg.decodeSaved(saved.encoded, saved.width, saved.height, signal)?.let {
                try {
                    Original24Resources.requireAvailable(context, Original24MemoryStage.BOUNDED_PREVIEW, checkMemory = true)
                    cache.remember(identity, saved.publication, it, cacheGeneration) }
                catch (failure: Throwable) { it.recycle(); throw failure }
                return checked(it, InitialPreviewKind.SAVED_JPEG, identity, signal)
            }
        }
        signal.throwIfCanceled()
        admit()
        val embedded = NativeEmbeddedJpeg.extract(fd, original.reference.fingerprint, signal)
        if (embedded.status == NativeEmbeddedJpegResult.Status.FOUND) {
            admit()
            InitialPreviewJpeg.decodeEmbedded(requireNotNull(embedded.jpeg), embedded.width, embedded.height,
                embedded.orientation, signal)?.let {
                return checked(it, InitialPreviewKind.EMBEDDED_JPEG, identity, signal)
            }
        }
        // Local candidate: the same admitted owner serially completes this native work before FULL.
        // Its measured exact-start cost remains a separate acceptance decision; never run in parallel.
        if (half != null) {
            val linear = half()
            signal.throwIfCanceled()
            Original24Resources.requireAvailable(context, Original24MemoryStage.BOUNDED_PREVIEW, checkMemory = true)
            val pixels = TrialDisplayPixels.encode(linear, InitialPreviewJpeg.MAX_EDGE) { signal.isCanceled }
            val bitmap = Bitmap.createBitmap(pixels, linear.width, linear.height, Bitmap.Config.ARGB_8888)
            if (bitmap.allocationByteCount > InitialPreviewJpeg.MAX_BITMAP_BYTES) {
                bitmap.recycle(); error("Half guide exceeded bounded display budget")
            }
            return checked(bitmap, InitialPreviewKind.HALF_GUIDE, identity, signal)
        }
        return null
    }
    private fun checked(bitmap: Bitmap, kind: InitialPreviewKind, identity: InitialPreviewIdentity,
        signal: CancellationSignal): InitialPreviewFrame {
        try { signal.throwIfCanceled(); return InitialPreviewFrame(bitmap, kind, identity) }
        catch (failure: Throwable) { bitmap.recycle(); throw failure }
    }
}

/** One offer per input request. Unpublished pixels are reclaimed; published pixels are UI-owned.
 * close is terminal, including after exact wins. No total-GC-heap bound is claimed. */
internal class InitialPreviewHandoff(private val post: (() -> Unit) -> Boolean,
    private val eligible: () -> Boolean, private val publish: (InitialPreviewFrame) -> Unit) {
    @Volatile private var closed = false
    fun offer(frame: InitialPreviewFrame) {
        if (closed) { frame.bitmap.recycle(); return }
        val queued = try { post {
            if (closed || !eligible()) frame.bitmap.recycle() else publish(frame)
        } } catch (failure: Throwable) { frame.bitmap.recycle(); throw failure }
        if (!queued) frame.bitmap.recycle()
    }
    fun close() { closed = true }
}
