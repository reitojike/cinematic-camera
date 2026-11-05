package dev.cinematic.p0.photoroll

import dev.cinematic.p0.photoedit.*
import dev.cinematic.p0.rawcapture.SharedOriginalReference

/** Process-local display intent only, never a durable queue generation or a saved edit recipe. */
internal data class TrialStrengthTicket(val owner: Any, val revision: Long, val strength: Double)

/** Strength-only temporary edit. Unsupported controls have no value/range; originals are never opened here. */
internal class TrialStrengthEditor private constructor(
    val original: SharedOriginalReference?,
    val requestedStrength: Double,
    val displayedStrength: Double?,
    val session: PhotoEditSession?,
    private val owner: Any,
    private val revision: Long,
) {
    val ticket get() = TrialStrengthTicket(owner, revision, requestedStrength)
    val hasUnappliedChanges get() = session?.let { it.active && it.draft.strength != requestedStrength } == true

    /** Same URI with different content/grant is not another source to inherit an existing draft. */
    fun bind(source: SharedOriginalReference): TrialStrengthEditor? = when {
        original == source -> this
        original != null -> null
        else -> copy(original = source)
    }

    fun begin(tapStep: Double): TrialStrengthEditor {
        val source = original ?: return this
        val shown = displayedStrength ?: return this
        if (session != null || shown != requestedStrength) return this
        return copy(session = PhotoEditSession.open(source, PhotoEditScales(tapStep), PhotoEditValues(shown), PhotoEditValues(.5)))
    }

    fun change(token: PhotoEditInputToken, value: Double): TrialStrengthEditor {
        val current = session ?: return this
        val changed = current.change(token, PhotoEditParameter.STRENGTH, value)
        return if (changed === current) this else copy(session = changed)
    }

    fun reset(token: PhotoEditInputToken): TrialStrengthEditor {
        val current = session ?: return this
        val reset = current.reset(token)
        return if (reset === current) this else copy(session = reset)
    }

    fun apply(token: PhotoEditInputToken): TrialStrengthEditor {
        val current = session ?: return this
        if (!current.active || current.inputToken != token || current.original != original || !hasUnappliedChanges) return this
        return copy(requestedStrength = current.draft.strength, displayedStrength = null, revision = nextRevision())
    }

    fun cancel(token: PhotoEditInputToken): TrialStrengthEditor {
        val current = session ?: return this
        if (!current.active || current.inputToken != token) return this
        val strength = current.confirmed.strength
        return copy(requestedStrength = strength, displayedStrength = displayedStrength.takeIf { strength == requestedStrength }, session = null,
            revision = if (strength != requestedStrength) nextRevision() else revision)
    }

    /** Closing the controls accepts only the displayed in-memory trial; it does not save settings. */
    fun close(token: PhotoEditInputToken): TrialStrengthEditor {
        val current = session ?: return this
        if (!current.active || current.inputToken != token || hasUnappliedChanges || displayedStrength != requestedStrength) return this
        return copy(session = null)
    }

    fun pause(): TrialStrengthEditor = copy(requestedStrength = session?.confirmed?.strength ?: requestedStrength,
        displayedStrength = null, session = null, revision = nextRevision())

    fun shown(ticket: TrialStrengthTicket, strength: Double): TrialStrengthEditor =
        if (accepts(ticket) && original != null && strength == requestedStrength) copy(displayedStrength = strength) else this

    fun accepts(ticket: TrialStrengthTicket) = ticket == this.ticket

    private fun nextRevision(): Long { check(revision < Long.MAX_VALUE); return revision + 1 }
    private fun copy(original: SharedOriginalReference? = this.original, requestedStrength: Double = this.requestedStrength,
        displayedStrength: Double? = this.displayedStrength, session: PhotoEditSession? = this.session, revision: Long = this.revision) =
        TrialStrengthEditor(original, requestedStrength, displayedStrength, session, owner, revision)

    companion object {
        fun empty() = TrialStrengthEditor(null, .5, null, null, Any(), 0)
    }
}
