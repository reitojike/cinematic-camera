package dev.cinematic.p0.photoedit

/** Input may overtake recomposition. Read the owner's actual draft, not the last displayed value. */
internal fun dispatchPhotoEditBarInput(
    scale: PhotoEditScale,
    expectedKey: Any,
    currentKey: Any,
    enabled: Boolean,
    valueNow: () -> Double,
    input: PhotoEditBarUpdate,
    onValueChange: (Double) -> Unit,
): Boolean {
    if (!enabled || expectedKey != currentKey) return false
    if (input.stepDirection !in -1..1 || (input.stepDirection != 0 && input.value != null)) return false
    if (input.stepDirection == 0 && input.value?.let(scale::contains) != true) return false
    val current = valueNow()
    if (!scale.contains(current)) return false
    val next = if (input.stepDirection == 0) input.value!! else scale.step(current, input.stepDirection) ?: return false
    if (next == current) return false
    onValueChange(next)
    return true
}
