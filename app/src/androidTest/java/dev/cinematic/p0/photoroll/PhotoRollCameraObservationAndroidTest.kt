package dev.cinematic.p0.photoroll

import android.os.SystemClock
import androidx.camera.view.PreviewView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.CameraProbe
import dev.cinematic.p0.rawcapture.AutoDevelopConditions
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhotoRollCameraObservationAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun foregroundChangeKeepsCameraAvailabilityCallbacksAndStopsAllowedObservations() {
        val published = CopyOnWriteArrayList<AutoDevelopConditions>()
        val observer = PhotoRollResourceObserver(instrumentation.targetContext, publishConditions = { published.add(it) })
        instrumentation.runOnMainSync { observer.start(); observer.foreground(true) }
        try {
            val deadline = SystemClock.elapsedRealtime() + 15_000
            var idle: Boolean?
            do {
                idle = observer.snapshot().camerasIdle
                if (idle == true) break
                SystemClock.sleep(50)
            } while (SystemClock.elapsedRealtime() < deadline)
            assertEquals(true, idle)
            instrumentation.runOnMainSync { observer.foreground(false) }
            val pausedFrom = published.lastIndex
            SystemClock.sleep(700)
            assertTrue(published.drop(pausedFrom).all { it.foreground == false })
        } finally { instrumentation.runOnMainSync { observer.stop() } }
    }

    @Test fun hostStartingIsVisibleBeforeLifecycleAndBackgroundClearsOnlyItsOwnIntent() {
        lateinit var probe: CameraProbe
        instrumentation.runOnMainSync {
            val owner = object : LifecycleOwner { override val lifecycle = LifecycleRegistry(this) }
            probe = CameraProbe(instrumentation.targetContext, owner)
            probe.onHostStarting()
            assertEquals(Lifecycle.State.INITIALIZED, owner.lifecycle.currentState)
            assertEquals(true, AndroidPhotoRollCameraObservations.snapshot().cameraMayStart)
            probe.onBackground()
            assertEquals(false, AndroidPhotoRollCameraObservations.snapshot().cameraMayStart)
        }
        try {
            // Exercise the real bounded worker→main observation path.
            assertEquals(false, AndroidPhotoRollCameraObservations.snapshot().recordingOrFinalizing)
        } finally { instrumentation.runOnMainSync { probe.close() } }
    }

    @Test fun providerDeliveredWhileBackgroundCannotBindCamera() {
        lateinit var probe: CameraProbe
        instrumentation.runOnMainSync {
            val owner = object : LifecycleOwner {
                override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.CREATED }
            }
            probe = CameraProbe(instrumentation.targetContext, owner)
            probe.initialize(PreviewView(instrumentation.targetContext))
        }
        try {
            val deadline = SystemClock.elapsedRealtime() + 15_000
            var observed: PhotoRollCameraSnapshot
            do {
                observed = AndroidPhotoRollCameraObservations.snapshot()
                if (observed.cameraMayStart == false) break
                SystemClock.sleep(50)
            } while (SystemClock.elapsedRealtime() < deadline)
            assertEquals(false, observed.cameraMayStart)
            assertEquals(false, observed.rebindInProgress)
            instrumentation.runOnMainSync { assertFalse(probe.bound) }
        } finally { instrumentation.runOnMainSync { probe.close() } }
    }
}
