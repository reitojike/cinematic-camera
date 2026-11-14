package dev.cinematic.p0.gpu.export

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** GL bytes are RGBA on every host; Bitmap rows are opaque 0xffRRGGBB. */
internal object GpuExportRgba8 {
    fun readRow(bytes: ByteBuffer, y: Int, row: IntArray) {
        val ints = bytes.order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
        ints.position(Math.multiplyExact(y, row.size))
        ints.get(row)
        for (x in row.indices) {
            val rgba = row[x]
            row[x] = (255 shl 24) or ((rgba and 255) shl 16) or (rgba and 0xff00) or ((rgba ushr 16) and 255)
        }
    }
}
