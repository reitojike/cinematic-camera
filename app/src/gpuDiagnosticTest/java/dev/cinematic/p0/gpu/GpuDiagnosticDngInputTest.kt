package dev.cinematic.p0.gpu

import android.net.Uri
import android.os.CancellationSignal
import android.os.OperationCanceledException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.photoroll.PhotoRollCameraSnapshot
import dev.cinematic.p0.photoroll.PhotoRollResources
import dev.cinematic.p0.photoroll.PhotoRollSourceFailure
import dev.cinematic.p0.rawcapture.DevelopExecution
import dev.cinematic.p0.rawcapture.DngVerificationStopped
import dev.cinematic.p0.rawcapture.AutoDevelopConditions
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.CompletionException
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** No photo fixtures or provider reads. Runs only in the isolated diagnostic emulator lane. */
@RunWith(AndroidJUnit4::class)
class GpuDiagnosticDngInputTest {
    private var ownsTestConditions = false
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val available = PhotoRollResources(true, 0, false, 2L * 1024 * 1024 * 1024,
        PhotoRollCameraSnapshot(false, false, false, false), true, true)

    @Before fun isolatedEmulatorOnly() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish")) { "Synthetic emulator only" }
        // Controlled admission observations only; not a measured device-capacity claim.
        DevelopExecution.updateConditions(available.developConditions(true))
        ownsTestConditions = true
    }

    @After fun restoreUnconfirmedConditions() {
        if (!ownsTestConditions) return
        try { GpuDiagnosticDngInput.release(context).get(10, TimeUnit.SECONDS) }
        finally { DevelopExecution.updateConditions(AutoDevelopConditions()) }
    }

    @Test fun cancelledSelectionDoesNotReadOrRetainAdmission() {
        val signal = CancellationSignal().apply { cancel() }
        val failure = failure { GpuDiagnosticDngInput.load(context, Uri.EMPTY, { available }, signal)
            .get(10, TimeUnit.SECONDS) }
        assertTrue(failure is OperationCanceledException)
        assertAdmissionReleased()
    }

    @Test fun wrappedCancellationIsNotAReadFailure() {
        val signal = CancellationSignal()
        for (cancelled in listOf(OperationCanceledException(), CancellationException(), DngVerificationStopped())) {
            assertTrue(inputWasCancelled(signal, CompletionException(ExecutionException(cancelled))))
        }
    }

    @Test fun trueReadFailureIsNotCancellation() {
        val signal = CancellationSignal()
        assertFalse(inputWasCancelled(signal, CompletionException(IOException("synthetic read failure"))))
        assertFalse(inputWasCancelled(signal, null))
        val malformed = object : RuntimeException() {
            override val cause: Throwable get() = throw IllegalStateException("synthetic accessor failure")
        }
        assertFalse(inputWasCancelled(signal, malformed))
        signal.cancel()
        assertTrue(inputWasCancelled(signal, null))
    }

    @Test fun nonLocalSelectionIsRejectedAndReleasesAdmissionForNextSelection() {
        // Rejection occurs in the existing local-files policy before querying any provider.
        repeat(2) {
            val failure = failure { GpuDiagnosticDngInput.load(context,
                Uri.parse("content://invalid.example/document/test.dng"), { available }, CancellationSignal())
                .get(10, TimeUnit.SECONDS) }
            assertTrue(failure is PhotoRollSourceFailure)
            assertAdmissionReleased()
        }
    }

    @Test fun unconfirmedForegroundCannotAcquireInputAdmission() {
        val failure = failure { GpuDiagnosticDngInput.load(context, Uri.EMPTY,
            { available.copy(foreground = null) }, CancellationSignal()).get(10, TimeUnit.SECONDS) }
        assertTrue(failure is IOException)
        assertAdmissionReleased()
    }

    private fun assertAdmissionReleased() {
        GpuDiagnosticDngInput.release(context).get(10, TimeUnit.SECONDS)
        val slot = DevelopExecution.tryStartPreviewObserved(available.developConditions(true))
        assertNotNull("Failed/cancelled input must release confirmed-closed resources", slot)
        DevelopExecution.completeAfterResourcesClosed(requireNotNull(slot))
    }

    private fun failure(action: () -> Unit): Throwable {
        try { action() } catch (failure: ExecutionException) { return requireNotNull(failure.cause) }
        fail("Input should have been rejected")
        throw AssertionError("unreachable")
    }
}
