package dev.cinematic.p0.raw

import java.util.concurrent.CancellationException

/** All look stages from an ALREADY developed R7 base. No DNG decode, R7 generation or I/O. */
internal object StageClearLook {
    fun renderFromBase(
        base: LinearSrgbImage,
        faces: FaceMask,
        strength: Double = StageClearV1.DEFAULT_STRENGTH,
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
        val glowed = StageClearGlow.render(base, strength)
        checkpoint()
        return StageClearProtections.finish(glowed, faces) { input ->
            checkpoint()
            val mist = StageClearMist.render(input, originalSkin, strength)
            checkpoint()
            val orton = StageClearOrton.render(mist, originalSkin)
            checkpoint()
            StageClearCharacter.render(orton, strength).also { checkpoint() }
        }.also { checkpoint() }
    }
}
