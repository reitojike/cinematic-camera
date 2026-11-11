package dev.cinematic.p0.gpu

import android.graphics.Bitmap
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.jpegrender.original.Original24Preflight
import dev.cinematic.p0.jpegrender.original.Original24PreviewSession
import dev.cinematic.p0.raw.LinearSrgbImage
import dev.cinematic.p0.rawcapture.DngContentVerifier
import dev.cinematic.p0.rawcapture.OriginalGrant
import dev.cinematic.p0.rawcapture.SharedOriginalReference
import dev.cinematic.p0.testing.writeSyntheticTrialDng
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Preparation only: generated input and native decode identity. No timing or physical-device claim. */
@RunWith(AndroidJUnit4::class)
class GpuGate1Part2CommonInputTest {
    @Test fun prepareSyntheticCommonInputAndVerifyCpuGpuIdentity() {
        gate1Part2OptIn("dev.cinematic.p0.gpu.GpuGate1Part2CommonInputTest#prepareSyntheticCommonInputAndVerifyCpuGpuIdentity", "common-input-v1")
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish")) { "Preparation is emulator-only" }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(checkNotNull(context.getExternalFilesDir("gpu-diagnostics")), "gate1-common-input-v1")
        check(!root.exists() && root.mkdirs()) { "Preparation already attempted; preserve prior evidence" }
        val input = File(root, "gate1-common-synthetic-1024x768-v1.dng")
        val report = JSONObject().put("version", "common-input-v1")
            .put("synthetic", true).put("dngTagWidth", 1024).put("dngTagHeight", 768).put("orientation", 1)
            .put("scope", "candidate APK CPU default input path versus GPU input path; not fixed CPU APK execution or gesture equivalence")
        var failure: Throwable? = null
        try {
            check(input.createNewFile())
            val written = input.outputStream().use { writeSyntheticTrialDng(it, 1024, 768, 1) }
            assertEquals(written, input.length())
            val fingerprint = DngContentVerifier.identify(input.length(), { input.inputStream() })
            report.put("sourceBytes", fingerprint.bytes).put("sourceSha256", fingerprint.sha256)
            val source = SharedOriginalReference("content://example.test/gate1/synthetic",
                fingerprint, OriginalGrant.USER_SELECTED)
            val geometry = ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY).use {
                Original24Preflight.inspect(it.fd, CancellationSignal())
            }
            report.put("nativeWidth", geometry.outputWidth).put("nativeHeight", geometry.outputHeight)
            assertEquals(1024, geometry.outputWidth); assertEquals(768, geometry.outputHeight)
            val work = File(context.cacheDir, "gate1-common-input-v1")
            check(!work.exists() && work.mkdirs())
            fun session(name: String) = Original24PreviewSession(source, File(work, name), {}, geometry) { input.inputStream() }
            val gpu = session("gpu-input").use { session ->
                session.loadGpuAsShotInput().also {
                    assertEquals(0, session.previewFieldBuilds)
                    assertNull(session.previewExternalEv); assertNull(session.previewDecisions)
                    assertFalse(session.hasUnreleasedResources)
                }
            }
            var cpuInput: LinearSrgbImage? = null
            session("cpu-input").use { session ->
                session.render(.5).recycle()
                session.comparisonBitmap(createBitmap = { image, _ ->
                    cpuInput = image
                    Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                })?.recycle()
                assertFalse(session.hasUnreleasedResources)
            }
            val cpu = checkNotNull(cpuInput)
            assertEquals(1024, cpu.width); assertEquals(768, cpu.height)
            assertEquals(cpu.width, gpu.width); assertEquals(cpu.height, gpu.height)
            repeat(cpu.pixelCount * 3) { index ->
                if (cpu[index].toRawBits() != gpu[index].toRawBits()) fail("AS_SHOT float channel mismatch")
            }
            val cpuHash = floatHash(cpu); val gpuHash = floatHash(gpu)
            assertEquals(cpuHash, gpuHash)
            report.put("asShotWidth", cpu.width).put("asShotHeight", cpu.height)
                .put("asShotChannels", cpu.pixelCount * 3).put("cpuAsShotFloatSha256", cpuHash)
                .put("gpuAsShotFloatSha256", gpuHash).put("asShotBitExact", true)
                .put("floatEncoding", "RGB float raw bits, little endian, row-major")
            DngContentVerifier.verify(fingerprint, { input.inputStream() })
            assertTrue(work.delete())
            report.put("originalUnchanged", true).put("outcome", "completed")
        } catch (caught: Throwable) {
            failure = caught
            report.put("outcome", "failed_or_stopped").put("failureClass", caught.javaClass.simpleName)
        } finally {
            File(root, "common-input-report.json").writeText(report.toString(2))
        }
        // Do not leak native exception messages or private paths into instrumentation output.
        if (failure != null) throw AssertionError("Common input preparation failed; see private metadata report")
    }

    private fun floatHash(input: LinearSrgbImage): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val sample = ByteArray(4)
        repeat(input.pixelCount * 3) { index ->
            val bits = input[index].toRawBits()
            repeat(4) { sample[it] = (bits ushr (it * 8)).toByte() }
            digest.update(sample)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
