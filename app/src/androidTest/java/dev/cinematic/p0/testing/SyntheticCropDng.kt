package dev.cinematic.p0.testing

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Non-photographic Bayer signal; independent cropped-RAW oracle, no native crop calculation. */
internal fun syntheticCropDng(
    originX: Int = 5, originY: Int = 5, cropWidth: Int = 48, cropHeight: Int = 32,
    orientation: Int = 1, pattern: Int = 0, isolatedCrop: Boolean = false,
    activeLeft: Int = 4, activeTop: Int = 6,
): ByteArray {
    val patterns = arrayOf(intArrayOf(0, 1, 1, 2), intArrayOf(1, 0, 2, 1),
        intArrayOf(1, 2, 0, 1), intArrayOf(2, 1, 1, 0))
    val cfa = patterns[pattern]
    val offsetX = if (isolatedCrop) activeLeft + originX else 0
    val offsetY = if (isolatedCrop) activeTop + originY else 0
    val width = if (isolatedCrop) cropWidth else 80
    val height = if (isolatedCrop) cropHeight else 64
    fun color(x: Int, y: Int) = cfa[(y % 2) * 2 + x % 2]
    data class Field(val tag: Int, val type: Int, val count: Int, val bytes: ByteArray)
    val fields = mutableListOf<Field>()
    fun field(tag: Int, type: Int, vararg values: Int) {
        val unit = when (type) { 1 -> 1; 3 -> 2; else -> 4 }
        val bytes = ByteBuffer.allocate(values.size * unit).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach { when (unit) { 1 -> bytes.put(it.toByte()); 2 -> bytes.putShort(it.toShort()); else -> bytes.putInt(it) } }
        fields += Field(tag, type, if (type == 5 || type == 10) values.size / 2 else values.size, bytes.array())
    }
    field(256, 4, width); field(257, 4, height); field(258, 3, 16); field(259, 3, 1)
    field(262, 3, 32803); field(273, 4, 0); field(274, 3, orientation); field(277, 3, 1)
    field(278, 4, height); field(279, 4, width * height * 2); field(33421, 3, 2, 2)
    field(33422, 1, color(offsetX, offsetY), color(offsetX + 1, offsetY),
        color(offsetX, offsetY + 1), color(offsetX + 1, offsetY + 1))
    field(50706, 1, 1, 4, 0, 0); field(50713, 3, 1, 1); field(50714, 4, 64); field(50717, 4, 4095)
    field(50721, 10, 1, 1, 0, 1, 0, 1, 0, 1, 1, 1, 0, 1, 0, 1, 0, 1, 1, 1)
    field(50728, 5, 1, 1, 1, 1, 1, 1); field(50778, 3, 21)
    field(50719, 4, if (isolatedCrop) 0 else originX, if (isolatedCrop) 0 else originY)
    field(50720, 4, cropWidth, cropHeight)
    if (isolatedCrop) field(50829, 4, 0, 0, height, width)
    else field(50829, 4, activeTop, activeLeft, 58, 76)
    fields.sortBy { it.tag }
    val extraStart = 8 + 2 + 12 * fields.size + 4
    val rawOffset = extraStart + fields.sumOf { if (it.bytes.size > 4) it.bytes.size else 0 }
    val out = ByteBuffer.allocate(rawOffset + width * height * 2).order(ByteOrder.LITTLE_ENDIAN)
    out.put('I'.code.toByte()).put('I'.code.toByte()).putShort(42).putInt(8).putShort(fields.size.toShort())
    var extra = extraStart
    fields.forEach {
        out.putShort(it.tag.toShort()).putShort(it.type.toShort()).putInt(it.count)
        when {
            it.tag == 273 -> out.putInt(rawOffset)
            it.bytes.size <= 4 -> { out.put(it.bytes); repeat(4 - it.bytes.size) { out.put(0) } }
            else -> { out.putInt(extra); it.bytes.copyInto(out.array(), extra); extra += it.bytes.size }
        }
    }
    out.putInt(0).position(rawOffset)
    repeat(height) { y -> repeat(width) { x ->
        val gx = x + offsetX; val gy = y + offsetY
        out.putShort((intArrayOf(700, 1700, 2700)[color(gx, gy)] + (gx * 7 + gy * 11) % 220).toShort())
    } }
    return out.array()
}
