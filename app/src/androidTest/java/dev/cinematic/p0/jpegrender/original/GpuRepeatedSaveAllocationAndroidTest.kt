package dev.cinematic.p0.jpegrender.original

import android.os.Debug
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

/** Actual synthetic DNG -> GPU save -> JPEG publication. Never changes save owner lifetime. */
class GpuRepeatedSaveAllocationAndroidTest {
    @Test fun fourPublishedSavesRecordNativeAllocationAfterConfirmedCleanup() =
        GpuExportBoundaryFixture.run(width = 1536, height = 1024, ordinaryActivity = true) { fixture ->
            val samples = JSONArray()
            repeat(4) { index ->
                // A distinct immutable original makes each iteration a real save, not published reuse.
                val source = if (index == 0) fixture.sources.single() else fixture.createSource()
                saveOnce(fixture, source)
                SystemClock.sleep(1_000)
                Runtime.getRuntime().gc(); System.runFinalization(); Runtime.getRuntime().gc()
                SystemClock.sleep(1_000)
                samples.put(JSONObject().put("iteration", index)
                    .put("nativeHeapBytes", Debug.getNativeHeapAllocatedSize()))
            }
            val app = InstrumentationRegistry.getInstrumentation().targetContext
            val dir = File(checkNotNull(app.getExternalFilesDir(null)), "additional_test_output/owner-reuse")
            check(dir.mkdirs() || dir.isDirectory)
            File(dir, "four-saves.json").writeText(JSONObject().put("samples", samples)
                .put("scope", "Fresh context per actual GPU save; synthetic 1536x1024 DNG; confirmed publication and cleanup").toString())
        }

    private fun saveOnce(fixture: GpuExportBoundaryFixture, source: dev.cinematic.p0.rawcapture.SharedOriginalReference) {
        val save = fixture.prepare(source)
        val witness = GpuExportBoundaryWitness()
        Original24GpuOutput.observeForDiagnostics(witness).use {
            save.advance(GpuExportBoundaryFixture.ALLOWED) { false }.get(180, TimeUnit.SECONDS)
        }
        witness.assertReleased()
        fixture.verifyPublication(save)
        fixture.verifyOriginals()
    }
}
