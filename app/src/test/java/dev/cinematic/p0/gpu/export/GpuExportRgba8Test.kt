package dev.cinematic.p0.gpu.export

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GpuExportRgba8Test {
    @Test fun bulkRowsMatchOldByteConversionWithEveryByteValueAndShortBand() {
        for (direct in listOf(false, true)) for (order in listOf(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN)) {
            val width = 257
            val bytes = (if (direct) ByteBuffer.allocateDirect(width * 4 * 3) else ByteBuffer.allocate(width * 4 * 3)).order(order)
            repeat(bytes.capacity()) { bytes.put(it, ((it * 73 + it / 7) and 255).toByte()) }
            bytes.limit(width * 4 * 2) // A short band: unread allocation tail must not participate.
            for (y in 0..1) {
                val expected = IntArray(width) { x ->
                    val i = (y * width + x) * 4
                    (255 shl 24) or ((bytes.get(i).toInt() and 255) shl 16) or
                        ((bytes.get(i + 1).toInt() and 255) shl 8) or (bytes.get(i + 2).toInt() and 255)
                }
                val actual = IntArray(width)
                GpuExportRgba8.readRow(bytes, y, actual)
                assertArrayEquals(expected, actual)
                assertEquals(0, bytes.position())
            }
            assertThrows(java.nio.BufferUnderflowException::class.java) {
                GpuExportRgba8.readRow(bytes, 2, IntArray(width))
            }
        }
    }
}
