package dev.cinematic.p0.gpu.reference

import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.raw.StageClearMist
import java.nio.ByteBuffer
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class GpuReferenceImagesTest {
    @Test fun everyFixtureIsRepeatableAtDefaultSizes() {
        for (size in GpuReferenceImages.FAST_SIZES + GpuReferenceImages.PREVIEW_SIZE + GpuReferenceImages.EXPORT_SIZE) {
            for (kind in GpuReferenceImages.Kind.entries) {
                assertArrayEquals("$kind $size", digest(kind, size), digest(kind, size))
            }
        }
    }

    /** 明示引数 -Pandroid.testInstrumentationRunnerArguments.gpuReferenceLarge=true のみ。 */
    @Test fun largeExportFixturesAreRepeatableWhenRequested() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("gpuReferenceLarge") == "true")
        for (kind in GpuReferenceImages.Kind.entries) {
            assertArrayEquals(kind.name, digest(kind, GpuReferenceImages.LARGE_EXPORT_SIZE), digest(kind, GpuReferenceImages.LARGE_EXPORT_SIZE))
        }
    }

    @Test fun nearWhiteFixturesExerciseAllThreeMistAutoStrengthRegions() {
        for (size in GpuReferenceImages.FAST_SIZES + GpuReferenceImages.PREVIEW_SIZE + GpuReferenceImages.EXPORT_SIZE) {
            val zero = GpuReferenceImages.create(GpuReferenceImages.Kind.NEAR_WHITE_0, size)
            val middle = GpuReferenceImages.create(GpuReferenceImages.Kind.NEAR_WHITE_2, size)
            val high = GpuReferenceImages.create(GpuReferenceImages.Kind.NEAR_WHITE_5, size)
            // 入力の性質を直接検証する。R7/Glow 後も面積率が保存されるとは仮定しない。
            assertEquals(0.0, StageClearMist.nearWhiteFraction(zero), 0.0)
            assertEquals(.02, StageClearMist.nearWhiteFraction(middle), .5 / middle.pixelCount)
            assertEquals(.05, StageClearMist.nearWhiteFraction(high), .5 / high.pixelCount)
            assertEquals(1.0, StageClearMist.autoStrength(zero), 0.0)
            assertTrue(StageClearMist.autoStrength(middle) in .79.. .81)
            assertEquals(.5, StageClearMist.autoStrength(high), 0.0)
        }
    }

    @Test fun fixturesCoverRequestedRanges() {
        val size = GpuReferenceImages.FAST_SIZES.first()
        fun range(kind: GpuReferenceImages.Kind): Pair<Float, Float> {
            val image = GpuReferenceImages.create(kind, size)
            var low = Float.POSITIVE_INFINITY
            var high = Float.NEGATIVE_INFINITY
            repeat(image.pixelCount * 3) { low = minOf(low, image[it]); high = maxOf(high, image[it]) }
            return low to high
        }
        assertEquals(0f to 1f, range(GpuReferenceImages.Kind.GREY))
        assertEquals(0f to .05f, range(GpuReferenceImages.Kind.DARK))
        assertEquals(.1f to 4f, range(GpuReferenceImages.Kind.HDR_LIGHTS))
        assertEquals(0f to 1f, range(GpuReferenceImages.Kind.SATURATED))
        val noise = range(GpuReferenceImages.Kind.NOISE)
        assertTrue(noise.first >= 0f && noise.second < 1f && noise.second > .99f)
    }

    // 一度に一枚だけ保持する。大判でも二枚分 + copyPixels の複製を積まない。
    private fun digest(kind: GpuReferenceImages.Kind, size: GpuReferenceImages.Size): ByteArray {
        val image = GpuReferenceImages.create(kind, size)
        val digest = MessageDigest.getInstance("SHA-256")
        val row = ByteBuffer.allocate(image.width * 3 * 4)
        repeat(image.height) { y ->
            row.clear()
            repeat(image.width * 3) { x -> row.putInt(image[y * image.width * 3 + x].toRawBits()) }
            digest.update(row.array())
        }
        return digest.digest()
    }
}
