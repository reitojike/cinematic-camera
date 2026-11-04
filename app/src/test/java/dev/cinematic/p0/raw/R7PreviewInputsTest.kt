package dev.cinematic.p0.raw

import java.util.concurrent.CancellationException
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

class R7PreviewInputsTest {
    @Test fun pcDiagnosticExposureAndEveryRoiPixelMatch() {
        val lines = java.util.zip.GZIPInputStream(requireNotNull(
            javaClass.getResourceAsStream("/r7_preview_inputs_v1.csv.gz")))
            .bufferedReader().use { it.readLines() }.filterNot { it.startsWith("#") }
        var next = 0; var cases = 0; var pixels = 0
        var maxEv = 0.0; var maxRoi = 0f; var maxP97 = 0f
        while (next < lines.size) {
            val header = lines[next++].split(',')
            assertEquals("CASE", header[0])
            val name = header[1]; val w = header[2].toInt(); val h = header[3].toInt()
            val ellipses = List(header[7].toInt()) {
                val row = lines[next++].split(','); assertEquals("ELLIPSE", row[0])
                R7PreviewInputs.FoundationEllipse(row[1].toFloat(), row[2].toFloat(), row[3].toFloat(), row[4].toFloat())
            }
            val rgb = FloatArray(w * h * 3); val roi = FloatArray(w * h)
            repeat(w * h) { p ->
                val row = lines[next++].split(',')
                assertEquals("PIX", row[0]); assertEquals(5, row.size)
                repeat(3) { c -> rgb[p * 3 + c] = row[c + 1].toFloat() }
                roi[p] = row[4].toFloat()
            }
            val image = LinearSrgbImage(w, h, rgb)
            val result = R7PreviewInputs.prepare(asShot(image), ellipses)
            val evError = abs(header[4].toDouble() - result.exposure.externalBaseEv)
            assertTrue("$name EV error=$evError", evError <= 2e-6)
            maxEv = maxOf(maxEv, evError)
            val p97Error = abs(header[5].toFloat() - result.exposure.centralP97)
            assertTrue("$name p97 error=$p97Error", p97Error <= 2e-6f)
            maxP97 = maxOf(maxP97, p97Error)
            assertEquals(header[6].toInt(), result.exposure.centralSampleCount)
            assertEquals(ellipses.size, result.explicitEllipseCount)
            repeat(w * h) { p ->
                val error = abs(roi[p] - result.foundationSkinRoi[p])
                assertTrue("$name ROI pixel $p error=$error", error <= 2e-6f)
                maxRoi = maxOf(maxRoi, error)
            }
            assertArrayEquals(rgb, image.copyPixels(), 0f)
            assertEquals(R7PreviewInputs.FaceDetectionStatus.NOT_RUN, result.faceDetection)
            cases++; pixels += w * h
        }
        assertEquals(14, cases); assertEquals(5354, pixels)
        println("Preview inputs PC cases=$cases pixels=$pixels maxEV=$maxEv maxP97=$maxP97 maxROI=$maxRoi")
    }

    @Test fun provenanceRemainsPreliminaryAsShotAndEmptyRoiIsExplicitNotRun() {
        val image = constant(8, 6, .2f)
        val result = R7PreviewInputs.prepare(R7PreviewInputs.AsShotInput(image,
            R7PreviewInputs.Source.LIBRAW_PRELIMINARY_AS_SHOT), emptyList())
        assertEquals(R7PreviewInputs.Source.LIBRAW_PRELIMINARY_AS_SHOT, result.exposure.source)
        assertEquals(R7PreviewInputs.WhiteBalanceState.AS_SHOT_BEFORE_W1, result.exposure.whiteBalance)
        assertEquals(R7PreviewInputs.ExposureMethod.PC_CENTRAL_P97_DIAGNOSTIC, result.exposure.method)
        assertEquals(R7PreviewInputs.FoundationRoiOrigin.EXPLICIT_NORMALIZED_ELLIPSES, result.foundationRoiOrigin)
        assertEquals(R7PreviewInputs.FaceDetectionStatus.NOT_RUN, result.faceDetection)
        assertEquals(0, result.explicitEllipseCount)
        assertArrayEquals(FloatArray(image.pixelCount), result.foundationSkinRoi.copyValues(), 0f)
        val expectedEv = (-.13 - .83 * kotlin.math.log2(result.exposure.centralP97.toDouble())) - .6
        assertEquals(expectedEv, result.exposure.externalBaseEv, 2e-6)
    }

