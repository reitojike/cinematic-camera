package dev.cinematic.p0.jpegrender.original

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class Original24PreflightTest {
    private fun values(rawWidth: Int = 6400, rawHeight: Int = 5000, width: Int = 6000, height: Int = 4000,
        orientation: Int = 1, cropLeft: Int = 0, cropTop: Int = 0) = intArrayOf(2, rawWidth, rawHeight, width, height, orientation,
        if (orientation >= 5) height else width, if (orientation >= 5) width else height, cropLeft, cropTop)

    @Test fun acceptsBoundaryRaw32mpAndFull24mpWithEveryPhysicalOrientation() {
        for (orientation in 1..8) {
            val geometry = Original24Preflight.fromNative(values(orientation = orientation))
            assertEquals(6000, geometry.sourceWidth)
            assertEquals(4000, geometry.sourceHeight)
            assertEquals(orientation, geometry.orientation)
            assertEquals(if (orientation >= 5) 4000 else 6000, geometry.outputWidth)
            assertEquals(if (orientation >= 5) 6000 else 4000, geometry.outputHeight)
        }
    }

    @Test fun acceptsIntegerCropSmallerThanRaw() {
        val geometry = Original24Preflight.fromNative(values(width = 4000, height = 3000))
        assertEquals(6400, geometry.rawWidth)
        assertEquals(5000, geometry.rawHeight)
        assertEquals(4000, geometry.outputWidth)
        assertEquals(3000, geometry.outputHeight)
    }

    @Test fun rejectsRawBeyond32mpAndOversizedAxesWithoutIntegerOverflow() {
        for ((width, height) in listOf(6401 to 5000, 8193 to 1, 1 to 8193,
            Int.MAX_VALUE to Int.MAX_VALUE, 0 to 5000, 6400 to -1)) {
            assertThrows(IOException::class.java) { Original24Preflight.fromNative(values(rawWidth = width, rawHeight = height, width = 1, height = 1)) }
        }
    }

    @Test fun rejectsOutputBeyond24mpAndInvalidCropExtents() {
        for ((width, height) in listOf(6001 to 4000, 6401 to 1, 1 to 5001, 0 to 3000, -1 to 1)) {
            assertThrows(IOException::class.java) { Original24Preflight.fromNative(values(width = width, height = height)) }
        }
    }

    @Test fun rejectsUnknownVersionShortTrailingAndInvalidOrientation() {
        val malformed = listOf(intArrayOf(), values().copyOf(9), values().copyOf(11),
            values().also { it[0] = 0 }, values().also { it[0] = 1 }, values().also { it[0] = 3 }, values(orientation = 0), values(orientation = 9))
        for (report in malformed) assertThrows(IOException::class.java) { Original24Preflight.fromNative(report) }
    }

    @Test fun cropOffsetMustFitRawIncludingExactRightAndBottomBoundary() {
        val boundary = Original24Preflight.fromNative(values(cropLeft = 400, cropTop = 1000))
        assertEquals(400, boundary.cropLeft)
        assertEquals(1000, boundary.cropTop)
        for ((left, top) in listOf(401 to 0, 0 to 1001, -1 to 0, 0 to -1, Int.MAX_VALUE to Int.MAX_VALUE))
            assertThrows(IOException::class.java) { Original24Preflight.fromNative(values(cropLeft = left, cropTop = top)) }
        assertNotEquals(boundary, boundary.copy(cropLeft = 399))
    }

    @Test fun rejectsUnexpectedRotationOrResizingInNativeReport() {
        for (report in listOf(values(orientation = 6).also { it[6] = 6000; it[7] = 4000 },
            values().also { it[6] = 3000; it[7] = 2000 }, values().also { it[6] = 0 }))
            assertThrows(IOException::class.java) { Original24Preflight.fromNative(report) }
    }
}
