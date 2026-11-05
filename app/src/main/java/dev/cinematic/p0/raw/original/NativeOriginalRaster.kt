package dev.cinematic.p0.raw.original

import android.os.CancellationSignal

/** Borrowed read-only source FD and create-only private output FD. Never passes a full Java RGB array. */
internal object NativeOriginalRaster {
    init { System.loadLibrary("cinematic_dng") }
    external fun decode24(sourceFd: Int, outputFd: Int, signal: CancellationSignal): String
    /** Geometry admission only. Does not unpack samples, create an image or attest a completed raster. */
    external fun preflight24(sourceFd: Int, signal: CancellationSignal): IntArray
}
