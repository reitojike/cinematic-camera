package dev.cinematic.p0.rawcapture

import dev.cinematic.p0.jpegrender.*
import dev.cinematic.p0.raw.LinearSrgbImage
import org.junit.Assert.*
import org.junit.After
import org.junit.Test

class PrivateTrialReuseTest {
    private val source = SharedOriginalReference("content://example.test/synthetic/1",
        OriginalFingerprint(16, "a".repeat(64)), OriginalGrant.USER_SELECTED)
    private fun request(strength: Double = .5, generation: Long = 1, original: SharedOriginalReference = source) =
        AutoDevelopRequest("synthetic_reuse", generation, original, JpegGenerationRecipe.trial(strength).snapshot,
            JpegGenerationRecipe.TRIAL_SCHEMA, 1024, 1024, AutoRenderQuality.PRIVATE_TRIAL_1024,
            RenderedFaceState.NOT_RUN, RenderedExposureState.DIAGNOSTIC, AutoInputProfile.UNKNOWN)
    private fun jpeg(request: AutoDevelopRequest) = JpegGenerationRequest(request.captureId, request.generation,
        request.source, requireNotNull(JpegGenerationRecipe.fromTrialSnapshot(request.recipe)))
    private fun foundation(width: Int = 4, height: Int = 3, sourceWidth: Int = 8, sourceHeight: Int = 6,
        oriented: Boolean = true, ev: Double = .25, geometry: String = TrialPreparedFoundation.GEOMETRY_VERSION) =
        TrialPreparedFoundation(LinearSrgbImage.takeOwnership(width, height, FloatArray(width * height * 3) { .2f }),
            sourceWidth, sourceHeight, oriented, ev, geometry)

    @After fun clearGlobalOwner() { PrivateTrialReuseRegistry.invalidateAll() }

    @Test fun strengthAndGenerationChangeReuseOneFoundationButInvalidateEveryOldBinding() {
        val session = PrivateTrialReuseSession()
        val first = request()
        val old = requireNotNull(session.bind(first))
        val base = foundation()
        assertNull(old.load(jpeg(first)))
        old.commit(jpeg(first), base)
        old.commitComparison(4, 3, IntArray(12) { it })
        val next = request(.75, 2)
        val current = requireNotNull(session.bind(next))
        assertSame(base, current.load(jpeg(next)))
        assertArrayEquals(IntArray(12) { it }, current.comparisonCopy(4, 3))
        assertNull(old.load(jpeg(first)))
        assertNull(old.comparisonCopy(4, 3))
        old.commit(jpeg(first), foundation())
        old.commitComparison(4, 3, IntArray(12) { 99 })
        old.invalidate() // Old failed/cancelled job cannot clear the newly bound foundation.
        assertSame(base, current.load(jpeg(next)))
        assertArrayEquals(IntArray(12) { it }, current.comparisonCopy(4, 3))
        assertNull(current.load(jpeg(next).copy(generation = 3)))
        assertNull(current.load(jpeg(first)))
    }

    @Test fun changedUriHashBytesOrGrantCannotLoadOrCommitThePreviousSource() {
        for (other in listOf(source.copy(uri = "content://example.test/synthetic/2"),
            source.copy(fingerprint = source.fingerprint.copy(sha256 = "b".repeat(64))),
            source.copy(fingerprint = source.fingerprint.copy(bytes = 17)), source.copy(grant = OriginalGrant.APP_OWNED))) {
            val session = PrivateTrialReuseSession()
            val first = request(); val old = requireNotNull(session.bind(first))
            old.commit(jpeg(first), foundation())
            val next = request(.75, 2, other); val current = requireNotNull(session.bind(next))
            assertNull(current.load(jpeg(next)))
            assertNull(current.load(jpeg(next).copy(source = source)))
            old.commit(jpeg(first), foundation())
            assertNull(current.load(jpeg(next)))
        }
    }

    @Test fun unknownPolicyInputEdgeAndNonPrivateRequestsAreNeverAdmitted() {
        val valid = request()
        for (invalid in listOf(
            valid.copy(recipe = valid.recipe.copy(payload = valid.recipe.payload.replace("libraw-as-shot-bounded-v1", "unknown-input-v1"))),
            valid.copy(recipe = valid.recipe.copy(payload = valid.recipe.payload.replace("quality=95", "quality=96"))),
            valid.copy(recipe = valid.recipe.copy(payload = valid.recipe.payload + ";unknown=value")),
            valid.copy(recipe = valid.recipe.copy(payload = valid.recipe.payload.replace("edge=1024", "edge=512"))),
            valid.copy(recipe = valid.recipe.copy(schema = JpegGenerationRecipe.SCHEMA)),
            valid.copy(width = 512), valid.copy(face = RenderedFaceState.VERIFIED_NONE),
            valid.copy(exposure = RenderedExposureState.SUPPORTED), valid.copy(profile = AutoInputProfile.SUPPORTED),
            valid.copy(quality = AutoRenderQuality.MANUAL_BOUNDED_1024_PRELIMINARY),
        )) {
            val session = PrivateTrialReuseSession()
            val lease = requireNotNull(session.bind(valid)); lease.commit(jpeg(valid), foundation())
            assertNull(session.bind(invalid))
            assertNull(lease.load(jpeg(valid)))
            assertNull(requireNotNull(session.bind(valid)).load(jpeg(valid)))
        }
    }

