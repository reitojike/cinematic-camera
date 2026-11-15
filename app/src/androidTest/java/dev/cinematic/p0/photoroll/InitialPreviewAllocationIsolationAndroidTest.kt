package dev.cinematic.p0.photoroll

import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.CancellationSignal
import android.os.Debug
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.gpu.GpuPreviewInputSize
import dev.cinematic.p0.jpegrender.original.*
import dev.cinematic.p0.photoedit.PhotoAdjustmentPolicy
import dev.cinematic.p0.raw.LinearSrgbImage
import dev.cinematic.p0.rawcapture.*
import dev.cinematic.p0.testing.writeSyntheticTrialDng
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Run each method in a fresh instrumentation process. Samples diagnose allocation ownership,
 * not a memory limit or leak verdict. GC requests do not guarantee collection or native release. */
class InitialPreviewAllocationIsolationAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun fullDecodeOnlyFourClosedSessions() {
        requireGpuInputSafetyFixture()
        val root = File.createTempFile("initial-allocation-", ".tmp", instrumentation.targetContext.cacheDir)
        check(root.delete() && root.mkdir())
        val input = File(root, "synthetic.dng")
        input.outputStream().use { writeSyntheticTrialDng(it, 1536, 1024, 1) }
        val source = SharedOriginalReference("content://synthetic/initial-allocation",
            DngContentVerifier.identify(input.length(), { input.inputStream() }), OriginalGrant.USER_SELECTED)
        val geometry = ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY).use {
            Original24Preflight.inspect(it.fd, CancellationSignal())
        }
        val samples = JSONArray().put(observe("baseline"))
        var success = false
        try {
            repeat(4) { index ->
                // The image local and session leave their helper stack before observation.
                decodeOnce(input, File(root, "decode-$index"), source, geometry)
                samples.put(observe("closed-$index"))
            }
            DngContentVerifier.verify(source.fingerprint, { input.inputStream() })
            write("decode-only.json", samples, "FULL session only; no GPU or Bitmap")
            success = true
        } finally {
            if (success) { assertTrue(input.delete()); assertTrue(root.delete()) }
        }
    }

    private fun decodeOnce(input: File, scratch: File, source: SharedOriginalReference,
        geometry: Original24PreflightGeometry) {
        val session = Original24PreviewSession(source, scratch, {}, geometry) { input.inputStream() }
        session.use {
            val image = it.loadGpuAsShotInput()
            val size = GpuPreviewInputSize.fit(geometry.outputWidth, geometry.outputHeight)
            assertEquals(size.width, image.width)
            assertEquals(size.height, image.height)
        }
        assertFalse(session.hasUnreleasedResources)
        assertFalse(scratch.exists())
    }

    @Test fun gpuOnlyFourClosedOwnersWithOneSharedSyntheticInput() {
        requireGpuInputSafetyFixture()
        // Same admitted dimensions as 1536x1024 FULL; no LibRaw, preflight, file or Bitmap.
        val size = GpuPreviewInputSize.fit(1536, 1024)
        val image = LinearSrgbImage(size.width, size.height,
            FloatArray(size.width * size.height * 3) { i -> .05f + (i % 251) / 300f })
        val samples = JSONArray().put(observe("baseline"))
        repeat(4) { index ->
            gpuOnce(image)
            samples.put(observe("closed-$index"))
        }
        write("gpu-only.json", samples, "One shared synthetic linear input retained across all runs; no decode")
    }

    private fun gpuOnce(image: LinearSrgbImage) {
        ImageReader.newInstance(64, 48, PixelFormat.RGBA_8888, 2).use { reader ->
            val owner = GpuPhotoRollSession(instrumentation.targetContext.assets)
            try {
                owner.renderer.attachSurface(reader.surface, 64, 48)
                owner.renderer.setStarted(true)
                owner.submit(PhotoAdjustmentPolicy.defaults)
                owner.renderer.openPhoto(image)
                val until = SystemClock.uptimeMillis() + 60_000
                while (owner.presented.value != PhotoAdjustmentPolicy.defaults) {
                    owner.failure.value?.let { throw AssertionError("Synthetic GPU isolation failed", it) }
                    check(SystemClock.uptimeMillis() < until) { "GPU isolation presentation timed out" }
                    reader.acquireLatestImage()?.close()
                    SystemClock.sleep(20)
                }
                reader.acquireLatestImage()?.close()
            } finally {
                owner.close()
                // Includes ledger bytes/textures/windows=0, retained input cleared and thread exit.
                assertTrue(owner.renderer.awaitClosed(10_000))
                GpuPhotoRollRetiredOwners.releaseBeforeOpen()
            }
        }
    }

    private fun observe(label: String): JSONObject {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(1_000)
        val before = sample()
        Runtime.getRuntime().gc()
        System.runFinalization()
        Runtime.getRuntime().gc()
        SystemClock.sleep(1_000)
        instrumentation.waitForIdleSync()
        return JSONObject().put("label", label).put("idleBeforeGc", before).put("afterGcRequest", sample())
    }

    private fun sample(): JSONObject {
        val runtime = Runtime.getRuntime()
        return JSONObject().put("nativeHeapBytes", Debug.getNativeHeapAllocatedSize())
            .put("pssKb", Debug.getPss()).put("javaUsedBytes", runtime.totalMemory() - runtime.freeMemory())
    }

    private fun write(name: String, samples: JSONArray, scope: String) {
        val configured = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        val base = configured?.let(::File) ?: File(instrumentation.targetContext.getExternalFilesDir(null), "additional_test_output")
        val output = File(base, "initial-preview-allocation").apply { check(isDirectory || mkdirs()) }
        File(output, name).writeText(JSONObject().put("scope", scope)
            .put("meaning", "Diagnostic samples only; no memory threshold; GC request is not collection proof")
            .put("samples", samples).toString(2))
    }
}
