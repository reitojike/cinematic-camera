package dev.cinematic.p0.photoroll

import dev.cinematic.p0.jpegrender.original.Original24PreflightGeometry
import dev.cinematic.p0.rawcapture.*
import org.junit.Assert.*
import org.junit.Test

class InitialPreviewPolicyTest {
    private val source = SharedOriginalReference("content://synthetic/1", OriginalFingerprint(3, "a".repeat(64)), OriginalGrant.USER_SELECTED)
    private val identity = InitialPreviewIdentity(source, Original24PreflightGeometry(10, 8, 10, 8, 1, 10, 8), "recipe-v1:all-values")
    @Test fun candidatePriorityIsExplicitAndStable() {
        assertEquals(listOf(InitialPreviewKind.SAVED_CACHE, InitialPreviewKind.SAVED_JPEG,
            InitialPreviewKind.EMBEDDED_JPEG, InitialPreviewKind.HALF_GUIDE), InitialPreviewPolicy.priority)
    }
    @Test fun savedImagesRequireCompleteMatchingRecipeAndOrientationEvidence() {
        for (kind in listOf(InitialPreviewKind.SAVED_CACHE, InitialPreviewKind.SAVED_JPEG)) {
            assertTrue(InitialPreviewPolicy.eligible(identity, identity, kind, true, true))
            assertFalse(InitialPreviewPolicy.eligible(identity, identity.copy(canonicalRecipe = "other"), kind, true, true))
            assertFalse(InitialPreviewPolicy.eligible(identity, identity, kind, false, true))
            assertFalse(InitialPreviewPolicy.eligible(identity, identity, kind, true, false))
        }
    }
    @Test fun allCandidatesBindToFullSourceIdentityAndAdmittedGeometry() {
        val others = listOf(identity.copy(source = source.copy(uri = "content://synthetic/2")),
            identity.copy(source = source.copy(fingerprint = OriginalFingerprint(3, "b".repeat(64)))),
            identity.copy(source = source.copy(fingerprint = OriginalFingerprint(4, "a".repeat(64)))),
            identity.copy(source = source.copy(grant = OriginalGrant.APP_OWNED)),
            identity.copy(geometry = Original24PreflightGeometry(10, 8, 8, 8, 1, 8, 8, 2, 0)))
        for (kind in InitialPreviewKind.entries) for (other in others)
            assertFalse(InitialPreviewPolicy.eligible(identity, other, kind, true, true))
    }
    @Test fun cameraGuidesAreNotRepresentedAsEditedResults() {
        for (kind in listOf(InitialPreviewKind.EMBEDDED_JPEG, InitialPreviewKind.HALF_GUIDE)) {
            assertFalse(kind.edited)
            assertTrue(InitialPreviewPolicy.eligible(identity, identity.copy(canonicalRecipe = "unapplied"), kind, false, true))
            assertFalse(InitialPreviewPolicy.eligible(identity, identity, kind, false, false))
        }
    }
    @Test fun exactFrameWinsEvenWhenLateGuideHasTheSameIdentity() {
        val gate = InitialPreviewGate(); val ticket = gate.begin(identity)
        assertTrue(gate.accept(ticket, InitialPreviewKind.EMBEDDED_JPEG))
        gate.exact(ticket)
        for (kind in InitialPreviewKind.entries) assertFalse(gate.accept(ticket, kind))
    }
    @Test fun aLateLowerPriorityResultNeverReplacesTheBetterImage() {
        val gate = InitialPreviewGate(); val ticket = gate.begin(identity)
        assertTrue(gate.accept(ticket, InitialPreviewKind.HALF_GUIDE))
        assertTrue(gate.accept(ticket, InitialPreviewKind.SAVED_JPEG))
        assertFalse(gate.accept(ticket, InitialPreviewKind.EMBEDDED_JPEG))
        assertFalse(gate.accept(ticket, InitialPreviewKind.SAVED_JPEG))
        assertTrue(gate.accept(ticket, InitialPreviewKind.SAVED_CACHE))
    }
    @Test fun retrySelectionLifecycleAndForeignOwnerFenceLateResults() {
        val gate = InitialPreviewGate(); val old = gate.begin(identity)
        val next = gate.begin(identity)
        assertFalse(gate.accept(old, InitialPreviewKind.SAVED_CACHE))
        gate.exact(old)
        assertTrue(gate.accept(next, InitialPreviewKind.SAVED_CACHE))
        gate.invalidate()
        assertFalse(gate.accept(next, InitialPreviewKind.SAVED_CACHE))
        val another = InitialPreviewGate(); another.begin(identity)
        assertFalse(another.accept(next, InitialPreviewKind.SAVED_CACHE))
    }
}
