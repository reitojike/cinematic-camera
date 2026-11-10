package dev.cinematic.p0.photoroll

import android.os.CancellationSignal
import android.os.OperationCanceledException
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PreviewTimingAndroidTest {
    @Test fun platformCancellationIsNeverRecordedAsFailure() {
        val signal = CancellationSignal().also { it.cancel() }
        val failure = try {
            signal.throwIfCanceled()
            error("Cancelled signal must throw")
        } catch (cancelled: OperationCanceledException) { cancelled }
        // Check both the owned signal and an exception from a renderer's own stop source.
        for (cancelled in listOf(signal.isCanceled, false)) {
            val lines = mutableListOf<String>()
            val timing = PreviewTiming(1, clock = { 0L }, sink = lines::add)
            timing.finishFailure(cancelled, failure)
            timing.finish("NOT_ADOPTED")
            assertEquals(1, lines.size)
            assertTrue(lines.single().contains("outcome=CANCELLED "))
        }
    }
}
