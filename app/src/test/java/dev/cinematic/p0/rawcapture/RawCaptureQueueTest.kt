package dev.cinematic.p0.rawcapture

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.Executor

class RawCaptureQueueTest {
    @get:Rule val temporary = TemporaryFolder()
    private val original = "synthetic camera bytes".toByteArray()

    @Test fun explicitIntentTravelsThroughPreparationReceiptsAndSharedOriginalHandoff() {
        val f = Fixture()
        val intent = CaptureDevelopIntent(RenderRecipeSnapshot("synthetic-policy-v1", "fixed"), "synthetic-renderer-v1")
        val ticket = f.queue.prepare("intent", setOf(RawOutput.DNG), f.observer, intent)
        f.prepareIo.runAll(); f.main.runAll()
        assertEquals(intent, ticket.session!!.receipt()!!.developIntent)
        f.streams().getValue(RawOutput.DNG).write(original)
        f.queue.received(ticket, RawOutput.DNG)
        f.pump()
        assertEquals(DngPublicationPhase.SHARED_ONLY, (f.notices.last() as RawCaptureNotice.Shared).record.phase)
        assertEquals(intent, ticket.session!!.receipt()!!.developIntent)
        assertEquals(1, f.rows.inserts)
        assertFalse(f.rawFile("intent").exists())
        assertEquals(CaptureReceiptPhase.FILES_RECEIVED, ticket.session!!.receipt()!!.phase)
    }

    @Test fun captureReceiptsAndCameraReservationBothPrecedeSharedIo() {
        val f = Fixture()
        val camera = f.queue.newCameraOwner()
        val ticket = f.prepare("first")
        f.streams().getValue(RawOutput.DNG).write(original)
        f.queue.received(ticket, RawOutput.DNG)
        f.completion.runAll()
        f.main.runAll()
        assertTrue(f.notices.any { it is RawCaptureNotice.Received })
        f.handoff.runAll()
        assertEquals(0, f.rows.inserts)
        camera.update(false)
        f.pump()
        assertEquals(1, f.rows.inserts)
        assertEquals(DngPublicationPhase.SHARED_ONLY, (f.notices.last() as RawCaptureNotice.Shared).record.phase)
        assertFalse(f.rawFile("first").exists())
    }

    @Test fun failureNotificationAndNextPreparationDoNotWaitForWriterClose() {
        val f = Fixture()
        val first = f.prepare("first")
        f.streams().getValue(RawOutput.DNG).write(original)
        f.queue.interrupt(first, IOException("Synthetic request failure"))
        f.main.runAll() // Completion/close executor deliberately not drained yet.
        assertTrue(f.notices.last() is RawCaptureNotice.Failed)
        val second = f.prepare("second")
        assertTrue(f.notices.last() is RawCaptureNotice.Prepared)
        f.handoff.runAll()
        assertEquals(0, f.rows.inserts)
        assertArrayEquals(original, f.rawFile("first").readBytes())
        f.queue.interrupt(second)
        f.pump()
        assertArrayEquals(original, f.rawFile("first").readBytes())
        assertEquals(0, f.rows.inserts)
    }

    @Test fun foregroundScanNeverConstructsACompetingLiveStore() {
        val f = Fixture()
        val ticket = f.prepare("first")
        f.queue.onForeground()
        f.prepareIo.runAll()
        assertEquals(1, f.factoryCalls)
        f.queue.interrupt(ticket)
        f.pump()
        f.queue.onForeground()
        f.pump()
        assertEquals(1, f.factoryCalls)
        assertTrue(f.rawFile("first").exists())
    }

    @Test fun missingObserverAbandonsPreparedStreamsWithoutDeletingThem() {
        val f = Fixture()
        val ticket = f.queue.prepare("abandoned", setOf(RawOutput.DNG), f.observer)
        f.prepareIo.runAll()
        ticket.observer.clear()
        f.pump()
        assertTrue(f.notices.isEmpty())
        assertTrue(f.rawFile("abandoned").exists())
        assertEquals(0, f.rows.inserts)
        assertEquals(CaptureReceiptPhase.INTERRUPTED, ticket.session!!.receipt()!!.phase)
        assertFalse(ticket.session!!.hasUnreleasedWriters)
    }

    @Test fun foreignTicketCannotInterruptOrCommitAnotherQueuesCapture() {
        val first = Fixture()
        val second = Fixture()
        val ticket = first.prepare("first")
        assertThrows(IllegalArgumentException::class.java) { second.queue.received(ticket, RawOutput.DNG) }
        assertThrows(IllegalArgumentException::class.java) { second.queue.interrupt(ticket) }
        assertFalse(ticket.terminal)
        first.queue.interrupt(ticket)
        first.pump()
    }

