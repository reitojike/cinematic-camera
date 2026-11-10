package dev.cinematic.p0.gpu.preview

import org.junit.Assert.*
import org.junit.Test

class GpuPreviewMailboxTest {
    @Test fun hundredUpdatesBeforeFrameConsumeOnlyLatestAndFinalValue() {
        val mailbox = LatestPreviewFrame(0)
        repeat(100) { mailbox.submit(it + 1) }
        assertEquals(100, mailbox.next())
        mailbox.rendered(checkNotNull(mailbox.next()))
        assertNull(mailbox.next())
        mailbox.submit(101)
        assertEquals(101, mailbox.next())
        mailbox.rendered(101)
        assertNull(mailbox.next())
    }

    @Test fun equalValueDoesNotRequestAnotherRender() {
        data class Request(val strength: Double, val before: Boolean)
        val mailbox = LatestPreviewFrame(Request(0.5, false))
        mailbox.rendered(checkNotNull(mailbox.next()))
        mailbox.submit(Request(0.5, false))
        assertNull(mailbox.next())
        mailbox.submit(Request(0.5, true))
        assertEquals(Request(0.5, true), mailbox.next())
    }

    @Test fun submissionDuringRenderIsNotAcknowledgedAsRendered() {
        val mailbox = LatestPreviewFrame(1)
        val inFlight = checkNotNull(mailbox.next())
        mailbox.submit(2)
        mailbox.submit(3)
        mailbox.rendered(inFlight)
        assertEquals(3, mailbox.next())
        mailbox.rendered(3)
        assertNull(mailbox.next())
    }

    @Test fun invalidationRedrawsUnchangedInputAfterSurfaceRecreation() {
        val mailbox = LatestPreviewFrame(7)
        mailbox.rendered(7)
        assertNull(mailbox.next())
        repeat(10) {
            mailbox.invalidate()
            assertEquals(7, mailbox.next())
            mailbox.rendered(7)
            assertNull(mailbox.next())
        }
    }
    @Test fun fakeDrawSeesOnlyTheLatestOfEachBurst() {
        val mailbox = LatestPreviewFrame(0)
        val drawn = mutableListOf<Int>()
        val draw: (Int) -> Unit = { drawn += it }
        repeat(10) { frame ->
            repeat(10) { update -> mailbox.submit(frame * 10 + update + 1) }
            mailbox.next()?.let { draw(it); mailbox.rendered(it) }
            assertNull(mailbox.next())
        }
        assertEquals((10..100 step 10).toList(), drawn)
    }

    @Test fun coalescedUpdatesKeepFirstUndrawnSubmitTimestamp() {
        var now = 100L
        val mailbox = LatestPreviewFrame(0) { now }
        mailbox.rendered(0)
        now = 200L
        mailbox.submit(1)
        now = 300L
        mailbox.submit(2)
        now = 400L
        mailbox.submit(3)
        val pending = checkNotNull(mailbox.beginFrame())
        assertEquals(3, pending.value)
        assertEquals(200L, pending.dirtySinceNanos)
        mailbox.rendered(pending.value)
        assertNull(mailbox.beginFrame())
    }

    @Test fun updatesDuringDrawStartTheirOwnFirstUndrawnTimestamp() {
        var now = 100L
        val mailbox = LatestPreviewFrame(0) { now }
        mailbox.rendered(0)
        now = 200L
        mailbox.submit(1)
        val drawing = checkNotNull(mailbox.beginFrame())
        assertEquals(200L, drawing.dirtySinceNanos)
        now = 300L
        mailbox.submit(2)
        now = 400L
        mailbox.submit(3)
        now = 500L
        mailbox.rendered(drawing.value)
        val next = checkNotNull(mailbox.beginFrame())
        assertEquals(3, next.value)
        assertEquals(300L, next.dirtySinceNanos)
        mailbox.rendered(next.value)
        assertNull(mailbox.beginFrame())
    }

}
