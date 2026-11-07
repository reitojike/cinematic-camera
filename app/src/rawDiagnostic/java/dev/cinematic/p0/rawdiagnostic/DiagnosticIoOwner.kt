package dev.cinematic.p0.rawdiagnostic

import dev.cinematic.p0.rawcapture.DngIoResources
import java.io.InputStream
import java.io.OutputStream

/** Harness streams, including report writes, retain failed closes just like production DNG IO. */
internal class DiagnosticIoOwner {
    private val resources = DngIoResources()
    val hasOpenResources get() = resources.hasOpenResources
    fun input(stream: InputStream) = resources.input(stream)
    fun output(stream: OutputStream) = resources.output(stream)
    data class Cleanup(val quiescent: Boolean, val sourceRemoved: Boolean, val closeFailed: Boolean)
    fun finish(attemptQuiescent: Boolean, removeSource: () -> Boolean): Cleanup {
        val closeFailed = resources.releaseAll() != null
        val quiescent = attemptQuiescent && !hasOpenResources
        // Never delete a source while an owner might still be using it.
        val removed = quiescent && try { removeSource() } catch (_: Exception) { false }
        return Cleanup(quiescent, removed, closeFailed)
    }
}
