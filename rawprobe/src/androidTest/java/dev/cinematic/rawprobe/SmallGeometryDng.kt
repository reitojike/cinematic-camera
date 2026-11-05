package dev.cinematic.rawprobe

import dev.cinematic.p0.testing.syntheticTrialDng
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Rebuild only generated fixture metadata; never reads a real photograph. */
internal fun smallGeometryDng(
    width: Int = 64, height: Int = 48, orientation: Int = 1,
    active: IntArray = intArrayOf(4, 4, 44, 60),
    origin: IntArray = intArrayOf(8, 6), crop: IntArray = intArrayOf(40, 28),
    pixelOffsetX: Int = 0, pixelOffsetY: Int = 0,
    malformed: String? = null,
): ByteArray {
    data class Tag(val id: Int, val type: Int, val count: Int, val bytes: ByteArray)
    val base = syntheticTrialDng(width, height, orientation)
    val input = ByteBuffer.wrap(base).order(ByteOrder.LITTLE_ENDIAN)
    val tags = mutableListOf<Tag>()
    repeat(input.getShort(8).toInt() and 65535) { i ->
        val p = 10 + i * 12
        val id = input.getShort(p).toInt() and 65535
        val type = input.getShort(p + 2).toInt() and 65535
        val count = input.getInt(p + 4)
        val bytes = count * when (type) { 1, 2 -> 1; 3 -> 2; 4 -> 4; 5, 10 -> 8; else -> error("Unexpected synthetic type") }
        val position = if (bytes <= 4) p + 8 else input.getInt(p + 8)
        tags += Tag(id, type, count, base.copyOfRange(position, position + bytes))
    }
    fun longs(values: IntArray) = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { values.forEach { putInt(it) } }.array()
    if (malformed != "missing-active") tags += Tag(50829, 4, 4, longs(active))
    tags += if (malformed == "fractional-crop") Tag(50719, 5, 2, longs(intArrayOf(1, 2, 6, 1))) else Tag(50719, 4, 2, longs(origin))
    tags += Tag(50720, 4, 2, longs(crop))
    if (malformed == "duplicate-active") tags += Tag(50829, 4, 4, longs(active))
    if (malformed == "user-crop") tags += Tag(51125, 5, 4, longs(intArrayOf(0, 1, 0, 1, 1, 1, 1, 1)))
    tags.sortBy { it.id }
    val extraStart = 8 + 2 + tags.size * 12 + 4
    val rawStart = extraStart + tags.sumOf { if (it.bytes.size > 4) it.bytes.size else 0 }
    val output = ByteBuffer.allocate(rawStart + width * height * 2).order(ByteOrder.LITTLE_ENDIAN)
    output.put('I'.code.toByte()).put('I'.code.toByte()).putShort(42).putInt(8).putShort(tags.size.toShort())
    var extra = extraStart
    for (tag in tags) {
        output.putShort(tag.id.toShort()).putShort(tag.type.toShort()).putInt(tag.count)
        when {
            tag.id == 273 -> output.putInt(rawStart)
            tag.bytes.size <= 4 -> { output.put(tag.bytes); repeat(4 - tag.bytes.size) { output.put(0) } }
            else -> { output.putInt(extra); tag.bytes.copyInto(output.array(), extra); extra += tag.bytes.size }
        }
    }
    output.putInt(0).position(rawStart)
    repeat(height) { y -> repeat(width) { x -> output.putShort((164 + ((x + pixelOffsetX) * 65 + (y + pixelOffsetY) * 25) % 3500).toShort()) } }
    return output.array()
}
