package dev.cinematic.p0.raw

import java.util.concurrent.CancellationException

/** All look stages from an ALREADY developed R7 base. No DNG decode, R7 generation or I/O. */
internal object StageClearLook {
    fun renderFromBase(
        base: LinearSrgbImage,
        faces: FaceMask,
        strength: Double = StageClearV1.DEFAULT_STRENGTH,
        shouldStop: () -> Boolean = { false },
    ): StageClearProtections.Result = renderFromBase(base, faces, strength, null, shouldStop)

    fun renderFromBase(
        base: LinearSrgbImage,
        faces: FaceMask,
        strength: Double = StageClearV1.DEFAULT_STRENGTH,
        decisions: StageClearDecisions?,
        shouldStop: () -> Boolean = { false },
    ): StageClearProtections.Result {
        require(strength.isFinite() && strength in 0.0..1.0) { "Strength must be within 0..1" }
        faces.requireAligned(base)
        fun checkpoint() {
            if (shouldStop()) throw CancellationException("Stage Clear rendering stopped")
        }
        // Cooperative boundaries only; a running colour/blur kernel is not interrupted.
        checkpoint()
        val originalSkin = StageClearGlow.skinWeight(base)
        checkpoint()
        val glowed = StageClearGlow.render(base, strength, originalSkin)
        checkpoint()
        return renderFromGlowed(glowed, originalSkin, faces, strength, decisions, ::checkpoint)
    }

    /** Entry-only decision sharing; all pixel kernels and cooperative boundaries stay unchanged. */
    internal fun renderFromGlowed(
        glowed: LinearSrgbImage,
        originalSkin: FloatPlane,
        faces: FaceMask,
        strength: Double,
        decisions: StageClearDecisions?,
        checkpoint: () -> Unit,
    ): StageClearProtections.Result = renderFromGlowed(glowed, originalSkin, faces, strength, decisions, null, checkpoint)

    internal fun renderFromGlowed(
        glowed: LinearSrgbImage, originalSkin: FloatPlane, faces: FaceMask,
        strength: Double, decisions: StageClearDecisions?, timing: RawPreviewTiming?,
        checkpoint: () -> Unit,
    ): StageClearProtections.Result = renderFromGlowed(glowed, originalSkin, faces, strength, decisions, timing, null, checkpoint)

    internal fun renderFromGlowed(glowed: LinearSrgbImage, originalSkin: FloatPlane, faces: FaceMask,
        strength: Double, decisions: StageClearDecisions?, timing: RawPreviewTiming?,
        execution: PreviewPixelExecution?, checkpoint: () -> Unit,
    ): StageClearProtections.Result {
        var firstAutoStrength: Double? = null
        var faceCutAutoStrength: Double? = null
        timing?.enter(RawPreviewTiming.Stage.PROTECTIONS)
        val result = StageClearProtections.finish(glowed, faces, decisions?.faceCutFactor) { input ->
            checkpoint()
            val isFirstPass = firstAutoStrength == null
            timing?.enter(RawPreviewTiming.Stage.MIST_AUTO)
            val autoStrength = if (decisions == null) StageClearMist.autoStrength(input) else {
                if (isFirstPass) decisions.mistAutoStrength else requireNotNull(decisions.faceCutMistAutoStrength)
            }
            if (isFirstPass) firstAutoStrength = autoStrength else faceCutAutoStrength = autoStrength
            timing?.enter(RawPreviewTiming.Stage.MIST)
            val mist = StageClearMist.render(input, originalSkin, strength, autoStrength)
            checkpoint()
            timing?.enter(RawPreviewTiming.Stage.ORTON)
            val orton = StageClearOrton.render(mist, originalSkin, execution)
            checkpoint()
            timing?.enter(RawPreviewTiming.Stage.CHARACTER)
            StageClearCharacter.render(orton, strength, execution).also {
                checkpoint()
                timing?.enter(RawPreviewTiming.Stage.PROTECTIONS)
            }
        }.also { checkpoint() }
        return result.copy(decisions = StageClearDecisions(
            requireNotNull(firstAutoStrength), result.faceCutFactor, faceCutAutoStrength,
        ))
    }
}
