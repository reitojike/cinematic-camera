package dev.cinematic.p0.rawcapture

/** Short identity lock only. Native cancellation runs outside it and never releases admission. */
internal class DevelopStopHook {
    private data class Binding(val attempt: AutoDevelopCoordinator.Attempt, val stop: () -> Unit)
    private var binding: Binding? = null
    @Synchronized fun bind(attempt: AutoDevelopCoordinator.Attempt, stop: () -> Unit) {
        check(binding == null || binding?.attempt === attempt)
        binding = Binding(attempt, stop)
    }
    fun stopIfRequested(coordinator: AutoDevelopCoordinator) {
        val current = synchronized(this) { binding }
        if (current != null && coordinator.shouldStop(current.attempt)) current.stop()
    }
    @Synchronized fun clear(attempt: AutoDevelopCoordinator.Attempt) {
        if (binding?.attempt === attempt) binding = null
    }
}
