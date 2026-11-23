package dev.cinematic.p0.gpu.export

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException

class GpuExportRgba8Test {
    @Test fun fromRowsDeliversRowsInOrderIncludingShortBand() {
        val indices = mutableListOf<Int>()
        val rows = mutableListOf<IntArray>()
        val consumer = GpuExportBandConsumer.fromRows(width = 2, stop = { false }) { y, row ->
            indices.add(y)
            rows.add(row.copyOf())
        }

        consumer.consume(4, 2, intArrayOf(1, 2, 3, 4))
        consumer.consume(6, 1, intArrayOf(5, 6, -1, -1))

        assertEquals(listOf(4, 5, 6), indices)
        assertArrayEquals(intArrayOf(1, 2), rows[0])
        assertArrayEquals(intArrayOf(3, 4), rows[1])
        assertArrayEquals(intArrayOf(5, 6), rows[2])
    }

    @Test fun fromRowsStopsDeliveringAfterCancellationWithinBand() {
        var stopped = false
        val indices = mutableListOf<Int>()
        val consumer = GpuExportBandConsumer.fromRows(width = 2, stop = { stopped }) { y, _ ->
            indices.add(y)
            if (y == 11) stopped = true
        }

        assertThrows(CancellationException::class.java) {
            consumer.consume(10, 4, IntArray(8))
        }
        assertEquals(listOf(10, 11), indices)
    }

    @Test fun bulkRowsMatchOldByteConversionWithEveryByteValueAndShortBand() {
        for (direct in listOf(false, true)) for (order in listOf(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN)) {
            val width = 257
            val bytes = (if (direct) ByteBuffer.allocateDirect(width * 4 * 3) else ByteBuffer.allocate(width * 4 * 3)).order(order)
            repeat(bytes.capacity()) { bytes.put(it, ((it * 73 + it / 7) and 255).toByte()) }
            bytes.limit(width * 4 * 2) // A short band: unread allocation tail must not participate.
            val band = IntArray(width * 3) { -1 }
            val words = GpuExportRgba8.words(bytes)
            for (y in 0..1) {
                val expected = IntArray(width) { x ->
                    val i = (y * width + x) * 4
                    (255 shl 24) or ((bytes.get(i).toInt() and 255) shl 16) or
                        ((bytes.get(i + 1).toInt() and 255) shl 8) or (bytes.get(i + 2).toInt() and 255)
                }
                val actual = IntArray(width)
                GpuExportRgba8.readRow(bytes, y, actual)
                assertArrayEquals(expected, actual)
                GpuExportRgba8.readNextRow(words, band, y * width, width)
                assertArrayEquals(expected, band.copyOfRange(y * width, (y + 1) * width))
                assertEquals(0, bytes.position())
            }
            assertEquals(0, words.remaining())
            assertArrayEquals(IntArray(width) { -1 }, band.copyOfRange(width * 2, width * 3))
            assertThrows(java.nio.BufferUnderflowException::class.java) {
                GpuExportRgba8.readRow(bytes, 2, IntArray(width))
            }
        }
    }
}
