package dev.cinematic.p0.photoroll

import android.app.ActivityManager
import android.content.Context
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.StatFs
import java.util.concurrent.ConcurrentHashMap

/** Observes availability; never opens, closes or controls a CameraX camera/session. */
internal class PhotoRollResourceObserver(context: Context) {
    private val cameraManager = context.getSystemService(CameraManager::class.java)
    private val activityManager = context.getSystemService(ActivityManager::class.java)
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val privateRoot = context.noBackupFilesDir
    @Volatile private var foreground = false
    private val available = ConcurrentHashMap<String, Boolean>()
    @Volatile private var ids: Set<String>? = null
    @Volatile private var registered = false
    @Volatile private var generation = 0L
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
            }
        } catch (_: Exception) { stop() }
    }

    fun stop() {
        foreground = false
        registered = false
        generation++
        try { callback?.let { cameraManager?.unregisterAvailabilityCallback(it) } } catch (_: Exception) { /* Unconfirmed remains blocked. */ }
        callback = null
        available.clear(); ids = null
    }

    fun foreground(value: Boolean) { foreground = value }

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
