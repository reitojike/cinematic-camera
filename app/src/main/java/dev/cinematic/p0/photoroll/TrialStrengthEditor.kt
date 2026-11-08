package dev.cinematic.p0.photoroll

import dev.cinematic.p0.photoedit.*
import dev.cinematic.p0.rawcapture.SharedOriginalReference

/** Process-local display intent only, never a durable queue generation or a saved edit recipe. */
internal data class TrialStrengthTicket(val owner: Any, val revision: Long, val values: PhotoEditValues) {
    val strength get() = values.strength
}

/** Process-local edit intent. Only the manual-trial path supplies warmth and exposure scales. */
internal class TrialStrengthEditor private constructor(
    val original: SharedOriginalReference?,
    val requestedValues: PhotoEditValues,
    val displayedValues: PhotoEditValues?,
    val session: PhotoEditSession?,
    private val owner: Any,
    private val revision: Long,
) {
    val requestedStrength get() = requestedValues.strength
    val displayedStrength get() = displayedValues?.strength
    val ticket get() = TrialStrengthTicket(owner, revision, requestedValues)
    val hasUnappliedChanges get() = session?.let { it.active && it.draft != requestedValues } == true

    /** Same URI with different content/grant is not another source to inherit an existing draft. */
    fun bind(source: SharedOriginalReference): TrialStrengthEditor? = when {
        original == source -> this
        original != null -> null
        else -> copy(original = source)
    }

    fun begin(tapStep: Double, adjustments: Boolean = false): TrialStrengthEditor {
        val source = original ?: return this
        val shown = displayedValues ?: return this
        if (session != null || shown != requestedValues) return this
        val scales = if (adjustments) PhotoAdjustmentPolicy.scales else PhotoEditScales(tapStep)
        if (!scales.contains(shown)) return this
        val defaults = if (adjustments) PhotoAdjustmentPolicy.defaults else PhotoEditValues(.5)
        return copy(session = PhotoEditSession.open(source, scales, shown, defaults))
    }

    fun change(token: PhotoEditInputToken, value: Double): TrialStrengthEditor =
        change(token, PhotoEditParameter.STRENGTH, value)

    fun change(token: PhotoEditInputToken, parameter: PhotoEditParameter, value: Double): TrialStrengthEditor {
        val current = session ?: return this
        val changed = current.change(token, parameter, value)
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
        return copy(requestedValues = current.draft, displayedValues = null, revision = nextRevision())
    }

    fun cancel(token: PhotoEditInputToken): TrialStrengthEditor {
        val current = session ?: return this
        if (!current.active || current.inputToken != token) return this
        val values = current.confirmed
        return copy(requestedValues = values, displayedValues = displayedValues.takeIf { values == requestedValues }, session = null,
            revision = if (values != requestedValues) nextRevision() else revision)
    }

    /** Caller must confirm durable metadata saving before adopting this accepted close. */
    fun close(token: PhotoEditInputToken): TrialStrengthEditor {
        val current = session ?: return this
        if (!current.active || current.inputToken != token || hasUnappliedChanges || displayedValues != requestedValues) return this
        return copy(session = null)
    }

    fun pause(): TrialStrengthEditor = copy(requestedValues = session?.confirmed ?: requestedValues,
        displayedValues = null, session = null, revision = nextRevision())

    /** App switch preserves the last submitted full recipe, but drops an unfinished gesture.
     * A later explicit Cancel is separate from Android lifecycle suspension. */
    fun suspendForLifecycle(): TrialStrengthEditor = copy(displayedValues = null, session = null, revision = nextRevision())

    fun shown(ticket: TrialStrengthTicket, strength: Double): TrialStrengthEditor =
        shown(ticket, requestedValues.copy(strength = strength))

    fun shown(ticket: TrialStrengthTicket, values: PhotoEditValues): TrialStrengthEditor =
        if (accepts(ticket) && original != null && values == requestedValues) copy(displayedValues = values) else this

    /** Approximate pixels cannot confirm committed settings or enable Close/Save. */
    fun showingDraft(): TrialStrengthEditor = copy(displayedValues = null)

    fun accepts(ticket: TrialStrengthTicket) = ticket == this.ticket

    private fun nextRevision(): Long { check(revision < Long.MAX_VALUE); return revision + 1 }
    private fun copy(original: SharedOriginalReference? = this.original, requestedValues: PhotoEditValues = this.requestedValues,
        displayedValues: PhotoEditValues? = this.displayedValues, session: PhotoEditSession? = this.session, revision: Long = this.revision) =
        TrialStrengthEditor(original, requestedValues, displayedValues, session, owner, revision)

    companion object {
        fun empty(strength: Double = .5): TrialStrengthEditor {
            require(strength.isFinite() && strength in 0.0..1.0)
            return empty(PhotoEditValues(strength))
        }
        fun empty(values: PhotoEditValues): TrialStrengthEditor {
            require(PhotoEditScales(.01).contains(values) || PhotoAdjustmentPolicy.scales.contains(values))
            return TrialStrengthEditor(null, values, null, null, Any(), 0)
        }
    }
}
