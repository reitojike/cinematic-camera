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
    ManualOriginalTrialSavePhase.UNCONFIRMED -> PhotoRollSaveSnapshot(PhotoRollSavePhase.UNCONFIRMED, gpuFailure = state.gpuFailure)
    ManualOriginalTrialSavePhase.GPU_FAILED -> PhotoRollSaveSnapshot(PhotoRollSavePhase.GPU_FAILED, gpuFailure = state.gpuFailure)
    ManualOriginalTrialSavePhase.CANCELLED -> PhotoRollSaveSnapshot(PhotoRollSavePhase.CANCELLED, gpuFailure = state.gpuFailure)
    ManualOriginalTrialSavePhase.HOLD -> PhotoRollSaveSnapshot(PhotoRollSavePhase.HOLD, gpuFailure = state.gpuFailure)
}

internal fun manualSaveVisibleSnapshot(core: ManualOriginalTrialSaveState,
    local: PhotoRollSaveSnapshot?): PhotoRollSaveSnapshot {
    val observed = manualSaveSnapshot(core)
    return if (observed.phase in setOf(PhotoRollSavePhase.HOLD, PhotoRollSavePhase.GPU_FAILED, PhotoRollSavePhase.CANCELLED)) observed else local ?: observed
}

/** A UI view of one durable generation. The raw owner keeps publication and retry authority. */
@TargetApi(29)
internal class ManualTrialJpegSaveOwner(
    private val handle: AndroidManualOriginalTrialSaves.ManualOriginalTrialHandle,
    private val resources: () -> PhotoRollResources,
) : PhotoRollJpegSaveOwner {
    override val supportsForegroundSave = true
    private val running = AtomicBoolean(false)
    private val localFailure = MutableStateFlow<PhotoRollSaveSnapshot?>(null)
    private var retryGpuFailure = false
    private var retryCancellation = false
    private val exportWork = object : ForegroundExportWork {
        override val identity get() = handle
        private var request: dev.cinematic.p0.rawcapture.AutoDevelopCoordinator.ForegroundExportRequest? = null
        override fun reserveForeground(conditions: dev.cinematic.p0.rawcapture.AutoDevelopConditions): Boolean {
            request = handle.reserveForegroundExport(conditions)
            return request != null
        }
        override val resourcesClosed get() = !handle.hasUnreleasedResources
        override fun advance(conditions: () -> dev.cinematic.p0.rawcapture.AutoDevelopConditions, stop: () -> Boolean) =
            handle.advanceForegroundExport(requireNotNull(request), conditions, retryGpuFailure = retryGpuFailure, retryCancellation = retryCancellation, stop = stop).thenApply { Unit }
        override fun observe(conditions: dev.cinematic.p0.rawcapture.AutoDevelopConditions) = handle.updateExportConditions(conditions)
        override fun requestStop() { request?.let(handle::stopForegroundExport) }
        override fun finished(failure: Throwable?) {
            request?.let(handle::abandonForegroundExport)
            request = null
            retryGpuFailure = false
            retryCancellation = false
            localFailure.value = if (failure == null) null else if (handle.hasUnreleasedResources)
                PhotoRollSaveSnapshot(PhotoRollSavePhase.HOLD) else PhotoRollSaveSnapshot(PhotoRollSavePhase.UNCONFIRMED)
        }
    }
    override val snapshots = combine(handle.state, localFailure, ForegroundExports.changes) { core, local, _ -> visible(core, local) }
    override val snapshot get() = visible(handle.state.value, localFailure.value)
    private fun visible(core: ManualOriginalTrialSaveState, local: PhotoRollSaveSnapshot?): PhotoRollSaveSnapshot {
        if (core.phase == ManualOriginalTrialSavePhase.HOLD) return manualSaveSnapshot(core)
        val active = ForegroundExports.gate.active()?.takeIf { it.value.identity === handle }
        return if (active != null) PhotoRollSaveSnapshot(if (active.held) PhotoRollSavePhase.HOLD else PhotoRollSavePhase.SAVING)
            else manualSaveVisibleSnapshot(core, local)
    }
    override val width get() = handle.geometry.outputWidth
    override val height get() = handle.geometry.outputHeight
    override val appliedStrength: Double get() = handle.appliedStrength
    override val appliedAdjustments: PhotoAdjustments get() = handle.appliedAdjustments

    override fun matches(source: SharedOriginalReference, mode: PhotoRollPreviewMode) =
        mode == PhotoRollPreviewMode.MANUAL_TRIAL && handle.matches(source)

    override fun startForegroundSave(context: android.content.Context): Boolean {
        if (snapshot.phase == PhotoRollSavePhase.PUBLISHED) return false
        if (snapshot.phase !in setOf(PhotoRollSavePhase.READY, PhotoRollSavePhase.UNCONFIRMED, PhotoRollSavePhase.GPU_FAILED, PhotoRollSavePhase.CANCELLED)) return true
        retryGpuFailure = snapshot.phase == PhotoRollSavePhase.GPU_FAILED
        retryCancellation = snapshot.phase == PhotoRollSavePhase.CANCELLED
        localFailure.value = null
        if (!ForegroundExports.start(context, exportWork, resources().developConditions(rendererAvailable = true)))
            localFailure.value = PhotoRollSaveSnapshot(PhotoRollSavePhase.UNCONFIRMED)
        return true
    }
    override val canCancelForegroundSave get() = ForegroundExports.owns(exportWork)
    override fun cancelForegroundSave() { ForegroundExports.cancel(exportWork) }

    override fun advance(shouldStop: () -> Boolean): PhotoRollSaveSnapshot {
        if (!running.compareAndSet(false, true)) return PhotoRollSaveSnapshot(PhotoRollSavePhase.SAVING)
        try {
            val explicitGpuRetry = snapshot.phase == PhotoRollSavePhase.GPU_FAILED
            val explicitCancellationRetry = snapshot.phase == PhotoRollSavePhase.CANCELLED
            localFailure.value = null
            if (shouldStop()) return uncertain()
            val future = handle.advance(resources().developConditions(rendererAvailable = true), retryGpuFailure = explicitGpuRetry, retryCancellation = explicitCancellationRetry, stop = shouldStop)
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
        return (if (core.phase in setOf(PhotoRollSavePhase.HOLD, PhotoRollSavePhase.GPU_FAILED, PhotoRollSavePhase.CANCELLED)) core else
            PhotoRollSaveSnapshot(PhotoRollSavePhase.UNCONFIRMED)).also { localFailure.value = it }
    }
}
