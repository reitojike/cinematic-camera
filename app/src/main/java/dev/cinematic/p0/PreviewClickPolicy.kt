package dev.cinematic.p0

/** performClick after a touch is an accessibility notification, not a second focus action. */
class PreviewClickPolicy {
    private var notifyingTap = false
    val shouldFocusCenter: Boolean get() = !notifyingTap

    fun notifyTapClick(notify: () -> Unit) {
        notifyingTap = true
        try { notify() } finally { notifyingTap = false }
    }
}
