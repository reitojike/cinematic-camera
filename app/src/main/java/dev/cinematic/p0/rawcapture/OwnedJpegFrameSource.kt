package dev.cinematic.p0.rawcapture

import dev.cinematic.p0.jpegrender.JpegFrameSource
import java.io.Closeable

/** A resource-owning frame source is mandatory. AndroidAsShotJpegFrames alone does not acknowledge
 * failed native FD closes, so it cannot be silently wrapped as a successfully closed source here. */
internal interface OwnedJpegFrameSource : JpegFrameSource, Closeable {
    fun requestStop() {}
}
