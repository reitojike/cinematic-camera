package dev.cinematic.p0.raw

import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

class StageClearBlurTest {
    @Test fun boxAndRestorationMatchEveryPcPixel() {
        val lines = java.util.zip.GZIPInputStream(requireNotNull(
            javaClass.getResourceAsStream("/stage_clear_blur_v1.csv.gz")))
            .bufferedReader().use { it.readLines() }.filterNot { it.startsWith("#") }
        var next = 0
        var cases = 0
        var maximumError = 0f
        while (next < lines.size) {
            val header = lines[next++].split(',')
            assertEquals("CASE", header[0])
            val name = header[1]
            val width = header[2].toInt()
            val height = header[3].toInt()
            val radius = header[4].toDouble()
            val smallWidth = header[5].toInt()
            val smallHeight = header[6].toInt()
            val input = FloatArray(width * height)
            val expected = FloatArray(input.size)
            repeat(input.size) { i ->
                val row = lines[next++].split(',')
                assertEquals("PIX", row[0])
                input[i] = row[1].toFloat()
                expected[i] = row[2].toFloat()
            }
            val reduced = FloatArray(smallWidth * smallHeight) {
                val row = lines[next++].split(',')
                assertEquals("LOW", row[0])
                row[1].toFloat()
            }
            val image = FloatPlane(width, height, input)
            val size = StageClearBlur.reducedSize(width, height, radius)
            assertEquals("$name width", smallWidth, size.first)
            assertEquals("$name height", smallHeight, size.second)
            val box = StageClearBlur.resize(image, smallWidth, smallHeight, StageClearBlur.Filter.BOX)
            maximumError = maxOf(maximumError, compare("$name BOX", reduced, box.copyValues()))
            val restored = StageClearBlur.plane(image, radius)
            maximumError = maxOf(maximumError, compare("$name restored", expected, restored.copyValues()))
            assertArrayEquals("$name input unchanged", input, image.copyValues(), 0f)
            cases++
        }
        assertEquals(25, cases)
        println("PC blur cases=$cases; maximum absolute error=$maximumError")
    }

    @Test fun rgbChannelsStayIndependentAndInputStaysUnchanged() {
        val width = 13
        val height = 21
        val source = FloatArray(width * height * 3) { i ->
            when (i % 3) {
                0 -> if (i / 3 == 0) 4f else 0f
                1 -> -1f + (i / 3 % width) * .2f
                else -> .25f
            }
        }
        val image = LinearSrgbImage(width, height, source)
        val rgb = StageClearBlur.rgb(image, .23).copyPixels()
        repeat(3) { channel ->
            val values = FloatArray(width * height) { source[it * 3 + channel] }
            val plane = StageClearBlur.plane(FloatPlane(width, height, values), .23).copyValues()
            assertArrayEquals(plane, FloatArray(values.size) { rgb[it * 3 + channel] }, 0f)
        }
        assertArrayEquals(source, image.copyPixels(), 0f)
    }

    @Test fun invalidDataIsRejectedAndPlanesOwnTheirStorage() {
        fun rejects(block: () -> Unit) { assertThrows(IllegalArgumentException::class.java, block) }
        rejects { FloatPlane(0, 1, floatArrayOf()) }
        rejects { FloatPlane(Int.MAX_VALUE, Int.MAX_VALUE, floatArrayOf()) }
        rejects { FloatPlane(1, 2, floatArrayOf(1f)) }
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            rejects { FloatPlane(1, 1, floatArrayOf(value)) }
        }
        val values = floatArrayOf(-.2f, 1.5f)
        val image = FloatPlane(2, 1, values)
        values[0] = 123f
        image.copyValues()[0] = 456f
        assertEquals(-.2f, image.copyValues()[0])
        for (radius in listOf(-.1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            rejects { StageClearBlur.plane(image, radius) }
            rejects { StageClearBlur.rgb(LinearSrgbImage(1, 1, FloatArray(3)), radius) }
        }
        rejects { StageClearBlur.resize(image, 0, 1, StageClearBlur.Filter.BOX) }
        rejects { StageClearBlur.resize(image, Int.MAX_VALUE, 2, StageClearBlur.Filter.BOX) }
    }

    private fun compare(label: String, expected: FloatArray, actual: FloatArray): Float {
        assertEquals(label, expected.size, actual.size)
        var error = 0f
        for (i in expected.indices) {
            val delta = abs(expected[i] - actual[i])
            assertTrue("$label pixel $i: expected ${expected[i]}, got ${actual[i]}",
                actual[i].isFinite() && delta <= 2e-6f)
            error = maxOf(error, delta)
        }
        return error
    }
}
