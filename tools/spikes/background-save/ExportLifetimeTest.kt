package spike.backgroundsave

/** Standalone pure JVM contract checks; no Gradle, Android, photograph, or native work. */
fun main() {
    var passed = 0
    fun scenario(block: () -> Unit) { block(); passed++ }
    scenario {
        val gate = ExportLifetime()
        check(gate.claim("synthetic:1", false) == null)
        check(gate.phase == ExportLifetime.Phase.IDLE)
    }
    scenario {
        val gate = ExportLifetime()
        val first = requireNotNull(gate.claim("synthetic:1", true))
        check(gate.claim("synthetic:1", true) == null)
        check(gate.claim("synthetic:2", true) == null)
        check(gate.stop(first, ExportLifetime.StopCause.USER))
        check(!gate.stop(first, ExportLifetime.StopCause.USER))
        check(gate.mustStop(first))
        check(gate.claim("synthetic:2", true) == null)
    }
    scenario {
        for (cause in ExportLifetime.StopCause.entries) {
            val gate = ExportLifetime()
            val first = requireNotNull(gate.claim("synthetic:1", true))
            check(gate.stop(first, cause))
            check(!gate.ownerReturned(first, false))
            check(gate.phase == ExportLifetime.Phase.RELEASE_UNCONFIRMED)
            check(gate.claim("synthetic:2", true) == null)
            check(gate.ownerReturned(first, true))
            check(gate.phase == ExportLifetime.Phase.IDLE)
        }
    }
    scenario {
        val gate = ExportLifetime()
        val first = requireNotNull(gate.claim("synthetic:1", true))
        check(gate.ownerReturned(first, true))
        val next = requireNotNull(gate.claim("synthetic:2", true))
        check(!gate.ownerReturned(first, true))
        check(!gate.stop(first, ExportLifetime.StopCause.TIMEOUT))
        check(!gate.mustStop(next))
        check(gate.mustStop(first))
    }
    scenario {
        val gate = ExportLifetime()
        val foreign = requireNotNull(ExportLifetime().claim("synthetic:1", true))
        requireNotNull(gate.claim("synthetic:1", true))
        check(!gate.ownerReturned(foreign, true))
        check(!gate.stop(foreign, ExportLifetime.StopCause.CAPTURE))
        check(gate.phase == ExportLifetime.Phase.RUNNING)
    }
    scenario {
        check(recoveryPlan(RecoveryEvidence.NO_INTENT) == RecoveryPlan.WAIT_FOR_EXPLICIT_SAVE)
        check(recoveryPlan(RecoveryEvidence.VERIFIED_JOURNAL) == RecoveryPlan.RECONCILE_EXISTING_ONLY)
        for (evidence in listOf(RecoveryEvidence.INTENT_ONLY, RecoveryEvidence.UNKNOWN_JOURNAL,
            RecoveryEvidence.INVALID_OR_UNREADABLE)) check(recoveryPlan(evidence) == RecoveryPlan.HOLD)
        // Process death loses process-local tokens; none of the durable recovery outcomes starts rendering.
        check(RecoveryEvidence.entries.map(::recoveryPlan).toSet() == RecoveryPlan.entries.toSet())
    }
    println("PASS: $passed pure contract scenarios (not Android/service integration)")
}
