package dev.cinematic.p0.jpegrender.original

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.cinematic.p0.gpu.*
import dev.cinematic.p0.rawcapture.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** #289: exact GPU-band cancellation -> real same-generation JPEG retry.
 * Synthetic 512x770 DNG, not 12/24MP acceptance, physical trim or a performance/quality gate.
 * Complements #346's JPEG-entry negative control without replacing or duplicating that case.
 */
@RunWith(AndroidJUnit4::class)
class Original24GpuBoundaryCancellationAndroidTest {
    @Test fun cancelInsideSecondFinalBandRetriesSameGenerationAndReleasesTextures() = cancelAndRetry(insideBand = true)
    @Test fun cancelAfterSecondB1BandRetriesSameGenerationAndReleasesTextures() = cancelAndRetry(insideBand = false)

    private fun cancelAndRetry(insideBand: Boolean) = GpuExportBoundaryFixture.run { fixture ->
        val save = fixture.prepare()
        val request = save.request
        val stopEvent = AtomicReference<GpuExportBoundaryWitness.Event>()
        val cancelledWitness = GpuExportBoundaryWitness { event ->
            val target = event.origin == 256 && event.rows == 256 &&
                if (insideBand) !event.complete && event.stage == "MIST" else event.complete && event.stage == "B1"
            if (target && stopEvent.compareAndSet(null, event)) {
                fixture.assertWorkingSource(save)
                // Use the real save owner's cancellation signal, never an injected exception.
                save.requestStop()
            }
        }
        val cancelled = Original24GpuOutput.observeForDiagnostics(cancelledWitness).use {
            save.advance(GpuExportBoundaryFixture.ALLOWED) { false }.get(180, TimeUnit.SECONDS)
        }
        assertNotNull("Requested named band must be reached", stopEvent.get())
        assertSame("No later GPU band callback after the requested stop", stopEvent.get(), cancelledWitness.events.last())
        assertEquals(ManualOriginalTrialSavePhase.CANCELLED, cancelled.phase)
        assertEquals(ManualOriginalTrialSaveReason.STOPPED, cancelled.reason)
        assertNull(cancelled.uri); assertNull(cancelled.gpuFailure)
        cancelledWitness.assertReleased()
        fixture.assertClosed(save, unpublished = true)
        fixture.verifyOriginals()
        val intent = requireNotNull(fixture.intent(save).read(ManualOriginalTrialRequestCodec.MAX_BYTES))
        assertArrayEquals(ManualOriginalTrialRequestCodec.encode(request), intent)
        if (insideBand) {
            assertTrue("First FINAL band was delivered before cancelling inside the second",
                cancelledWitness.events.any { it.complete && it.stage == "FINAL" && it.origin == 0 && it.rows == 256 })
            assertFalse(cancelledWitness.events.any { it.complete && it.stage == "FINAL" && it.origin == 256 })
        } else {
            assertFalse("B1 cancellation precedes final readback", cancelledWitness.events.any { it.stage == "MIST" })
        }

        val idleWitness = GpuExportBoundaryWitness()
        Original24GpuOutput.observeForDiagnostics(idleWitness).use {
            assertEquals(cancelled, save.advance(GpuExportBoundaryFixture.ALLOWED) { false }.get(30, TimeUnit.SECONDS))
            assertSame(save, AndroidManualOriginalTrialSaves.prepare(fixture.app, request.source, request.strength,
                GpuExportBoundaryFixture.ALLOWED, request.adjustments).get(30, TimeUnit.SECONDS))
        }
        assertTrue("Normal advance/prepare cannot restart GPU", idleWitness.events.isEmpty() && idleWitness.released.isEmpty())
        assertNull(fixture.journal(save).read())
        assertArrayEquals(intent, fixture.intent(save).read(ManualOriginalTrialRequestCodec.MAX_BYTES))

        // Next-save preference must not change the engine frozen by this cancelled generation.
        assertTrue(fixture.setting.select(DevelopEngine.CPU, GpuAvailability.Available))
        val retryWitness = GpuExportBoundaryWitness()
        Original24GpuOutput.observeForDiagnostics(retryWitness).use {
            save.advance(GpuExportBoundaryFixture.ALLOWED, retryCancellation = true) { false }.get(180, TimeUnit.SECONDS)
        }
        retryWitness.assertReleased()
        assertEquals(listOf(0 to 256, 256 to 256, 512 to 256, 768 to 2),
            retryWitness.events.filter { it.complete && it.stage == "FINAL" }.map { it.origin to it.rows })
        assertEquals(request, save.request)
        assertEquals(DevelopEngine.CPU, fixture.setting.current())
        assertArrayEquals(intent, fixture.intent(save).read(ManualOriginalTrialRequestCodec.MAX_BYTES))
        fixture.verifyPublication(save)
        assertNull(save.state.value.gpuFailure)
        val published = save.state.value
        val record = fixture.journal(save).read()
        val reconcileWitness = GpuExportBoundaryWitness()
        Original24GpuOutput.observeForDiagnostics(reconcileWitness).use {
            assertEquals(published, save.advance(GpuExportBoundaryFixture.ALLOWED) { false }.get(30, TimeUnit.SECONDS))
        }
        assertTrue(reconcileWitness.events.isEmpty() && reconcileWitness.released.isEmpty())
        assertEquals(record, fixture.journal(save).read())
        Log.i("GpuExportBoundaries", "case=${if (insideBand) "inside_final_band" else "after_b1_band"} " +
            "origin=256 rows=256 sameGeneration=true explicitRetry=published textureBytes=0 textures=0")
    }
}
