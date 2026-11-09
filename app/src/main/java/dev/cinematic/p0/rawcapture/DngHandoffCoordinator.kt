package dev.cinematic.p0.rawcapture

import java.io.Closeable
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal interface DngHandoffRows : DngPendingRows {
    fun openRead(target: DngPublicationTarget, uri: String, pending: Boolean): InputStream
    fun openOutput(target: DngPublicationTarget, uri: String): OutputStream
    fun publish(target: DngPublicationTarget, uri: String)
}

internal interface DngHandoffSource {
    fun receipt(): CaptureReceipt
    fun openOriginal(): InputStream
    fun reserveRelease(record: DngPublicationRecord): DngOriginalRelease
}

internal interface DngOriginalRelease : Closeable {
    fun exists(): Boolean
    fun openOriginal(): InputStream
    fun deleteOriginal(record: DngPublicationRecord)
}

internal class DngHandoffBusy : IOException("DNG handoff is already active")

/** One process-scoped owner per operation. Synchronous IO; never run on UI/capture callback thread. */
internal class DngHandoffCoordinator(
    private val journal: DngPublicationJournal,
    private val rows: DngHandoffRows,
    private val source: DngHandoffSource,
) {
    private val active = AtomicBoolean(false)
    @Volatile private var held: DngIoResources? = null
    val hasUnreleasedResources get() = held?.hasOpenResources == true

    fun releaseResources() = exclusive { releaseHeld() }

    fun advance(shouldStop: () -> Boolean = { false }): DngPublicationRecord = exclusive {
        releaseHeld() // No new open/write until earlier resources are positively closed.
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

    private fun run(resources: DngIoResources, stop: () -> Boolean): DngPublicationRecord {
        checkContinue(stop)
        var existing = journal.read()
        if (existing?.phase in setOf(DngPublicationPhase.UNKNOWN, DngPublicationPhase.SHARED_ONLY)) return requireNotNull(existing)
        var sourceVerified = false
        if (existing == null || existing.phase == DngPublicationPhase.PREPARED) {
            val receipt = source.receipt()
            val fingerprint = requireNotNull(receipt.originalFingerprint) { "Original is not verified" }
            DngContentVerifier.verify(fingerprint, { resources.input(source.openOriginal()) }, stop)
            sourceVerified = true
            checkContinue(stop)
            existing = journal.prepare(receipt)
        }
        var current = requireNotNull(existing)
        if (current.phase in setOf(DngPublicationPhase.PREPARED, DngPublicationPhase.INSERTING)) {
            current = DngInsertCoordinator(journal, rows).advance()
        }
        if (current.phase == DngPublicationPhase.UNKNOWN) return current
        var firstCopy = false
        if (current.phase == DngPublicationPhase.ROW_RECORDED) {
            if (!matches(current, true)) return unknown(current)
            if (!sourceVerified) DngContentVerifier.verify(current.fingerprint, { resources.input(source.openOriginal()) }, stop)
            sourceVerified = true
            checkContinue(stop)
            current = journal.markCopying(current.revision)
            firstCopy = true
        }
        if (current.phase == DngPublicationPhase.COPYING) {
            if (!matches(current, true)) return unknown(current)
            val alreadyComplete = if (firstCopy) false else try {
                verifyRow(current, true, resources, stop); true
            } catch (changed: DngContentChanged) {
                // use() can suppress a close failure behind the primary mismatch. Do not recopy then.
                if (resources.hasOpenResources || changed.suppressed.isNotEmpty()) throw changed
                false
            }
            checkReleased(resources)
            if (!alreadyComplete) {
                if (!sourceVerified) DngContentVerifier.verify(current.fingerprint, { resources.input(source.openOriginal()) }, stop)
                if (!matches(current, true)) return unknown(current)
                checkContinue(stop)
                val record = current
                DngContentVerifier.copy(
                    record.fingerprint, { resources.input(source.openOriginal()) },
                    { resources.output(rows.openOutput(record.target, requireNotNull(record.uri))) }, stop,
                )
            }
            if (!matches(current, true)) return unknown(current)
            verifyRow(current, true, resources, stop)
            checkContinue(stop)
            checkReleased(resources)
            current = journal.markBytesComplete(current.revision)
        }
        if (current.phase == DngPublicationPhase.BYTES_COMPLETE) {
            if (!matches(current, true)) return unknown(current)
            try { verifyRow(current, true, resources, stop) }
            catch (_: DngContentChanged) { return unknown(current) }
            checkContinue(stop)
            current = journal.markPublishing(current.revision)
        }
        if (current.phase == DngPublicationPhase.PUBLISHING) {
            val row = inspect(current) ?: return unknown(current)
            val pending = row.pending ?: return unknown(current)
            if (!current.target.accepts(row, pending)) return unknown(current)
            try { verifyRow(current, pending, resources, stop) }
            catch (_: DngContentChanged) { return unknown(current) }
            if (pending) {
                checkContinue(stop)
                rows.publish(current.target, requireNotNull(current.uri)) // Failure leaves PUBLISHING for reconciliation.
                val observed = inspect(current) ?: return unknown(current)
                if (current.target.accepts(observed, true)) throw IOException("DNG publication unconfirmed")
                if (!current.target.accepts(observed, false)) return unknown(current)
                try { verifyRow(current, false, resources, stop) }
                catch (_: DngContentChanged) { return unknown(current) }
            }
            checkContinue(stop)
            checkReleased(resources)
            current = journal.markPublished(current.revision)
        }
        if (current.phase == DngPublicationPhase.PUBLISHED) {
            verifyShared(current, resources, stop)
            checkContinue(stop)
            current = journal.markSharedReady(current.revision)
        }
        if (current.phase in setOf(DngPublicationPhase.SHARED_READY, DngPublicationPhase.LOCAL_RELEASE_PENDING)) {
            verifyShared(current, resources, stop)
            checkReleased(resources)
            source.reserveRelease(current).use { gate ->
                if (gate.exists()) DngContentVerifier.verify(current.fingerprint, { resources.input(gate.openOriginal()) }, stop)
                verifyShared(current, resources, stop)
                checkContinue(stop)
                if (current.phase == DngPublicationPhase.SHARED_READY) current = journal.markLocalReleasePending(current.revision)
                // The commit can fail after persisting; do not delete in that call. Recovery rechecks both copies.
                verifyShared(current, resources, stop)
                checkContinue(stop)
                checkReleased(resources)
                gate.deleteOriginal(current)
                if (gate.exists()) throw IOException("Temporary original release unconfirmed")
                verifyShared(current, resources, stop)
                checkReleased(resources)
                current = journal.markSharedOnly(current.revision)
            }
        }
        return current
    }

    private fun inspect(record: DngPublicationRecord): DngPendingRow? = try {
        rows.inspect(requireNotNull(record.uri))?.takeIf { it.uri == record.uri }
    } catch (_: Exception) { null }

    private fun matches(record: DngPublicationRecord, pending: Boolean): Boolean = inspect(record)?.let { record.target.accepts(it, pending) } == true
    private fun unknown(record: DngPublicationRecord) = journal.markUnknown(record.revision, record.uri)
    private fun verifyRow(record: DngPublicationRecord, pending: Boolean, resources: DngIoResources, stop: () -> Boolean) {
        DngContentVerifier.verify(record.fingerprint, {
            resources.input(rows.openRead(record.target, requireNotNull(record.uri), pending))
        }, stop)
    }
    private fun verifyShared(record: DngPublicationRecord, resources: DngIoResources, stop: () -> Boolean) {
        if (!matches(record, false)) throw IOException("Shared original unavailable or changed")
        verifyRow(record, false, resources, stop)
    }
    private fun checkContinue(stop: () -> Boolean) { if (stop()) throw DngVerificationStopped() }
    private fun checkReleased(resources: DngIoResources) { if (resources.hasOpenResources) throw IOException("DNG resources remain open") }
    private fun releaseHeld() {
        val resources = held ?: return
        val failure = resources.releaseAll()
        if (!resources.hasOpenResources) held = null
        if (failure != null) throw failure
        checkReleased(resources)
    }
    private inline fun <T> exclusive(action: () -> T): T {
        if (!active.compareAndSet(false, true)) throw DngHandoffBusy()
        try { return action() } finally { active.set(false) }
    }
}

/** Retains failed closes for retry. A thrown close never decrements the live resource count. */
internal class DngIoResources {
    private val count = AtomicInteger(0)
    private val resources = mutableListOf<Resource>()
    val hasOpenResources get() = count.get() != 0
    private inner class Resource(private val value: Closeable) : Closeable {
        private var released = false
        override fun close() {
            if (released) return
            value.close()
            released = true
            count.decrementAndGet()
        }
    }
    private fun own(value: Closeable): Resource = Resource(value).also { resources += it; count.incrementAndGet() }
    fun <T : Closeable, R> useOwned(value: T, block: (T) -> R): R = own(value).use { block(value) }
    fun input(stream: InputStream): InputStream {
        val resource = own(stream)
        return object : FilterInputStream(stream) { override fun close() = resource.close() }
    }
    fun output(stream: OutputStream): OutputStream {
        val resource = own(stream)
        return object : FilterOutputStream(stream) {
            override fun write(bytes: ByteArray, offset: Int, length: Int) = out.write(bytes, offset, length)
            override fun close() = resource.close()
        }
    }
    fun releaseAll(): Throwable? {
        var failure: Throwable? = null
        resources.asReversed().forEach {
            try { it.close() } catch (t: Throwable) {
                val previous = failure
                if (previous == null) failure = t else if (previous !== t) previous.addSuppressed(t)
            }
        }
        return failure
    }
}
