package dev.cinematic.p0

import org.junit.Assert.*
import org.junit.Test

class VideoPublicationTest {
    private class Fake : VideoSink {
        val calls = mutableListOf<String>()
        var closeFails = false; var validationFails = false; var publishFails = false; var deleteFails = false
        var details = VideoFileDetails(1000, 3840, 2160, 90, 1000, 60, 59.0, "video/avc", "unknown", true)
        override fun close() { calls += "close"; if (closeFails) error("descriptor close") }
        override fun validate(): VideoFileDetails { calls += "validate"; if (validationFails) error("unreadable output"); return details }
        override fun publish() { calls += "publish"; if (publishFails) error("pending update") }
        override fun delete() { calls += "delete"; if (deleteFails) error("row delete") }
    }

    @Test fun onlyClosedValidatedOutputIsPublished() {
        val sink = Fake()
        assertEquals(sink.details, VideoPublication.finish(sink))
        assertEquals(listOf("close", "validate", "publish"), sink.calls)
    }

    @Test fun recorderErrorClosesAndDeletesWithoutPublishingEvenIfFileMightHaveBytes() {
        val sink = Fake()
        val failure = assertThrows(VideoPublicationException::class.java) { VideoPublication.finish(sink, "source inactive") }
        assertTrue(failure.message!!.contains("source inactive"))
        assertEquals(listOf("close", "delete"), sink.calls)
    }

    @Test fun validationOrEmptyMetadataCannotBecomeGallerySuccess() {
        for (sink in listOf(Fake().apply { validationFails = true }, Fake().apply { details = details.copy(bytes = 0) },
            Fake().apply { details = details.copy(width = 0) }, Fake().apply { details = details.copy(durationMs = 0) },
            Fake().apply { details = details.copy(sampledFrames = 0) })) {
            assertThrows(VideoPublicationException::class.java) { VideoPublication.finish(sink) }
            assertEquals(listOf("close", "validate", "delete"), sink.calls)
        }
    }

    @Test fun failedPublicationRollsBackOnlyItsOwnSink() {
        val sink = Fake().apply { publishFails = true }
        assertThrows(VideoPublicationException::class.java) { VideoPublication.finish(sink) }
        assertEquals(listOf("close", "validate", "publish", "delete"), sink.calls)
    }

    @Test fun closeAndDeleteFailuresAreReportedAndDeletionStillAttempted() {
        val sink = Fake().apply { closeFails = true; deleteFails = true }
        val failure = assertThrows(VideoPublicationException::class.java) { VideoPublication.finish(sink) }
        assertEquals(listOf("close", "close", "delete"), sink.calls)
        assertEquals(2, failure.cleanupFailures)
        assertTrue(failure.cause!!.message!!.contains("descriptor close"))
    }
}
