package dev.cinematic.p0.jpegrender.original

import android.graphics.BitmapFactory
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.rawcapture.*
import dev.cinematic.p0.raw.original.OriginalStage
import dev.cinematic.p0.raw.original.OriginalStageTrace
import org.json.JSONArray
import org.json.JSONObject
import dev.cinematic.p0.testing.writeSyntheticTrialDng
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic private DNG only; never installs into or reads the camera app's media. */
@RunWith(AndroidJUnit4::class)
class OriginalR7ColorFusionAndroidTest {
    @Test fun separateAndFusedPassesProduceIdenticalJpegAndReleaseOwners() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "dev.cinematic.p0.original24probe")
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        check(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        val root = File(context.cacheDir, "synthetic-r7-fusion-${UUID.randomUUID()}")
        check(root.mkdir())
        val timings = JSONArray()
        try {
            for (orientation in listOf(1, 6)) for (strength in listOf(0.0, .5, 1.0)) {
                val source = ByteArrayOutputStream().also { writeSyntheticTrialDng(it, 256, 192, orientation) }.toByteArray()
                val fingerprint = DngContentVerifier.identify(source.size.toLong(), { ByteArrayInputStream(source) })
                val reference = SharedOriginalReference("content://example.test/r7-fusion", fingerprint, OriginalGrant.USER_SELECTED)
                val request = Original24Request("synthetic_fusion", 1L, reference, strength)
                val outputs = mutableListOf<ByteArray>()
                for (fused in listOf(false, true)) {
                    val bytes = ByteArrayOutputStream()
                    val io = object : AutoRenderIo {
                        override fun openOriginal() = ByteArrayInputStream(source)
                        override fun openOriginalDescriptor(): AutoReadOnlyDescriptor = error("Use verified snapshot")
                        override fun openOutput() = bytes
                        override fun openOutputRead() = ByteArrayInputStream(bytes.toByteArray())
                    }
                    val scratch = File(root, "attempt-$orientation-$strength-$fused")
                    var colorNanos = 0L
                    val colorStages = setOf(OriginalStage.R7_VIBRANCE, OriginalStage.R7_CRISP,
                        OriginalStage.R7_SKIN, OriginalStage.R7_COLOR_FUSED)
                    val trace = OriginalStageTrace { stage, nanos, complete ->
                        if (stage in colorStages) { assertTrue(complete); colorNanos += nanos }
                    }
                    val attempt = Original24Attempt(context, request, io, scratch, trace,
                        parallelFoundationWorkers = 2, fuseR7ColorRows = fused)
                    val start = System.nanoTime()
                    val result = attempt.use { it.render { false } }
                    timings.put(JSONObject().put("orientation", orientation).put("strength", strength)
                        .put("fused", fused).put("renderAndCloseNanos", System.nanoTime() - start)
                        .put("colorPassNanos", colorNanos))
                    assertFalse(attempt.hasUnreleasedResources)
                    assertFalse(scratch.exists())
                    DngContentVerifier.verify(fingerprint, { ByteArrayInputStream(source) })
                    val jpeg = bytes.toByteArray()
                    val inspected = AndroidJpegInspector().inspect({ ByteArrayInputStream(jpeg) }, jpeg.size.toLong()) { false }
                    assertEquals(result.fingerprint, inspected.fingerprint)
                    val bitmap = requireNotNull(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size))
                    try {
                        assertEquals(if (orientation == 6) 192 else 256, bitmap.width)
                        assertEquals(if (orientation == 6) 256 else 192, bitmap.height)
                    } finally { bitmap.recycle() }
                    outputs += jpeg
                    // AGP pulls standard additional output before teardown. Synthetic JPEGs only.
                    val configured = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
                    val outputRoot = if (configured != null) File(configured) else
                        File(requireNotNull(context.externalMediaDirs.firstOrNull()), "additional_test_output")
                    val out = File(outputRoot, "r7-fusion")
                    check(out.isDirectory || out.mkdirs())
                    File(out, "synthetic-fusion-$orientation-$strength-$fused.jpg").writeBytes(jpeg)
                    File(out, "synthetic-timings.json").writeText(JSONObject()
                        .put("scope", "256x192 synthetic quality check; fixed baseline/candidate order; no dedicated warmup or memory sampler; not a speed benchmark")
                        .put("samples", timings).toString(2))
                }
                assertArrayEquals("orientation=$orientation strength=$strength", outputs[0], outputs[1])
            }
        } finally { check(root.delete()) }
    }

    @Test fun fusedWorkerCancellationAndFailureCloseAttemptOnEmulator() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "dev.cinematic.p0.original24probe")
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        check(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        for (fail in listOf(false, true)) {
            val source = ByteArrayOutputStream().also { writeSyntheticTrialDng(it, 64, 48, 1) }.toByteArray()
            val fingerprint = DngContentVerifier.identify(source.size.toLong(), { ByteArrayInputStream(source) })
            val reference = SharedOriginalReference("content://example.test/r7-fusion-stop", fingerprint, OriginalGrant.USER_SELECTED)
            val output = ByteArrayOutputStream()
            val io = object : AutoRenderIo {
                override fun openOriginal() = ByteArrayInputStream(source)
                override fun openOriginalDescriptor(): AutoReadOnlyDescriptor = error("Use verified snapshot")
                override fun openOutput() = output
                override fun openOutputRead() = ByteArrayInputStream(output.toByteArray())
            }
            val armed = AtomicBoolean(false)
            val reached = AtomicBoolean(false)
            val fault = IOException("synthetic fused worker fault")
            val trace = OriginalStageTrace { stage, _, complete ->
                if (stage == OriginalStage.R7_FOUNDATION && complete) armed.set(true)
            }
            val scratch = File(context.cacheDir, "synthetic-r7-fusion-stop-${UUID.randomUUID()}")
            val attempt = Original24Attempt(context, Original24Request("synthetic_fusion_stop", 1L, reference),
                io, scratch, trace, fuseR7ColorRows = true)
            val action = {
                attempt.use { it.render {
                    if (armed.get() && Thread.currentThread().name == "original-r7-row") {
                        reached.set(true)
                        if (fail) throw fault else true
                    } else false
                } }
            }
            check(!scratch.exists())
            try {
                if (fail) assertSame(fault, assertThrows(IOException::class.java) { action() })
                else assertThrows(CancellationException::class.java) { action() }
                assertTrue(reached.get())
                assertFalse(attempt.hasUnreleasedResources)
                // All temporary photo copies are removed after owner release.
                assertFalse(scratch.exists())
                assertEquals(0, output.size())
                DngContentVerifier.verify(fingerprint, { ByteArrayInputStream(source) })
            } finally {
                attempt.close()
                check(!attempt.hasUnreleasedResources)
                check(scratch.canonicalFile.parentFile == context.cacheDir.canonicalFile)
                if (scratch.exists()) {
                    requireNotNull(scratch.listFiles()).forEach { file ->
                        check(file.name in setOf("source-snapshot.dng", "source-linear.rgb16") ||
                            file.name.matches(Regex("raster-[0-9]+[.]f32")))
                        check(java.nio.file.Files.isRegularFile(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
                        check(file.delete())
                    }
                    check(scratch.delete())
                }
            }
        }
    }

}
