package dev.cinematic.p0.rawcapture

import android.os.CancellationSignal
import android.os.Looper
import dev.cinematic.p0.jpegrender.AndroidAsShotJpegFrames
import dev.cinematic.p0.jpegrender.JpegFrame
import dev.cinematic.p0.raw.LinearSrgbImage
import dev.cinematic.p0.raw.NativeDngDecoder
import java.io.IOException
import java.util.concurrent.CancellationException

/** The same existing native as-shot decode, with the source FD retained by the queue backend.
 * Caller already owns DevelopExecution's slot; never use this beside an independent preview worker.
 * No W1/Pixel profile, face/exposure claim, or implicit production registration. */
internal class AndroidOwnedJpegFrames(private val io: AutoRenderIo,
    private val expected: SharedOriginalReference) : OwnedJpegFrameSource {
    override val contract = AndroidAsShotJpegFrames.CONTRACT
    private val cancellation = CancellationSignal()
    private var descriptor: AutoReadOnlyDescriptor? = null
    private var started = false
    override fun load(source: SharedOriginalReference, longEdge: Int, shouldStop: () -> Boolean): JpegFrame {
        check(Looper.myLooper() != Looper.getMainLooper())
        check(!started); started = true
        require(source == expected && longEdge == 1024)
        checkpoint(shouldStop)
        if (!NativeDngDecoder.available) throw IOException("DNG decoder unavailable")
        val owner = io.openOriginalDescriptor().also { descriptor = it }
        var failure: Throwable? = null
        val decoded = try { NativeDngDecoder.decode(owner.fd, longEdge, cancellation, 0) }
        catch (t: Throwable) { failure = t; throw t }
        finally {
            try { close() } catch (cleanup: Throwable) {
                if (failure == null) throw cleanup
                if (cleanup !== failure) failure.addSuppressed(cleanup)
            }
        }
        checkpoint(shouldStop)
        return JpegFrame(LinearSrgbImage(decoded.width, decoded.height, decoded.pixels),
            decoded.sourceWidth, decoded.sourceHeight, true)
    }
    private fun checkpoint(stop: () -> Boolean) {
        if (cancellation.isCanceled || stop()) throw CancellationException("Owned DNG decode stopped")
    }
    override fun requestStop() = cancellation.cancel()
    override fun close() { descriptor?.close(); descriptor = null }
}
