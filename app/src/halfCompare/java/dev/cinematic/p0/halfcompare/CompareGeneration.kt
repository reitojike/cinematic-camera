package dev.cinematic.p0.halfcompare

/** UI-thread-owned generation: stale success/error must never replace a newer selection. */
internal class CompareGeneration {
    private var value = 0L
    fun next(): Long = ++value
    fun accepts(candidate: Long): Boolean = candidate == value
}
