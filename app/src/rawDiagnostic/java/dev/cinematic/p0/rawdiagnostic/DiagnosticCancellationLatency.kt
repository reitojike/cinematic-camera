package dev.cinematic.p0.rawdiagnostic

import dev.cinematic.p0.raw.original.OriginalStage

/** One bounded observation. Times are UI-handler receipt, not physical touch or presentation. */
internal class DiagnosticCancellationLatency(private val clock: () -> Long = System::nanoTime) {
    enum class Origin { USER_BUTTON, LIFECYCLE }
    enum class RenderOutcome { NOT_ENTERED, SUCCESS, CANCELLED, FAILED }
    private data class Request(val at: Long, val origin: Origin, val stage: OriginalStage?, val duringRender: Boolean, val stageElapsedNanos: Long?)
    data class Observation(val status: String, val origin: String, val stageAtRequest: String,
        val duringRender: Boolean, val renderOutcome: RenderOutcome, val stageElapsedAtRequestNanos: Long?,
        val requestToRenderExitNanos: Long?, val requestToAttemptScopeExitNanos: Long?,
        val requestToOwnersReleasedNanos: Long?, val requestToHarnessQuiescentNanos: Long?)
    private var request: Request? = null
    private var targetStage: OriginalStage? = null
    private var stageStart: Long? = null
    private var rendering = false
    private var outcome = RenderOutcome.NOT_ENTERED
    private var renderExit: Long? = null
    private var scopeExit: Long? = null
    private var ownersReleased = false
    private var harnessExit: Long? = null

    @Synchronized fun request(origin: Origin) {
        if (request == null) {
            val at = clock()
            request = Request(at, origin, targetStage, rendering,
                stageStart?.let { (at - it).takeIf { elapsed -> elapsed >= 0 } })
        }
    }
    @Synchronized fun renderStarted() { rendering = true }
    @Synchronized fun stageStarted(stage: OriginalStage) {
        if (stage == OriginalStage.GLOW || stage == OriginalStage.MIST) {
            targetStage = stage; stageStart = clock()
        }
    }
    @Synchronized fun stageFinished(stage: OriginalStage) {
        if (targetStage == stage) { targetStage = null; stageStart = null }
    }
    @Synchronized fun renderExited(result: RenderOutcome) {
        renderExit = clock(); outcome = result; rendering = false; targetStage = null; stageStart = null
    }
    @Synchronized fun attemptScopeExited(released: Boolean) {
        scopeExit = clock(); ownersReleased = released
    }
    @Synchronized fun harnessFinished(quiescent: Boolean) {
        if (quiescent) harnessExit = clock()
    }
    @Synchronized fun observation(intendedStage: OriginalStage? = null): Observation {
        val r = request
        fun after(event: Long?): Long? = if (r == null || event == null) null
            else (event - r.at).takeIf { it >= 0 }
        val render = after(renderExit)
        val scope = after(scopeExit)
        val owners = if (ownersReleased) scope else null
        val harness = after(harnessExit)
        val status = when {
            r == null -> "NO_REQUEST"
            r.origin != Origin.USER_BUTTON -> "NON_USER_REQUEST"
            !r.duringRender || r.stage == null -> "OUTSIDE_GLOW_MIST"
            outcome != RenderOutcome.CANCELLED || render == null -> "RENDER_NOT_CANCELLED"
            owners == null || harness == null -> "HOLD_NOT_QUIESCENT"
            intendedStage != null && r.stage != intendedStage -> "MISSED_INTENDED_STAGE"
            else -> "OBSERVED_TARGET_STAGE_CANCEL"
        }
        return Observation(status, r?.origin?.name ?: "NONE", r?.stage?.name ?: "OUTSIDE_GLOW_MIST",
            r?.duringRender ?: false, outcome, r?.stageElapsedNanos, render, scope, owners, harness)
    }
}

/** Explicit single-attempt selection; never inherits legacy comparison, reverse or memory controls. */
internal enum class ManualCancellationPlan(val arm: Int, val targetStage: OriginalStage) {
    A_GLOW(1, OriginalStage.GLOW), B_GLOW(2, OriginalStage.GLOW),
    A_MIST(1, OriginalStage.MIST), B_MIST(2, OriginalStage.MIST);
    val armLabel get() = if (arm == 1) "A" else "B"
    val label get() = "Save safety: Manual Cancel $armLabel / ${targetStage.name}"
}
