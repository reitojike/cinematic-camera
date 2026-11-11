package dev.cinematic.p0.gpu.export

import dev.cinematic.p0.raw.original.OriginalRaster
import dev.cinematic.p0.raw.original.originalCheckpoint
import dev.cinematic.p0.raw.original.validateOriginalSize
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** Replays only the original raster. The RGBA upload buffer is borrowed for one callback. */
internal object GpuExportBands {
    const val HEIGHT = 256

    fun forEach(input: OriginalRaster, height: Int = HEIGHT, stop: () -> Boolean,
        consume: (originY: Int, rows: Int, rgba: FloatBuffer) -> Unit) {
        validateOriginalSize(input.width, input.height)
        require(height == HEIGHT || height == 64) { "Export band height must be explicit: 256 or test-only 64" }
        originalCheckpoint(stop)
        val buffer = ByteBuffer.allocateDirect(Math.multiplyExact(input.width * minOf(height, input.height), 16))
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        var expectedY = 0
        var origin = 0
        fun emit() {
            originalCheckpoint(stop)
            buffer.flip()
            consume(origin, expectedY - origin, buffer)
            originalCheckpoint(stop)
            origin = expectedY
            buffer.clear()
        }
        input.rows(stop) { y, row ->
            originalCheckpoint(stop)
            check(y == expectedY && y < input.height) { "Original rows must be contiguous and ordered" }
            require(row.size == input.width * 3 && row.all { it.isFinite() })
            for (x in 0 until input.width) {
                buffer.put(row[x * 3]); buffer.put(row[x * 3 + 1]); buffer.put(row[x * 3 + 2]); buffer.put(1f)
            }
            expectedY++
            if (expectedY - origin == height || expectedY == input.height) emit()
        }
        check(expectedY == input.height && origin == expectedY) { "Original raster ended early" }
        originalCheckpoint(stop)
    }
}
