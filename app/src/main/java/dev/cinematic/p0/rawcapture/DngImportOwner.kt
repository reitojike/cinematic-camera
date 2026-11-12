package dev.cinematic.p0.rawcapture

import java.io.IOException
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/** Read only: an external original can never be deleted or replaced through this port. */
internal fun interface DngImportSource { fun openReadOnly(sourceUri: String): InputStream }

/** Every mutation must atomically constrain the expected pending target. */
internal interface DngImportRows {
    fun insert(target: DngImportTarget): String?
    fun find(target: DngImportTarget): List<DngPendingRow>
    fun inspect(uri: String): DngPendingRow?
    fun openRead(target: DngImportTarget, uri: String, pending: Boolean): InputStream
    fun openPendingOutput(target: DngImportTarget, uri: String): OutputStream
    fun publish(target: DngImportTarget, uri: String)
    fun discardPending(target: DngImportTarget, uri: String)
}

internal enum class DngImportResult { READY, CANCELLED, WAIT_SOURCE, HOLD }

/** Synchronous import core. READY describes bytes, not editability. Caller supplies admission/explicit resume. */
internal class DngImportOwner(
    private val journal: DngImportJournal,
    private val source: DngImportSource,
    private val rows: DngImportRows,
    private val interrupted: () -> Boolean = { false },
    private val spaceAvailable: (Long) -> Boolean,
) {
    private val active = AtomicBoolean(false)
    private val stop = AtomicBoolean(false)
    private var held: DngIoResources? = null
    val hasUnreleasedResources get() = held?.hasOpenResources == true

    /** Signal only. CANCELLED requires a subsequent advance and positive cleanup/reconciliation. */
    fun requestStop() { stop.set(true) }

    /** Retry only resource closure, never another copy/publication effect. */
    fun releaseResources() {
        check(active.compareAndSet(false, true)) { "Import owner busy" }
        try { releaseHeld() } finally { active.set(false) }
    }

    fun advance(): DngImportResult {
        check(active.compareAndSet(false, true)) { "Import owner busy" }
        try {
            releaseHeld()
            var current = requireNotNull(journal.read())
            if (stop.get() && !current.stopRequested) current = journal.stop(current)
            if (current.phase == DngImportPhase.CANCELLED) return DngImportResult.CANCELLED
            val resources = DngIoResources().also { held = it }
            var failure: Throwable? = null
            try { return run(current, resources) }
            catch (t: Throwable) {
                failure = t
                if (stop.get()) try {
                    journal.read()?.let { if (!it.stopRequested) journal.stop(it) }
                } catch (commit: Throwable) { if (t !== commit) t.addSuppressed(commit) }
                throw t
            } finally {
                val cleanup = resources.releaseAll()
                if (!resources.hasOpenResources) held = null
                if (cleanup != null) {
                    if (failure != null) { if (failure !== cleanup) failure.addSuppressed(cleanup) } else throw cleanup
                }
            }
        } finally { active.set(false) }
    }

    private fun run(initial: DngImportRecord, resources: DngIoResources): DngImportResult {
        var current = initial
        if (current.phase == DngImportPhase.READY) {
            if (!matches(current, false)) return DngImportResult.HOLD
            verify(current, false, resources, cancellable = false)
            return DngImportResult.READY
        }
        if (current.stopRequested) return cancel(current, resources)
        if (current.phase == DngImportPhase.PREPARED) {
            if (current.grant == DngImportGrant.UNAVAILABLE) return DngImportResult.WAIT_SOURCE
            if (!spaceAvailable(current.fingerprint.bytes)) throw IOException("Import capacity unavailable")
            DngContentVerifier.verify(current.fingerprint, { resources.input(source.openReadOnly(current.sourceUri)) }, ::stopped)
            current = journal.advance(current, DngImportPhase.INSERTING)
            // Only this invocation's newly committed INSERTING permits insert.
            val uri = rows.insert(current.target) ?: return DngImportResult.HOLD
            if (!isDngRowUri(uri)) return DngImportResult.HOLD
            val row = rows.inspect(uri) ?: return DngImportResult.HOLD
            if (row.uri != uri || !current.target.accepts(row, true)) return DngImportResult.HOLD
            current = journal.advance(current, DngImportPhase.COPYING, uri)
        } else if (current.phase == DngImportPhase.INSERTING) {
            val row = rows.find(current.target).singleOrNull() ?: return DngImportResult.HOLD
            if (!current.target.accepts(row, true)) return DngImportResult.HOLD
            current = journal.advance(current, DngImportPhase.COPYING, row.uri)
        }
        if (current.phase == DngImportPhase.COPYING) {
            if (!matches(current, true)) return DngImportResult.HOLD
            val complete = try { verify(current, true, resources); true }
                catch (mismatch: DngContentChanged) {
                    if (resources.hasOpenResources || mismatch.suppressed.isNotEmpty()) throw mismatch
                    false
                } catch (unopened: FileNotFoundException) {
                    // MediaStore may have a pending row before its data file is created. This is
                    // not proof of absence/completion: retry only the same positively owned pending.
                    if (resources.hasOpenResources || unopened.suppressed.isNotEmpty()) throw unopened
                    if (!matches(current, true)) return DngImportResult.HOLD
                    false
                }
            if (!complete) {
                if (current.grant == DngImportGrant.UNAVAILABLE) return DngImportResult.WAIT_SOURCE
                if (!spaceAvailable(current.fingerprint.bytes)) throw IOException("Import capacity unavailable")
                val copy = current
                DngContentVerifier.copy(copy.fingerprint, { resources.input(source.openReadOnly(copy.sourceUri)) },
                    { resources.output(rows.openPendingOutput(copy.target, requireNotNull(copy.uri))) }, ::stopped)
                if (!matches(current, true)) return DngImportResult.HOLD
                verify(current, true, resources)
            }
            released(resources)
            current = journal.advance(current, DngImportPhase.VERIFIED)
        }
        if (current.phase == DngImportPhase.VERIFIED) {
            if (!matches(current, true)) return DngImportResult.HOLD
            verify(current, true, resources)
            checkStop()
            current = journal.advance(current, DngImportPhase.PUBLISHING)
        }
        if (current.phase == DngImportPhase.PUBLISHING) {
            val row = rows.inspect(requireNotNull(current.uri)) ?: return DngImportResult.HOLD
            val pending = row.pending ?: return DngImportResult.HOLD
            if (row.uri != current.uri || !current.target.accepts(row, pending)) return DngImportResult.HOLD
            verify(current, pending, resources, cancellable = pending)
            if (pending) {
                checkStop()
                rows.publish(current.target, requireNotNull(current.uri))
            }
            if (!matches(current, false)) return DngImportResult.HOLD
            verify(current, false, resources, cancellable = false)
            released(resources)
            if (stop.get() && !current.stopRequested) current = journal.stop(current)
            current = journal.advance(current, DngImportPhase.READY)
        }
        return if (current.phase == DngImportPhase.READY) DngImportResult.READY else DngImportResult.HOLD
    }

    private fun cancel(record: DngImportRecord, resources: DngIoResources): DngImportResult {
        var current = record
        if (current.phase == DngImportPhase.PREPARED) {
            journal.advance(current, DngImportPhase.CANCELLED)
            return DngImportResult.CANCELLED
        }
        if (current.uri == null) {
            // Even cancellation cannot interpret an unknown insert outcome as no row.
            val row = rows.find(current.target).singleOrNull() ?: return DngImportResult.HOLD
            if (!current.target.accepts(row, true)) return DngImportResult.HOLD
            current = journal.advance(current, DngImportPhase.DISCARDING, row.uri)
        }
        val uri = requireNotNull(current.uri)
        val row = rows.inspect(uri)
        if (row == null) {
            if (current.phase != DngImportPhase.DISCARDING) return DngImportResult.HOLD
            journal.advance(current, DngImportPhase.CANCELLED)
            return DngImportResult.CANCELLED
        }
        val pending = row.pending ?: return DngImportResult.HOLD
        if (row.uri != uri || !current.target.accepts(row, pending)) return DngImportResult.HOLD
        if (!pending) {
            if (current.phase != DngImportPhase.PUBLISHING) return DngImportResult.HOLD
            verify(current, false, resources, cancellable = false)
            released(resources)
            journal.advance(current, DngImportPhase.READY)
            return DngImportResult.READY // Never delete published originals, including unsupported DNGs.
        }
        released(resources)
        if (current.phase != DngImportPhase.DISCARDING) current = journal.advance(current, DngImportPhase.DISCARDING)
        rows.discardPending(current.target, uri)
        if (rows.inspect(uri) != null) return DngImportResult.HOLD
        journal.advance(current, DngImportPhase.CANCELLED)
        return DngImportResult.CANCELLED
    }

    private fun matches(record: DngImportRecord, pending: Boolean): Boolean {
        val row = rows.inspect(requireNotNull(record.uri)) ?: return false
        return row.uri == record.uri && record.target.accepts(row, pending)
    }
    private fun verify(record: DngImportRecord, pending: Boolean, resources: DngIoResources, cancellable: Boolean = true) {
        DngContentVerifier.verify(record.fingerprint,
            { resources.input(rows.openRead(record.target, requireNotNull(record.uri), pending)) },
            { cancellable && stopped() })
    }
    private fun stopped() = stop.get() || interrupted()
    private fun checkStop() { if (stopped()) throw DngVerificationStopped() }
    private fun released(resources: DngIoResources) { check(!resources.hasOpenResources) { "Import resources remain open" } }
    private fun releaseHeld() {
        val resources = held ?: return
        val failure = resources.releaseAll()
        if (!resources.hasOpenResources) held = null
        if (failure != null) throw failure
        released(resources)
    }
}
