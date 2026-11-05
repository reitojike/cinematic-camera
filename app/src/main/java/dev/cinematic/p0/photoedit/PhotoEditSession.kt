package dev.cinematic.p0.photoedit

import dev.cinematic.p0.rawcapture.SharedOriginalReference

internal enum class PhotoEditParameter { STRENGTH, TEMPERATURE, BRIGHTNESS }

/** Temperature/brightness are caller-defined UI coordinates, not a Kelvin/EV contract yet. */
internal data class PhotoEditValues(val strength: Double, val temperature: Double? = null, val brightness: Double? = null) {
    operator fun get(parameter: PhotoEditParameter) = when (parameter) {
        PhotoEditParameter.STRENGTH -> strength
        PhotoEditParameter.TEMPERATURE -> temperature
        PhotoEditParameter.BRIGHTNESS -> brightness
    }

    fun with(parameter: PhotoEditParameter, value: Double) = when (parameter) {
        PhotoEditParameter.STRENGTH -> copy(strength = value)
        PhotoEditParameter.TEMPERATURE -> copy(temperature = value)
        PhotoEditParameter.BRIGHTNESS -> copy(brightness = value)
    }
}

internal class PhotoEditScales(
    strengthTapStep: Double,
    val temperature: PhotoEditScale? = null,
    val brightness: PhotoEditScale? = null,
) {
    val strength = PhotoEditScale(0.0, 1.0, strengthTapStep)
    operator fun get(parameter: PhotoEditParameter) = when (parameter) {
        PhotoEditParameter.STRENGTH -> strength
        PhotoEditParameter.TEMPERATURE -> temperature
        PhotoEditParameter.BRIGHTNESS -> brightness
    }

    fun contains(values: PhotoEditValues) = PhotoEditParameter.entries.all {
        val scale = this[it]
        val value = values[it]
        if (scale == null) value == null else value != null && scale.contains(value)
    }
}

/** Process-local input authority. A reset/exit invalidates a finger already on the bar. */
internal data class PhotoEditInputToken(val owner: Any, val epoch: Long)

/** Display matching only. Never substitute this revision for the durable queue's generation. */
internal data class PhotoEditPreviewTicket(val input: PhotoEditInputToken, val revision: Long, val values: PhotoEditValues)

/**
 * Immutable draft of one checked original. No file access, render submission, commit or JPEG publication.
 * Confirmed/default values must be supplied explicitly; unagreed product defaults are not invented here.
 */
internal class PhotoEditSession private constructor(
    val original: SharedOriginalReference,
    val scales: PhotoEditScales,
    val confirmed: PhotoEditValues,
    val defaults: PhotoEditValues,
    val draft: PhotoEditValues,
    val active: Boolean,
    private val owner: Any,
    private val epoch: Long,
    val revision: Long,
) {
    val dirty get() = draft != confirmed
    val inputToken get() = PhotoEditInputToken(owner, epoch)
    val previewTicket get() = PhotoEditPreviewTicket(inputToken, revision, draft)

    fun change(token: PhotoEditInputToken, parameter: PhotoEditParameter, value: Double): PhotoEditSession {
        if (!accepts(token) || scales[parameter]?.contains(value) != true || draft[parameter] == value) return this
        return next(draft.with(parameter, value))
    }

    fun step(token: PhotoEditInputToken, parameter: PhotoEditParameter, direction: Int): PhotoEditSession {
        if (!accepts(token)) return this
        val scale = scales[parameter] ?: return this
        val current = draft[parameter] ?: return this
        val value = scale.step(current, direction) ?: return this
        return change(token, parameter, value)
    }

    fun reset(token: PhotoEditInputToken): PhotoEditSession =
        if (accepts(token)) next(defaults, invalidateInput = true) else this

    /** Cancels this draft only; it cannot undo an exported JPEG or alter the original. */
    fun cancel(token: PhotoEditInputToken): PhotoEditSession =
        if (accepts(token)) next(confirmed, invalidateInput = true, active = false) else this

    fun acceptsPreview(ticket: PhotoEditPreviewTicket) = active && ticket == previewTicket

    private fun accepts(token: PhotoEditInputToken) = active && token == inputToken

    private fun next(values: PhotoEditValues, invalidateInput: Boolean = false, active: Boolean = this.active): PhotoEditSession {
        check(revision < Long.MAX_VALUE && (!invalidateInput || epoch < Long.MAX_VALUE))
        return PhotoEditSession(original, scales, confirmed, defaults, values, active, owner,
            epoch + if (invalidateInput) 1 else 0, revision + 1)
    }

    companion object {
        fun open(original: SharedOriginalReference, scales: PhotoEditScales,
            confirmed: PhotoEditValues, defaults: PhotoEditValues): PhotoEditSession {
            require(scales.contains(confirmed) && scales.contains(defaults))
            require(defaults.strength == 0.5) { "The agreed Stage Clear default strength is 0.5" }
            return PhotoEditSession(original, scales, confirmed, defaults, confirmed, true, Any(), 0, 0)
        }
    }
}
