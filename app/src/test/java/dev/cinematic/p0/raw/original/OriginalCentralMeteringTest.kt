package dev.cinematic.p0.raw.original

import dev.cinematic.p0.jpegrender.original.verifyAnalysisParityBestEffort
import dev.cinematic.p0.metrics.SaveTiming
import dev.cinematic.p0.metrics.SaveTimingKind
import dev.cinematic.p0.metrics.SaveTimingOutcome
import dev.cinematic.p0.metrics.SaveTimingRecord
import dev.cinematic.p0.metrics.SaveTimingStage
import dev.cinematic.p0.raw.R7PreviewInputs
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OriginalCentralMeteringTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun physical12MpRgb16FileMatchesBaselineBitForBit() {
        val file = writeRgb16File("12mp.rgb16", 4000, 2972, Pattern.REALISTIC)
        try {
            val source = OriginalRasterFile(file, 4000, 2972, 2)
            assertMatchesBaseline(source)
            assertFalse(source.hasPendingOwners)
        } finally {
            file.delete()
        }
    }

    @Test fun physical24MpRgb16FileMatchesBaselineAndIsInvariantToOuterBorderChanges() {
        val width = 6000
        val height = 4000
        val file = writeRgb16File("24mp.rgb16", width, height, Pattern.REALISTIC)
        try {
            val source = OriginalRasterFile(file, width, height, 2)
            val (outW, outH) = analysisDimensions(width, height)
            assertEquals(1024, outW)
            assertEquals(682, outH)
            val (baselineCentral, baselineExposure) = assertMatchesBaseline(source)
            assertFalse(source.hasPendingOwners)

            // Mutate outer borders outside the central input slice [1500, 4500) x [997, 2997)
            // including sentinel pixels immediately adjacent to the slice boundary.
            val (sxStart, sxEnd, syStart, syEnd) = centralSupportBounds(width, height, outW, outH)
            assertEquals(1500, sxStart)
            assertEquals(4500, sxEnd)
            assertEquals(997, syStart)
            assertEquals(2997, syEnd)
            mutateOuterBorderInPlace(file, width, height, sxStart, sxEnd, syStart, syEnd)

            val mutatedCentral = OriginalFields.reduceCentralRgb(source, outW, outH) { false }
            val mutatedExposure = R7PreviewInputs.meterCentralRgb(
                mutatedCentral, outW, outH, R7PreviewInputs.Source.LIBRAW_PRELIMINARY_AS_SHOT,
            )
            assertFalse(source.hasPendingOwners)
            assertFloatBitsEqual("24MP outer-border invariance", baselineCentral, mutatedCentral)
            assertEquals(baselineExposure.centralSampleCount, mutatedExposure.centralSampleCount)
            assertEquals(baselineExposure.centralP97.toRawBits(), mutatedExposure.centralP97.toRawBits())
            assertEquals(baselineExposure.externalBaseEv.toRawBits(), mutatedExposure.externalBaseEv.toRawBits())
        } finally {
            file.delete()
        }
    }

    @Test fun physicalVerticalFirstRgb16FileMatchesBaselineBitForBit() {
        val file = writeRgb16File("vertical-first.rgb16", 10, 8192, Pattern.REALISTIC)
        try {
            val source = OriginalRasterFile(file, 10, 8192, 2)
            assertMatchesBaseline(source)
            assertFalse(source.hasPendingOwners)
        } finally {
            file.delete()
        }
    }

    @Test fun all65536Uint16ValuesInPhysicalRgb16MatchRowsBitForBit() {
        // 512 x 256 -> outW=512, outH=256, central=256x128=32,768 pixels * 3 = 98,304 samples > 65,536.
        val width = 512
        val height = 256
        val file = writeRgb16File("all-u16.rgb16", width, height, Pattern.ALL_U16)
        try {
            val source = OriginalRasterFile(file, width, height, 2)
            // Direct check of readRgb16Window vs rows() on the full window for all 65,536 values.
            val fromRows = ArrayList<FloatArray>(height)
            source.rows({ false }) { _, row -> fromRows.add(row.copyOf()) }
            var verifiedSamples = 0
            val seen = BooleanArray(65536)
            source.readRgb16Window(0, height, 0, width, { false }) { y, row ->
                val expected = fromRows[y]
                assertEquals(expected.size, row.size)
                for (i in row.indices) {
                    assertEquals("u16 conversion mismatch at y=$y i=$i", expected[i].toRawBits(), row[i].toRawBits())
                    val sample = ((y * width * 3 + i) and 65535)
                    seen[sample] = true
                    verifiedSamples++
                }
            }
            assertEquals(width * height * 3, verifiedSamples)
            assertTrue("All 65536 uint16 values must be exercised", seen.all { it })
            assertMatchesBaseline(source)
            assertFalse(source.hasPendingOwners)
        } finally {
            file.delete()
        }
    }

    @Test fun boundarySentinelsAndOuterRewritesMatchBaselineAndStayInvariant() {
        for ((width, height) in listOf(513 to 343, 640 to 480, 1024 to 683, 2049 to 1541, 100 to 2, 2 to 100)) {
            val (outW, outH) = analysisDimensions(width, height)
            val (sxStart, sxEnd, syStart, syEnd) = centralSupportBounds(width, height, outW, outH)
            val fileA = writeRgb16File("sentinel-a-${width}x$height.rgb16", width, height, Pattern.REALISTIC)
            val fileB = writeRgb16File("sentinel-b-${width}x$height.rgb16", width, height, Pattern.REALISTIC)
            try {
                mutateOuterBorderInPlace(fileB, width, height, sxStart, sxEnd, syStart, syEnd)
                val sourceA = OriginalRasterFile(fileA, width, height, 2)
                val sourceB = OriginalRasterFile(fileB, width, height, 2)
                val (centralA, exposureA) = assertMatchesBaseline(sourceA)
                val (centralB, exposureB) = assertMatchesBaseline(sourceB)
                assertFloatBitsEqual("sentinel invariance ${width}x$height", centralA, centralB)
                assertEquals(exposureA.centralP97.toRawBits(), exposureB.centralP97.toRawBits())
                assertEquals(exposureA.externalBaseEv.toRawBits(), exposureB.externalBaseEv.toRawBits())
            } finally {
                fileA.delete()
                fileB.delete()
            }
        }
    }

    @Test fun exposureClampBoundariesMatchBaselineBitForBit() {
        for (pattern in listOf(Pattern.CLAMP_DARK, Pattern.CLAMP_BRIGHT, Pattern.CLAMP_HIGH_EDGE, Pattern.MIDTONE)) {
            val source = StreamingRgb16Raster(640, 480, pattern)
            assertMatchesBaseline(source)
        }
        // HDR Float32 raster straddling the -3.0 EV clamp boundary (p97 ~ 10.99).
        val hdrSource = object : OriginalRaster {
            override val width = 640
            override val height = 480
            override fun rows(stop: () -> Boolean, consume: (Int, FloatArray) -> Unit) {
                val row = FloatArray(width * 3)
                for (y in 0 until height) {
                    originalCheckpoint(stop)
                    for (x in 0 until width) {
                        val v = if (((x * 31 + y * 17) % 100) >= 96) 11.5f + ((x + y) % 10) * 0.1f else 8.0f + ((x + y) % 30) * 0.1f
                        val base = x * 3
                        row[base] = v; row[base + 1] = v; row[base + 2] = v
                    }
                    consume(y, row)
                }
            }
        }
        assertMatchesBaseline(hdrSource)
    }

    @Test fun dimensionMatrixCoversRemaindersBoxPhaseSmallUpscaleAndVerticalFirst() {
        val dimensions = listOf(
            // 12MP 4:3 horizontal & vertical
            4000 to 3000,
            3000 to 4000,
            // 24MP vertical
            4000 to 6000,
            // All out % 4 in {0, 1, 2, 3}
            2049 to 1537,
            2049 to 1539,
            2049 to 1541,
            2049 to 1543,
            1024 to 683,
            683 to 1024,
            // BOX exact distance == +/-0.5 phase boundaries
            2048 to 2048,
            3072 to 2048,
            4096 to 3072,
            5120 to 3072,
            // 1:1, small, upscaling boundaries
            1024 to 768,
            513 to 343,
            4 to 4,
            3 to 3,
            2 to 100,
            100 to 2,
            1 to 1,
            8192 to 10,
            // verticalFirst boundary: false (3000, 2999) vs true (2998, 2000, 10x8192)
            3000 to 6000,
            2999 to 6000,
            2998 to 6000,
            2000 to 6000,
            10 to 8192,
            29 to 97,
        )
        for ((width, height) in dimensions) {
            assertMatchesBaseline(StreamingRgb16Raster(width, height, Pattern.REALISTIC))
        }
        // Explicit verticalFirst small reduction (29x97 -> 7x11: H-outH=86 > 2*(29-7)=44).
        assertMatchesBaseline(StreamingRgb16Raster(29, 97, Pattern.REALISTIC), explicitOutW = 7, explicitOutH = 11)
    }

    @Test fun shortLongAndMutatedRgb16FilesFailCleanlyWithoutPendingOwners() {
        val width = 64
        val height = 48
        val valid = writeRgb16File("valid.rgb16", width, height, Pattern.REALISTIC)
        val shortFile = temp.newFile("short.rgb16")
        val longFile = temp.newFile("long.rgb16")
        try {
            val allBytes = valid.readBytes()
            shortFile.writeBytes(allBytes.copyOf(allBytes.size - 1))
            longFile.writeBytes(allBytes + byteArrayOf(0))
            for (badFile in listOf(shortFile, longFile)) {
                val source = OriginalRasterFile(badFile, width, height, 2)
                assertThrows(IOException::class.java) {
                    OriginalFields.reduceCentralRgb(source, width, height) { false }
                }
                assertFalse(source.hasPendingOwners)
            }
            // Mid-read truncation and extension during readRgb16Window
            for (truncate in listOf(true, false)) {
                val mutated = temp.newFile(if (truncate) "trunc.rgb16" else "ext.rgb16")
                mutated.writeBytes(allBytes)
                val source = OriginalRasterFile(mutated, width, height, 2)
                assertThrows(IOException::class.java) {
                    source.readRgb16Window(12, 36, 16, 48, { false }) { y, _ ->
                        if (y == 12) {
                            RandomAccessFile(mutated, "rw").use { raf ->
                                raf.setLength(if (truncate) allBytes.size.toLong() - 6L else allBytes.size.toLong() + 6L)
                            }
                        }
                    }
                }
                assertFalse(source.hasPendingOwners)
            }
        } finally {
            valid.delete()
        }
    }

    @Test fun float32FileWithOuterBorderNanStillFailsWithIoException() {
        val width = 16
        val height = 16
        val file = temp.newFile("outer-nan.f32")
        val buffer = ByteBuffer.allocate(width * height * 3 * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        repeat(width * height * 3) { index ->
            buffer.putFloat(if (index == 0) Float.NaN else 0.25f)
        }
        file.writeBytes(buffer.array())
        val source = OriginalRasterFile(file, width, height, 4)
        assertThrows(IOException::class.java) {
            OriginalFields.reduceCentralRgb(source, width, height) { false }
        }
        assertFalse(source.hasPendingOwners)
    }

    @Test fun cancellationAcrossReduceAndMeteringLeavesNoPendingOwners() {
        val width = 64
        val height = 48
        val file = writeRgb16File("cancel.rgb16", width, height, Pattern.REALISTIC)
        try {
            val source = OriginalRasterFile(file, width, height, 2)
            // Cancel before start
            assertThrows(CancellationException::class.java) {
                OriginalFields.reduceCentralRgb(source, width, height) { true }
            }
            assertFalse(source.hasPendingOwners)
            // Cancel mid-row-loop
            var calls = 0
            assertThrows(CancellationException::class.java) {
                OriginalFields.reduceCentralRgb(source, width, height) { ++calls >= 5 }
            }
            assertFalse(source.hasPendingOwners)
            // Cancel before finish (on verticalFirst path where finish has row checkpoints)
            val vfFile = writeRgb16File("cancel-vf.rgb16", 16, 128, Pattern.REALISTIC)
            val vfSource = OriginalRasterFile(vfFile, 16, 128, 2)
            var totalStops = 0
            OriginalFields.reduceCentralRgb(vfSource, 8, 32) { totalStops++; false }
            assertTrue(totalStops > 3)
            var vfCalls = 0
            assertThrows(CancellationException::class.java) {
                OriginalFields.reduceCentralRgb(vfSource, 8, 32) { ++vfCalls == totalStops }
            }
            assertFalse(vfSource.hasPendingOwners)
            vfFile.delete()
            // Cancel during P97 metering at every checkpoint boundary
            val centralRgb = OriginalFields.reduceCentralRgb(source, width, height) { false }
            var meterChecks = 0
            R7PreviewInputs.meterCentralRgb(centralRgb, width, height, R7PreviewInputs.Source.LIBRAW_PRELIMINARY_AS_SHOT) {
                meterChecks++; false
            }
            assertTrue(meterChecks > 2)
            for (stopAt in listOf(1, 2, meterChecks / 2, meterChecks - 1, meterChecks)) {
                var c = 0
                assertThrows(CancellationException::class.java) {
                    R7PreviewInputs.meterCentralRgb(
                        centralRgb, width, height, R7PreviewInputs.Source.LIBRAW_PRELIMINARY_AS_SHOT,
                    ) { ++c == stopAt }
                }
                assertEquals(stopAt, c)
            }
        } finally {
            file.delete()
        }
    }

    @Test fun invalidArgumentsAreRejectedBeforeIo() {
        val file = writeRgb16File("invalid-args.rgb16", 16, 16, Pattern.MIDTONE)
        try {
            val f32 = OriginalRasterFile(file, 8, 16, 4)
            assertThrows(IllegalArgumentException::class.java) {
                f32.readRgb16Window(0, 8, 0, 8, { false }) { _, _ -> }
            }
            val rgb16 = OriginalRasterFile(file, 16, 16, 2)
            for ((sr, er, sc, ec) in listOf(
                listOf(-1, 4, 0, 4),
                listOf(4, 4, 0, 4),
                listOf(0, 17, 0, 4),
                listOf(0, 4, -1, 4),
                listOf(0, 4, 4, 4),
                listOf(0, 4, 0, 17),
            )) {
                assertThrows(IllegalArgumentException::class.java) {
                    rgb16.readRgb16Window(sr, er, sc, ec, { false }) { _, _ -> }
                }
            }
            assertThrows(IllegalArgumentException::class.java) {
                OriginalFields.reduceCentralRgb(rgb16, 1, 4) { false }
            }
            assertThrows(IllegalArgumentException::class.java) {
                R7PreviewInputs.meterCentralRgb(FloatArray(12), 1025, 4, R7PreviewInputs.Source.SYNTHETIC_AS_SHOT)
            }
            assertThrows(IllegalArgumentException::class.java) {
                R7PreviewInputs.meterCentralRgb(FloatArray(12), 1, 4, R7PreviewInputs.Source.SYNTHETIC_AS_SHOT)
            }
            assertThrows(IllegalArgumentException::class.java) {
                R7PreviewInputs.meterCentralRgb(FloatArray(11), 4, 4, R7PreviewInputs.Source.SYNTHETIC_AS_SHOT)
            }
        } finally {
            file.delete()
        }
    }

    @Test fun analysisParityDiagnosticReportsCompletedFailedSkippedIncompleteAndUnavailableWithoutThrowing() {
        val width = 64
        val height = 48
        val (outW, outH) = analysisDimensions(width, height)
        val file = writeRgb16File("parity.rgb16", width, height, Pattern.REALISTIC)
        try {
            val source = OriginalRasterFile(file, width, height, 2)
            val central = OriginalFields.reduceCentralRgb(source, outW, outH) { false }
            val actual = R7PreviewInputs.meterCentralRgb(
                central, outW, outH, R7PreviewInputs.Source.LIBRAW_PRELIMINARY_AS_SHOT,
            )

            // 1. Exact match -> COMPLETED with elapsed nanos and preserved pixels
            run {
                val records = mutableListOf<SaveTimingRecord>()
                var clockTicks = 0
                val timing = SaveTiming.start(26, SaveTimingKind.SAVE, records::addAll)!!
                timing.pixels(width, height)
                verifyAnalysisParityBestEffort(
                    file, width, height, outW, outH, actual, timing,
                    clock = { if (clockTicks++ == 0) 1_000L else 6_500L },
                ) { false }
                timing.finish(SaveTimingOutcome.PUBLISHED)
                val row = records.single { it.stage == SaveTimingStage.ANALYSIS_REDUCE_PARITY }
                assertEquals(SaveTimingOutcome.COMPLETED, row.outcome)
                assertEquals(5_500L, row.nanos)
                assertEquals(width.toLong() * height, row.pixels)
            }

            // 2. 1-ULP mismatch in externalBaseEv, centralP97, or centralSampleCount -> FAILED
            val mismatches = listOf(
                actual.copy(externalBaseEv = Math.nextUp(actual.externalBaseEv)),
                actual.copy(centralP97 = Math.nextUp(actual.centralP97)),
                actual.copy(centralSampleCount = actual.centralSampleCount + 1),
            )
            for (bad in mismatches) {
                val records = mutableListOf<SaveTimingRecord>()
                val timing = SaveTiming.start(26, SaveTimingKind.SAVE, records::addAll)!!
                verifyAnalysisParityBestEffort(file, width, height, outW, outH, bad, timing) { false }
                timing.finish(SaveTimingOutcome.PUBLISHED)
                val row = records.single { it.stage == SaveTimingStage.ANALYSIS_REDUCE_PARITY }
                assertEquals(SaveTimingOutcome.FAILED, row.outcome)
                assertNotNull(row.nanos)
            }

            // 3. Pre-stopped -> SKIPPED with NA nanos (never opens file)
            run {
                val records = mutableListOf<SaveTimingRecord>()
                val timing = SaveTiming.start(26, SaveTimingKind.SAVE, records::addAll)!!
                verifyAnalysisParityBestEffort(
                    File(temp.root, "nonexistent.rgb16"), width, height, outW, outH, actual, timing,
                ) { true }
                timing.finish(SaveTimingOutcome.PUBLISHED)
                val row = records.single { it.stage == SaveTimingStage.ANALYSIS_REDUCE_PARITY }
                assertEquals(SaveTimingOutcome.SKIPPED, row.outcome)
                assertNull(row.nanos)
            }

            // 4. Mid-run cancellation or missing file -> INCOMPLETE without throwing
            run {
                val records = mutableListOf<SaveTimingRecord>()
                val timing = SaveTiming.start(26, SaveTimingKind.SAVE, records::addAll)!!
                var checks = 0
                verifyAnalysisParityBestEffort(file, width, height, outW, outH, actual, timing) { ++checks >= 3 }
                timing.finish(SaveTimingOutcome.PUBLISHED)
                val row = records.single { it.stage == SaveTimingStage.ANALYSIS_REDUCE_PARITY }
                assertEquals(SaveTimingOutcome.INCOMPLETE, row.outcome)
            }
            run {
                val records = mutableListOf<SaveTimingRecord>()
                val timing = SaveTiming.start(26, SaveTimingKind.SAVE, records::addAll)!!
                verifyAnalysisParityBestEffort(
                    File(temp.root, "missing.rgb16"), width, height, outW, outH, actual, timing,
                ) { false }
                timing.finish(SaveTimingOutcome.PUBLISHED)
                val row = records.single { it.stage == SaveTimingStage.ANALYSIS_REDUCE_PARITY }
                assertEquals(SaveTimingOutcome.INCOMPLETE, row.outcome)
            }

            // 5. Invalid preconditions -> UNAVAILABLE with NA nanos, without throwing
            run {
                val records = mutableListOf<SaveTimingRecord>()
                val timing = SaveTiming.start(26, SaveTimingKind.SAVE, records::addAll)!!
                verifyAnalysisParityBestEffort(file, 0, height, outW, outH, actual, timing) { false }
                timing.finish(SaveTimingOutcome.PUBLISHED)
                val row = records.single { it.stage == SaveTimingStage.ANALYSIS_REDUCE_PARITY }
                assertEquals(SaveTimingOutcome.UNAVAILABLE, row.outcome)
                assertNull(row.nanos)
            }

            // 6. Throwing clock -> COMPLETED with NA nanos (never fabricates 0)
            run {
                val records = mutableListOf<SaveTimingRecord>()
                val timing = SaveTiming.start(26, SaveTimingKind.SAVE, records::addAll)!!
                verifyAnalysisParityBestEffort(
                    file, width, height, outW, outH, actual, timing,
                    clock = { throw IOException("clock unavailable") },
                ) { false }
                timing.finish(SaveTimingOutcome.PUBLISHED)
                val row = records.single { it.stage == SaveTimingStage.ANALYSIS_REDUCE_PARITY }
                assertEquals(SaveTimingOutcome.COMPLETED, row.outcome)
                assertNull(row.nanos)
            }
        } finally {
            file.delete()
        }
    }

    private enum class Pattern {
        REALISTIC, ALL_U16, CLAMP_DARK, CLAMP_BRIGHT, CLAMP_HIGH_EDGE, MIDTONE
    }

    private fun analysisDimensions(width: Int, height: Int): Pair<Int, Int> {
        val scale = min(1.0, 1024.0 / max(width, height))
        return max(4, floor(width * scale).toInt()) to max(4, floor(height * scale).toInt())
    }

    private fun centralSupportBounds(width: Int, height: Int, outW: Int, outH: Int): IntArray {
        val x0 = outW / 4; val x1 = 3 * outW / 4
        val y0 = outH / 4; val y1 = 3 * outH / 4
        val columns = originalWeights(width, outW, OriginalFilter.BOX)
        val vertical = originalWeights(height, outH, OriginalFilter.BOX)
        val sxStart = (x0 until x1).minOf { columns[it].first }
        val sxEnd = (x0 until x1).maxOf { columns[it].first + columns[it].weights.size }
        var syStart = height
        var syEnd = 0
        for (dy in y0 until y1) {
            val w = vertical[dy]
            for (offset in w.weights.indices) {
                if (w.weights[offset] != 0.0) {
                    val sy = w.first + offset
                    if (sy < syStart) syStart = sy
                    if (sy + 1 > syEnd) syEnd = sy + 1
                }
            }
        }
        return intArrayOf(sxStart, sxEnd, syStart, syEnd)
    }

    private fun assertMatchesBaseline(
        source: OriginalRaster,
        explicitOutW: Int? = null,
        explicitOutH: Int? = null,
    ): Pair<FloatArray, R7PreviewInputs.DiagnosticExposure> {
        val (defaultW, defaultH) = analysisDimensions(source.width, source.height)
        val outW = explicitOutW ?: defaultW
        val outH = explicitOutH ?: defaultH
        val x0 = outW / 4; val x1 = 3 * outW / 4
        val y0 = outH / 4; val y1 = 3 * outH / 4
        val centralW = x1 - x0
        val centralH = y1 - y0

        val oldAnalysis = OriginalFields.reduce(source, outW, outH, 3, { false }) { rgb, out ->
            rgb.copyInto(out)
        }.reducedRgb()
        val oldExposure = R7PreviewInputs.prepare(
            R7PreviewInputs.AsShotInput(oldAnalysis, R7PreviewInputs.Source.LIBRAW_PRELIMINARY_AS_SHOT),
            emptyList(),
        ) { false }.exposure

        val newCentral = OriginalFields.reduceCentralRgb(source, outW, outH) { false }
        val newExposure = R7PreviewInputs.meterCentralRgb(
            newCentral, outW, outH, R7PreviewInputs.Source.LIBRAW_PRELIMINARY_AS_SHOT,
        ) { false }

        assertEquals(centralW * centralH * 3, newCentral.size)
        for (y in y0 until y1) {
            for (x in x0 until x1) {
                val oldBase = (y * outW + x) * 3
                val newBase = ((y - y0) * centralW + (x - x0)) * 3
                for (c in 0..2) {
                    val expectedBits = oldAnalysis[oldBase + c].toRawBits()
                    val actualBits = newCentral[newBase + c].toRawBits()
                    if (expectedBits != actualBits) {
                        fail(
                            "Central pixel mismatch for ${source.width}x${source.height} -> ${outW}x$outH " +
                                "at ($x,$y,c=$c): expected=${oldAnalysis[oldBase + c]} actual=${newCentral[newBase + c]}",
                        )
                    }
                }
            }
        }
        assertEquals(oldExposure.centralSampleCount, newExposure.centralSampleCount)
        assertEquals(
            "centralP97 mismatch for ${source.width}x${source.height} -> ${outW}x$outH",
            oldExposure.centralP97.toRawBits(),
            newExposure.centralP97.toRawBits(),
        )
        assertEquals(
            "externalBaseEv mismatch for ${source.width}x${source.height} -> ${outW}x$outH",
            oldExposure.externalBaseEv.toRawBits(),
            newExposure.externalBaseEv.toRawBits(),
        )
        return newCentral to newExposure
    }

    private fun assertFloatBitsEqual(label: String, expected: FloatArray, actual: FloatArray) {
        assertEquals(label, expected.size, actual.size)
        for (i in expected.indices) {
            if (expected[i].toRawBits() != actual[i].toRawBits()) {
                fail("$label mismatch at index $i: expected=${expected[i]} actual=${actual[i]}")
            }
        }
    }

    private fun writeRgb16File(name: String, width: Int, height: Int, pattern: Pattern): File {
        val file = temp.newFile(name)
        val rowBytes = ByteArray(width * 6)
        val rowSamples = IntArray(width * 3)
        file.outputStream().buffered(65536).use { out ->
            for (y in 0 until height) {
                fillRowU16(y, width, height, pattern, rowSamples)
                var b = 0
                for (i in 0 until rowSamples.size) {
                    val v = rowSamples[i]
                    rowBytes[b++] = (v and 0xFF).toByte()
                    rowBytes[b++] = ((v ushr 8) and 0xFF).toByte()
                }
                out.write(rowBytes)
            }
        }
        return file
    }

    private fun mutateOuterBorderInPlace(
        file: File,
        width: Int,
        height: Int,
        sxStart: Int,
        sxEnd: Int,
        syStart: Int,
        syEnd: Int,
    ) {
        val rowStride = width.toLong() * 6L
        val fullHighRow = ByteArray(width * 6) { 0xFF.toByte() }
        val fullZeroRow = ByteArray(width * 6)
        RandomAccessFile(file, "rw").use { raf ->
            // Overwrite rows outside [syStart, syEnd), including syStart - 1 and syEnd
            val outerRows = linkedSetOf<Int>()
            if (syStart > 0) {
                outerRows += 0
                for (d in 1..3) if (syStart - d >= 0) outerRows += syStart - d
            }
            if (syEnd < height) {
                outerRows += height - 1
                for (d in 0..2) if (syEnd + d < height) outerRows += syEnd + d
            }
            for (y in outerRows) {
                raf.seek(y.toLong() * rowStride)
                raf.write(if ((y and 1) == 0) fullHighRow else fullZeroRow)
            }
            // Overwrite columns outside [sxStart, sxEnd) on every row inside [syStart, syEnd)
            val leftCols = sxStart
            val rightCols = width - sxEnd
            val leftBytes = if (leftCols > 0) ByteArray(leftCols * 6) { 0xFF.toByte() } else null
            val rightBytes = if (rightCols > 0) ByteArray(rightCols * 6) { 0x00.toByte() } else null
            if (leftBytes != null || rightBytes != null) {
                for (y in syStart until syEnd) {
                    val rowBase = y.toLong() * rowStride
                    if (leftBytes != null) {
                        raf.seek(rowBase)
                        raf.write(leftBytes)
                    }
                    if (rightBytes != null) {
                        raf.seek(rowBase + sxEnd.toLong() * 6L)
                        raf.write(rightBytes)
                    }
                }
            }
        }
    }

    private fun fillRowU16(y: Int, width: Int, height: Int, pattern: Pattern, out: IntArray) {
        when (pattern) {
            Pattern.ALL_U16 -> {
                val base = y * width * 3
                for (i in 0 until width * 3) out[i] = (base + i) and 65535
            }
            Pattern.CLAMP_DARK -> {
                for (x in 0 until width) {
                    val base = x * 3
                    out[base] = 0; out[base + 1] = 0; out[base + 2] = (x + y) and 1
                }
            }
            Pattern.CLAMP_BRIGHT -> {
                for (x in 0 until width) {
                    val base = x * 3
                    val v = 65500 + ((x * 7 + y * 13) % 36)
                    out[base] = v; out[base + 1] = v; out[base + 2] = v
                }
            }
            Pattern.CLAMP_HIGH_EDGE -> {
                // Target p97 right around the +6.0 EV clamp knee (luminance ~ 0.00598, u16 ~ 392).
                for (x in 0 until width) {
                    val base = x * 3
                    val hi = ((x * 29 + y * 23) % 100) >= 96
                    val v = if (hi) 388 + ((x + y) and 15) else 250 + ((x + y) % 120)
                    out[base] = v; out[base + 1] = v; out[base + 2] = v
                }
            }
            Pattern.MIDTONE -> {
                for (x in 0 until width) {
                    val base = x * 3
                    out[base] = 12000 + (x * 13 + y * 7) % 8000
                    out[base + 1] = 10000 + (x * 11 + y * 5) % 8000
                    out[base + 2] = 9000 + (x * 17 + y * 3) % 8000
                }
            }
            Pattern.REALISTIC -> {
                // Smooth 2D luminance/color gradient + high-frequency texture + steep top ~3% specular highlights.
                val yf = (y * 16000L / max(1, height - 1)).toInt()
                for (x in 0 until width) {
                    val xf = (x * 12000L / max(1, width - 1)).toInt()
                    val hash = ((x * 73856093) xor (y * 19349663)) and 0x7FFFFFFF
                    val bucket = hash % 1000
                    val base = x * 3
                    if (bucket >= 968) {
                        // Top ~3.2% steep highlight tail (35000..65535) for single-ULP P97 sensitivity.
                        val spec = 35000 + ((bucket - 968) * 954) + (hash % 31)
                        out[base] = min(65535, spec)
                        out[base + 1] = min(65535, spec - ((hash ushr 4) % 900))
                        out[base + 2] = min(65535, spec - ((hash ushr 8) % 1400))
                    } else {
                        val checker = if (((x + y) and 1) == 0) 420 else -380
                        val fine = ((x * 19 + y * 37) % 1024) - 512
                        val r = (1200 + xf + yf + checker + fine).coerceIn(0, 34000)
                        val g = (900 + (xf * 3) / 4 + yf + checker - fine / 2).coerceIn(0, 34000)
                        val b = (700 + xf / 2 + (yf * 4) / 5 - checker + fine).coerceIn(0, 34000)
                        out[base] = r
                        out[base + 1] = g
                        out[base + 2] = b
                    }
                }
            }
        }
    }

    private inner class StreamingRgb16Raster(
        override val width: Int,
        override val height: Int,
        private val pattern: Pattern,
    ) : OriginalRaster {
        override fun rows(stop: () -> Boolean, consume: (Int, FloatArray) -> Unit) {
            val samples = IntArray(width * 3)
            val row = FloatArray(width * 3)
            for (y in 0 until height) {
                originalCheckpoint(stop)
                fillRowU16(y, width, height, pattern, samples)
                for (i in 0 until samples.size) row[i] = samples[i] / 65535f
                consume(y, row)
            }
        }
    }
}
