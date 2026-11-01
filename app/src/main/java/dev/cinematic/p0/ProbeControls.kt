package dev.cinematic.p0

object ProbeControls {
    fun normalizedPreviewPoint(x: Float, y: Float, width: Int, height: Int): Pair<Float, Float> {
        require(width > 0 && height > 0) { "Preview layout not ready" }
        require(x.isFinite() && y.isFinite() && x >= 0 && y >= 0 && x <= width && y <= height) { "Tap outside preview view bounds" }
        return x / width to y / height
    }

    fun evTarget(current: Int, delta: Int, range: IntRange, supported: Boolean, autoAccepted: Boolean): Int {
        require(supported) { "CameraX exposure compensation unsupported" }
        require(autoAccepted) { "EV requires accepted AUTO exposure" }
        val target = Math.addExact(current, delta)
        require(target in range) { "EV index $target outside $range" }
        return target
    }

    fun thermalName(status: Int): String = when (status) {
        0 -> "NONE"; 1 -> "LIGHT"; 2 -> "MODERATE"; 3 -> "SEVERE"
        4 -> "CRITICAL"; 5 -> "EMERGENCY"; 6 -> "SHUTDOWN"
        else -> "UNKNOWN($status)"
    }

    // Android thermal severity, not a temperature or a camera quality measurement.
    fun shouldStopRecording(status: Int) = status >= 3
}
