package dev.cinematic.p0.jpegrender.original

import dev.cinematic.p0.raw.FaceMask
import dev.cinematic.p0.raw.PhotoAdjustments
import dev.cinematic.p0.raw.PreparedStageClearGlow
import dev.cinematic.p0.raw.R7Base
import dev.cinematic.p0.raw.R7PreviewInputs
import dev.cinematic.p0.raw.original.OriginalFields
import dev.cinematic.p0.raw.original.OriginalRaster
import dev.cinematic.p0.raw.original.OriginalRasterWorkspace
import dev.cinematic.p0.raw.original.originalCheckpoint
import java.nio.file.Files
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import org.junit.Assert.*
import org.junit.Test

/** Checks the shared meter and the visible difference across the real 1024px reduction boundary. */
class OriginalPreviewParityTest {
    private class MemoryRaster(override val width: Int, override val height: Int,
        private val pixels: FloatArray) : OriginalRaster {
        override fun rows(stop: () -> Boolean, consume: (Int, FloatArray) -> Unit) {
            val row = FloatArray(width * 3)
            repeat(height) { y ->
                originalCheckpoint(stop)
                pixels.copyInto(row, 0, y * row.size, (y + 1) * row.size)
                consume(y, row)
            }
        }
    }

    @Test fun originalAndPreviewShareUnalteredMeterWithManualControlsAbove1024px() {
        val width = 1152; val height = 768
        val pixels = FloatArray(width * height * 3)
        repeat(height) { y -> repeat(width) { x ->
            val xx = (x + .5) / width; val yy = (y + .5) / height
            fun light(cx: Double, cy: Double, spread: Double) =
                exp(-((xx - cx).pow(2) + (yy - cy).pow(2)) / spread)
            val neutral = .035 + .48 * xx + .13 * yy + if ((x + y * 3) % 17 < 3) .045 else 0.0
            val cool = 1.6 * light(.21, .30, .00032)
            val warm = 1.3 * light(.74, .42, .00065)
            val i = (y * width + x) * 3
            pixels[i] = (neutral * 1.13 + cool * .66 + warm * 1.45).toFloat()
            pixels[i + 1] = (neutral * .92 + cool * 1.04 + warm * .78).toFloat()
            pixels[i + 2] = (neutral * .77 + cool * 1.57 + warm * .36).toFloat()
        } }
        val source = MemoryRaster(width, height, pixels)
        val scale = min(1.0, 1024.0 / max(width, height))
        val smallWidth = floor(width * scale).toInt(); val smallHeight = floor(height * scale).toInt()
        assertEquals(1024, smallWidth)
        val asShot = OriginalFields.reduce(source, smallWidth, smallHeight, 3, { false }) { rgb, out ->
            rgb.copyInto(out)
        }.reducedRgb()
        val controls = R7PreviewInputs.prepare(R7PreviewInputs.AsShotInput(asShot,
            R7PreviewInputs.Source.LIBRAW_PRELIMINARY_AS_SHOT), emptyList())
        val cases = listOf(
            Triple("neutral", .5, PhotoAdjustments.NONE),
            Triple("strength", 1.0, PhotoAdjustments.NONE),
            Triple("warmth", .5, PhotoAdjustments(70.0, 0.0)),
            Triple("exposure", .5, PhotoAdjustments(0.0, .6)),
            Triple("combined", .75, PhotoAdjustments(70.0, .6)),
        )
        for ((label, strength, edits) in cases) {
            val externalEv = controls.exposure.externalBaseEv + edits.exposureEv
            val observed = original24PreviewFoundation(asShot, edits) { false }
            assertEquals(externalEv, observed.externalEv, 0.0)
            val foundation = observed.image
            val expectedFoundation = R7Base.render(edits.applyWarmth(asShot), controls.foundationSkinRoi, externalEv)
            assertArrayEquals(expectedFoundation.copyPixels(), foundation.copyPixels(), 0f)
            val preview = PreparedStageClearGlow.prepare(foundation) {}.render(
                FaceMask(smallWidth, smallHeight, FloatArray(smallWidth * smallHeight)), strength) {}.image

            val red = 2.0.pow(edits.warmth / 200.0)
            val blue = 2.0.pow(-edits.warmth / 200.0)
            val norm = .2126 * red + .7152 + .0722 * blue
            val gains = doubleArrayOf(red / norm, 1.0 / norm, blue / norm)
            val adjusted = object : OriginalRaster {
                override val width = source.width
                override val height = source.height
                override fun rows(stop: () -> Boolean, consume: (Int, FloatArray) -> Unit) = source.rows(stop) { y, row ->
                    row.indices.forEach { i -> row[i] = (row[i] * gains[i % 3]).toFloat() }
                    consume(y, row)
                }
            }
            val root = Files.createTempDirectory("original-preview-parity").toFile()
            val work = OriginalRasterWorkspace(root.resolve("attempt"))
            try {
                val full = dev.cinematic.p0.raw.original.OriginalStageClear24(work).render(
                    adjusted, externalEv, strength).raster
                val reducedFull = OriginalFields.reduce(full, smallWidth, smallHeight, 3, { false }) { rgb, out ->
                    rgb.copyInto(out)
                }.reducedRgb()
                val before = preview.copyPixels(); val after = reducedFull.copyPixels()
                val differences = DoubleArray(before.size) { abs(before[it] - after[it]).toDouble() }.also { it.sort() }
                val mean = differences.average()
                val p95 = differences[(differences.size * .95).toInt()]
                val p99 = differences[(differences.size * .99).toInt()]
                println("$label preview/original 1152x768 -> ${smallWidth}x$smallHeight: ev=$externalEv, mean=$mean, p95=$p95, p99=$p99, max=${differences.last()}")
                // These bounds allow the expected nonlinearity and spatial filtering difference from
                // rendering before vs after reduction, while catching a changed exposure or recipe.
                assertTrue("$label mean=$mean", mean < .001)
                assertTrue("$label p99=$p99", p99 < .01)
                assertTrue("$label max=${differences.last()}", differences.last() < .05)
            } finally {
                work.disposeConfirmed()
                assertTrue(root.delete())
            }
        }
    }
}
