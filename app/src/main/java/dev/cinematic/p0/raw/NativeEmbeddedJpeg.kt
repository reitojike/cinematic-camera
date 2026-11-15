package dev.cinematic.p0.raw

import android.os.CancellationSignal
import android.os.Looper
import androidx.annotation.Keep
import dev.cinematic.p0.rawcapture.OriginalFingerprint

/** Caller owns the read-only FD and shared admission until this synchronous call returns.
 * Only the fingerprint-matched native snapshot is parsed. No RAW fallback, image decode,
 * provider reopen, persistent copy, saved generation or image publication is performed.
 * LibRaw opaque parser allocations are not a hard bound on total native memory.
 */
@Keep
internal object NativeEmbeddedJpeg {
    fun extract(fd: Int, expected: OriginalFingerprint, cancellation: CancellationSignal): NativeEmbeddedJpegResult {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Embedded JPEG requires an admitted IO worker" }
        require(expected.bytes in 1..64L * 1024 * 1024)
        check(NativeDngDecoder.available) { "DNG decoder is unavailable" }
        cancellation.throwIfCanceled()
        val hash = ByteArray(32) { expected.sha256.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val result = extractNative(fd, expected.bytes, hash, cancellation)
        cancellation.throwIfCanceled()
        return result
    }

    private external fun extractNative(fd: Int, bytes: Long, digest: ByteArray,
        cancellation: CancellationSignal): NativeEmbeddedJpegResult
}

@Keep
internal class NativeEmbeddedJpegResult(
    private val statusCode: Int,
    val jpeg: ByteArray?,
    val width: Int,
    val height: Int,
    val orientation: Int,
    private val evidenceCode: Int,
) {
    enum class Status { FOUND, ABSENT, UNSUPPORTED, INVALID }
    enum class OrientationEvidence { UNKNOWN, THUMBNAIL, THUMBNAIL_AND_EXIF }
    val status: Status = Status.entries.getOrNull(statusCode) ?: error("Invalid embedded JPEG status")
    val evidence: OrientationEvidence = OrientationEvidence.entries.getOrNull(evidenceCode)
        ?: error("Invalid embedded JPEG orientation evidence")
    init {
        if (status == Status.FOUND) {
            require(jpeg != null && jpeg.size in 1..8 * 1024 * 1024)
            require(width in 1..4096 && height in 1..4096 && width.toLong() * height <= 4_000_000)
            require(orientation in 1..8 && evidence != OrientationEvidence.UNKNOWN)
        } else require(jpeg == null)
    }
}
