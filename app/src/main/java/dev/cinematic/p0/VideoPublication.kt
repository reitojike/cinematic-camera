package dev.cinematic.p0

data class VideoFileDetails(
    val bytes: Long,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val durationMs: Long,
    val sampledFrames: Int,
    val sampleFps: Double?,
    val codec: String?,
    val color: String,
    val hasAudio: Boolean,
)

interface VideoSink {
    fun close()
    fun validate(): VideoFileDetails
    fun publish()
    fun delete()
}

class VideoPublicationException(message: String, cause: Exception, val cleanupFailures: Int) :
    Exception(message, cause)

/** Recorder completion is distinct from a valid, closed, published MP4. */
object VideoPublication {
    fun finish(sink: VideoSink, recorderError: String? = null): VideoFileDetails {
        var closed = false
        try {
            sink.close(); closed = true
            check(recorderError == null) { recorderError!! }
            val details = sink.validate()
            check(details.bytes > 0 && details.width > 0 && details.height > 0 && details.durationMs > 0 && details.sampledFrames > 0) {
                "Empty or invalid MP4 metadata"
            }
            sink.publish()
            return details
        } catch (failure: Exception) {
            var cleanupFailures = 0
            if (!closed) try { sink.close() } catch (_: Exception) { cleanupFailures++ }
            try { sink.delete() } catch (_: Exception) { cleanupFailures++ }
            throw VideoPublicationException(
                "${failure.message}; cleanupFailures=$cleanupFailures", failure, cleanupFailures)
        }
    }
}
