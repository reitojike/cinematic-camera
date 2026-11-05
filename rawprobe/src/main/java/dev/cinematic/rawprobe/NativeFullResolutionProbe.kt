package dev.cinematic.rawprobe

import android.os.CancellationSignal

/** Separate diagnostic entry. The camera app does not expose or load this entry. */
internal object NativeFullResolutionProbe {
    init { System.loadLibrary("cinematic_dng") }
    external fun inspect(fd: Int, signal: CancellationSignal, observer: StageObserver): String
}

internal fun interface StageObserver {
    fun onStage(name: String)
}
