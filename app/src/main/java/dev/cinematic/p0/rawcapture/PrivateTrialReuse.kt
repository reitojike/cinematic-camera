package dev.cinematic.p0.rawcapture

import dev.cinematic.p0.jpegrender.JpegGenerationRecipe
import dev.cinematic.p0.jpegrender.JpegGenerationRequest
import dev.cinematic.p0.jpegrender.TrialFoundationReuse
import dev.cinematic.p0.jpegrender.TrialPreparedFoundation
import dev.cinematic.p0.raw.PhotoAdjustments

/** Single process opt-in owner. Lock order is registry -> session; a session never calls this registry. */
internal object PrivateTrialReuseRegistry {
    private var active: PrivateTrialReuseSession? = null

    @Synchronized fun openSession(): PrivateTrialReuseSession {
        active?.close()
        return PrivateTrialReuseSession().also { active = it }
    }

    @Synchronized fun invalidateAll() { active?.close(); active = null }

    /** Called inside the existing queue IO allocator. Legacy calls unbind without retaining a lease. */
    @Synchronized fun bind(session: PrivateTrialReuseSession?, request: AutoDevelopRequest): PrivateTrialReuseLease? {
        if (session == null) { active?.unbind(); return null }
        if (active !== session) return null
        return session.bind(request)
    }

    @Synchronized fun forRequest(request: AutoDevelopRequest): PrivateTrialReuseLease? = active?.forRequest(request)
}

/** One immutable base plus one comparison array, shared by all generations/backends in this session.
 * Old leases only retain this owner and tiny metadata, never a previous entry's pixel arrays. */
internal class PrivateTrialReuseSession internal constructor() : AutoCloseable {
    private var stopped = false
    private var epoch = 0L
    private var binding = 0L
    private var bound: AutoDevelopRequest? = null
    private var entry: Entry? = null
    val closed: Boolean @Synchronized get() = stopped

    private data class Key(val source: SharedOriginalReference, val input: String,
        val controls: RenderRecipeSnapshot, val edge: Int, val geometry: String, val adjustments: PhotoAdjustments)
    private class Entry(val key: Key, val foundation: TrialPreparedFoundation, var comparison: IntArray? = null)

    @Synchronized fun bind(request: AutoDevelopRequest): PrivateTrialReuseLease? {
        advanceBinding()
        bound = null
        if (stopped) return null
        val key = key(request) ?: run { clearEntry(); return null }
        if (entry?.key != key) entry = null
        bound = request
        return PrivateTrialReuseLease(this, epoch, binding, request)
    }

    /** Remove admission only; retain the one entry for a later explicit opt-in on the same key. */
    @Synchronized internal fun unbind() { advanceBinding(); bound = null }

    @Synchronized internal fun forRequest(request: AutoDevelopRequest): PrivateTrialReuseLease? =
        if (!stopped && bound == request && key(request) != null)
            PrivateTrialReuseLease(this, epoch, binding, request) else null

    /** Pause/memory clearing invalidates every old lease even if a later request has identical values. */
    @Synchronized fun clear() { clearEntry(); advanceBinding(); bound = null }

    @Synchronized override fun close() {
        stopped = true
        clearEntry(); advanceBinding(); bound = null
    }

    @Synchronized internal fun load(lease: PrivateTrialReuseLease, request: JpegGenerationRequest): TrialPreparedFoundation? {
        if (!accepts(lease, request)) return null
        val cached = entry ?: return null
        return cached.foundation.takeIf { cached.key == key(lease.request) && it.validFor(request) }
    }

    @Synchronized internal fun commit(lease: PrivateTrialReuseLease, request: JpegGenerationRequest,
        foundation: TrialPreparedFoundation) {
        if (!accepts(lease, request) || !foundation.validFor(request) || foundation.pixelBytes > MAX_PIXEL_BYTES) return
        val key = key(lease.request) ?: return
        // No generation-keyed collection and no second base: replacement drops the preceding arrays.
        entry = Entry(key, foundation)
    }

    @Synchronized internal fun comparisonCopy(lease: PrivateTrialReuseLease, width: Int, height: Int): IntArray? {
        if (!accepts(lease)) return null
        val cached = entry ?: return null
        if (cached.key != key(lease.request) || !geometry(cached, width, height)) return null
        return cached.comparison?.clone()
    }

    /** Ownership of a fresh, exclusively owned array transfers here. Caller must never modify it again. */
    @Synchronized internal fun commitComparison(lease: PrivateTrialReuseLease, width: Int, height: Int, pixels: IntArray) {
        if (!accepts(lease)) return
        val cached = entry ?: return
        val count = width.toLong() * height
        if (cached.key != key(lease.request) || !geometry(cached, width, height) || count != pixels.size.toLong() ||
            cached.foundation.pixelBytes + count * 4 > MAX_PIXEL_BYTES) return
        cached.comparison = pixels
    }

    @Synchronized internal fun invalidate(lease: PrivateTrialReuseLease) {
        if (accepts(lease)) { clearEntry(); advanceBinding(); bound = null }
    }

    private fun geometry(cached: Entry, width: Int, height: Int) =
        width in 1..JpegGenerationRecipe.MAX_EDGE && height in 1..JpegGenerationRecipe.MAX_EDGE &&
            width == cached.foundation.base.width && height == cached.foundation.base.height

    private fun accepts(lease: PrivateTrialReuseLease): Boolean = !stopped && lease.epoch == epoch &&
        lease.binding == binding && bound == lease.request && key(lease.request) != null

    private fun accepts(lease: PrivateTrialReuseLease, request: JpegGenerationRequest): Boolean =
        accepts(lease) && request.captureId == lease.request.captureId && request.generation == lease.request.generation &&
            request.source == lease.request.source && request.recipe.snapshot == lease.request.recipe

    private fun clearEntry() { entry = null; epoch++ }
    private fun advanceBinding() { binding++ }

    private fun key(request: AutoDevelopRequest): Key? {
        if (!request.boundedTrial) return null
        val recipe = JpegGenerationRecipe.fromTrialSnapshot(request.recipe) ?: return null
        return Key(request.source, recipe.inputContract, recipe.controls, JpegGenerationRecipe.MAX_EDGE,
            TrialPreparedFoundation.GEOMETRY_VERSION, recipe.adjustments)
    }

    companion object { const val MAX_PIXEL_BYTES = 16L * 1024 * 1024 }
}

/** A binding capability, not an entry snapshot. Closing/rebinding can immediately reclaim pixel arrays. */
internal class PrivateTrialReuseLease internal constructor(private val owner: PrivateTrialReuseSession,
    internal val epoch: Long, internal val binding: Long, internal val request: AutoDevelopRequest) : TrialFoundationReuse {
    override fun load(request: JpegGenerationRequest) = owner.load(this, request)
    override fun commit(request: JpegGenerationRequest, foundation: TrialPreparedFoundation) = owner.commit(this, request, foundation)
    override fun invalidate() = owner.invalidate(this)
    fun comparisonCopy(width: Int, height: Int): IntArray? = owner.comparisonCopy(this, width, height)
    /** Fresh array ownership transfers on success; the caller must never mutate it after this call. */
    fun commitComparison(width: Int, height: Int, pixels: IntArray) = owner.commitComparison(this, width, height, pixels)
}
