package dev.cinematic.p0.photoedit

/**
 * Input may overtake recomposition. Interpret every input against the owner's actual draft.
 * True means the callback was invoked, not that the Session accepted its captured token.
 */
internal fun dispatchPhotoEditBarInput(
    scale: PhotoEditScale,
    expectedKey: Any,
    currentKey: Any,
    enabled: Boolean,
    valueNow: () -> Double,
    input: PhotoEditBarInput?,
    onValueChange: (Double) -> Unit,
): Boolean {
    if (!enabled || expectedKey != currentKey) return false
    val valid = when (input) {
        null -> false
        is PhotoEditBarInput.RelativeDrag -> input.fractionDelta.isFinite() && input.fractionDelta != 0.0
        is PhotoEditBarInput.Step -> input.direction == -1 || input.direction == 1
        is PhotoEditBarInput.SetValue -> scale.contains(input.value)
    }
    if (!valid) return false
    val current = valueNow()
    if (!scale.contains(current)) return false
    val next = when (input) {
        is PhotoEditBarInput.RelativeDrag -> scale.atFraction(scale.fraction(current) + input.fractionDelta)
        is PhotoEditBarInput.Step -> scale.step(current, input.direction) ?: return false
        is PhotoEditBarInput.SetValue -> input.value
        null -> return false
    }
    if (next == current) return false
    onValueChange(next)
    return true
}
