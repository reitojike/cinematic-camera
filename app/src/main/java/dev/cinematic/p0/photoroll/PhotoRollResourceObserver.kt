package dev.cinematic.p0.photoroll

import android.app.ActivityManager
import android.content.Context
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.StatFs
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import dev.cinematic.p0.rawcapture.AutoDevelopConditions
import dev.cinematic.p0.rawcapture.AutoDevelopBlocker
import dev.cinematic.p0.rawcapture.AutoDevelopPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Observes availability; never opens, closes or controls a CameraX camera/session. */
internal class PhotoRollResourceObserver(context: Context,
    private val publishConditions: (AutoDevelopConditions) -> Unit = {},
    private val authority: PhotoRollObservationAuthority = PhotoRollObservationOwners.authority,
    private val serviceOwned: Boolean = false,
) {
    private val cameraManager = context.getSystemService(CameraManager::class.java)
    private val activityManager = context.getSystemService(ActivityManager::class.java)
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val privateRoot = context.noBackupFilesDir
    @Volatile private var foreground = false
    private val available = ConcurrentHashMap<String, Boolean>()
    @Volatile private var ids: Set<String>? = null
    @Volatile private var registered = false
    @Volatile private var generation = 0L
    private val observationLock = Any()
    private var observationEpoch = 0L
    private var lastPublishedBlockers: Set<AutoDevelopBlocker>? = null
    private var polling: CoroutineScope? = null
    private var callback: CameraManager.AvailabilityCallback? = null

    fun start() {
        stop()
        try {
            ids = cameraManager?.cameraIdList?.toSet()?.takeIf { it.isNotEmpty() }
            if (ids != null) {
                registered = true
                val epoch = generation
                val next = object : CameraManager.AvailabilityCallback() {
                    override fun onCameraAvailable(cameraId: String) { if (registered && generation == epoch) available[cameraId] = true }
                    override fun onCameraUnavailable(cameraId: String) { if (registered && generation == epoch) available[cameraId] = false }
                }
                callback = next
                cameraManager?.registerAvailabilityCallback(next, Handler(Looper.getMainLooper()))
                polling = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scope -> scope.launch {
                    while (isActive) {
                        val observedEpoch = synchronized(observationLock) { generation to observationEpoch }
                        val conditions = snapshot().developConditions(rendererAvailable = true)
                        synchronized(observationLock) {
                            if (registered && generation == observedEpoch.first && observationEpoch == observedEpoch.second) {
                                val blockers = AutoDevelopPolicy.blockers(conditions)
                                if (foreground && lastPublishedBlockers != blockers) {
                                    Log.i("CinematicRaw", "PHOTO_ROLL_BLOCKERS=${blockers.map { it.name }.sorted().joinToString(",")}")
                                    lastPublishedBlockers = blockers
                                }
                                if (serviceOwned) publishConditions(conditions)
                                else authority.observed(this@PhotoRollResourceObserver, conditions, publishConditions)
                            }
                        }
                        delay(500)
                    }
                } }
            }
        } catch (_: Exception) { stop() }
    }

    fun stop() {
        synchronized(observationLock) {
            foreground = false
            registered = false
            generation++
            observationEpoch++
            lastPublishedBlockers = null
            if (serviceOwned) publishConditions(AutoDevelopConditions())
            else authority.foreground(this, false, publishConditions)
        }
        polling?.cancel(); polling = null
        try { callback?.let { cameraManager?.unregisterAvailabilityCallback(it) } } catch (_: Exception) { /* Unconfirmed remains blocked. */ }
        callback = null
        available.clear(); ids = null
    }

    fun foreground(value: Boolean) = synchronized(observationLock) {
        foreground = value
        observationEpoch++ // A pre-pause IO observation must not restore an old allowed snapshot.
        lastPublishedBlockers = null
        Log.i("CinematicRaw", "PHOTO_ROLL_FOREGROUND=$value")
        authority.foreground(this, value, publishConditions)
    }

    fun snapshot(): PhotoRollResources {
        val idle = ids?.let { known -> registered && known.all { available[it] == true } }
        val thermal = try { if (Build.VERSION.SDK_INT >= 29) powerManager?.currentThermalStatus else null } catch (_: Exception) { null }
        val memory = try { ActivityManager.MemoryInfo().also { info -> activityManager?.getMemoryInfo(info) } }
            catch (_: Exception) { null }
        val storage = try { StatFs(privateRoot.absolutePath).availableBytes >= PRIVATE_REQUIRED_BYTES }
            catch (_: Exception) { null }
        return PhotoRollResources(idle, thermal, memory?.lowMemory, memory?.availMem,
            AndroidPhotoRollCameraObservations.snapshot(), foreground, storage)
    }

    companion object { private const val PRIVATE_REQUIRED_BYTES = 64L * 1024 * 1024 }
}
