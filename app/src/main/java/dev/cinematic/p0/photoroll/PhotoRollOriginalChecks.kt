package dev.cinematic.p0.photoroll

import android.os.CancellationSignal
import android.os.OperationCanceledException
import dev.cinematic.p0.rawcapture.AutoDevelopCoordinator
import dev.cinematic.p0.rawcapture.DevelopExecution
import java.io.Closeable
import java.io.FilterInputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/** Retains failed-close cursors/streams; read cancellation is never a close acknowledgement. */
internal class PhotoRollReadResources {
    private val pending = mutableListOf<Closeable>()
    @Volatile var finished = false
    @Synchronized private fun track(resource: Closeable) { pending += resource }
    @Synchronized private fun close(resource: Closeable) {
        if (pending.none { it === resource }) return
        resource.close()
        pending.removeAll { it === resource }
    }
    fun open(create: () -> InputStream): InputStream {
        val resource = create().also(::track)
        return object : FilterInputStream(resource) { override fun close() = this@PhotoRollReadResources.close(resource) }
    }
    fun <T : Closeable, R> useOwned(resource: T, block: (T) -> R): R {
        track(resource)
        var failure: Throwable? = null
        try { return block(resource) }
        catch (error: Throwable) { failure = error; throw error }
        finally { try { close(resource) } catch (closing: Throwable) {
            if (failure == null) throw closing else if (failure !== closing) failure.addSuppressed(closing)
        } }
    }
    @Synchronized fun closeRetained(): Boolean {
        pending.toList().forEach { try { close(it) } catch (_: Exception) { /* Retain for explicit retry. */ } }
        return pending.isEmpty()
    }
}

/** Hash lease ends BEFORE preparing/awaiting a queue handle. There is no second execution gate. */
internal object PhotoRollOriginalChecks {
    private val owners = ConcurrentHashMap<AutoDevelopCoordinator.Attempt, PhotoRollReadResources>()
    private fun release(token: AutoDevelopCoordinator.Attempt, reader: PhotoRollReadResources) {
        if (reader.finished && reader.closeRetained() && owners.remove(token, reader)) DevelopExecution.completeAfterResourcesClosed(token)
    }
    fun <T> run(resources: () -> PhotoRollResources, cancellation: CancellationSignal, preview: Boolean = false,
        gpuRequest: dev.cinematic.p0.gpu.preview.GpuInputRequestDeadline? = null,
        onGpuAdmission: (Throwable?) -> Unit = {},
        read: (PhotoRollReadResources, () -> Boolean) -> T): T {
        owners.forEach { (token, reader) -> release(token, reader) }
        var admitted: AutoDevelopCoordinator.Attempt? = null
        if (gpuRequest != null) {
            cancellation.throwIfCanceled()
            val conditions = resources().developConditions(rendererAvailable = true)
            lateinit var result: AutoDevelopCoordinator.GpuDiagnosticPreviewAdmission
            admitted = gpuRequest.tryAdmit {
                DevelopExecution.tryStartGpuDiagnosticPreviewObserved(conditions).also { result = it }.attempt
            }
            if (admitted == null) throw dev.cinematic.p0.gpu.preview.GpuInputAdmissionRefused(result.snapshot).also(onGpuAdmission)
            onGpuAdmission(null)
        }
        while (admitted == null) {
            cancellation.throwIfCanceled()
            val conditions = resources().developConditions(rendererAvailable = true)
            photoRollAdmissionFailure(conditions, preview)?.let { throw PhotoRollSourceFailure(it) }
            admitted = if (preview) DevelopExecution.tryStartPreviewObserved(conditions)
                else DevelopExecution.tryStartObserved(conditions)
            if (admitted == null) {
                if (owners.values.any { it.finished }) throw PhotoRollSourceFailure("前の原本読み取りの解放を確認できません。原本を確認して再試行してください")
                Thread.sleep(100)
            }
        }
        val token = requireNotNull(admitted)
        val reader = PhotoRollReadResources()
        owners[token] = reader
        try {
            cancellation.throwIfCanceled()
            if (DevelopExecution.shouldStop(token)) throw OperationCanceledException()
            return read(reader) { cancellation.isCanceled || DevelopExecution.shouldStop(token) }
        } finally { reader.finished = true; release(token, reader) }
    }
}
