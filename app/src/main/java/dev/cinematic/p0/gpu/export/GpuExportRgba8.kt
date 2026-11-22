package dev.cinematic.p0.gpu.export

import dev.cinematic.p0.raw.original.originalCheckpoint
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer

/** GL bytes are RGBA on every host; Bitmap rows are opaque 0xffRRGGBB. */
internal object GpuExportRgba8 {
    fun readRow(bytes: ByteBuffer, y: Int, row: IntArray) {
        val ints = words(bytes)
        ints.position(Math.multiplyExact(y, row.size))
        readNextRow(ints, row, 0, row.size)
    }

    fun words(bytes: ByteBuffer): IntBuffer = bytes.order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()

    fun readNextRow(ints: IntBuffer, argb: IntArray, offset: Int, width: Int) {
        ints.get(argb, offset, width)
        for (index in offset until offset + width) {
            val rgba = argb[index]
            argb[index] = (255 shl 24) or ((rgba and 255) shl 16) or (rgba and 0xff00) or ((rgba ushr 16) and 255)
        }
    }
}

/** The consumer borrows the array until it returns. Only the first width * rows entries are valid. */
internal fun interface GpuExportBandConsumer {
    fun consume(origin: Int, rows: Int, argb: IntArray)

    companion object {
        fun fromRows(
            width: Int,
            stop: () -> Boolean,
            consumeRow: (Int, IntArray) -> Unit,
        ): GpuExportBandConsumer {
            val row by lazy(LazyThreadSafetyMode.NONE) { IntArray(width) }
            return GpuExportBandConsumer { origin, rows, argb ->
                repeat(rows) { y ->
                    val offset = y * width
                    argb.copyInto(row, endIndex = offset + width, startIndex = offset)
                    originalCheckpoint(stop)
                    consumeRow(origin + y, row)
                }
            }
        }
    }
}
