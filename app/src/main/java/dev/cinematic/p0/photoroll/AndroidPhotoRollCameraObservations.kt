package dev.cinematic.p0.photoroll

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** CameraProbe state is read as one main-thread observation, never from a stale launch extra. */
internal object AndroidPhotoRollCameraObservations {
    private val owners = PhotoRollCameraOwners()

    fun register(read: () -> PhotoRollCameraSnapshot): PhotoRollCameraOwners.Owner {
        check(Looper.myLooper() == Looper.getMainLooper())
        return owners.register(read)
    }

    fun holdCaptureUntil(closed: () -> Boolean) {
        check(Looper.myLooper() == Looper.getMainLooper())
        owners.holdCaptureUntil(closed)
    }

    fun snapshot(): PhotoRollCameraSnapshot {
        if (Looper.myLooper() == Looper.getMainLooper()) return owners.snapshot()
        val result = CompletableFuture<PhotoRollCameraSnapshot>()
        if (!Handler(Looper.getMainLooper()).post { result.complete(owners.snapshot()) }) return PhotoRollCameraSnapshot()
        return try { result.get(500, TimeUnit.MILLISECONDS) }
        catch (_: TimeoutException) {
            Log.w("CinematicRaw", "PHOTO_ROLL_CAMERA_SNAPSHOT_TIMEOUT")
            PhotoRollCameraSnapshot()
        }
        catch (_: Exception) { PhotoRollCameraSnapshot() }
    }
}
