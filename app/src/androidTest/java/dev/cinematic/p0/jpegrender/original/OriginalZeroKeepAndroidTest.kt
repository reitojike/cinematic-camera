package dev.cinematic.p0.jpegrender.original

import android.graphics.Bitmap
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.raw.original.*
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.pow
import kotlin.math.roundToInt

/** Synthetic emulator-only output check. No DNG, photos, shared storage or publication. */
@RunWith(AndroidJUnit4::class)
class OriginalZeroKeepAndroidTest {
    @Test fun syntheticFullPipelineJpegRemainsExact() {
        assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val width = 48; val height = 32
        val source = FloatArray(width * height * 3) { ((it * 37L) % 4096) / 2048f - .05f }
        val originalBits = source.map { it.toRawBits() }.toIntArray()
        val input = object : OriginalRaster {
            override val width = 48
            override val height = 32
            override fun rows(stop: () -> Boolean, consume: (Int, FloatArray) -> Unit) {
                repeat(height) { y -> originalCheckpoint(stop); consume(y, source.copyOfRange(y * width * 3, (y + 1) * width * 3)) }
            }
        }
        fun encode(enabled: Boolean): ByteArray {
            val root = Files.createTempDirectory(context.cacheDir.toPath(), "zero-keep-").toFile()
            val work = OriginalRasterWorkspace(root.resolve("attempt"))
            var bitmap: Bitmap? = null
            try {
                val result = OriginalStageClear24(work, parallelOrtonWorkers = 4, skipZeroKeepOrton = enabled)
                    .render(input, .23, .5)
                val pixels = IntArray(width * height)
                fun channel(v: Float): Int {
                    val x = v.coerceIn(0f, 1f)
                    val s = if (x <= .0031308f) x * 12.92f else 1.055f * x.pow(1f / 2.4f) - .055f
                    return (s * 255f).roundToInt().coerceIn(0, 255)
                }
                result.raster.rows({ false }) { y, row -> repeat(width) { x ->
                    pixels[y * width + x] = (255 shl 24) or (channel(row[x * 3]) shl 16) or
                        (channel(row[x * 3 + 1]) shl 8) or channel(row[x * 3 + 2])
                } }
                val outputBitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
                bitmap = outputBitmap
                return ByteArrayOutputStream().use { out ->
                    assertTrue(outputBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out))
                    out.toByteArray()
                }
            } finally { bitmap?.recycle(); work.closePending(); work.disposeConfirmed(); check(root.delete()) }
        }
        assertArrayEquals(encode(false), encode(true))
        assertArrayEquals(originalBits, source.map { it.toRawBits() }.toIntArray())
    }
}
