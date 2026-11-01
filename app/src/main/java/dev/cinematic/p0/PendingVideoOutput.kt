package dev.cinematic.p0

import android.content.ContentValues
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.util.UUID

/** Owns one newly inserted pending row and its descriptor, never existing media. */
@RequiresApi(29)
class PendingVideoOutput private constructor(
    private val context: Context,
    val uri: Uri,
    val descriptor: ParcelFileDescriptor,
) : VideoSink {
    private var closed = false

    override fun close() {
        if (!closed) { descriptor.close(); closed = true }
    }

    override fun validate(): VideoFileDetails {
        val resolver = context.contentResolver
        val bytes = resolver.openFileDescriptor(uri, "r")?.use { it.statSize }
            ?: error("Cannot read recorded MP4")
        val retriever = MediaMetadataRetriever()
        val width: Int; val height: Int; val rotation: Int; val duration: Long
        try {
            retriever.setDataSource(context, uri)
            check(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) == "video/mp4") {
                "Recorder output is not an MP4"
            }
            width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
        } finally { retriever.release() }
        val extractor = MediaExtractor()
        var count = 0; var first = -1L; var last = -1L
        var codec: String? = null; var color = "unknown"; var hasAudio = false
        try {
            extractor.setDataSource(context, uri, null)
            var videoTrack = -1
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) hasAudio = true
                if (mime.startsWith("video/") && videoTrack == -1) {
                    videoTrack = index; codec = mime
                    fun value(key: String) = if (format.containsKey(key)) format.getInteger(key).toString() else "unknown"
                    color = "standard=${value(MediaFormat.KEY_COLOR_STANDARD)} transfer=${value(MediaFormat.KEY_COLOR_TRANSFER)} range=${value(MediaFormat.KEY_COLOR_RANGE)}"
                }
            }
            check(videoTrack >= 0) { "MP4 has no video track" }
            extractor.selectTrack(videoTrack)
            while (count < 30000 && extractor.sampleTime >= 0) {
                val time = extractor.sampleTime
                if (first < 0) first = time
                last = time; count++
                if (!extractor.advance()) break
            }
        } finally { extractor.release() }
        val fps = if (count > 1 && last > first) (count - 1) * 1_000_000.0 / (last - first) else null
        return VideoFileDetails(bytes, width, height, rotation, duration, count, fps, codec, color, hasAudio)
    }

    override fun publish() {
        check(context.contentResolver.update(uri, ContentValues().apply {
            put(MediaStore.Video.Media.IS_PENDING, 0)
        }, null, null) == 1) { "Cannot publish completed MP4" }
    }

    override fun delete() {
        check(context.contentResolver.delete(uri, null, null) == 1) { "Cannot remove failed recording row" }
    }

    companion object {
        const val RELATIVE_PATH = "Movies/Cinematic Camera/"
        fun create(context: Context): PendingVideoOutput {
            val app = context.applicationContext
            val resolver = app.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, "${UUID.randomUUID()}.mp4")
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, RELATIVE_PATH)
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }) ?: error("Cannot create pending MP4")
            try {
                val descriptor = resolver.openFileDescriptor(uri, "rw") ?: error("Cannot open MP4 descriptor")
                return PendingVideoOutput(app, uri, descriptor)
            } catch (failure: Exception) {
                try { check(resolver.delete(uri, null, null) == 1) { "Failed row cleanup" } }
                catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
                throw failure
            }
        }
    }
}
