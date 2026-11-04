package dev.cinematic.p0.raw

import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

class R7BaseTest {
    @Test fun everyStageAndLogBaseMatchAllPcPixels() {
        val lines = java.util.zip.GZIPInputStream(requireNotNull(javaClass.getResourceAsStream("/r7_base_v1.csv.gz")))
            .bufferedReader().use { it.readLines() }.filterNot { it.startsWith("#") }
        var next = 0; var cases = 0; var pixels = 0
        val maximum = FloatArray(7)
        while (next < lines.size) {
            val header = lines[next++].split(',')
            assertEquals("CASE", header[0])
            val name = header[1]; val w = header[2].toInt(); val h = header[3].toInt(); val ev = header[4].toDouble()
            val rgb = FloatArray(w * h * 3); val roi = FloatArray(w * h); val log = FloatArray(w * h)
            val expected = List(6) { FloatArray(w * h * 3) }
            repeat(w * h) { p ->
                val row = lines[next++].split(',')
                assertEquals("PIX", row[0]); assertEquals(24, row.size)
                repeat(3) { rgb[p * 3 + it] = row[1 + it].toFloat() }
                roi[p] = row[4].toFloat(); log[p] = row[5].toFloat()
                repeat(6) { stage -> repeat(3) { c -> expected[stage][p * 3 + c] = row[6 + stage * 3 + c].toFloat() } }
            }
            val input = LinearSrgbImage(w, h, rgb); val region = FloatPlane(w, h, roi)
            val actual = R7Base.stages(input, region, ev)
            maximum[0] = maxOf(maximum[0], compare("$name log", log, R7Base.smoothLogLuma(actual[0]).copyValues()))
            repeat(6) { stage -> maximum[stage + 1] = maxOf(maximum[stage + 1],
                compare("$name stage $stage", expected[stage], actual[stage].copyPixels())) }
            compare("$name render", expected.last(), R7Base.render(input, region, ev).copyPixels())
            assertArrayEquals(rgb, input.copyPixels(), 0f); assertArrayEquals(roi, region.copyValues(), 0f)
            cases++; pixels += w * h
        }
        assertEquals(7, cases); assertEquals(4908, pixels)
        println("R7 all-pixel cases=$cases pixels=$pixels maxima log/profile/tone/foundation/vibrance/crisp/skin=${maximum.toList()}")
    }

    @Test fun explicitExternalExposureAndRoiValidation() {
        val input = LinearSrgbImage(1, 1, floatArrayOf(.2f, .2f, .2f))
        val zero = FloatPlane(1, 1, floatArrayOf(0f))
        assertTrue(R7Base.render(input, zero, 1.0)[1] > R7Base.render(input, zero, 0.0)[1])
        for (ev in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 2000.0)) {
            assertThrows(IllegalArgumentException::class.java) { R7Base.render(input, zero, ev) }
        }
        assertThrows(IllegalArgumentException::class.java) { R7Base.render(input, FloatPlane(2, 1, FloatArray(2)), 0.0) }
        for (value in listOf(-.1f, 1.1f)) {
            assertThrows(IllegalArgumentException::class.java) { R7Base.render(input, FloatPlane(1, 1, floatArrayOf(value)), 0.0) }
        }
    }

    private fun compare(label: String, expected: FloatArray, actual: FloatArray): Float {
        assertEquals(label, expected.size, actual.size)
        var maximum = 0f
        for (i in expected.indices) {
            val error = abs(expected[i] - actual[i]); maximum = maxOf(maximum, error)
            assertTrue("$label channel $i expected=${expected[i]} actual=${actual[i]} error=$error",
                actual[i].isFinite() && error <= 2e-5f)
        }
        return maximum
    }
}
