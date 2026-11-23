package dev.cinematic.p0.raw.original

import android.os.CancellationSignal

/** Borrowed read-only source FD and create-only private output FD. Never passes a full Java RGB array. */
internal object NativeOriginalRaster {
    init { System.loadLibrary("cinematic_dng") }
    external fun decode24(sourceFd: Int, outputFd: Int, signal: CancellationSignal): String
    // JNI implementations are present only in .pubdiag builds.
    external fun registerDecodeConfigPath(path: ByteArray?)
    external fun forceNextDecodeThreads(threads: Int, restoreThreads: Int)
    /** Unadopted half-size preview candidate. Never a source for full-resolution saving.
     * Caller must validate the half-specific report and retain normal snapshot/cleanup ownership. */
    external fun decodeHalfPreview(sourceFd: Int, outputFd: Int, signal: CancellationSignal): String
    /** Geometry admission only. Does not unpack samples, create an image or attest a completed raster. */
    external fun preflight24(sourceFd: Int, signal: CancellationSignal): IntArray
}
