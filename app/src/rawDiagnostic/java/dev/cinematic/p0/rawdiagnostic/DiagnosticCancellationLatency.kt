package dev.cinematic.p0.rawdiagnostic

import dev.cinematic.p0.raw.original.OriginalStage

/** One bounded observation. Times are UI-handler receipt, not physical touch or presentation. */
internal class DiagnosticCancellationLatency(private val clock: () -> Long = System::nanoTime) {
    enum class Origin { USER_BUTTON, LIFECYCLE }
    enum class RenderOutcome { NOT_ENTERED, SUCCESS, CANCELLED, FAILED }
    private data class Request(val at: Long, val origin: Origin, val stage: OriginalStage?, val duringRender: Boolean)
    data class Observation(val status: String, val origin: String, val stageAtRequest: String,
        val duringRender: Boolean, val renderOutcome: RenderOutcome,
        val requestToRenderExitNanos: Long?, val requestToAttemptScopeExitNanos: Long?,
        val requestToOwnersReleasedNanos: Long?, val requestToHarnessQuiescentNanos: Long?)
    private var request: Request? = null
    private var targetStage: OriginalStage? = null
    private var rendering = false
    private var outcome = RenderOutcome.NOT_ENTERED
    private var renderExit: Long? = null
    private var scopeExit: Long? = null
    private var ownersReleased = false
    private var harnessExit: Long? = null

    @Synchronized fun request(origin: Origin) {
        if (request == null) request = Request(clock(), origin, targetStage, rendering)
    }
    @Synchronized fun renderStarted() { rendering = true }
    @Synchronized fun stageStarted(stage: OriginalStage) {
        if (stage == OriginalStage.GLOW || stage == OriginalStage.MIST) targetStage = stage
    }
    @Synchronized fun stageFinished(stage: OriginalStage) {
        if (targetStage == stage) targetStage = null
    }
    @Synchronized fun renderExited(result: RenderOutcome) {
        renderExit = clock(); outcome = result; rendering = false; targetStage = null
    }
    @Synchronized fun attemptScopeExited(released: Boolean) {
        scopeExit = clock(); ownersReleased = released
    }
    @Synchronized fun harnessFinished(quiescent: Boolean) {
        if (quiescent) harnessExit = clock()
    }
    @Synchronized fun observation(): Observation {
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
            else -> "OBSERVED_TARGET_STAGE_CANCEL"
        }
        return Observation(status, r?.origin?.name ?: "NONE", r?.stage?.name ?: "OUTSIDE_GLOW_MIST",
            r?.duringRender ?: false, outcome, render, scope, owners, harness)
    }
}
