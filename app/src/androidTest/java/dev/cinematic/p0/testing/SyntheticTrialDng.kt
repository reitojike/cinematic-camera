package dev.cinematic.p0.testing

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Generated, non-photographic TIFF/DNG for native trial integration tests. No device identity. */
internal fun syntheticTrialDng(width: Int = 32, height: Int = 24, orientation: Int = 1): ByteArray =
    ByteArrayOutputStream().also { writeSyntheticTrialDng(it, width, height, orientation) }.toByteArray()

/** Streams the raw rows so a 12MP input fixture is not held as a second full Java byte array. */
internal fun writeSyntheticTrialDng(output: OutputStream, width: Int, height: Int, orientation: Int): Long {
    fun shorts(vararg values: Int) = ByteBuffer.allocate(values.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        .apply { values.forEach { putShort(it.toShort()) } }.array()
    fun longs(vararg values: Int) = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        .apply { values.forEach { putInt(it) } }.array()
    data class Entry(val tag: Int, val type: Int, val count: Int, val bytes: ByteArray)
    val entries = mutableListOf<Entry>()
    fun short(tag: Int, vararg values: Int) { entries += Entry(tag, 3, values.size, shorts(*values)) }
    fun long(tag: Int, value: Int) { entries += Entry(tag, 4, 1, longs(value)) }
    fun ascii(tag: Int, value: String) { entries += Entry(tag, 2, value.length + 1, (value + '\u0000').toByteArray()) }
    long(256, width); long(257, height); short(258, 16); short(259, 1); short(262, 32803)
    ascii(271, "Synthetic"); ascii(272, "Bayer fixture")
    long(273, 0); short(274, orientation); short(277, 1); long(278, height); long(279, width * height * 2)
    short(33421, 2, 2)
    entries += Entry(33422, 1, 4, byteArrayOf(0, 1, 1, 2))
    entries += Entry(50706, 1, 4, byteArrayOf(1, 4, 0, 0))
    short(50713, 1, 1); long(50714, 64); long(50717, 4095)
    entries += Entry(50721, 10, 9, longs(1, 1, 0, 1, 0, 1, 0, 1, 1, 1, 0, 1, 0, 1, 0, 1, 1, 1))
    entries += Entry(50728, 5, 3, longs(1, 1, 1, 1, 1, 1))
    short(50778, 21)
    entries.sortBy { it.tag }
    val start = 8 + 2 + 12 * entries.size + 4
    val rawOffset = start + entries.sumOf { if (it.bytes.size > 4) it.bytes.size else 0 }
    val buffer = ByteBuffer.allocate(rawOffset).order(ByteOrder.LITTLE_ENDIAN)
    buffer.put('I'.code.toByte()).put('I'.code.toByte()).putShort(42).putInt(8).putShort(entries.size.toShort())
    var extra = start
    for (entry in entries) {
        buffer.putShort(entry.tag.toShort()).putShort(entry.type.toShort()).putInt(entry.count)
        when {
            entry.tag == 273 -> buffer.putInt(rawOffset)
            entry.bytes.size <= 4 -> { buffer.put(entry.bytes); repeat(4 - entry.bytes.size) { buffer.put(0) } }
            else -> { buffer.putInt(extra); System.arraycopy(entry.bytes, 0, buffer.array(), extra, entry.bytes.size); extra += entry.bytes.size }
        }
    }
    buffer.putInt(0)
    output.write(buffer.array())
    val row = ByteBuffer.allocate(width * 2).order(ByteOrder.LITTLE_ENDIAN)
    repeat(height) { y ->
        row.clear()
        repeat(width) { x -> row.putShort((164 + (x * 65 + y * 25) % 3500).toShort()) }
        output.write(row.array())
    }
    return rawOffset.toLong() + width.toLong() * height * 2
}
