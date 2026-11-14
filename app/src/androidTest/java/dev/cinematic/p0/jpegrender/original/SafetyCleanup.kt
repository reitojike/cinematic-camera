package dev.cinematic.p0.jpegrender.original

/** Test-only cleanup: attempt every independent action without replacing the first failure. */
internal fun preserveSafetyCleanup(primary: Throwable?, vararg actions: () -> Unit) {
    var first = primary
    for (action in actions) {
        try { action() } catch (error: Throwable) {
            val previous = first
            if (previous == null) first = error
            else if (previous !== error) previous.addSuppressed(error)
        }
    }
    if (primary == null) first?.let { throw it }
}
internal fun requireOwnedSafetyIdentity(actual: Any, expected: Any?) {
    check(expected != null && actual === expected) { "Never cancel an unrelated save" }
}