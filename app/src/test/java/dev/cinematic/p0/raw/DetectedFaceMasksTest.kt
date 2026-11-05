package dev.cinematic.p0.raw

import java.util.concurrent.CancellationException
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

class DetectedFaceMasksTest {
    @Test fun everyPixelMatchesExistingPcFaceAreaIncludingTopSixAndStableTies() {
        val lines = java.util.zip.GZIPInputStream(requireNotNull(javaClass.getResourceAsStream("/detected_face_masks_v1.csv.gz")))
            .bufferedReader().use { it.readLines() }.filterNot { it.startsWith("#") }
        var next = 0; var cases = 0; var pixels = 0; var maximum = 0f
        while (next < lines.size) {
            val header = lines[next++].split(','); assertEquals("CASE", header[0])
            val w = header[2].toInt(); val h = header[3].toInt()
            val boxes = List(header[4].toInt()) {
                val row = lines[next++].split(','); assertEquals("BOX", row[0])
                DetectedFaceBox(row[1].toInt(), row[2].toInt(), row[3].toInt(), row[4].toInt())
            }
            val mask = DetectedFaceMasks.fromBoxes(w, h, boxes)
            repeat(w * h) { p ->
                val row = lines[next++].split(','); assertEquals("PIX", row[0])
                val error = abs(row[1].toFloat() - mask[p])
                assertTrue("${header[1]} pixel$p error=$error", error <= 2e-6f)
                maximum = maxOf(maximum, error)
            }
            cases++; pixels += w * h
        }
        assertEquals(13, cases)
        println("PC detected-box mask cases=$cases pixels=$pixels max_abs=$maximum; synthetic boxes only")
    }

    @Test fun invalidDomainAndCooperativeCancellationReturnNoMask() {
        val box = DetectedFaceBox(2, 2, 8, 8)
        for ((w, h) in listOf(0 to 32, 32 to 0, -1 to 32, 32 to -1, 1025 to 32, 32 to 1025,
                Int.MAX_VALUE to 32, 32 to Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { DetectedFaceMasks.fromBoxes(w, h, listOf(box)) }
        }
        assertThrows(IllegalArgumentException::class.java) { DetectedFaceMasks.fromBoxes(32, 32, List(65) { box }) }
        for (coordinate in listOf(-4097, 4097, Int.MIN_VALUE, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { DetectedFaceBox(coordinate, 0, 1, 1) }
            assertThrows(IllegalArgumentException::class.java) { DetectedFaceBox(0, coordinate, 1, 1) }
        }
        for (extent in listOf(-1, 0, 4097, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { DetectedFaceBox(0, 0, extent, 1) }
            assertThrows(IllegalArgumentException::class.java) { DetectedFaceBox(0, 0, 1, extent) }
        }
        for (boxes in listOf(emptyList(), listOf(box))) {
            var checks = 0
            DetectedFaceMasks.fromBoxes(16, 12, boxes) { checks++; false }
            for (stopAt in 1..checks) {
                var count = 0
                assertThrows(CancellationException::class.java) {
                    DetectedFaceMasks.fromBoxes(16, 12, boxes) { ++count == stopAt }
                }
            }
        }
    }

    @Test fun preservesCallerOrderAndDefensiveMaskCopyAcrossCalls() {
        val small = DetectedFaceBox(12, 2, 2, 2)
        val large = DetectedFaceBox(-2, 2, 10, 10)
        val boxes = mutableListOf(small, large)
        val before = boxes.toList()
        val first = DetectedFaceMasks.fromBoxes(16, 12, boxes)
        val expected = first.copyWeights()
        assertEquals(before, boxes)
        boxes.clear()
        val external = first.copyWeights()
        external.fill(0f)
        assertArrayEquals(expected, first.copyWeights(), 0f)
        DetectedFaceMasks.fromBoxes(16, 12, emptyList())
        assertArrayEquals(expected, first.copyWeights(), 0f)
    }

    @Test fun validBoundsAndAllOutsideBoxesNeedNoFaceOrDetectorState() {
        DetectedFaceBox(-4096, -4096, 4096, 4096)
        val outside = DetectedFaceBox(4096, 4096, 1, 1)
        val mask = DetectedFaceMasks.fromBoxes(1, 1, listOf(outside))
        assertEquals(0f, mask[0], 0f)
        val upperRaster = DetectedFaceMasks.fromBoxes(1024, 1, emptyList())
        assertEquals(1024, upperRaster.width)
        assertEquals(1, upperRaster.height)
    }
}
