package dev.cinematic.p0.jpegrender

import dev.cinematic.p0.raw.LinearSrgbImage
import dev.cinematic.p0.raw.PhotoAdjustments
import dev.cinematic.p0.raw.FaceMask
import dev.cinematic.p0.raw.PreparedStageClearGlow
import dev.cinematic.p0.raw.StageClearLook
import dev.cinematic.p0.raw.StageClearProtections
import java.util.concurrent.CancellationException

/** The retained trial foundation: immutable R7 pixels, small Glow fields and checked metadata.
 * Never retains an as-shot image, ROI, face mask, decoder, stream, or an Android display owner. */
internal class TrialPreparedFoundation(
    val base: LinearSrgbImage,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val physicallyOriented: Boolean,
    val externalEv: Double,
    val geometryVersion: String = GEOMETRY_VERSION,
    val adjustments: PhotoAdjustments = PhotoAdjustments(),
) {
    @Volatile private var previewFields: PreparedStageClearGlow? = null
    val pixelBytes: Long get() = base.width.toLong() * base.height * 3 * 4
    val previewFieldBytes: Long get() = previewFields?.retainedFieldBytes ?: 0

    /** This verified foundation is already bound to source, controls and epoch by TrialFoundationReuse. */
    fun renderTrial(faces: FaceMask, strength: Double, shouldStop: () -> Boolean): StageClearProtections.Result {
        fun checkpoint() { if (shouldStop()) throw CancellationException("Trial preview stopped") }
        checkpoint()
        if (base.width < 2 || base.height < 2) {
            return StageClearLook.renderFromBase(base, faces, strength).also { checkpoint() }
        }
        val fields = previewFields ?: synchronized(this) {
            previewFields ?: PreparedStageClearGlow.prepare(base, ::checkpoint).also { previewFields = it }
        }
        checkpoint()
        return fields.render(faces, strength, ::checkpoint).also { checkpoint() }
    }

    internal fun validFor(request: JpegGenerationRequest): Boolean =
        request.recipe.isTrial && JpegGenerationRecipe.fromTrialSnapshot(request.recipe.snapshot) != null &&
            adjustments == request.recipe.adjustments &&
            geometryVersion == GEOMETRY_VERSION && physicallyOriented && externalEv.isFinite() &&
            sourceWidth > 0 && sourceHeight > 0 && base.width in 1..JpegGenerationRecipe.MAX_EDGE &&
            base.height in 1..JpegGenerationRecipe.MAX_EDGE &&
            minOf(base.width, base.height) <= minOf(sourceWidth, sourceHeight) &&
            maxOf(base.width, base.height) <= maxOf(sourceWidth, sourceHeight)

    companion object {
        /** Bump whenever the native physical orientation/bounded scaling or R7 foundation changes. */
        const val GEOMETRY_VERSION = "physical-bounded-1024-r7-final-v1"
    }
}

/** Optional private-session optimization. A lease must recheck its epoch/binding on every access.
 * Completed/manual publication paths must neither read nor mutate this optimization. */
internal interface TrialFoundationReuse {
    fun load(request: JpegGenerationRequest): TrialPreparedFoundation?
    /** Only after successful JPEG inspection, post-source hash and positively closed attempt resources. */
    fun commit(request: JpegGenerationRequest, foundation: TrialPreparedFoundation)
    fun invalidate()
}
