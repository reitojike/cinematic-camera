package dev.cinematic.p0.photoroll

import dev.cinematic.p0.raw.PhotoAdjustments
import dev.cinematic.p0.rawcapture.OriginalFingerprint
import dev.cinematic.p0.rawcapture.OriginalGrant
import dev.cinematic.p0.rawcapture.SharedOriginalReference
import org.junit.Assert.*
import org.junit.Test

class PhotoRollPreviewRetentionTest {
    private val source = SharedOriginalReference("content://example.test/photo/1", OriginalFingerprint(3, "a".repeat(64)), OriginalGrant.USER_SELECTED)
    private val key = PhotoRollPreviewKey(source, .5, PhotoAdjustments.NONE, "stage-clear-v1", 1024, PhotoRollPreviewMode.MANUAL_TRIAL)

    @Test fun returningWithTheSameVerifiedIdentityRetainsOnlyThatTicket() {
        val owner = PhotoRollPreviewRetention()
        val first = owner.begin(key)
        assertEquals(first, owner.begin(key))
        assertTrue(owner.accepts(first))
    }

    @Test fun everyInputAndRenderingCoordinateInvalidatesOldPixels() {
        for (changed in listOf(key.copy(strength = .6), key.copy(adjustments = PhotoAdjustments(1.0, 0.0)),
            key.copy(adjustments = PhotoAdjustments(0.0, .1)), key.copy(lookVersion = "future"), key.copy(maxEdge = 512),
            key.copy(mode = PhotoRollPreviewMode.STAGE_CLEAR),
            key.copy(source = source.copy(uri = "content://example.test/photo/2")),
            key.copy(source = source.copy(fingerprint = OriginalFingerprint(3, "b".repeat(64)))),
            key.copy(source = source.copy(fingerprint = OriginalFingerprint(4, "a".repeat(64)))),
            key.copy(source = source.copy(grant = OriginalGrant.APP_OWNED)))) {
            val owner = PhotoRollPreviewRetention()
            val old = owner.begin(key)
            val current = owner.begin(changed)
            assertFalse(owner.accepts(old))
            assertTrue(owner.accepts(current))
        }
    }

    @Test fun lowMemoryOrExplicitInvalidationRejectsLateWritesEvenForSameKey() {
        val owner = PhotoRollPreviewRetention()
        val old = owner.begin(key)
        owner.clear()
        assertFalse(owner.accepts(old))
        val replacement = owner.begin(key)
        assertNotEquals(old, replacement)
        assertFalse(owner.accepts(old))
        assertTrue(owner.accepts(replacement))
    }

    @Test fun processDeathHasNoOldPixelAuthority() {
        val old = PhotoRollPreviewRetention().begin(key)
        val replacement = PhotoRollPreviewRetention()
        replacement.begin(key)
        assertFalse(replacement.accepts(old))
    }
}