    @Test fun invalidFoundationMetadataAndUnknownGeometryAreNotStored() {
        val request = request()
        for (bad in listOf(foundation(sourceWidth = 0), foundation(sourceHeight = 0), foundation(oriented = false),
            foundation(ev = Double.NaN), foundation(ev = Double.POSITIVE_INFINITY), foundation(geometry = "future"),
            foundation(sourceWidth = 2, sourceHeight = 2), foundation(width = 1025, height = 1, sourceWidth = 4000))) {
            val lease = requireNotNull(PrivateTrialReuseSession().bind(request))
            lease.commit(jpeg(request), bad)
            assertNull(lease.load(jpeg(request)))
            lease.commitComparison(4, 3, IntArray(12))
            assertNull(lease.comparisonCopy(4, 3))
        }
    }

    @Test fun exactSixteenMiBPixelBoundAndComparisonGeometryAreEnforcedWithoutCopyAliasing() {
        val selected = request()
        val lease = requireNotNull(PrivateTrialReuseSession().bind(selected))
        val base = foundation(1024, 1024, 4000, 4000)
        assertEquals(12L * 1024 * 1024, base.pixelBytes)
        assertEquals(16L * 1024 * 1024, PrivateTrialReuseSession.MAX_PIXEL_BYTES)
        lease.commit(jpeg(selected), base)
        lease.commitComparison(1024, 1024, IntArray(1024 * 1024) { 7 })
        val copy = requireNotNull(lease.comparisonCopy(1024, 1024))
        assertEquals(PrivateTrialReuseSession.MAX_PIXEL_BYTES, base.pixelBytes + copy.size * 4L)
        copy[0] = 99
        assertEquals(7, requireNotNull(lease.comparisonCopy(1024, 1024))[0])
        lease.commitComparison(1024, 1024, IntArray(1024 * 1024 + 1) { 33 })
        lease.commitComparison(1025, 1024, IntArray(1))
        assertEquals(7, requireNotNull(lease.comparisonCopy(1024, 1024))[0])
        assertNull(lease.comparisonCopy(1024, 1023))
        assertNull(lease.comparisonCopy(Int.MAX_VALUE, Int.MAX_VALUE))
    }

    @Test fun replacingTheSingleFoundationDropsItsOldComparisonInsteadOfAccumulatingGenerations() {
        val session = PrivateTrialReuseSession()
        var previous: PrivateTrialReuseLease? = null
        for (generation in 1L..20L) {
            val selected = request(if (generation % 2 == 0L) 1.0 else 0.0, generation)
            val lease = requireNotNull(session.bind(selected))
            val base = foundation(ev = generation.toDouble())
            lease.commit(jpeg(selected), base)
            assertSame(base, lease.load(jpeg(selected)))
            assertNull(lease.comparisonCopy(4, 3))
            lease.commitComparison(4, 3, IntArray(12) { generation.toInt() })
            assertEquals(generation.toInt(), requireNotNull(lease.comparisonCopy(4, 3))[0])
            previous?.invalidate()
            assertSame(base, lease.load(jpeg(selected)))
            previous = lease
        }
    }

    @Test fun clearCloseAndGlobalCaptureOrPauseInvalidationRefuseAllOldCapabilities() {
        val selected = request()
        val session = PrivateTrialReuseRegistry.openSession()
        val lease = requireNotNull(PrivateTrialReuseRegistry.bind(session, selected))
        lease.commit(jpeg(selected), foundation())
        assertNotNull(PrivateTrialReuseRegistry.forRequest(selected))
        session.clear()
        assertFalse(session.closed)
        assertNull(lease.load(jpeg(selected))); assertNull(PrivateTrialReuseRegistry.forRequest(selected))
        val afterClear = requireNotNull(PrivateTrialReuseRegistry.bind(session, selected))
        assertNull(afterClear.load(jpeg(selected)))
        afterClear.commit(jpeg(selected), foundation())
        PrivateTrialReuseRegistry.invalidateAll() // Same entry point used for pause/capture/memory pressure.
        assertTrue(session.closed)
        assertNull(afterClear.load(jpeg(selected)))
        afterClear.commit(jpeg(selected), foundation())
        assertNull(session.bind(selected))
        assertNull(PrivateTrialReuseRegistry.forRequest(selected))
        val nextSession = PrivateTrialReuseRegistry.openSession()
        val next = requireNotNull(PrivateTrialReuseRegistry.bind(nextSession, selected))
        assertNull(next.load(jpeg(selected)))
        PrivateTrialReuseRegistry.openSession()
        assertTrue(nextSession.closed)
        assertNull(next.load(jpeg(selected)))
    }

    @Test fun legacyNullBindingUnbindsWithoutClosingOrExposingRetainedData() {
        val selected = request()
        val session = PrivateTrialReuseRegistry.openSession()
        val old = requireNotNull(PrivateTrialReuseRegistry.bind(session, selected))
        val base = foundation(); old.commit(jpeg(selected), base)
        assertNull(PrivateTrialReuseRegistry.bind(null, selected))
        assertFalse(session.closed)
        assertNull(PrivateTrialReuseRegistry.forRequest(selected))
        assertNull(old.load(jpeg(selected)))
        old.invalidate()
        val current = requireNotNull(PrivateTrialReuseRegistry.bind(session, selected))
        assertSame(base, current.load(jpeg(selected)))
        assertNull(PrivateTrialReuseRegistry.forRequest(selected.copy(generation = 2)))
    }
}
