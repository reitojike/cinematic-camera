package dev.cinematic.p0.rawcapture

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/** Metadata type is shared with ORIGINAL rows, but every mutating method requires a JPEG target. No delete API. */
internal interface RenderedJpegRows {
    fun insert(target: JpegPublicationTarget): String?
    fun find(target: JpegPublicationTarget): List<DngPendingRow>
    fun inspect(uri: String): DngPendingRow?
    fun openRead(target: JpegPublicationTarget, uri: String, pending: Boolean): InputStream
    fun openOutput(target: JpegPublicationTarget, uri: String): OutputStream
    fun publish(target: JpegPublicationTarget, uri: String)
}

/**
 * Completed output -> one recoverable MediaStore operation. No renderer/scheduler/camera activation.
 * One retained owner per capture+generation, on a capture-priority IO executor. Cancel is cooperative.
 * isCurrent must be authoritative for this request; integration must serialize generation changes
 * with admission. External original changes after the last read cannot be excluded atomically.
 */
internal class RecoverablePhotoPublisher(
    private val journal: JpegPublicationJournal,
    private val rows: RenderedJpegRows,
    private val render: CompletedRender,
    supportedContracts: Set<RendererPublicationContract>,
    private val input: RenderedJpegInput,
    private val inspector: JpegInspector,
    private val isCurrent: () -> Boolean,
) {
    private val supportedContracts = supportedContracts.toSet()
    private val active = AtomicBoolean(false)
    @Volatile private var held: DngIoResources? = null
    val hasUnreleasedResources get() = held?.hasOpenResources == true

    fun releaseResources() = exclusive { releaseHeld() }
    fun advance(shouldStop: () -> Boolean = { false }): JpegPublicationRecord = exclusive {
        releaseHeld()
        val resources = DngIoResources().also { held = it }
        var failure: Throwable? = null
        try { run(resources, shouldStop) }
        catch (t: Throwable) { failure = t; throw t }
        finally {
            val cleanup = resources.releaseAll()
            if (!resources.hasOpenResources) held = null
            if (cleanup != null) {
                if (failure != null) { if (failure !== cleanup) failure.addSuppressed(cleanup) } else throw cleanup
            }
        }
    }

    private fun run(resources: DngIoResources, stop: () -> Boolean): JpegPublicationRecord {
        render.requirePublishable(supportedContracts)
        var current = journal.read()
        if (current != null && current.render != render) throw CaptureJournalConflict()
        if (current?.phase == JpegPublicationPhase.UNKNOWN) return current
        if (current?.phase == JpegPublicationPhase.PUBLISHED) {
            verifyPublic(current, resources, stop) // Never recreate a later deleted/changed shared row.
            return current
        }
        checkContinue(stop)
        if (current == null) {
            verifyOriginal(resources, stop)
            val inspection = inspector.inspect({ resources.input(input.openJpeg()) }, render.request.output.bytes, stop)
            if (inspection.output != render.request.output || inspection.fingerprint.bytes != inspection.output.bytes) {
                throw IOException("Completed JPEG metadata does not match the request")
            }
            checkReleased(resources)
            checkContinue(stop)
            current = journal.prepare(render, inspection.fingerprint)
        }
        var record = requireNotNull(current)
        if (record.phase == JpegPublicationPhase.PREPARED) {
            checkContinue(stop)
            verifyPrivate(record, resources, stop)
            verifyOriginal(resources, stop)
            checkContinue(stop)
            record = journal.advance(record.revision, record.phase, JpegPublicationPhase.INSERTING)
            // Do not deliberately leave a durable INSERTING intent without issuing its one insert.
            val uri = try { rows.insert(record.target) } catch (_: Exception) { return unknown(record) }
            if (uri == null || !isDngRowUri(uri)) return unknown(record)
            val row = try { rows.inspect(uri) } catch (_: Exception) { return journal.unknown(record.revision, uri) }
            if (row == null || row.uri != uri || !record.target.accepts(row)) return journal.unknown(record.revision, uri)
            record = journal.recordRow(record.revision, uri)
        }
        if (record.phase == JpegPublicationPhase.INSERTING) {
            checkContinue(stop)
            val candidates = try { rows.find(record.target) } catch (_: Exception) { return unknown(record) }
            val row = candidates.singleOrNull()
            if (row == null || !record.target.accepts(row)) return unknown(record)
            record = journal.recordRow(record.revision, row.uri)
        }
        var firstCopy = false
        if (record.phase == JpegPublicationPhase.ROW_RECORDED) {
            if (!matches(record, true)) return unknown(record)
            verifyPrivate(record, resources, stop)
            verifyOriginal(resources, stop)
            checkContinue(stop)
            record = journal.advance(record.revision, record.phase, JpegPublicationPhase.COPYING)
            firstCopy = true
        }
        if (record.phase == JpegPublicationPhase.COPYING) {
            if (!matches(record, true)) return unknown(record)
            val complete = if (firstCopy) false else try { verifyRow(record, true, resources, stop); true }
            catch (changed: DngContentChanged) {
                if (resources.hasOpenResources || changed.suppressed.isNotEmpty()) throw changed
                false
            }
            checkReleased(resources)
            if (!complete) {
                verifyOriginal(resources, stop)
                checkContinue(stop)
                if (!matches(record, true)) return unknown(record)
                DngContentVerifier.copy(record.fingerprint, { resources.input(input.openJpeg()) },
                    { resources.output(rows.openOutput(record.target, requireNotNull(record.uri))) }, { stopped(stop) })
            }
            if (!matches(record, true)) return unknown(record)
            verifyRow(record, true, resources, stop)
            checkContinue(stop)
            checkReleased(resources)
            record = journal.advance(record.revision, record.phase, JpegPublicationPhase.BYTES_COMPLETE)
        }
        if (record.phase == JpegPublicationPhase.BYTES_COMPLETE) {
            if (!matches(record, true)) return unknown(record)
            try { verifyRow(record, true, resources, stop) } catch (_: DngContentChanged) { return unknown(record) }
            checkContinue(stop)
            record = journal.advance(record.revision, record.phase, JpegPublicationPhase.PUBLISHING)
        }
        if (record.phase == JpegPublicationPhase.PUBLISHING) {
            val row = inspect(record) ?: return unknown(record)
            val pending = row.pending ?: return unknown(record)
            if (!record.target.accepts(row, pending)) return unknown(record)
            try { verifyRow(record, pending, resources, stop) } catch (_: DngContentChanged) { return unknown(record) }
            if (pending) {
                verifyOriginal(resources, stop) // Last full source check before pending release.
                checkReleased(resources)
                checkContinue(stop)
                rows.publish(record.target, requireNotNull(record.uri)) // Lost acknowledgement stays PUBLISHING.
                if (matches(record, true)) throw IOException("JPEG publication unconfirmed")
                if (!matches(record, false)) return unknown(record)
                try { verifyRow(record, false, resources, stop) } catch (_: DngContentChanged) { return unknown(record) }
            }
            checkContinue(stop)
            checkReleased(resources)
            record = journal.advance(record.revision, record.phase, JpegPublicationPhase.PUBLISHED)
        }
        verifyPublic(record, resources, stop)
        return record
    }

    private fun verifyOriginal(resources: DngIoResources, stop: () -> Boolean) = DngContentVerifier.verify(
        render.request.source.fingerprint, { resources.input(input.openOriginal(render.request.source)) }, { stopped(stop) })
    private fun verifyPrivate(record: JpegPublicationRecord, resources: DngIoResources, stop: () -> Boolean) =
        DngContentVerifier.verify(record.fingerprint, { resources.input(input.openJpeg()) }, { stopped(stop) })
    private fun verifyRow(record: JpegPublicationRecord, pending: Boolean, resources: DngIoResources, stop: () -> Boolean) =
        DngContentVerifier.verify(record.fingerprint,
            { resources.input(rows.openRead(record.target, requireNotNull(record.uri), pending)) }, stop)
    private fun verifyPublic(record: JpegPublicationRecord, resources: DngIoResources, stop: () -> Boolean) {
        if (record.phase != JpegPublicationPhase.PUBLISHED || !matches(record, false)) throw IOException("Published JPEG unavailable or changed")
        verifyRow(record, false, resources, stop)
    }
    private fun inspect(record: JpegPublicationRecord): DngPendingRow? = try {
        rows.inspect(requireNotNull(record.uri))?.takeIf { it.uri == record.uri }
    } catch (_: Exception) { null }
    private fun matches(record: JpegPublicationRecord, pending: Boolean) = inspect(record)?.let { record.target.accepts(it, pending) } == true
    private fun unknown(record: JpegPublicationRecord) = journal.unknown(record.revision, record.uri)
    private fun stopped(stop: () -> Boolean) = stop() || !isCurrent()
    private fun checkContinue(stop: () -> Boolean) { if (stopped(stop)) throw IOException("JPEG publication stopped or superseded") }
    private fun checkReleased(resources: DngIoResources) { if (resources.hasOpenResources) throw IOException("JPEG resources remain open") }
    private fun releaseHeld() {
        val resources = held ?: return
        val failure = resources.releaseAll()
        if (!resources.hasOpenResources) held = null
        if (failure != null) throw failure
        checkReleased(resources)
    }
    private inline fun <T> exclusive(action: () -> T): T {
        if (!active.compareAndSet(false, true)) throw IOException("JPEG publication already active")
        try { return action() } finally { active.set(false) }
    }
}
