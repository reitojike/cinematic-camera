package dev.cinematic.p0.rawcapture

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.lang.ref.WeakReference
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal sealed interface RawCaptureNotice {
    data class Prepared(val outputs: Map<RawOutput, OutputStream>) : RawCaptureNotice
    data class Received(val receipt: CaptureReceipt) : RawCaptureNotice
    data class Shared(val record: DngPublicationRecord, val interrupted: Boolean) : RawCaptureNotice
    data class Failed(val stage: String, val failure: Exception) : RawCaptureNotice
}
internal fun interface RawCaptureObserver { fun onNotice(ticket: RawCaptureTicket, notice: RawCaptureNotice) }

internal class RawCaptureTicket internal constructor(
    val id: String,
    internal val writer: RawHandoffAdmission.Blocker,
    observer: RawCaptureObserver?,
) {
    internal val observer = WeakReference(observer)
    internal val serial = RawCaptureSerial()
    internal val writerReleased = AtomicBoolean(false)
    @Volatile internal var session: RawCaptureSession? = null
    @Volatile internal var terminal = false
    @Volatile internal var interrupted = false
    internal var notifiedRevision: Long? = null // Handoff worker only.
}

/** One process owner. No image buffers/Activity references; all file IO uses supplied executors. */
internal class RawCaptureQueue(
    private val root: File,
    private val sessionFactory: (String) -> RawCaptureSession,
    private val prepareIo: Executor,
    private val completionIo: Executor,
    private val handoffIo: Executor,
    private val main: Executor,
    private val report: (String) -> Unit = {},
    private val recoveryIo: Executor = prepareIo,
) {
    private val admission = RawHandoffAdmission()
    private val background = admission.newBlocker()
    private val visibleOwners = mutableSetOf<CameraOwner>()
    private val tickets = ConcurrentHashMap<String, RawCaptureTicket>()
    private val requests = AtomicLong()
    private val scheduled = AtomicBoolean(false)
    private val scanScheduled = AtomicBoolean(false)
    private var held: Pair<RawCaptureTicket, RawHandoffAdmission.Attempt>? = null // Handoff worker only.

    internal class CameraOwner internal constructor(
        private val admission: RawHandoffAdmission,
        private val blocker: RawHandoffAdmission.Blocker,
        private val wake: () -> Unit,
        private val visibility: (CameraOwner, Boolean) -> Unit,
    ) {
        private var closed = false
        private var blockedValue = true
        @Synchronized fun foreground(visible: Boolean) { if (!closed) visibility(this, visible) }
        @Synchronized fun update(blocked: Boolean) {
            if (closed || blockedValue == blocked) return
            blockedValue = blocked
            admission.block(blocker, blocked)
            if (!blocked) wake()
        }
        @Synchronized fun close() {
            if (closed) return
            closed = true
            visibility(this, false)
            admission.remove(blocker)
            wake()
        }
    }
    fun newCameraOwner() = CameraOwner(admission, admission.newBlocker(), ::requestWork, ::visibility)
    private fun visibility(owner: CameraOwner, visible: Boolean) {
        synchronized(visibleOwners) {
            if (visible) visibleOwners.add(owner) else visibleOwners.remove(owner)
            admission.block(background, visibleOwners.isEmpty())
        }
        if (visible) requestWork()
    }

    fun prepare(id: String, expected: Set<RawOutput>, observer: RawCaptureObserver,
        developIntent: CaptureDevelopIntent? = null): RawCaptureTicket {
        requireCaptureId(id)
        require(RawOutput.DNG in expected)
        val ticket = RawCaptureTicket(id, admission.newBlocker(), observer)
        if (tickets.putIfAbsent(id, ticket) != null) {
            admission.remove(ticket.writer)
            throw IllegalArgumentException("Capture already owned")
        }
        ticket.serial.execute(prepareIo) {
            try {
                val session = sessionFactory(id).also { ticket.session = it }
                val outputs = session.prepare(expected, developIntent)
                if (ticket.interrupted) interruptOnIo(ticket)
                else emit(ticket, RawCaptureNotice.Prepared(outputs))
            } catch (failure: Exception) {
                ticket.terminal = true
                ticket.interrupted = true
                releaseWriterIfClosed(ticket)
                emit(ticket, RawCaptureNotice.Failed("prepare", failure))
            }
        }
        return ticket
    }

    fun received(ticket: RawCaptureTicket, format: RawOutput) {
        owned(ticket)
        ticket.serial.execute(completionIo) {
            try {
                val receipt = requireNotNull(ticket.session).received(format)
                if (receipt.receivedBytes.keys == receipt.expectedFormats) {
                    ticket.terminal = true
                    releaseWriterIfClosed(ticket)
                }
                emit(ticket, RawCaptureNotice.Received(receipt))
                requestWork()
            } catch (failure: Exception) {
                ticket.interrupted = true
                ticket.terminal = true
                emit(ticket, RawCaptureNotice.Failed("receipt", failure))
                interruptOnIo(ticket)
            }
        }
    }

    /** Read an owned, fully handed-off capture without starting handoff or changing its state.
     * Missing/unfinished captures are unavailable; corrupt durable records complete exceptionally. */
    fun readDevelopRequest(captureId: String): CompletableFuture<AutoDevelopRequest?> {
        requireCaptureId(captureId)
        val result = CompletableFuture<AutoDevelopRequest?>()
        handoffIo.execute {
            if (result.isCancelled) return@execute
            try {
                val ticket = tickets[captureId]
                // This atomic flag becomes true only after writer close. Do not query synchronized
                // output state here: a slow close must never stall the handoff worker.
                if (ticket == null || !ticket.writerReleased.get() || !ticket.terminal || ticket.interrupted) {
                    result.complete(null)
                    return@execute
                }
                val session = ticket.session
                val request = session?.readDevelopRequest()
                result.complete(if (ticket.interrupted || ticket.session !== session) null else request)
            } catch (failure: Exception) { result.completeExceptionally(failure) }
        }
        return result
    }

    /** UI may report request failure immediately; writer reservations remain until actual close. */
    fun interrupt(ticket: RawCaptureTicket, failure: Exception? = null) {
        owned(ticket)
        ticket.interrupted = true
        ticket.terminal = true
        if (failure != null) emit(ticket, RawCaptureNotice.Failed("capture", failure))
        ticket.serial.execute(completionIo) { interruptOnIo(ticket) }
    }

    fun onForeground() {
        // Retain existing live owners; only retry close for requests already known terminal.
        tickets.values.filter { it.terminal }.forEach { ticket ->
            ticket.serial.execute(completionIo) { interruptOnIo(ticket) }
        }
        if (!scanScheduled.compareAndSet(false, true)) return
        recoveryIo.execute {
            try {
                if (Files.notExists(root.toPath(), LinkOption.NOFOLLOW_LINKS)) { return@execute }
                val directories = root.listFiles() ?: throw IOException("Capture root unavailable")
                directories.forEach { directory ->
                    if (!directory.name.matches(Regex("[A-Za-z0-9_-]{1,64}")) ||
                        !Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) return@forEach
                    val ticket = RawCaptureTicket(directory.name, admission.newBlocker(), null)
                    if (tickets.putIfAbsent(ticket.id, ticket) != null) {
                        admission.remove(ticket.writer)
                        return@forEach
                    }
                    ticket.serial.execute(recoveryIo) {
                        try {
                            val session = sessionFactory(ticket.id).also { ticket.session = it }
                            session.recoverAfterProcessDeath()
                            ticket.terminal = true
                        } catch (failure: Exception) {
                            ticket.terminal = true
                            report("RAW_RECOVERY_HELD type=${failure.javaClass.simpleName}")
                        } finally { releaseWriterIfClosed(ticket); requestWork() }
                    }
                }
            } catch (failure: Exception) { report("RAW_SCAN_HELD type=${failure.javaClass.simpleName}") }
            finally { scanScheduled.set(false); requestWork() }
        }
    }

    private fun interruptOnIo(ticket: RawCaptureTicket) {
        try { ticket.session?.interruptAndCloseWriters() }
        catch (failure: Exception) { report("RAW_WRITER_HELD type=${failure.javaClass.simpleName}") }
        finally { releaseWriterIfClosed(ticket); requestWork() }
    }
    private fun releaseWriterIfClosed(ticket: RawCaptureTicket) {
        if (ticket.session?.hasUnreleasedWriters != true && ticket.writerReleased.compareAndSet(false, true)) {
            admission.remove(ticket.writer)
        }
    }
    private fun owned(ticket: RawCaptureTicket) { require(tickets[ticket.id] === ticket) { "Foreign capture owner" } }

    private fun emit(ticket: RawCaptureTicket, notice: RawCaptureNotice) {
        main.execute {
            val observer = ticket.observer.get()
            if (observer != null) observer.onNotice(ticket, notice)
            else if (notice is RawCaptureNotice.Prepared) interrupt(ticket)
        }
    }
    private fun requestWork() { requests.incrementAndGet(); scheduleWorker() }
    private fun scheduleWorker() {
        if (!scheduled.compareAndSet(false, true)) return
        handoffIo.execute {
            val requested = requests.get()
            try { runHandoffs() }
            finally {
                scheduled.set(false)
                if (requests.get() != requested) scheduleWorker()
            }
        }
    }
    private fun runHandoffs() {
        held?.let { (ticket, attempt) ->
            val session = requireNotNull(ticket.session)
            try { session.releaseHandoffResources() }
            catch (failure: Exception) { report("RAW_RESOURCE_HELD type=${failure.javaClass.simpleName}") }
            if (session.hasUnreleasedHandoffResources) return
            admission.completeAfterResourcesClosed(attempt)
            held = null
        }
        for (ticket in tickets.values) {
            if (!ticket.terminal) continue
            val session = ticket.session ?: continue
            val attempt = admission.tryStart() ?: return
            try {
                val record = session.advanceHandoff { admission.shouldStop(attempt) }
                if (record != null && ticket.notifiedRevision != record.revision) {
                    ticket.notifiedRevision = record.revision
                    report("RAW_HANDOFF phase=${record.phase}")
                    emit(ticket, RawCaptureNotice.Shared(record, ticket.interrupted))
                }
            } catch (_: DngVerificationStopped) { /* Latched stop; resume only on a later wake-up. */ }
            catch (failure: Exception) { emit(ticket, RawCaptureNotice.Failed("shared", failure)) }
            finally {
                if (session.hasUnreleasedHandoffResources) held = ticket to attempt
                else admission.completeAfterResourcesClosed(attempt)
            }
            if (held != null) return
        }
    }
}

/** Serializes each capture without allowing a blocked close to occupy the preparation executor. */
internal class RawCaptureSerial {
    private data class Work(val executor: Executor, val action: () -> Unit)
    private val queue = ArrayDeque<Work>()
    private var running = false
    fun execute(executor: Executor, action: () -> Unit) {
        val start = synchronized(this) {
            queue.addLast(Work(executor, action))
            if (running) false else { running = true; true }
        }
        if (start) dispatch()
    }
    private fun dispatch() {
        val next = synchronized(this) {
            if (queue.isEmpty()) { running = false; null } else queue.removeFirst()
        } ?: return
        next.executor.execute { try { next.action() } finally { dispatch() } }
    }
}
