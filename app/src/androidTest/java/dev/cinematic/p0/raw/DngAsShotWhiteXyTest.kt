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
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DngAsShotWhiteXyTest {
    @Test fun requestedUserWbMatchesIndependentRawTagOracle() {
        val oracle = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("as_shot_white_xy_v1.json").bufferedReader().use { it.readText() })
        val cases = oracle.getJSONArray("cases")
        assertEquals(11, cases.length())
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            fixture(metadata = case.getJSONObject("metadata"), signature = case.getString("signature")).use { file ->
                val actual = requireNotNull(native(file.file).whiteBalance.requestedUserWb)
                val expected = case.getJSONArray("requested_wb")
                listOf(actual.red, actual.green, actual.blue).forEachIndexed { channel, value ->
                    val reference = expected.getDouble(channel)
                    assertEquals("${case.getString("id")} channel=$channel", reference, value.toDouble(), 2e-5 + 2e-6 * abs(reference))
                }
            }
        }
    }

    @Test fun whiteXyWithNonidentityCalibrationUsesExplicitLegacyBasisAndPreservesOriginal() {
        fixture(third = true).use { file ->
            val before = digest(file.file)
            val result = native(file.file)
            assertEquals(16, result.width)
            assertEquals(12, result.height)
            assertTrue(result.pixels.all { it.isFinite() && it in 0f..1f })
            val summary = result.whiteBalance
            assertEquals(DngInputQuality.AS_SHOT_PRELIMINARY, summary.quality)
            assertEquals(DngWhiteBalanceBasis.RAW_TAG_WHITE_XY_LEGACY_DUAL, summary.basis)
            assertNotNull(summary.requestedUserWb)
            assertEquals(true, summary.thirdCalibrationPresent)
            assertEquals(false, summary.forwardMatrixPresent)
            assertEquals(false, summary.lookTablePresent)
            assertEquals(false, summary.opcodeList2Present)
            assertFalse(summary.pixelProfileApplied)
            assertArrayEquals(before, digest(file.file))
            assertError("DNG_W1_METADATA") { native(file.file, mode = 1) }
            assertArrayEquals(result.pixels, native(file.file).pixels, 0f)
            fixture(third = true, mutate = { tags, order ->
                replace(tags, 52530) { rational(52530, listOf(3.0, -.2, .1, .1, .5, -.1, -.2, .1, 2.0), true, order) }
            }).use { differentThird ->
                val thirdBefore = digest(differentThird.file)
                val other = native(differentThird.file)
                assertEquals(summary.requestedUserWb, other.whiteBalance.requestedUserWb)
                assertArrayEquals(result.pixels, other.pixels, 0f)
                assertArrayEquals(thirdBefore, digest(differentThird.file))
            }
        }
    }

    @Test fun whiteXyAndEquivalentNeutralUseTheSameLibRawColourTransform() {
        fixture().use { white ->
            val fromXy = native(white.file)
            val wb = requireNotNull(fromXy.whiteBalance.requestedUserWb)
            fixture(neutral = listOf(1.0 / wb.red, 1.0, 1.0 / wb.blue)).use { neutral ->
                val reference = native(neutral.file)
                assertEquals(DngWhiteBalanceBasis.LIBRAW_CAMERA, reference.whiteBalance.basis)
                assertNull(reference.whiteBalance.requestedUserWb)
                assertNull(reference.whiteBalance.thirdCalibrationPresent)
                assertArrayEquals(reference.pixels, fromXy.pixels, 8f / 65535f)
            }
            fixture(x = .4, y = .4).use { other ->
                val different = native(other.file)
                assertNotEquals(wb, different.whiteBalance.requestedUserWb)
                assertTrue(different.pixels.indices.any { abs(different.pixels[it] - fromXy.pixels[it]) > .001f })
            }
        }
    }

    @Test fun acceptsMatchingUtf8AndEmptySignaturesAndBigEndianMetadata() {
        fixture(signature = "calibration-\u03b1", bigEndian = true).use { big ->
            fixture().use { little ->
                assertEquals(native(little.file).whiteBalance.requestedUserWb, native(big.file).whiteBalance.requestedUserWb)
                assertArrayEquals(native(little.file).pixels, native(big.file).pixels, 0f)
            }
        }
        fixture(signature = "").use { explicitEmpty ->
            fixture().use { absent -> assertEquals(native(absent.file).whiteBalance.requestedUserWb, native(explicitEmpty.file).whiteBalance.requestedUserWb) }
        }
        fixture(signature = "calibration", mutate = { tags, _ ->
            replace(tags, 50931) { it.copy(type = 1) }
            replace(tags, 50932) { it.copy(type = 1) }
        }).use { byteSignature ->
            fixture(signature = "calibration").use { asciiSignature ->
                assertEquals(native(asciiSignature.file).whiteBalance.requestedUserWb, native(byteSignature.file).whiteBalance.requestedUserWb)
            }
        }
    }

    @Test fun rejectsMismatchedNonidentityCalibrationButAllowsIdentityCalibration() {
        fixture(signature = "first", mutate = { tags, _ ->
            replace(tags, 50932) { ascii(50932, "different") }
        }).use { file -> assertError("DNG_UNSUPPORTED") { native(file.file) } }
        fixture(signature = "first", identityCalibration = true, mutate = { tags, _ ->
            replace(tags, 50932) { ascii(50932, "different") }
        }).use { file -> assertTrue(native(file.file).pixels.any { it > 0f }) }
    }

    @Test fun acceptsOnlySceneReferredAndUnambiguousPrimaryProfile() {
        fixture(mutate = { tags, order ->
            tags += shorts(50879, 0, order = order)
            tags += ascii(50934, "primary")
            tags += ascii(50936, "primary")
        }).use { matching ->
            fixture().use { absent -> assertArrayEquals(native(absent.file).pixels, native(matching.file).pixels, 0f) }
        }
        val mutations: List<(MutableList<Tag>, ByteOrder) -> Unit> = listOf(
            { tags, order -> tags += shorts(50879, 1, order = order) },
            { tags, order -> tags += shorts(50879, 2, order = order) },
            { tags, order -> tags += shorts(50879, 255, order = order) },
            { tags, order -> tags += long(50879, 0, order) },
            { tags, order -> tags += shorts(50879, 0, 0, order = order) },
            { tags, order -> tags += shorts(50879, 0, order = order); tags += shorts(50879, 0, order = order) },
            { tags, _ -> tags += ascii(50934, "external") },
            { tags, _ -> tags += ascii(50934, "external"); tags += ascii(50936, "primary") },
            { tags, _ -> tags += ascii(50934, "primary"); tags += ascii(50936, "primary"); tags += ascii(50936, "duplicate") },
            { tags, _ -> tags += ascii(50934, "primary").copy(offset = Int.MAX_VALUE) },
            { tags, _ -> tags += ascii(50934, "primary").copy(type = 7) },
            { tags, _ -> tags += ascii(50936, "x".repeat(256)) },
        )
        for ((index, mutate) in mutations.withIndex()) fixture(mutate = mutate).use { file ->
            assertError("DNG_UNSUPPORTED", "profile=$index") { native(file.file) }
        }
        fixture().use { assertTrue(native(it.file).pixels.isNotEmpty()) }
    }

    @Test fun rejectsSingleMatrixNonD65AndAlternateProfileWithoutFallback() {
        val mutations: List<(MutableList<Tag>, ByteOrder) -> Unit> = listOf(
            { tags, _ -> tags.removeAll { it.id == 50722 || it.id == 50779 } },
            { tags, order -> replace(tags, 50779) { shorts(50779, 23, order = order) } },
            { tags, order -> tags += long(50933, 0, order) },
            { tags, order -> replace(tags, 50779) { shorts(50779, 17, order = order) } },
            { tags, order -> tags += rational(50728, listOf(0.0, 0.0, 0.0), false, order) },
            { tags, _ -> tags.removeAll { it.id == 50729 } },
        )
        for ((index, mutate) in mutations.withIndex()) fixture(mutate = mutate).use { file ->
            assertError("DNG_UNSUPPORTED", "case=$index") { native(file.file) }
        }
        fixture().use { assertTrue(native(it.file).pixels.isNotEmpty()) }
    }

    @Test fun rejectsMalformedAndUnsafeWhiteXyMetadata() {
        val mutations: List<(MutableList<Tag>, ByteOrder) -> Unit> = listOf(
            { tags, _ -> tags += tags.first { it.id == 50729 } },
            { tags, _ -> replace(tags, 50729) { it.copy(type = 10) } },
            { tags, _ -> replace(tags, 50729) { it.copy(count = 1) } },
            { tags, _ -> replace(tags, 50729) { it.copy(offset = Int.MAX_VALUE) } },
            { tags, _ -> replace(tags, 50729) { it.copy(bytes = it.bytes.copyOf().apply { fill(0, 4, 8) }) } },
            { tags, order -> replace(tags, 50729) { rational(50729, listOf(.8, .4), false, order) } },
            { tags, order -> replace(tags, 50729) { rational(50729, listOf(.25, .25), false, order) } },
            { tags, order -> replace(tags, 50729) { rational(50729, listOf(.56, .4), false, order) } },
            { tags, order -> replace(tags, 50721) { rational(50721, List(9) { 1.0 }, true, order) } },
            { tags, order -> replace(tags, 50723) { rational(50723, List(9) { 1.0 }, true, order) } },
            { tags, order -> replace(tags, 50727) { rational(50727, listOf(0.0, 1.0, 1.0), false, order) } },
            { tags, order -> replace(tags, 50714) { long(50714, 4094, order) } },
            { tags, _ -> tags += ascii(50931, "duplicate") },
            { tags, _ -> replace(tags, 50931) { it.copy(bytes = byteArrayOf(65), count = 1) } },
            { tags, _ -> replace(tags, 50931) { it.copy(bytes = byteArrayOf(65, 0, 66, 0), count = 4) } },
            { tags, order ->
                replace(tags, 50729) { rational(50729, listOf(.48, .41), false, order) }
                replace(tags, 50721) { rational(50721, listOf(.000001, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0), true, order) }
                replace(tags, 50723) { rational(50723, List(9) { if (it % 4 == 0) 1.0 else 0.0 }, true, order) }
            },
        )
        for ((index, mutate) in mutations.withIndex()) fixture(signature = "calibration", mutate = mutate).use { file ->
            assertError("DNG_UNSUPPORTED", "case=$index") { native(file.file) }
        }
        fixture(signature = "calibration", mutate = { tags, _ ->
            replace(tags, 50931) { it.copy(bytes = byteArrayOf(0xc0.toByte(), 0x80.toByte(), 0), count = 3) }
        }).use { file -> assertError("DNG_UNSUPPORTED") { native(file.file) } }
        fixture(ambiguousIfd = true).use { file -> assertError("DNG_UNSUPPORTED") { native(file.file) } }
    }

    @Test fun cancellationRecoversAndValidCameraWbIsNotReinterpreted() {
        fixture(third = true).use { file ->
            val before = digest(file.file)
            val cancelled = CancellationSignal().apply { cancel() }
            assertError("DNG_CANCELLED") { native(file.file, signal = cancelled) }
            assertTrue(native(file.file).pixels.isNotEmpty())
            assertArrayEquals(before, digest(file.file))
        }
        // Existing valid camera WB remains the original route, even if unsupported profile tags exist.
        fixture(neutral = listOf(.5, 1.0, .75), third = true).use { file ->
            val result = native(file.file)
            assertEquals(DngWhiteBalanceBasis.LIBRAW_CAMERA, result.whiteBalance.basis)
            assertNull(result.whiteBalance.requestedUserWb)
            assertNull(result.whiteBalance.thirdCalibrationPresent)
        }
    }

    private data class Tag(val id: Int, val type: Int, val count: Int, val bytes: ByteArray, val offset: Int? = null)
    private class Fixture(val file: File) : AutoCloseable { override fun close() { check(file.delete()) } }
    private fun native(file: File, mode: Int = 0, signal: CancellationSignal = CancellationSignal()) =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { NativeDngDecoder.decode(it.fd, 1024, signal, mode) }
    private fun digest(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
    private fun assertError(code: String, detail: String = "", action: () -> Any) {
        try { action(); fail("Expected $code $detail") }
        catch (error: IOException) { assertEquals(detail, code, error.message) }
    }
    private fun replace(tags: MutableList<Tag>, id: Int, change: (Tag) -> Tag) {
        val index = tags.indexOfFirst { it.id == id }; tags[index] = change(tags[index])
    }
    private fun ascii(id: Int, value: String): Tag {
        val bytes = (value + '\u0000').toByteArray(Charsets.UTF_8)
        return Tag(id, 2, bytes.size, bytes)
    }
    private fun shorts(id: Int, vararg values: Int, order: ByteOrder) = Tag(id, 3, values.size,
        ByteBuffer.allocate(values.size * 2).order(order).apply { values.forEach { putShort(it.toShort()) } }.array())
    private fun long(id: Int, value: Int, order: ByteOrder) =
        Tag(id, 4, 1, ByteBuffer.allocate(4).order(order).putInt(value).array())
    private fun rational(id: Int, values: List<Double>, signed: Boolean, order: ByteOrder) = Tag(id, if (signed) 10 else 5, values.size,
        ByteBuffer.allocate(values.size * 8).order(order).apply {
            values.forEach { putInt((it * 1_000_000).roundToLong().toInt()); putInt(1_000_000) }
        }.array())

    /** Public non-photographic 32x24 Bayer pattern. All metadata below is invented for these tests. */
    private fun fixture(
        x: Double = .3457, y: Double = .3585, neutral: List<Double>? = null,
        third: Boolean = false, signature: String? = null, identityCalibration: Boolean = false,
        bigEndian: Boolean = false, ambiguousIfd: Boolean = false, metadata: JSONObject? = null,
        mutate: (MutableList<Tag>, ByteOrder) -> Unit = { _, _ -> },
    ): Fixture {
        val order = if (bigEndian) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
        fun short(id: Int, vararg values: Int) = shorts(id, *values, order = order)
        fun uint(id: Int, value: Int) = long(id, value, order)
        fun ratios(id: Int, values: List<Double>, signed: Boolean = true) = rational(id, values, signed, order)
        fun values(name: String, fallback: List<Double>): List<Double> = if (metadata?.has(name) == true) {
            val array = metadata.getJSONArray(name)
            (0 until array.length()).map { array.getDouble(it) }
        } else fallback
        val identity = List(9) { if (it % 4 == 0) 1.0 else 0.0 }
        val tags = mutableListOf(
            uint(256, 32), uint(257, 24), short(258, 16), short(259, 1), short(262, 32803),
            ascii(271, "Synthetic"), ascii(272, "WhiteXY fixture"), uint(273, 0), short(274, 1), short(277, 1), uint(278, 24), uint(279, 1536),
            short(33421, 2, 2), Tag(33422, 1, 4, byteArrayOf(0, 1, 1, 2)), Tag(50706, 1, 4, byteArrayOf(1, 6, 0, 0)),
            short(50713, 1, 1), uint(50714, 64), uint(50717, 4095),
            ratios(50721, values("cm1", listOf(.78, -.12, -.06, -.2, 1.1, .08, .01, -.05, .82))),
            ratios(50722, values("cm2", listOf(.9, -.18, -.08, -.15, 1.05, .05, .02, -.08, .95))),
            ratios(50723, values("cc1", if (identityCalibration) identity else listOf(1.02, .01, 0.0, -.01, .99, .02, 0.0, .01, 1.03))),
            ratios(50724, values("cc2", if (identityCalibration) identity else listOf(.98, .02, .01, .01, 1.01, -.01, .02, 0.0, 1.04))),
            ratios(50727, values("ab", listOf(1.1, .95, 1.05)), false),
            short(50778, values("illum1", listOf(17.0)).single().toInt()), short(50779, values("illum2", listOf(21.0)).single().toInt()),
        )
        tags += if (neutral == null) ratios(50729, values("white_xy", listOf(x, y)), false) else ratios(50728, neutral, false)
        if (signature != null) { tags += ascii(50931, signature); tags += ascii(50932, signature) }
        if (third) {
            tags += short(52529, 23)
            tags += ratios(52530, identity)
            tags += ratios(52531, identity)
        }
        if (ambiguousIfd) tags += uint(330, 0)
        mutate(tags, order)
        tags.sortBy { it.id }
        val start = 8 + 2 + tags.size * 12 + 4
        val subIfd = start + tags.sumOf { if (it.bytes.size > 4) it.bytes.size else 0 }
        val rawOffset = subIfd + if (ambiguousIfd) 34 else 0
        val buffer = ByteBuffer.allocate(rawOffset + 1536).order(order)
        val marker = if (bigEndian) 'M' else 'I'
        buffer.put(marker.code.toByte()).put(marker.code.toByte()).putShort(42).putInt(8).putShort(tags.size.toShort())
        var extra = start
        for (tag in tags) {
            buffer.putShort(tag.id.toShort()).putShort(tag.type.toShort()).putInt(tag.count)
            when {
                tag.offset != null -> buffer.putInt(tag.offset)
                tag.id == 273 -> buffer.putInt(rawOffset)
                tag.id == 330 -> buffer.putInt(subIfd)
                tag.bytes.size <= 4 -> { buffer.put(tag.bytes); repeat(4 - tag.bytes.size) { buffer.put(0) } }
                else -> { buffer.putInt(extra); System.arraycopy(tag.bytes, 0, buffer.array(), extra, tag.bytes.size); extra += tag.bytes.size }
            }
        }
        buffer.putInt(0)
        if (ambiguousIfd) {
            buffer.position(subIfd)
            buffer.putShort(1).putShort(50729.toShort()).putShort(5).putInt(2).putInt(subIfd + 18).putInt(0)
            buffer.putInt(3457).putInt(10000).putInt(3585).putInt(10000)
        }
        buffer.position(rawOffset)
        repeat(24) { row -> repeat(32) { col -> buffer.putShort((164 + (col * 65 + row * 25) % 3500).toShort()) } }
        val file = File.createTempFile("synthetic-white-xy-", ".bin", InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
        file.writeBytes(buffer.array())
        return Fixture(file)
    }
}
