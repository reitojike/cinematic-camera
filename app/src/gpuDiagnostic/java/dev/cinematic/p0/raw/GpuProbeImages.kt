package dev.cinematic.p0.raw

/** 原寸の全配列を使う補助は1024pxまで。12MP診断は帯の入口を直接使う。 */
internal object GpuProbeImages {
    val lastWallNs = ThreadLocal<Long>()
    fun blur(image: LinearSrgbImage, radius: Double): LinearSrgbImage {
        require(maxOf(image.width, image.height) <= 1024)
        val started = System.nanoTime()
        val output = FloatArray(image.pixelCount * 3)
        val times = GpuBlurProbe.run(image.width, image.height, radius,
            source = { top, rows -> FloatArray(image.width * rows * 4) { i ->
                if (i % 4 == 3) 1f else image[((top * image.width + i / 4) * 3) + i % 4]
            } }, consume = { top, frame ->
                repeat(frame.width * frame.height) { p -> repeat(3) { c ->
                    output[(top * frame.width + p) * 3 + c] = frame.rgba[p * 4 + c]
                } }
            }, cancelled = { GpuDiagnosticService.cancelled() })
        lastWallNs.set(System.nanoTime() - started)
        android.util.Log.i("GpuDiagnostic", "blurNs=${times.elapsedNs} uploadNs=${times.uploadNs} drawNs=${times.drawNs} readbackNs=${times.readbackNs} passBytes=${times.largestPassBytes} renderer=${times.renderer}")
        return LinearSrgbImage.takeOwnership(image.width, image.height, output)
    }
}