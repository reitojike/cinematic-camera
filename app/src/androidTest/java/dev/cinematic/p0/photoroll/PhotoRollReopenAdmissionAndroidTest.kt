package dev.cinematic.p0.photoroll

import android.os.CancellationSignal
import android.os.SystemClock
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.CameraProbe
import dev.cinematic.p0.rawcapture.AutoDevelopPolicy
import dev.cinematic.p0.rawcapture.DevelopExecution
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

/** Isolated emulator only: real state observation/admission, synthetic bytes, no camera or photos. */
class PhotoRollReopenAdmissionAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val idle = PhotoRollCameraSnapshot(false, false, false, false)

    private fun withObserver(block: (PhotoRollResourceObserver) -> Unit) {
        check(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        val observer = PhotoRollResourceObserver(instrumentation.targetContext, DevelopExecution::updateConditions)
        instrumentation.runOnMainSync { observer.start(); observer.foreground(true) }
        try { block(observer) } finally { instrumentation.runOnMainSync { observer.stop() } }
    }

    private fun awaitAllowed(observer: PhotoRollResourceObserver) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        do {
            if (AutoDevelopPolicy.blockers(observer.snapshot().developConditions(true)).isEmpty()) return
            SystemClock.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        fail("State observation did not become ready: " +
            AutoDevelopPolicy.blockers(observer.snapshot().developConditions(true)))
    }

    private fun readSyntheticOriginal(observer: PhotoRollResourceObserver): Int =
        PhotoRollOriginalChecks.run(observer::snapshot, CancellationSignal()) { readers, _ ->
            readers.open { ByteArrayInputStream(byteArrayOf(73)) }.use { it.read() }
        }

    @Test fun restoredEditorWithoutCaptureHostCanReadAndResume() = withObserver { observer ->
        assertEquals(idle, AndroidPhotoRollCameraObservations.snapshot())
        awaitAllowed(observer)
        assertEquals(73, readSyntheticOriginal(observer))
        instrumentation.runOnMainSync { observer.foreground(false); observer.stop() }
        assertNotNull(photoRollAdmissionFailure(observer.snapshot().developConditions(true)))
        instrumentation.runOnMainSync { observer.start(); observer.foreground(true) }
        awaitAllowed(observer)
        assertEquals(73, readSyntheticOriginal(observer))
    }

    @Test fun lastFinishedCaptureHostDoesNotRequireAReplacement() = withObserver { observer ->
        instrumentation.runOnMainSync {
            val lifecycle = object : LifecycleOwner { override val lifecycle = LifecycleRegistry(this) }
            val probe = CameraProbe(instrumentation.targetContext, lifecycle)
            probe.onHostStarting()
            assertEquals(true, AndroidPhotoRollCameraObservations.snapshot().cameraMayStart)
            probe.close() // No initialize/bind, takePicture, or recording call.
        }
        assertEquals(idle, AndroidPhotoRollCameraObservations.snapshot())
        awaitAllowed(observer)
        assertEquals(73, readSyntheticOriginal(observer))
    }

    @Test fun pendingCaptureWithoutUiStillBlocksUntilCallbacksAndWriterFinish() = withObserver { observer ->
        awaitAllowed(observer)
        var callbacksFinished = false
        var writerFinished = false
        instrumentation.runOnMainSync {
            AndroidPhotoRollCameraObservations.holdCaptureUntil { callbacksFinished && writerFinished }
        }
        try {
            assertEquals(true, AndroidPhotoRollCameraObservations.snapshot().captureInProgress)
            try { readSyntheticOriginal(observer); fail("Unfinished capture must block the source read") }
            catch (expected: PhotoRollSourceFailure) { assertTrue(expected.message.orEmpty().contains("撮影処理")) }
            instrumentation.runOnMainSync { callbacksFinished = true }
            assertEquals(true, AndroidPhotoRollCameraObservations.snapshot().captureInProgress)
            instrumentation.runOnMainSync { writerFinished = true }
            assertEquals(idle, AndroidPhotoRollCameraObservations.snapshot())
            awaitAllowed(observer)
            assertEquals(73, readSyntheticOriginal(observer))
        } finally {
            instrumentation.runOnMainSync { callbacksFinished = true; writerFinished = true }
            AndroidPhotoRollCameraObservations.snapshot() // Retire the finished synthetic lease.
        }
    }
}