    @Test fun startupRecoveryOnlyPublishesConfirmedDngAndPreservesUnconfirmedFiles() {
        val f = Fixture()
        val known = f.prepare("known")
        f.streams().getValue(RawOutput.DNG).write(original)
        f.queue.received(known, RawOutput.DNG)
        f.completion.runAll() // Keep old queue's handoff worker stopped: simulated process boundary.
        val unknown = f.prepare("unknown")
        f.streams().getValue(RawOutput.DNG).write(original)
        f.queue.interrupt(unknown)
        f.completion.runAll()
        f.handoff.clear() // Discard old-process work before constructing the new process queue.
        val newHandoff = Manual()
        val restarted = f.newQueue(newHandoff)
        restarted.newCameraOwner().apply { foreground(true); update(false) }
        restarted.onForeground()
        f.prepareIo.runAll()
        newHandoff.runAll()
        f.main.runAll()
        assertEquals(1, f.rows.inserts)
        assertFalse(f.rawFile("known").exists())
        assertArrayEquals(original, f.rawFile("unknown").readBytes())
    }

    @Test fun backgroundStopsAnAdmittedCopyAndForegroundResumesTheSameUri() {
        val f = Fixture()
        val ticket = f.prepare("first")
        f.streams().getValue(RawOutput.DNG).write(original)
        f.queue.received(ticket, RawOutput.DNG)
        f.completion.runAll()
        f.rows.onWrite = { f.foreground.foreground(false) }
        f.pump()
        assertEquals(1, f.rows.inserts)
        assertTrue(f.rawFile("first").exists())
        assertFalse(f.notices.any { it is RawCaptureNotice.Shared })
        f.rows.onWrite = {}
        f.foreground.foreground(true)
        f.pump()
        assertEquals(1, f.rows.inserts)
        assertFalse(f.rawFile("first").exists())
        assertTrue(f.notices.last() is RawCaptureNotice.Shared)
    }

    @Test fun lastActivityCloseDoesNotStartNewHandoffs() {
        val f = Fixture()
        val ticket = f.prepare("first")
        f.streams().getValue(RawOutput.DNG).write(original)
        f.queue.received(ticket, RawOutput.DNG)
        f.completion.runAll()
        f.foreground.close()
        f.pump()
        assertEquals(0, f.rows.inserts)
        assertArrayEquals(original, f.rawFile("first").readBytes())
    }

    private class Manual : Executor {
        private val actions = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { actions.addLast(command) }
        val pending get() = actions.isNotEmpty()
        fun runAll() { var count = 0; while (actions.isNotEmpty()) { check(count++ < 100); actions.removeFirst().run() } }
        fun clear() = actions.clear()
    }
    private inner class Fixture {
        val root = temporary.newFolder()
        val prepareIo = Manual()
        val completion = Manual()
        val handoff = Manual()
        val main = Manual()
        val receipts = mutableMapOf<String, Memory>()
        val publications = mutableMapOf<String, Memory>()
        val rows = Rows()
        var factoryCalls = 0
        val notices = mutableListOf<RawCaptureNotice>()
        val observer = RawCaptureObserver { _, notice -> notices += notice }
        val queue = newQueue()
        val foreground = queue.newCameraOwner().apply { foreground(true); update(false) }
        fun newQueue(handoffExecutor: Executor = handoff) = RawCaptureQueue(root, { id ->
            factoryCalls++
            RawCaptureSession(
                RawCaptureStore(root, id, receipts.getOrPut(id) { Memory() }),
                DngPublicationJournal(id, publications.getOrPut(id) { Memory() }), rows,
            )
        }, prepareIo, completion, handoffExecutor, main)
        fun prepare(id: String): RawCaptureTicket {
            val ticket = queue.prepare(id, setOf(RawOutput.DNG), observer)
            prepareIo.runAll(); main.runAll()
            return ticket
        }
        fun streams() = (notices.last() as RawCaptureNotice.Prepared).outputs
        fun rawFile(id: String) = File(File(root, id), RawOutput.DNG.fileName)
        fun pump() {
            repeat(20) {
                prepareIo.runAll(); completion.runAll(); main.runAll(); handoff.runAll(); main.runAll()
                if (!prepareIo.pending && !completion.pending && !handoff.pending && !main.pending) return
            }
            error("Queue did not quiesce")
        }
    }
    private class Memory : AtomicJournalStorage {
        private var bytes: ByteArray? = null
        override fun read(maxBytes: Int) = bytes?.copyOf()
        override fun replace(contents: ByteArray) { bytes = contents.copyOf() }
    }
    private class Rows : DngHandoffRows {
        var inserts = 0
        var onWrite: () -> Unit = {}
        private var bytes = byteArrayOf()
        private var row: DngPendingRow? = null
        override fun insert(target: DngPublicationTarget): String {
            inserts++
            val uri = "content://media/external_primary/images/media/17"
            row = DngPendingRow(uri, target.displayName, target.volume, target.relativePath, target.mimeType, true, true, false)
            return uri
        }
        override fun inspect(uri: String) = row
        override fun find(target: DngPublicationTarget) = listOfNotNull(row)
        override fun openRead(target: DngPublicationTarget, uri: String, pending: Boolean) = ByteArrayInputStream(bytes)
        override fun openOutput(target: DngPublicationTarget, uri: String) = object : ByteArrayOutputStream() {
            override fun write(bytes: ByteArray, offset: Int, length: Int) { super.write(bytes, offset, length); onWrite() }
            override fun close() { bytes = toByteArray(); super.close() }
        }
        override fun publish(target: DngPublicationTarget, uri: String) { row = row!!.copy(pending = false) }
    }
}
