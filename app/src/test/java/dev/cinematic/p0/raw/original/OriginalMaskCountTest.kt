package dev.cinematic.p0.raw.original

import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class OriginalMaskCountTest {
    @Test fun preservesZeroFortyNineFiftyAndStrictHalfThreshold() {
        for (expected in listOf(0, 49, 50)) {
            val faces = OriginalMask { y, row -> row.indices.forEach { x -> row[x] = if (y * row.size + x < expected) 1f else .5f } }
            assertEquals(expected, countOriginalMaskPixels(10, 5, faces) { false })
        }
        val values = floatArrayOf(0f, .5f, Float.fromBits(.5f.toRawBits() - 1), Float.fromBits(.5f.toRawBits() + 1), 1f)
        assertEquals(2, countOriginalMaskPixels(values.size, 1, OriginalMask { _, row -> values.copyInto(row) }) { false })
    }

    @Test fun everyMaskRowIsCalledInOrderEvenAfterFiftyPixels() {
        val rows = mutableListOf<Int>()
        val faces = OriginalMask { y, row ->
            assertEquals(rows.size, y)
            rows += y
            row.fill(if (y < 4) 1f else 0f)
        }
        assertEquals(64, countOriginalMaskPixels(16, 8, faces) { false })
        assertEquals((0 until 8).toList(), rows)
    }

    @Test fun noneStillChecksEveryRowRatherThanReturningImmediately() {
        var checkpoints = 0
        assertEquals(0, countOriginalMaskPixels(16, 8, OriginalMask.NONE) { checkpoints++; false })
        assertEquals(8, checkpoints)
    }

    @Test fun invalidValuesAfterFiftyPixelsAreNotSkipped() {
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -.001f, 1.001f)) {
            val rows = mutableListOf<Int>()
            val faces = OriginalMask { y, row ->
                rows += y
                row.fill(1f)
                if (y == 3) row[row.lastIndex] = invalid
            }
            assertThrows(IllegalArgumentException::class.java) { countOriginalMaskPixels(25, 4, faces) { false } }
            assertEquals(listOf(0, 1, 2, 3), rows)
        }
    }

    @Test fun cancellationIsCheckedBeforeEachCallbackIncludingTheFirst() {
        var calls = 0
        val faces = OriginalMask { _, row -> calls++; row.fill(1f) }
        assertThrows(CancellationException::class.java) { countOriginalMaskPixels(16, 4, faces) { true } }
        assertEquals(0, calls)
        var checkpoints = 0
        assertThrows(CancellationException::class.java) {
            countOriginalMaskPixels(16, 4, faces) { ++checkpoints == 3 }
        }
        assertEquals(3, checkpoints)
        assertEquals(2, calls)
    }

    @Test fun invalidDimensionsFailBeforeMaskCallbacksOrStopChecks() {
        var calls = 0
        val faces = OriginalMask { _, _ -> calls++ }
        for ((width, height) in listOf(0 to 1, 1 to 0, 8193 to 1, 6001 to 4000, Int.MAX_VALUE to Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) {
                countOriginalMaskPixels(width, height, faces) { calls++; false }
            }
        }
        assertEquals(0, calls)
    }

    @Test fun maskCallbackFailureIsPropagatedWithoutCallingLaterRows() {
        val failure = IllegalStateException("Synthetic mask callback failure")
        var calls = 0
        val observed = assertThrows(IllegalStateException::class.java) {
            countOriginalMaskPixels(25, 4, OriginalMask { y, row ->
                calls++; row.fill(1f)
                if (y == 2) throw failure
            }) { false }
        }
        assertSame(failure, observed)
        assertEquals(3, calls)
    }
}
