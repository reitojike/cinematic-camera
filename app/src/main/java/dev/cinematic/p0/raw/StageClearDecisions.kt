package dev.cinematic.p0.raw

/**
 * Global choices from one completed look render, independent of image dimensions.
 * Keep the face-cut pass separate: scaling the glowed input can change Mist's auto strength.
 * The preview owner scopes these values to a photograph; reuse across edit values is approximate.
 */
internal data class StageClearDecisions(
    val mistAutoStrength: Double,
    val faceCutFactor: Double,
    val faceCutMistAutoStrength: Double? = null,
) {
    init {
        require(mistAutoStrength.isFinite() && mistAutoStrength in 0.0..1.0)
        require(faceCutFactor.isFinite() && faceCutFactor > 0.0 && faceCutFactor <= 1.0)
        require((faceCutFactor < 1.0) == (faceCutMistAutoStrength != null)) {
            "A face-cut rerender requires its own Mist decision"
        }
        require(faceCutMistAutoStrength == null ||
            faceCutMistAutoStrength.isFinite() && faceCutMistAutoStrength in 0.0..1.0)
    }
}
