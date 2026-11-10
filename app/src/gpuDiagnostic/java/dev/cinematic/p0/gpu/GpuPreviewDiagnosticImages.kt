package dev.cinematic.p0.gpu

import dev.cinematic.p0.raw.LinearSrgbImage
import dev.cinematic.p0.raw.StageClearColor
import java.util.Random
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** androidTest/reference/GpuReferenceImages.kt から診断専用に複製。写真・共有入力を使わない。
 * 合成データのみ。各 create 呼び出しで seed をリセットし、実行順に依存させない。 */
internal object GpuPreviewDiagnosticImages {
    const val SEED = 20261110L
    data class Size(val width: Int, val height: Int)
    val FAST_SIZES = listOf(Size(64, 48), Size(256, 192))
    val PREVIEW_SIZE = Size(1024, 768)
    val EXPORT_SIZE = Size(1600, 1200)
    val LARGE_EXPORT_SIZE = Size(4000, 3000) // opt-in のみ

    enum class Kind {
        GREY, DARK, HDR_LIGHTS, SKIN, SATURATED,
        NEAR_WHITE_0, NEAR_WHITE_2, NEAR_WHITE_5, NOISE, MIXED,
    }

    fun create(kind: Kind, size: Size): LinearSrgbImage {
        val (width, height) = size
        require(width >= 2 && height >= 2 && width.toLong() * height <= 12_000_000)
        val random = Random(SEED)
        val palette = intArrayOf(0xff0000, 0x00ff00, 0x0000ff, 0x00ffff, 0xff00ff, 0xffff00)
        val pixels = FloatArray(width * height * 3)
        when (kind) {
            Kind.GREY, Kind.DARK -> repeat(width * height) { p ->
                val value = (p % width).toFloat() / (width - 1) * if (kind == Kind.DARK) .05f else 1f
                repeat(3) { pixels[p * 3 + it] = value }
            }
            Kind.HDR_LIGHTS -> {
                pixels.fill(.1f)
                // 全サイズで半径 2, 5, 10, 20px を含める。縁は画像で切る。
                for (radius in intArrayOf(2, 5, 10, 20)) {
                    val cx = random.nextInt(width)
                    val cy = random.nextInt(height)
                    for (y in maxOf(0, cy - radius)..minOf(height - 1, cy + radius)) {
                        for (x in maxOf(0, cx - radius)..minOf(width - 1, cx + radius)) {
                            if ((x - cx) * (x - cx) + (y - cy) * (y - cy) <= radius * radius) {
                                repeat(3) { pixels[(y * width + x) * 3 + it] = 4f }
                            }
                        }
                    }
                }
            }
            Kind.SKIN -> {
                pixels.fill(.1f)
                val lab = FloatArray(3)
                for (y in height / 4 until 3 * height / 4) {
                    for (x in width / 4 until 3 * width / 4) {
                        val u = (x - width / 4).toDouble() / maxOf(1, 3 * width / 4 - width / 4 - 1)
                        val v = (y - height / 4).toDouble() / maxOf(1, 3 * height / 4 - height / 4 - 1)
                        val hue = Math.toRadians(30 + 30 * u)
                        val chroma = .03 + .09 * v
                        lab[0] = (.5 + .3 * u).toFloat()
                        lab[1] = (chroma * cos(hue)).toFloat()
                        lab[2] = (chroma * sin(hue)).toFloat()
                        StageClearColor.toRgb(lab, pixels, (y * width + x) * 3)
                    }
                }
            }
            Kind.SATURATED -> repeat(width * height) { p ->
                val patch = minOf(2, (p % width) * 3 / width) + if (p / width >= height / 2) 3 else 0
                val rgb = palette[patch]
                repeat(3) { c -> pixels[p * 3 + c] = ((rgb ushr (16 - c * 8)) and 255) / 255f }
            }
            Kind.NEAR_WHITE_0, Kind.NEAR_WHITE_2, Kind.NEAR_WHITE_5 -> {
                pixels.fill(.1f)
                val fraction = when (kind) { Kind.NEAR_WHITE_2 -> .02; Kind.NEAR_WHITE_5 -> .05; else -> 0.0 }
                val count = (width * height * fraction).roundToInt()
                // 横長の連続領域。丸め誤差は高々半画素。
                repeat(count * 3) { pixels[it] = 1f }
            }
            Kind.NOISE -> pixels.indices.forEach { pixels[it] = random.nextFloat() }
            Kind.MIXED -> {
                // 奇数高でも全画素を覆う、灰色 / HDR / 肌 / ノイズの四分割。
                val kinds = listOf(Kind.GREY, Kind.HDR_LIGHTS, Kind.SKIN, Kind.NOISE)
                for (quadrant in kinds.indices) {
                    val x0 = if (quadrant % 2 == 0) 0 else width / 2
                    val x1 = if (quadrant % 2 == 0) width / 2 else width
                    val y0 = if (quadrant < 2) 0 else height / 2
                    val y1 = if (quadrant < 2) height / 2 else height
                    // 許可サイズの半分は常に 2px 以上。
                    require(x1 - x0 >= 2 && y1 - y0 >= 2)
                    val tile = create(kinds[quadrant], Size(x1 - x0, y1 - y0))
                    for (y in y0 until y1) for (x in x0 until x1) repeat(3) { c ->
                        pixels[(y * width + x) * 3 + c] = tile[((y - y0) * tile.width + x - x0) * 3 + c]
                    }
                }
            }
        }
        check(pixels.all { it.isFinite() && it >= 0f })
        return LinearSrgbImage.takeOwnership(width, height, pixels)
    }
}
