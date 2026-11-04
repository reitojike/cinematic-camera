package dev.cinematic.p0.jpegrender

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import android.os.CancellationSignal
import dev.cinematic.p0.raw.DngPreviewDecoder
import dev.cinematic.p0.raw.DngPreviewFailure
import dev.cinematic.p0.raw.DngPreviewResult
import dev.cinematic.p0.raw.LinearSrgbImage
import dev.cinematic.p0.rawcapture.SharedOriginalReference
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CancellationException
import kotlin.math.pow
import kotlin.math.roundToInt

/** Dedicated 1024-bounded decode, never the cached 512 trial Bitmap. Worker thread only.
 * Caller must cancel this same signal when its reservation is revoked, including during native decode.
 * Uses preliminary as-shot input: no W1/Pixel profile or supported-exposure claim is made here.
 */
internal class AndroidAsShotJpegFrames(
    private val resolver: ContentResolver,
    private val cancellation: CancellationSignal,
) : JpegFrameSource {
    override val contract = CONTRACT
    override fun load(source: SharedOriginalReference, longEdge: Int, shouldStop: () -> Boolean): JpegFrame {
        checkpoint(shouldStop)
        val decoded = DngPreviewDecoder.decode(resolver, Uri.parse(source.uri), cancellation, longEdge)
        checkpoint(shouldStop)
        return when (decoded) {
            is DngPreviewResult.Ready -> JpegFrame(decoded.preliminaryLinearSrgb, decoded.sourceWidth, decoded.sourceHeight, true)
            is DngPreviewResult.Failed -> {
                if (decoded.reason == DngPreviewFailure.CANCELLED) throw CancellationException("JPEG DNG decode stopped")
                throw IOException("JPEG DNG decode failed: ${decoded.reason}")
            }
        }
    }
    private fun checkpoint(stop: () -> Boolean) {
        if (cancellation.isCanceled || stop()) throw CancellationException("JPEG DNG decode stopped")
    }
    companion object { const val CONTRACT = "libraw-as-shot-bounded-v1" }
}

/** SDR sRGB transfer exactly once, one ARGB row plus one codec Bitmap; no source EXIF is copied. */
internal object AndroidSrgbJpegEncoder : LinearJpegEncoder {
    override fun encode(image: LinearSrgbImage, quality: Int, output: OutputStream, shouldStop: () -> Boolean): Boolean {
        require(quality in 1..100)
        require(image.width <= JpegGenerationRecipe.MAX_EDGE && image.height <= JpegGenerationRecipe.MAX_EDGE)
        fun checkpoint() { if (shouldStop()) throw CancellationException("JPEG encoding stopped") }
        checkpoint()
        val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.setHasAlpha(false)
            val row = IntArray(image.width)
            for (y in 0 until image.height) {
                checkpoint()
                for (x in row.indices) {
                    val p = (y * image.width + x) * 3
                    row[x] = (255 shl 24) or (channel(image[p]) shl 16) or (channel(image[p + 1]) shl 8) or channel(image[p + 2])
                }
                bitmap.setPixels(row, 0, image.width, 0, y, image.width, 1)
            }
            checkpoint()
            return bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output).also { checkpoint() }
        } finally { bitmap.recycle() }
    }
    private fun channel(linear: Float): Int {
        val x = linear.toDouble().coerceIn(0.0, 1.0)
        val srgb = if (x <= .0031308) 12.92 * x else 1.055 * x.pow(1.0 / 2.4) - .055
        return (srgb * 255.0).roundToInt().coerceIn(0, 255)
    }
}
