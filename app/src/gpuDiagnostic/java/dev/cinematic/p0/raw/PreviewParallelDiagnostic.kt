package dev.cinematic.p0.raw

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/** 診断variantだけの合成比較。製品のプレビュー全体やDNG保存の時間ではない。 */
internal object PreviewParallelDiagnostic {
    data class Sample(val stage: String, val workers: Int, val nanos: Long, val sha256: String)
    data class Comparison(val width: Int, val height: Int, val samples: List<Sample>)

    fun compare(
        width: Int = 1024,
        height: Int = 768,
        reverse: Boolean = false,
        shouldStop: () -> Boolean = { false },
    ): Comparison {
        require(width in 1..1024 && height in 1..1024)
        fun checkpoint() {
            if (shouldStop() || Thread.currentThread().isInterrupted) throw CancellationException("診断を取消しました")
        }
        checkpoint()
        val input = LinearSrgbImage(width, height, FloatArray(width * height * 3) { i ->
            val p = i / 3
            val u = (p % width).toFloat() / maxOf(width - 1, 1)
            when (i % 13) {
                0 -> -.001f
                1 -> 1.6f
                2 -> u * u * .018f
                else -> ((p * 37 + i % 3 * 113) % 1009) / 1008f
            }
        })
        val skin = FloatPlane(width, height, FloatArray(width * height) { (it % 5) / 4f })
        val stages = listOf<Pair<String, (PreviewPixelExecution?) -> LinearSrgbImage>>(
            "Foundation" to { execution -> R7Base.foundation(input, skin, 0.0, execution) },
            "Orton" to { execution -> StageClearOrton.render(StageClearMist.Result(input, .5), skin, execution) },
            "Character" to { execution -> StageClearCharacter.render(input, .5, execution) },
        )
        val samples = mutableListOf<Sample>()
        for ((name, render) in stages) {
            val outputs = mutableListOf<LinearSrgbImage>()
            for (workers in if (reverse) listOf(2, 1) else listOf(1, 2)) {
                checkpoint()
                val start = System.nanoTime()
                val output = if (workers == 1) render(null) else PreviewPixelExecution(2, shouldStop).use { render(it) }
                val elapsed = System.nanoTime() - start
                checkpoint()
                outputs += output
                samples += Sample(name, workers, elapsed, digest(output, ::checkpoint))
            }
            repeat(input.pixelCount * 3) { i ->
                if (i % 4096 == 0) checkpoint()
                check(outputs[0][i].toRawBits() == outputs[1][i].toRawBits()) { "$name の出力が一致しません" }
            }
        }
        checkpoint()
        return Comparison(width, height, samples.toList())
    }

    private fun digest(image: LinearSrgbImage, checkpoint: () -> Unit): String {
        val hash = MessageDigest.getInstance("SHA-256")
        val bytes = ByteBuffer.allocate(4096)
        repeat(image.pixelCount * 3) { i ->
            bytes.putInt(image[i].toRawBits())
            if (!bytes.hasRemaining()) {
                checkpoint()
                hash.update(bytes.array())
                bytes.clear()
            }
        }
        if (bytes.position() > 0) hash.update(bytes.array(), 0, bytes.position())
        return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
