package dev.cinematic.p0

enum class PhotoCallbackOutcome { PARTIAL, COMPLETE, IGNORED }

/** Main-thread aggregation only; publication and staging-file cleanup remain caller-owned. */
class PhotoCallbackGate(expected: Set<Int>) {
    private val expected = expected.toSet().also { require(it.isNotEmpty()) { "No expected photo formats" } }
    private val seen = mutableSetOf<Int>()
    var terminal: Boolean = false; private set
    var publishing: Boolean = false; private set

    fun saved(format: Int): PhotoCallbackOutcome {
        if (terminal) return PhotoCallbackOutcome.IGNORED
        require(format in expected) { "Unexpected camera output format $format" }
        if (!seen.add(format)) return PhotoCallbackOutcome.IGNORED
        if (seen != expected) return PhotoCallbackOutcome.PARTIAL
        terminal = true
        publishing = true
        return PhotoCallbackOutcome.COMPLETE
    }

    fun fail(): Boolean {
        if (terminal) return false
        terminal = true
        return true
    }
}
