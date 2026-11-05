package dev.cinematic.p0.photoroll

import android.annotation.TargetApi
import dev.cinematic.p0.raw.PhotoAdjustments
import dev.cinematic.p0.rawcapture.AndroidManualOriginalTrialSaves
import dev.cinematic.p0.rawcapture.ManualOriginalTrialSavePhase
import dev.cinematic.p0.rawcapture.ManualOriginalTrialSaveState
import dev.cinematic.p0.rawcapture.SharedOriginalReference
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine

internal fun manualSaveSnapshot(state: ManualOriginalTrialSaveState): PhotoRollSaveSnapshot = when (state.phase) {
    ManualOriginalTrialSavePhase.READY -> PhotoRollSaveSnapshot(PhotoRollSavePhase.READY)
    ManualOriginalTrialSavePhase.SAVING -> PhotoRollSaveSnapshot(PhotoRollSavePhase.SAVING)
    ManualOriginalTrialSavePhase.PUBLISHED -> if (state.uri != null)
        PhotoRollSaveSnapshot(PhotoRollSavePhase.PUBLISHED, state.uri) else PhotoRollSaveSnapshot(PhotoRollSavePhase.HOLD)
    ManualOriginalTrialSavePhase.UNCONFIRMED -> PhotoRollSaveSnapshot(PhotoRollSavePhase.UNCONFIRMED)
    ManualOriginalTrialSavePhase.HOLD -> PhotoRollSaveSnapshot(PhotoRollSavePhase.HOLD)
}

/** A UI view of one durable generation. The raw owner keeps publication and retry authority. */
@TargetApi(29)
internal class ManualTrialJpegSaveOwner(
    private val handle: AndroidManualOriginalTrialSaves.ManualOriginalTrialHandle,
    private val resources: () -> PhotoRollResources,
) : PhotoRollJpegSaveOwner {
    private val running = AtomicBoolean(false)
    private val localFailure = MutableStateFlow<PhotoRollSaveSnapshot?>(null)
    override val snapshots = combine(handle.state, localFailure) { core, local -> local ?: manualSaveSnapshot(core) }
    override val snapshot get() = localFailure.value ?: manualSaveSnapshot(handle.state.value)
    override val width get() = handle.geometry.outputWidth
    override val height get() = handle.geometry.outputHeight
    override val appliedStrength: Double get() = handle.appliedStrength
    override val appliedAdjustments: PhotoAdjustments get() = handle.appliedAdjustments

    override fun matches(source: SharedOriginalReference, mode: PhotoRollPreviewMode) =
        mode == PhotoRollPreviewMode.MANUAL_TRIAL && handle.matches(source)

    override fun advance(shouldStop: () -> Boolean): PhotoRollSaveSnapshot {
        if (!running.compareAndSet(false, true)) return PhotoRollSaveSnapshot(PhotoRollSavePhase.SAVING)
        try {
            localFailure.value = null
            if (shouldStop()) return uncertain()
            val future = handle.advance(resources().developConditions(rendererAvailable = true), shouldStop)
            var stopRequested = false
            while (true) {
                if (shouldStop() && !stopRequested) { stopRequested = true; handle.requestStop() }
                try {
                    val result = future.get(250, TimeUnit.MILLISECONDS)
                    if (shouldStop()) return uncertain()
                    return manualSaveSnapshot(result)
                } catch (_: TimeoutException) { /* The same queue owner still owns the work. */ }
            }
        } catch (_: InterruptedException) {
            handle.requestStop()
            Thread.currentThread().interrupt()
            return uncertain()
        } catch (_: Exception) { return uncertain() }
        catch (_: OutOfMemoryError) { handle.requestStop(); return uncertain() }
        finally { running.set(false) }
    }

    private fun uncertain(): PhotoRollSaveSnapshot {
        val core = manualSaveSnapshot(handle.state.value)
        return (if (core.phase == PhotoRollSavePhase.HOLD) core else
            PhotoRollSaveSnapshot(PhotoRollSavePhase.UNCONFIRMED)).also { localFailure.value = it }
    }
}
