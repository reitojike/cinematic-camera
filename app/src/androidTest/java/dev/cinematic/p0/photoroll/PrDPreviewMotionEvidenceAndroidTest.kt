package dev.cinematic.p0.photoroll

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Test
import org.junit.Assume.assumeTrue

/** Decode an explicitly supplied, local synthetic-screen recording; never reads user media. */
class PrDPreviewMotionEvidenceAndroidTest {
    @Test fun extractSyntheticScreenRecordingFrames() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Manual synthetic recording only", args.getString("motionEvidence") == "true")
        check(args.getString("isolated") == "true")
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = File(context.filesDir, "prd-motion.mp4")
        val output = File(context.filesDir, "prd-motion-frames"); check(output.isDirectory || output.mkdirs())
        check(output.listFiles().isNullOrEmpty()) { "Use a fresh empty evidence directory" }
        MediaMetadataRetriever().use { reader ->
            reader.setDataSource(input.path)
            val count = requireNotNull(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)).toInt()
            val duration = requireNotNull(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)).toLong()
            File(output, "metadata.txt").writeText("frameCount=$count durationMs=$duration synthetic screen only\n")
            for (index in 0 until count) {
                val bitmap = requireNotNull(reader.getFrameAtIndex(index))
                try { File(output, "frame-%04d.png".format(index)).outputStream().use {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                } } finally { bitmap.recycle() }
            }
        }
    }
}
