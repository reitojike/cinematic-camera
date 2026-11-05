package dev.cinematic.p0.jpegrender.original

import android.os.CancellationSignal
import dev.cinematic.p0.raw.original.NativeOriginalRaster
import java.io.IOException

/** Expected crop/orientation only. Not OriginalRasterGeometry/fullCoverage, a decoded image,
 * source-fingerprint verification, profile/face/EV evidence or a publication proof. */
internal data class Original24PreflightGeometry internal constructor(
    val rawWidth: Int,
    val rawHeight: Int,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val orientation: Int,
    val outputWidth: Int,
    val outputHeight: Int,
    val cropLeft: Int = 0,
    val cropTop: Int = 0,
) {
    init {
        if (rawWidth !in 1..8192 || rawHeight !in 1..8192 || rawWidth.toLong() * rawHeight > 32_000_000 ||
            sourceWidth !in 1..rawWidth || sourceHeight !in 1..rawHeight || orientation !in 1..8 ||
            sourceWidth.toLong() * sourceHeight > 24_000_000 ||
            cropLeft < 0 || cropTop < 0 || cropLeft.toLong() + sourceWidth > rawWidth ||
            cropTop.toLong() + sourceHeight > rawHeight ||
            outputWidth != (if (orientation >= 5) sourceHeight else sourceWidth) ||
            outputHeight != (if (orientation >= 5) sourceWidth else sourceHeight))
            throw IOException("Original 24MP geometry is unsupported")
    }
}

/** Call on existing IO before creating the save owner. Borrowed read-only regular snapshot FD;
 * the caller owns its close and separately confirms source identity. Native reads <=64MiB with
 * pread and reuses the full decoder's classic-TIFF parser and LibRaw open/geometry admission.
 * No second TIFF parser, unpack, make-image, crop application or output file is introduced here.
 * Full decode still repeats these gates and verifies actual crop/rows after unpack. */
internal object Original24Preflight {
    fun inspect(sourceFd: Int, signal: CancellationSignal): Original24PreflightGeometry {
        if (sourceFd < 0) throw IOException("Invalid original source descriptor")
        return fromNative(NativeOriginalRaster.preflight24(sourceFd, signal))
    }

    internal fun fromNative(values: IntArray): Original24PreflightGeometry {
        if (values.size != 10 || values[0] != 2) throw IOException("Unknown original geometry preflight schema")
        return Original24PreflightGeometry(values[1], values[2], values[3], values[4], values[5], values[6], values[7], values[8], values[9])
    }
}
