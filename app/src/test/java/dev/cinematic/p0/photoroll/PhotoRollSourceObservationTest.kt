package dev.cinematic.p0.photoroll

import dev.cinematic.p0.rawcapture.DngContentChanged
import dev.cinematic.p0.rawcapture.DngContentVerifier
import dev.cinematic.p0.rawcapture.DngIoResources
import dev.cinematic.p0.rawcapture.OriginalGrant
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class PhotoRollSourceObservationTest {
    private val baseline = PhotoRollSourceObservation(false, 3, 100, OriginalGrant.APP_OWNED)

    @Test fun identicalMetadataIsOnlyAPreviewHint() {
        baseline.requireMatches(baseline.copy())
        val fingerprint = DngContentVerifier.identify(3, { ByteArrayInputStream("abc".toByteArray()) })
        baseline.requireMatches(baseline.copy()) // A same-size/same-time replacement passes metadata.
        assertThrows(DngContentChanged::class.java) {
            DngContentVerifier.verify(fingerprint, { ByteArrayInputStream("abd".toByteArray()) })
        }
    }

    @Test fun changedSizeTimeGrantOriginOrMissingTimeFails() {
        for (changed in listOf(baseline.copy(bytes = 4), baseline.copy(modified = 101),
            baseline.copy(modified = null), baseline.copy(fromFiles = true),
            baseline.copy(grant = OriginalGrant.USER_SELECTED))) {
            assertThrows(DngContentChanged::class.java) { baseline.requireMatches(changed) }
        }
    }

    @Test fun unknownTimestampIsExplicitAndNeverRebased() {
        val unknown = baseline.copy(modified = null)
        unknown.requireMatches(unknown.copy())
        assertThrows(DngContentChanged::class.java) { unknown.requireMatches(baseline) }
        assertNull(unknown.modified)
    }

    @Test fun malformedObservationsCannotBecomeABaseline() {
        for (bytes in listOf(0L, -1L)) assertThrows(IllegalArgumentException::class.java) { baseline.copy(bytes = bytes) }
        for (time in listOf(0L, -1L)) assertThrows(IllegalArgumentException::class.java) { baseline.copy(modified = time) }
    }

    @Test fun failedMetadataOwnerCloseStaysOwnedUntilRetry() {
        val io = DngIoResources()
        var fail = true
        val cursor = Closeable { if (fail) throw IOException("synthetic close") }
        assertThrows(IOException::class.java) { io.useOwned(cursor) { 7 } }
        assertTrue(io.hasOpenResources)
        assertNotNull(io.releaseAll())
        fail = false
        assertNull(io.releaseAll())
        assertFalse(io.hasOpenResources)
    }
}
