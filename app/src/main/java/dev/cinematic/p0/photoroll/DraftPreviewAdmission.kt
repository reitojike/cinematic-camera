package dev.cinematic.p0.photoroll

import dev.cinematic.p0.photoedit.PhotoEditInputToken
import dev.cinematic.p0.photoedit.PhotoEditValues
import dev.cinematic.p0.rawcapture.SharedOriginalReference

/** Main-thread display admission. One conflated pending value and one running render.
 * A completed value may display while a newer draft waits, but never after release/close/source change. */
internal class DraftPreviewAdmission {
    data class Ticket(val epoch: Long, val sequence: Long, val source: SharedOriginalReference,
        val committed: TrialStrengthTicket, val token: PhotoEditInputToken, val values: PhotoEditValues)
    private var epoch = 0L
    private var sequence = 0L
    private var displayedSequence = 0L
    private var latest: Ticket? = null
    fun request(editor: TrialStrengthEditor): Ticket? {
        val source = editor.original ?: return null
        val session = editor.session?.takeIf { it.active } ?: return null
        check(sequence < Long.MAX_VALUE)
        return Ticket(epoch, ++sequence, source, editor.ticket, session.inputToken, session.draft).also { latest = it }
    }
    fun invalidate() { check(epoch < Long.MAX_VALUE); epoch++; latest = null }
    fun canStart(ticket: Ticket, editor: TrialStrengthEditor, selectedUri: String?) = latest == ticket &&
        matches(ticket, editor, selectedUri) && editor.session?.draft == ticket.values
    fun canDisplay(ticket: Ticket, editor: TrialStrengthEditor, selectedUri: String?) =
        ticket.sequence > displayedSequence && matches(ticket, editor, selectedUri)
    fun displayed(ticket: Ticket) { check(ticket.epoch == epoch && ticket.sequence > displayedSequence); displayedSequence = ticket.sequence }
    private fun matches(ticket: Ticket, editor: TrialStrengthEditor, selectedUri: String?) = epoch == ticket.epoch &&
        selectedUri == ticket.source.uri && editor.original == ticket.source && editor.accepts(ticket.committed) &&
        editor.session?.inputToken == ticket.token
}
