package dev.cinematic.p0.raw

import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.roundToLong
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DngW1TrialTest {
    @Test fun requestedUserWbMatchesPcForEverySyntheticMetadataCase() {
        val cases = oracle().getJSONArray("cases")
        assertEquals(22, cases.length())
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            fixture(case.getJSONObject("metadata")).use { file ->
                val before = digest(file.file)
                val result = native(file.file)
                val requested = requireNotNull(result.whiteBalance.requestedUserWb)
                val expected = case.getJSONArray("requested_wb")
                listOf(requested.red, requested.green, requested.blue).forEachIndexed { channel, actual ->
                    val reference = expected.getDouble(channel)
                    assertEquals("${case.getString("id")} channel=$channel", reference, actual.toDouble(), 2e-5 + 2e-6 * abs(reference))
                }
                assertEquals(DngInputQuality.W1_ONLY_PRELIMINARY, result.whiteBalance.quality)
                assertFalse(result.whiteBalance.pixelProfileApplied)
                assertEquals(false, result.whiteBalance.forwardMatrixPresent)
                assertEquals(false, result.whiteBalance.lookTablePresent)
                assertEquals(false, result.whiteBalance.opcodeList2Present)
                assertArrayEquals(before, digest(file.file))
            }
        }
    }

    @Test fun appliesUserWbWithoutCameraOverrideAndLeavesDefaultAsShotUntouched() {
        val metadata = case("neutral-4")
        fixture(metadata).use { file ->
            val implicit = ParcelFileDescriptor.open(file.file, ParcelFileDescriptor.MODE_READ_ONLY).use {
                NativeDngDecoder.decode(it.fd, 1024, CancellationSignal())
            }
            val explicit = ParcelFileDescriptor.open(file.file, ParcelFileDescriptor.MODE_READ_ONLY).use {
                NativeDngDecoder.decode(it.fd, 1024, CancellationSignal(), 0)
            }
            assertArrayEquals(implicit.pixels, explicit.pixels, 0f)
            assertNull(implicit.whiteBalance.requestedUserWb)
            assertNull(implicit.whiteBalance.forwardMatrixPresent)
            assertNull(implicit.whiteBalance.lookTablePresent)
            assertNull(implicit.whiteBalance.opcodeList2Present)
            assertEquals(DngInputQuality.AS_SHOT_PRELIMINARY, implicit.whiteBalance.quality)
            val trial = native(file.file)
            assertTrue(trial.pixels.indices.any { abs(trial.pixels[it] - implicit.pixels[it]) > .001f })
            val requested = requireNotNull(trial.whiteBalance.requestedUserWb)
            val referenceMetadata = JSONObject(metadata.toString()).put("neutral", JSONArray(listOf(1.0 / requested.red, 1.0, 1.0 / requested.blue)))
            fixture(referenceMetadata).use { referenceFile ->
                val reference = native(referenceFile.file, mode = 0)
                // Same LibRaw colour path with the independently encoded requested camera WB.
                assertArrayEquals(reference.pixels, trial.pixels, 8f / 65535f)
            }
        }
    }

    @Test fun whiteXyTrialWorksWhenExistingAsShotCamMulIsUnavailable() = fixture(case("xy-4")).use { file ->
        assertError("DNG_UNSUPPORTED") { native(file.file, mode = 0) }
        assertTrue(native(file.file).pixels.any { it > 0f })
    }

    @Test fun omittedProfileStagesAreReportedAsPresenceNotAsApplied() {
        val metadata = case("neutral-4")
        fixture(metadata, profileTags = true).use { file ->
            val result = native(file.file)
            assertEquals(true, result.whiteBalance.forwardMatrixPresent)
            assertEquals(true, result.whiteBalance.lookTablePresent)
            assertEquals(true, result.whiteBalance.opcodeList2Present)
            assertEquals(DngInputQuality.W1_ONLY_PRELIMINARY, result.whiteBalance.quality)
            assertFalse(result.whiteBalance.pixelProfileApplied)
            fixture(metadata).use { baseline -> assertArrayEquals(native(baseline.file).pixels, result.pixels, 0f) }
            assertNull(native(file.file, mode = 0).whiteBalance.opcodeList2Present)
        }
    }

    @Test fun acceptsIdentityCalibrationAndBigEndianRationals() {
        fixture(case("neutral-4"), bigEndian = true, identityCalibration = true).use { file ->
            val actual = requireNotNull(native(file.file).whiteBalance.requestedUserWb)
            fixture(case("neutral-4")).use { same -> assertEquals(native(same.file).whiteBalance.requestedUserWb, actual) }
        }
    }

    @Test fun rejectsMalformedCoreMetadataWithoutFallbackAndRecovers() {
        val base = case("neutral-4")
        val mutations: List<(MutableList<Tag>) -> Unit> = listOf(
            { tags -> tags.removeAll { it.tag == 50728 } },
            { tags -> tags += rational(50729, listOf(.3127, .3290), false) },
            { tags -> tags.removeAll { it.tag == 50721 } },
            { tags -> tags += tags.first { it.tag == 50721 } },
            { tags -> replace(tags, 50721) { it.copy(count = 8) } },
            { tags -> replace(tags, 50721) { it.copy(type = 5) } },
            { tags -> replace(tags, 50721) { it.copy(bytes = it.bytes.copyOf().apply { fill(0, 4, 8) }) } },
            { tags -> replace(tags, 50721) { rational(50721, List(9) { 1.0 }, true) } },
            { tags -> replace(tags, 50721) { it.copy(forcedOffset = Int.MAX_VALUE) } },
            { tags -> replace(tags, 50721) { it.copy(type = 11, bytes = ByteBuffer.allocate(36).order(ByteOrder.LITTLE_ENDIAN).apply { repeat(9) { putFloat(Float.NaN) } }.array()) } },
            { tags -> replace(tags, 50728) { rational(50728, listOf(-1.0, 1.0, 1.0), true) } },
            { tags -> replace(tags, 50778) { shorts(50778, 255) } },
            { tags -> tags.removeAll { it.tag == 50779 } },
            { tags -> tags += rational(50727, listOf(1.0, 2.0, 1.0), false) },
            { tags -> tags += rational(50723, listOf(2.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0), true) },
            { tags -> tags += shorts(52529, 21) },
        )
        for ((index, mutate) in mutations.withIndex()) fixture(base, mutate = mutate).use { file ->
            assertError("DNG_W1_METADATA", "mutation=$index") { native(file.file) }
        }
        fixture(base, ambiguousIfd = true).use { file -> assertError("DNG_W1_METADATA") { native(file.file) } }
        fixture(base).use { assertTrue(native(it.file).pixels.isNotEmpty()) }
    }

    @Test fun rejectsWhitePointAndSearchDomainBoundaries() {
        // Positive xy outside the supported Planck search; no endpoint clipping is accepted.
        // Last xy is the public PC Planck approximation at 1740K: source is inside
        // the grid, but its +22 mired target is below 1700K.
        for (xy in listOf(listOf(.56, .40), listOf(.25, .25), listOf(.8, .4), listOf(.556153, .405749))) {
            val metadata = JSONObject(case("xy-4").toString()).put("white_xy", JSONArray(xy))
            fixture(metadata).use { file -> assertError("DNG_W1_METADATA") { native(file.file) } }
        }
        fixture(case("xy-4")).use { file ->
            val signal = CancellationSignal().apply { cancel() }
            assertError("DNG_CANCELLED") { native(file.file, signal = signal) }
            assertTrue(native(file.file).pixels.isNotEmpty())
        }
    }

    @Test fun rejectsUnsafeLibRawScaleArithmeticBeforeProcessing() {
        // A nonsingular diagonal CM can still request extreme channel scaling.
        val extreme = JSONObject(case("single-21").toString()).put("cm1", JSONArray(listOf(
            .000001, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0,
        )))
        fixture(extreme).use { file -> assertError("DNG_W1_METADATA") { native(file.file) } }
        // Black leaves just one code value of headroom. Even ordinary WB can overflow
        // LibRaw's float-to-int intermediate for the worst 16-bit input sample.
        fixture(case("neutral-4"), mutate = { tags ->
            replace(tags, 50714) { it.copy(bytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(4094).array()) }
        }).use { file -> assertError("DNG_W1_METADATA") { native(file.file) } }
    }

    private fun oracle() = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("w1_trial_v1.json").bufferedReader().use { it.readText() })
    private fun case(id: String): JSONObject {
        val all = oracle().getJSONArray("cases")
        return (0 until all.length()).map { all.getJSONObject(it) }.first { it.getString("id") == id }.getJSONObject("metadata")
    }
    private fun native(file: File, mode: Int = 1, signal: CancellationSignal = CancellationSignal()) =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { NativeDngDecoder.decode(it.fd, 1024, signal, mode) }
    private fun digest(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
    private fun assertError(code: String, detail: String = "", action: () -> Any) {
        try { action(); fail("Expected $code $detail") }
        catch (error: IOException) { assertEquals(detail, code, error.message) }
    }
    private data class Tag(val tag: Int, val type: Int, val count: Int, val bytes: ByteArray, val forcedOffset: Int? = null)
    private fun replace(tags: MutableList<Tag>, tag: Int, change: (Tag) -> Tag) {
        val index = tags.indexOfFirst { it.tag == tag }; tags[index] = change(tags[index])
    }
    private fun shorts(tag: Int, vararg values: Int, order: ByteOrder = ByteOrder.LITTLE_ENDIAN) =
        Tag(tag, 3, values.size, ByteBuffer.allocate(values.size * 2).order(order).apply { values.forEach { putShort(it.toShort()) } }.array())
    private fun rational(tag: Int, values: List<Double>, signed: Boolean, order: ByteOrder = ByteOrder.LITTLE_ENDIAN): Tag =
        Tag(tag, if (signed) 10 else 5, values.size, ByteBuffer.allocate(values.size * 8).order(order).apply {
            values.forEach { putInt((it * 1_000_000).roundToLong().toInt()); putInt(1_000_000) }
        }.array())
    private class Fixture(val file: File) : AutoCloseable { override fun close() { check(file.delete()) } }

    /** Writes bounded artificial DNG bytes only; no user file or metadata is ever read. */
    private fun fixture(
        metadata: JSONObject, bigEndian: Boolean = false, profileTags: Boolean = false,
        identityCalibration: Boolean = false, ambiguousIfd: Boolean = false,
        mutate: (MutableList<Tag>) -> Unit = {},
    ): Fixture {
        val order = if (bigEndian) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
        fun ints(vararg values: Int) = ByteBuffer.allocate(values.size * 4).order(order).apply { values.forEach { putInt(it) } }.array()
        fun long(tag: Int, value: Int) = Tag(tag, 4, 1, ints(value))
        fun short(tag: Int, vararg values: Int) = shorts(tag, *values, order = order)
        fun ratios(tag: Int, values: List<Double>, signed: Boolean) = rational(tag, values, signed, order)
        val tags = mutableListOf(
            long(256, 32), long(257, 24), short(258, 16), short(259, 1), short(262, 32803),
            Tag(271, 2, 10, "Synthetic\u0000".toByteArray()), Tag(272, 2, 11, "WB fixture\u0000".toByteArray()),
            long(273, 0), short(274, 1), short(277, 1), long(278, 24), long(279, 32 * 24 * 2),
            short(33421, 2, 2), Tag(33422, 1, 4, byteArrayOf(0, 1, 1, 2)),
            Tag(50706, 1, 4, byteArrayOf(1, 4, 0, 0)), short(50713, 1, 1), long(50714, 64), long(50717, 4095),
        )
        val ids = mapOf("neutral" to 50728, "white_xy" to 50729, "cm1" to 50721, "cm2" to 50722, "illum1" to 50778, "illum2" to 50779)
        for ((name, tag) in ids) if (metadata.has(name)) {
            val array = metadata.getJSONArray(name)
            val values = (0 until array.length()).map { array.getDouble(it) }
            tags += if (name.startsWith("illum")) short(tag, values.single().toInt()) else ratios(tag, values, name.startsWith("cm"))
        }
        if (identityCalibration) {
            tags += ratios(50727, listOf(1.0, 1.0, 1.0), false)
            for (tag in listOf(50723, 50724)) tags += ratios(tag, List(9) { if (it % 4 == 0) 1.0 else 0.0 }, true)
        }
        if (profileTags) {
            for (tag in listOf(50964, 50965)) tags += ratios(tag, listOf(.96422, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, .82521), true)
            tags += Tag(50981, 4, 3, ints(1, 2, 1))
            tags += Tag(50982, 11, 6, ByteBuffer.allocate(24).order(order).apply { listOf(0f, 1f, 1f, 0f, 1f, 1f).forEach { putFloat(it) } }.array())
            tags += Tag(51009, 7, 4, byteArrayOf(0, 0, 0, 0)) // Empty list; no inference about GainMap.
        }
        if (ambiguousIfd) tags += long(330, 0)
        mutate(tags)
        tags.sortBy { it.tag }
        val extrasStart = 8 + 2 + tags.size * 12 + 4
        val subIfd = extrasStart + tags.sumOf { if (it.bytes.size > 4) it.bytes.size else 0 }
        val rawOffset = subIfd + if (ambiguousIfd) 18 + 24 else 0
        val buffer = ByteBuffer.allocate(rawOffset + 32 * 24 * 2).order(order)
        val marker = if (bigEndian) 'M' else 'I'
        buffer.put(marker.code.toByte()).put(marker.code.toByte()).putShort(42).putInt(8).putShort(tags.size.toShort())
        var extra = extrasStart
        for (tag in tags) {
            buffer.putShort(tag.tag.toShort()).putShort(tag.type.toShort()).putInt(tag.count)
            when {
                tag.forcedOffset != null -> buffer.putInt(tag.forcedOffset)
                tag.tag == 273 -> buffer.putInt(rawOffset)
                tag.tag == 330 -> buffer.putInt(subIfd)
                tag.bytes.size <= 4 -> { buffer.put(tag.bytes); repeat(4 - tag.bytes.size) { buffer.put(0) } }
                else -> { buffer.putInt(extra); System.arraycopy(tag.bytes, 0, buffer.array(), extra, tag.bytes.size); extra += tag.bytes.size }
            }
        }
        buffer.putInt(0)
        if (ambiguousIfd) {
            buffer.position(subIfd)
            buffer.putShort(1).putShort(50728.toShort()).putShort(5).putInt(3).putInt(subIfd + 18).putInt(0)
            repeat(3) { buffer.putInt(1).putInt(1) }
        }
        buffer.position(rawOffset)
        repeat(24) { y -> repeat(32) { x -> buffer.putShort((164 + (x * 65 + y * 25) % 3500).toShort()) } }
        val file = File.createTempFile("synthetic-w1-", ".bin", InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
        file.writeBytes(buffer.array())
        return Fixture(file)
    }
}
