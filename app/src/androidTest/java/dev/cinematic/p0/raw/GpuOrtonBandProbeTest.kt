package dev.cinematic.p0.raw

import android.graphics.Bitmap
import android.os.Debug
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.pow

/** Synthetic-only comparison. PNG is private test output, never a product photo. */
class GpuOrtonBandProbeTest {
    @Before fun syntheticEmulatorOnly() {
        // 明示的な opt-in がなければ、画像生成や EGL/GLES 初期化より前にスキップする。
        assumeTrue("GPU 検証は gpuBandProbe=true の明示指定時だけ実行します",
            InstrumentationRegistry.getArguments().getString("gpuBandProbe") == "true")
        check(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish")) { "Synthetic emulator only" }
    }
    @Test fun wholeLookSyntheticPreviewDifferenceAndBands() {
        val width = InstrumentationRegistry.getArguments().getString("gpuProbeWidth")?.toInt() ?: 257
        require(width in 32..1024)
        val height = width * 3 / 4 + 1
        for (dark in listOf(false, true)) {
            val input = LinearSrgbImage(width, height, FloatArray(width * height * 3) { i ->
                val x = ((i / 3) % width).toFloat() / (width - 1)
                val y = ((i / 3) / width).toFloat() / (height - 1)
                if (dark) x * x * .018f else when (i % 3) {
                    0 -> x * 1.6f - .02f
                    1 -> y * .7f
                    else -> if (x > .8f && y > .7f) 2.0f else (1f - x) * .3f
                }
            })
            val base = R7Base.render(input, FloatPlane(width, height, FloatArray(width * height)), 0.0)
            val faces = FaceMask(width, height, FloatArray(width * height))
            for (strength in listOf(0.25, 0.5, 1.0)) {
                val startCpu = System.nanoTime()
                val cpu = StageClearLook.renderFromBase(base, faces, strength).image
                val cpuNs = System.nanoTime() - startCpu
                val startGpu = System.nanoTime()
                val gpu = candidate(base, faces, strength, 64)
                val gpuNs = System.nanoTime() - startGpu
                val narrow = candidate(base, faces, strength, 31)
                val reference = pixels(cpu)
                val actual = pixels(gpu)
                val otherBands = pixels(narrow)
                var maximum = 0
                var changed = 0
                var newFlatEdges = 0
                for (p in reference.indices) {
                    var difference = 0
                    for (shift in listOf(0, 8, 16)) {
                        difference = maxOf(difference, abs(((reference[p] shr shift) and 255) - ((actual[p] shr shift) and 255)))
                    }
                    maximum = maxOf(maximum, difference)
                    if (difference != 0) changed++
                    if (dark && p % width > 0 && reference[p] != reference[p - 1] && actual[p] == actual[p - 1]) newFlatEdges++
                }
                Log.i("GpuBandProbe", "width=$width height=$height dark=$dark strength=$strength max8=$maximum changed=$changed pixels=${reference.size} newFlatEdges=$newFlatEdges cpuNs=$cpuNs gpuNs=$gpuNs managedSnapshot=${Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory()} nativeSnapshot=${Debug.getNativeHeapAllocatedSize()}")
                // Full end-to-end Look, not the isolated shader's error. Save gate remains untested.
                assertTrue("Preview difference exceeds 2/255: $maximum", maximum <= 2)
                assertArrayEquals("Band boundaries must not change output", actual, otherBands)
                val label = (if (dark) "dark" else "hdr") + "-${(strength * 100).toInt()}"
                png("$label-cpu", width, height, reference)
                png("$label-gpu", width, height, actual)
            }
        }
    }

    @Test fun cancellationReleasesContextAndNextRunWorks() {
        val image = LinearSrgbImage(17, 13, FloatArray(17 * 13 * 3) { .2f })
        val skin = FloatPlane(17, 13, FloatArray(17 * 13))
        val mist = StageClearMist.render(image, skin, .5)
        // After upload, after draw, and after readback respectively.
        for (stopAt in listOf(4, 5, 6)) {
            var checks = 0
            try {
                GpuOrtonBandProbe.render(mist, skin, 4) { ++checks >= stopAt }
                fail("Expected cooperative cancellation")
            } catch (_: CancellationException) { /* no output is returned */ }
            val next = GpuOrtonBandProbe.render(mist, skin, 4)
            assertEquals(17, next.output.width)
        }
    }

    private fun candidate(base: LinearSrgbImage, faces: FaceMask, strength: Double, band: Int): LinearSrgbImage {
        val skin = StageClearGlow.skinWeight(base)
        val glow = StageClearGlow.render(base, strength, skin)
        return StageClearProtections.finish(glow, faces) {
            val mist = StageClearMist.render(it, skin, strength)
            StageClearCharacter.render(GpuOrtonBandProbe.render(mist, skin, band).output, strength)
        }.image
    }
    private fun pixels(image: LinearSrgbImage) = IntArray(image.pixelCount) { p ->
        val r = previewChannel(image[p * 3])
        val g = previewChannel(image[p * 3 + 1])
        val b = previewChannel(image[p * 3 + 2])
        (255 shl 24) or (r shl 16) or (g shl 8) or b
    }
    // Exact display quantizer from Original24PreviewSession; CPU golden is unchanged.
    private fun previewChannel(linear: Float): Int {
        val value = linear.toDouble().coerceIn(0.0, 1.0)
        return ((if (value <= .0031308) 12.92 * value else 1.055 * value.pow(1.0 / 2.4) - .055) * 255)
            .roundToInt().coerceIn(0, 255)
    }
    private fun png(name: String, width: Int, height: Int, pixels: IntArray) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val configured = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        val root = configured?.let(::File) ?: File(requireNotNull(context.externalMediaDirs.firstOrNull()), "additional_test_output")
        val directory = File(root, "synthetic-gpu-comparison")
        check(directory.exists() || directory.mkdirs())
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        try { File(directory, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
    }
}