    @Test fun invalidDimensionsEllipsesAndNonfiniteInputsNeverProduceControls() {
        for ((w, h) in listOf(1 to 1, 1 to 4, 4 to 1, 1025 to 2, 2 to 1025)) {
            assertThrows(IllegalArgumentException::class.java) { asShot(constant(w, h, .2f)) }
        }
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -.1f, 1.1f)) {
            assertThrows(IllegalArgumentException::class.java) { ellipse(cx = bad) }
            assertThrows(IllegalArgumentException::class.java) { ellipse(cy = bad) }
            assertThrows(IllegalArgumentException::class.java) { ellipse(rx = bad) }
            assertThrows(IllegalArgumentException::class.java) { ellipse(ry = bad) }
        }
        assertThrows(IllegalArgumentException::class.java) { ellipse(rx = 0f) }
        assertThrows(IllegalArgumentException::class.java) { ellipse(ry = 0f) }
        assertThrows(IllegalArgumentException::class.java) {
            R7PreviewInputs.prepare(asShot(constant(4, 4, .2f)), List(7) { ellipse() })
        }
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { asShot(constant(4, 4, bad)) }
        }
        val extreme = FloatArray(2 * 4 * 3)
        repeat(3) { c -> extreme[(1 * 2) * 3 + c] = -Float.MAX_VALUE; extreme[(2 * 2) * 3 + c] = Float.MAX_VALUE }
        assertThrows(IllegalArgumentException::class.java) {
            R7PreviewInputs.prepare(asShot(LinearSrgbImage(2, 4, extreme)), emptyList())
        }
    }

    @Test fun cancellationAtBeginRowsSortAndReturnDoesNotReturnSuccessOrChangeInput() {
        val image = constant(8, 6, .2f); val before = image.copyPixels()
        val regions = listOf(ellipse())
        var checkpoints = 0
        R7PreviewInputs.prepare(asShot(image), regions) { checkpoints++; false }
        assertEquals(13, checkpoints) // begin + 3 central rows + before/after sort + 6 ROI rows + return
        for (stopAt in 1..checkpoints) {
            var calls = 0
            assertThrows(CancellationException::class.java) {
                R7PreviewInputs.prepare(asShot(image), regions) { ++calls == stopAt }
            }
            assertEquals(stopAt, calls)
            assertArrayEquals(before, image.copyPixels(), 0f)
        }
    }

    @Test fun r7CancellationCoversAllSixStageBoundariesAndPreservesDefaultOutput() {
        val image = constant(8, 6, .2f); val before = image.copyPixels()
        val roi = FloatPlane(8, 6, FloatArray(48)); val roiBefore = roi.copyValues()
        val baseline = R7Base.render(image, roi, 0.0)
        var checkpoints = 0
        val checked = R7Base.render(image, roi, 0.0) { checkpoints++; false }
        assertEquals(7, checkpoints)
        assertArrayEquals(baseline.copyPixels(), checked.copyPixels(), 0f)
        for (stopAt in 1..checkpoints) {
            var calls = 0
            assertThrows(CancellationException::class.java) { R7Base.render(image, roi, 0.0) { ++calls == stopAt } }
            assertEquals(stopAt, calls)
            assertArrayEquals(before, image.copyPixels(), 0f)
            assertArrayEquals(roiBefore, roi.copyValues(), 0f)
        }
    }

    @Test fun maximumPreviewHostObservationIsNotADevicePerformanceGate() {
        val image = constant(1024, 768, .2f)
        val started = System.nanoTime()
        val result = R7PreviewInputs.prepare(asShot(image), listOf(ellipse()))
        val elapsedMs = (System.nanoTime() - started) / 1_000_000.0
        assertEquals(1024, result.foundationSkinRoi.width)
        assertEquals(768, result.foundationSkinRoi.height)
        assertEquals(512 * 384, result.exposure.centralSampleCount)
        println("Synthetic 1024x768 preview input preparation host observation=${elapsedMs}ms; no device/peak-memory claim")
    }

    private fun asShot(image: LinearSrgbImage) =
        R7PreviewInputs.AsShotInput(image, R7PreviewInputs.Source.SYNTHETIC_AS_SHOT)
    private fun constant(w: Int, h: Int, value: Float) = LinearSrgbImage(w, h, FloatArray(w * h * 3) { value })
    private fun ellipse(cx: Float = .5f, cy: Float = .5f, rx: Float = .3f, ry: Float = .4f) =
        R7PreviewInputs.FoundationEllipse(cx, cy, rx, ry)
}
