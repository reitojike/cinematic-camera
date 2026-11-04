package dev.cinematic.p0.photoroll

import dev.cinematic.p0.rawcapture.AutoDevelopConditions

/** Only the current foreground photo screen may update common resource observations. */
internal class PhotoRollObservationAuthority {
    private var current: Any? = null
    @Synchronized fun foreground(owner: Any, visible: Boolean, publish: (AutoDevelopConditions) -> Unit) {
        if (visible) {
            current = owner
            publish(AutoDevelopConditions(foreground = true))
        } else if (current === owner) {
            current = null
            publish(AutoDevelopConditions(foreground = false))
        }
    }
    @Synchronized fun observed(owner: Any, conditions: AutoDevelopConditions, publish: (AutoDevelopConditions) -> Unit) {
        if (current === owner) publish(conditions)
    }
}

internal object PhotoRollObservationOwners { val authority = PhotoRollObservationAuthority() }
