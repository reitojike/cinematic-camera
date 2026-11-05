package dev.cinematic.p0.rawcapture

import java.io.OutputStream

/** One live session per capture. Receipt methods use capture IO; handoff uses a separate single worker. */
internal class RawCaptureSession(
    private val store: RawCaptureStore,
    private val publication: DngPublicationJournal,
    rows: DngHandoffRows,
) {
    private val outputs = mutableMapOf<RawOutput, RawCaptureOutput>()
    private val handoff = DngHandoffCoordinator(publication, rows, store.handoffSource(publication))
    private var identifying: DngIoResources? = null

    val hasUnreleasedWriters get() = outputs.values.any { !it.isReleased() }
    val hasUnreleasedHandoffResources get() = identifying?.hasOpenResources == true || handoff.hasUnreleasedResources
    fun releaseHandoffResources() {
        identifying?.let { resources ->
            val failure = resources.releaseAll()
            if (!resources.hasOpenResources) identifying = null
            if (failure != null) throw failure
        }
        handoff.releaseResources()
    }

    fun prepare(expected: Set<RawOutput>, developIntent: CaptureDevelopIntent? = null): Map<RawOutput, OutputStream> {
        try {
            val receipt = store.create(expected, developIntent)
            expected.forEach { outputs[it] = store.openOutput(receipt.revision, it) }
            return outputs.toMap()
        } catch (failure: Throwable) {
            try { interruptAndCloseWriters() } catch (cleanup: Throwable) {
                if (failure !== cleanup) failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }

    fun receipt(): CaptureReceipt? = store.readReceipt()

    /** A format-specific CameraX success callback is required; bytes/close alone never imply success. */
    fun received(format: RawOutput): CaptureReceipt {
        val output = requireNotNull(outputs[format]) { "Unexpected camera output" }
        output.close()
        return store.receivedCurrent(output)
    }

    /** Request failure stops further writes but retains every file, including unknown/partial outputs. */
    fun interruptAndCloseWriters(): CaptureReceipt? {
        var receipt: CaptureReceipt? = null
        var failure: Throwable? = null
        try {
            receipt = store.readReceipt()?.let { store.interrupt(it.revision) }
        } catch (t: Throwable) { failure = t }
        outputs.values.forEach {
            try { it.close() } catch (t: Throwable) {
                val previous = failure
                if (previous == null) failure = t else if (previous !== t) previous.addSuppressed(t)
            }
        }
        failure?.let { throw it }
        return receipt
    }

    /** Only for a newly reconstructed process owner. Unrecorded files are never promoted. */
    fun recoverAfterProcessDeath(): CaptureReceipt? = store.recoverAfterProcessDeath()

    fun advanceHandoff(shouldStop: () -> Boolean = { false }): DngPublicationRecord? {
        releaseHandoffResources()
        var receipt = store.readReceipt() ?: return null
        if (RawOutput.DNG !in receipt.receivedBytes) return null
        if (receipt.originalFingerprint == null) {
            val resources = DngIoResources().also { identifying = it }
            var failure: Throwable? = null
            try { receipt = store.verifyOriginalTracked(receipt.revision, resources::input, shouldStop) }
            catch (t: Throwable) { failure = t; throw t }
            finally {
                val cleanup = resources.releaseAll()
                if (!resources.hasOpenResources) identifying = null
                if (cleanup != null) {
                    if (failure == null) throw cleanup else if (failure !== cleanup) failure.addSuppressed(cleanup)
                }
            }
        }
        check(receipt.originalFingerprint != null)
        return handoff.advance(shouldStop)
    }
}
