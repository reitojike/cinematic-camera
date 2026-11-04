package dev.cinematic.p0.raw

/** All look stages from an ALREADY developed R7 base. No DNG decode, R7 generation or I/O. */
internal object StageClearLook {
    fun renderFromBase(
        base: LinearSrgbImage,
        faces: FaceMask,
        strength: Double = StageClearV1.DEFAULT_STRENGTH,
    ): StageClearProtections.Result {
        require(strength.isFinite() && strength in 0.0..1.0) { "Strength must be within 0..1" }
        faces.requireAligned(base)
        val originalSkin = StageClearGlow.skinWeight(base)
        val glowed = StageClearGlow.render(base, strength)
        return StageClearProtections.finish(glowed, faces) { input ->
            val mist = StageClearMist.render(input, originalSkin, strength)
            val orton = StageClearOrton.render(mist, originalSkin)
            StageClearCharacter.render(orton, strength)
        }
    }
}
