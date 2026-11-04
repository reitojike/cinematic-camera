package dev.cinematic.p0.raw

import android.content.ContentResolver
import android.net.Uri
import android.os.CancellationSignal
import android.os.Looper
import android.os.OperationCanceledException
import androidx.annotation.Keep
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Read-only, single-flight LibRaw preview. Call on a worker; caller owns the URI read grant.
 * This is preliminary scene-linear sRGB, NOT an R7 base or the Pixel profile-matched PC decode.
 * No face detection, JPEG export, persistent grant, file copy or publication is performed here.
 */
internal object DngPreviewDecoder {
    private val busy = AtomicBoolean(false)

    fun decode(
        resolver: ContentResolver,
        uri: Uri,
        cancellation: CancellationSignal,
        longEdge: Int = 1024,
    ): DngPreviewResult {
        require(longEdge in 1..1024) { "Preview long edge must be within 1..1024" }
        check(Looper.myLooper() != Looper.getMainLooper()) { "DNG decode requires a worker thread" }
        if (!busy.compareAndSet(false, true)) return DngPreviewResult.Failed(DngPreviewFailure.BUSY)
        try {
            cancellation.throwIfCanceled()
            if (uri.scheme != "content") return DngPreviewResult.Failed(DngPreviewFailure.SOURCE)
            if (!NativeDngDecoder.available) return DngPreviewResult.Failed(DngPreviewFailure.UNAVAILABLE)
            val decoded = resolver.openFileDescriptor(uri, "r", cancellation)?.use { source ->
                NativeDngDecoder.decode(source.fd, longEdge, cancellation)
            } ?: return DngPreviewResult.Failed(DngPreviewFailure.SOURCE)
            cancellation.throwIfCanceled()
            return DngPreviewResult.Ready(
                LinearSrgbImage(decoded.width, decoded.height, decoded.pixels),
                decoded.sourceWidth, decoded.sourceHeight,
            )
        } catch (_: OperationCanceledException) {
            return DngPreviewResult.Failed(DngPreviewFailure.CANCELLED)
        } catch (_: SecurityException) {
            return DngPreviewResult.Failed(DngPreviewFailure.SOURCE)
        } catch (error: IOException) {
            return DngPreviewResult.Failed(
                when (error.message) {
                    "DNG_CANCELLED" -> DngPreviewFailure.CANCELLED
                    "DNG_LIMIT" -> DngPreviewFailure.LIMIT
                    "DNG_UNSUPPORTED" -> DngPreviewFailure.UNSUPPORTED
                    "DNG_MEMORY" -> DngPreviewFailure.MEMORY
                    else -> DngPreviewFailure.SOURCE
                },
            )
        } catch (_: UnsatisfiedLinkError) {
            return DngPreviewResult.Failed(DngPreviewFailure.UNAVAILABLE)
        } catch (_: OutOfMemoryError) {
            return DngPreviewResult.Failed(DngPreviewFailure.MEMORY)
        } finally {
            busy.set(false)
        }
    }
}

internal sealed interface DngPreviewResult {
    /** Half-size LibRaw decode + box downsample; orientation applied by LibRaw. R7 is still required. */
    data class Ready(
        val preliminaryLinearSrgb: LinearSrgbImage,
        val sourceWidth: Int,
        val sourceHeight: Int,
    ) : DngPreviewResult
    data class Failed(val reason: DngPreviewFailure) : DngPreviewResult
}

internal enum class DngPreviewFailure { BUSY, CANCELLED, SOURCE, LIMIT, UNSUPPORTED, MEMORY, UNAVAILABLE }

@Keep
internal class NativeDngPixels(
    val width: Int,
    val height: Int,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val pixels: FloatArray,
)

@Keep
internal object NativeDngDecoder {
    val available: Boolean = try { System.loadLibrary("cinematic_dng"); true } catch (_: UnsatisfiedLinkError) { false }
    external fun decode(fd: Int, longEdge: Int, cancellation: CancellationSignal): NativeDngPixels
}
