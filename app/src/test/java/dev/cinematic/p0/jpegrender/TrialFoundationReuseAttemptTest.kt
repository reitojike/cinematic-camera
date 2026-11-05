package dev.cinematic.p0.jpegrender

import dev.cinematic.p0.raw.*
import dev.cinematic.p0.rawcapture.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

/** Real R7/Look, synthetic frame/codec/inspector. These tests make no Android/native/quality claim. */
class TrialFoundationReuseAttemptTest {
    private val original = "synthetic read-only original".toByteArray()
    private val source = SharedOriginalReference("content://example.test/synthetic/1",
        DngContentVerifier.identify(original.size.toLong(), { original.inputStream() }), OriginalGrant.USER_SELECTED)
    private fun image() = LinearSrgbImage(4, 4, FloatArray(48) { channel ->
        if (channel / 3 == 5) 1.3f else (.02 + (channel % 11) * .045).toFloat()
    })
    private fun auto(strength: Double, generation: Long, adjustments: PhotoAdjustments = PhotoAdjustments()) = AutoDevelopRequest("synthetic_reuse", generation,
        source, JpegGenerationRecipe.trial(strength, adjustments).snapshot, JpegGenerationRecipe.TRIAL_SCHEMA, 1024, 1024,
        AutoRenderQuality.PRIVATE_TRIAL_1024, RenderedFaceState.NOT_RUN, RenderedExposureState.DIAGNOSTIC, AutoInputProfile.UNKNOWN)
    private fun jpeg(request: AutoDevelopRequest) = JpegGenerationRequest(request.captureId, request.generation,
        request.source, requireNotNull(JpegGenerationRecipe.fromTrialSnapshot(request.recipe)))

    private inner class Fixture {
        val session = PrivateTrialReuseSession()
        var frameLoads = 0
        var controlsCalls = 0
        var codecSuccess = true
        var corruptRead = false
        var changedOriginal = false
        var failOutputClose = false
        var afterEncode: () -> Unit = {}
        var afterPostHashClose: () -> Unit = {}
        val pixels = mutableListOf<FloatArray>()

        inner class Run(strength: Double, generation: Long, useReuse: Boolean = true, adjustments: PhotoAdjustments = PhotoAdjustments()) {
            val definition = auto(strength, generation, adjustments)
            val request = jpeg(definition)
            val lease = if (useReuse) requireNotNull(session.bind(definition)) else null
            var commits = 0
            var invalidates = 0
            var sourceOpens = 0
            var outputClosed = false
            var readbackClosed = false
            val output = ByteArrayOutputStream()
            val probe = lease?.let { lease -> object : TrialFoundationReuse {
                override fun load(request: JpegGenerationRequest) = lease.load(request)
                override fun commit(request: JpegGenerationRequest, foundation: TrialPreparedFoundation) {
                    assertTrue(outputClosed); assertTrue(readbackClosed)
                    assertEquals(2, sourceOpens)
                    commits++; lease.commit(request, foundation)
                }
                override fun invalidate() { invalidates++; lease.invalidate() }
            } }
            val attempt = JpegGenerationAttempt(request, object : PrivateJpegTarget {
                override val captureId = request.captureId
                override val generation = request.generation
                override fun createOutput() = object : ByteArrayOutputStream() {
                    override fun write(bytes: ByteArray, offset: Int, length: Int) = output.write(bytes, offset, length)
                    override fun close() {
                        if (failOutputClose) throw IOException("Synthetic output close failure")
                        outputClosed = true
                    }
                }
                override fun openRead() = object : ByteArrayInputStream(output.toByteArray()) {
                    override fun close() { readbackClosed = true; super.close() }
                }
            }, object : JpegFrameSource {
                override val contract = AndroidAsShotJpegFrames.CONTRACT
                override fun load(source: SharedOriginalReference, longEdge: Int, shouldStop: () -> Boolean): JpegFrame {
                    frameLoads++
                    return JpegFrame(image(), 8, 8, true)
                }
            }, object : JpegControlsProvider {
                override val contract = AsShotNoFaceTrialControls.contract
                override fun prepare(image: LinearSrgbImage, policy: RenderRecipeSnapshot, shouldStop: () -> Boolean): JpegControls {
                    controlsCalls++
                    return AsShotNoFaceTrialControls.prepare(image, policy, shouldStop)
                }
            }, {
                sourceOpens++
                val bytes = if (changedOriginal && sourceOpens == 2) "changed synthetic original".toByteArray() else original
                object : ByteArrayInputStream(bytes) {
                    override fun close() { super.close(); if (sourceOpens == 2) afterPostHashClose() }
                }
            }, LinearJpegEncoder { rendered, _, stream, _ ->
                val values = rendered.copyPixels(); pixels += values
                val data = DataOutputStream(stream); values.forEach(data::writeFloat)
                afterEncode()
                codecSuccess
            }, JpegInspector { open, stop ->
                val fp = DngContentVerifier.identify(192, open, stop)
                JpegInspection(JpegOutputDescription(if (corruptRead) 3 else 4, 4, 1, "sRGB", fp.bytes), fp)
            }, probe)
        }

        fun run(strength: Double = .5, generation: Long = 1, useReuse: Boolean = true,
            adjustments: PhotoAdjustments = PhotoAdjustments()) = Run(strength, generation, useReuse, adjustments)
        fun expected(strength: Double): FloatArray {
            val input = image()
            val prepared = AsShotNoFaceTrialControls.prepare(input, AsShotNoFaceTrialControls.POLICY) { false }
            return StageClearLook.renderFromBase(R7Base.render(input, prepared.foundationRoi, prepared.externalEv),
                prepared.faces, strength).image.copyPixels()
        }
    }

    @Test fun warmthAndExposureChangeRealEncodedPixelsAndInvalidateOnlyTheirFoundation() {
        val f = Fixture()
        f.run().attempt.renderTrial { false }
        val originalPixels = f.pixels.last()
        val edited = PhotoAdjustments(45.0, .7)
        val warmed = f.run(generation = 2, adjustments = edited)
        warmed.attempt.renderTrial { false }
        assertFalse(originalPixels.contentEquals(f.pixels.last()))
        val editedPixels = f.pixels.last()
        assertEquals(2, f.frameLoads)
        f.run(generation = 3, adjustments = edited).attempt.renderTrial { false }
        assertArrayEquals(editedPixels, f.pixels.last(), 0f)
        assertEquals(2, f.frameLoads)
        val uncached = Fixture()
        uncached.run(adjustments = edited, useReuse = false).attempt.renderTrial { false }
        assertArrayEquals(editedPixels, uncached.pixels.last(), 0f)
        f.run(generation = 4, adjustments = edited.copy(exposureEv = .8)).attempt.renderTrial { false }
        assertEquals(3, f.frameLoads)
        assertFalse(editedPixels.contentEquals(f.pixels.last()))
        f.run(generation = 5).attempt.renderTrial { false }
        assertEquals(4, f.frameLoads)
        assertArrayEquals(originalPixels, f.pixels.last(), 0f)
    }

    @Test fun warmthAloneAndEvAloneEachReachTheEncoder() {
        val f = Fixture()
        f.run().attempt.renderTrial { false }
        val neutral = f.pixels.last()
        f.run(generation = 2, adjustments = PhotoAdjustments(warmth = 50.0)).attempt.renderTrial { false }
        assertFalse(neutral.contentEquals(f.pixels.last()))
        f.run(generation = 3, adjustments = PhotoAdjustments(exposureEv = 1.0)).attempt.renderTrial { false }
        assertFalse(neutral.contentEquals(f.pixels.last()))
        assertEquals(3, f.frameLoads)
    }

    @Test fun missThenStrengthHitsHaveExactlyTheUncachedPixelsAndBothOriginalHashes() {
        val f = Fixture()
        for ((index, strength) in listOf(.5, .75, .25, 0.0, 1.0).withIndex()) {
            val run = f.run(strength, index + 1L)
            val generated = run.attempt.renderTrial { false }
            assertEquals(run.request.recipe.snapshot, generated.request.recipe)
            assertArrayEquals(f.expected(strength), f.pixels.last(), 0f)
            assertEquals(2, run.sourceOpens)
            assertFalse(run.attempt.hasUnreleasedResources)
            assertEquals(if (index == 0) 1 else 0, run.commits)
            assertEquals(0, run.invalidates)
            assertEquals(1, f.frameLoads); assertEquals(1, f.controlsCalls)
            assertNotNull(run.lease!!.load(run.request))
            run.attempt.releaseResources()
        }
    }

    @Test fun legacyNullReuseStillDecodesAndPreparesControlsForEveryRequest() {
        val f = Fixture()
        repeat(2) { index ->
            val run = f.run(.5, index + 1L, useReuse = false)
            run.attempt.renderTrial { false }
            assertArrayEquals(f.expected(.5), f.pixels.last(), 0f)
            assertEquals(2, run.sourceOpens)
        }
        assertEquals(2, f.frameLoads); assertEquals(2, f.controlsCalls)
    }

    @Test fun ordinaryCancellationNeverCommitsAnUnfinishedCandidateAndKeepsAnEarlierVerifiedBase() {
        val f = Fixture()
        val firstCancelled = f.run()
        var stopped = false
        f.afterEncode = { stopped = true }
        assertThrows(CancellationException::class.java) { firstCancelled.attempt.renderTrial { stopped } }
        assertEquals(0, firstCancelled.commits); assertEquals(0, firstCancelled.invalidates)
        assertNull(firstCancelled.lease!!.load(firstCancelled.request))
        f.afterEncode = {}; stopped = false
        val successful = f.run(.5, 2)
        successful.attempt.renderTrial { false }
        val verified = successful.lease!!.load(successful.request)!!
        val cancelledHit = f.run(.75, 3)
        f.afterEncode = { stopped = true }
        assertThrows(CancellationException::class.java) { cancelledHit.attempt.renderTrial { stopped } }
        assertEquals(0, cancelledHit.commits); assertEquals(0, cancelledHit.invalidates)
        assertSame(verified, cancelledHit.lease!!.load(cancelledHit.request))
        assertEquals(2, f.frameLoads) // Failed miss + successful miss; cancelled hit did not decode.
        f.afterEncode = {}
        val resumed = f.run(.25, 4)
        resumed.attempt.renderTrial { false }
        assertEquals(2, f.frameLoads)
        assertArrayEquals(f.expected(.25), f.pixels.last(), 0f)
    }

    @Test fun cancellationAfterPostHashAndResourceCloseCannotCommitTheNewFoundation() {
        val f = Fixture()
        var stopped = false
        f.afterPostHashClose = { stopped = true }
        val run = f.run()
        assertThrows(CancellationException::class.java) { run.attempt.renderTrial { stopped } }
        assertTrue(run.outputClosed); assertTrue(run.readbackClosed)
        assertFalse(run.attempt.hasUnreleasedResources)
        assertEquals(0, run.commits); assertEquals(0, run.invalidates)
        assertNull(run.lease!!.load(run.request))
    }

    @Test fun codecReadbackAndChangedOriginalFailuresInvalidateAndNeverCommit() {
        for (kind in listOf("codec", "readback", "original")) {
            val f = Fixture()
            val first = f.run(); first.attempt.renderTrial { false }
            when (kind) {
                "codec" -> f.codecSuccess = false
                "readback" -> f.corruptRead = true
                "original" -> f.changedOriginal = true
            }
            val run = f.run(.75, 2)
            assertThrows(IOException::class.java) { run.attempt.renderTrial { false } }
            assertEquals(0, run.commits)
            assertTrue(run.invalidates > 0)
            assertNull(run.lease!!.load(run.request))
            assertFalse(run.attempt.hasUnreleasedResources)
            assertEquals(1, f.frameLoads) // The failing run was a hit, yet still rechecked the original.
        }
    }

    @Test fun failedCloseInvalidatesEvenWhenCancellationWasThePrimaryFailure() {
        for (withCancellation in listOf(false, true)) {
            val f = Fixture()
            val first = f.run(); first.attempt.renderTrial { false }
            val run = f.run(.75, 2)
            f.failOutputClose = true
            if (withCancellation) f.afterEncode = { throw CancellationException("Synthetic strength supersession") }
            assertThrows(Exception::class.java) { run.attempt.renderTrial { false } }
            assertTrue(run.attempt.hasUnreleasedResources)
            assertEquals(0, run.commits)
            assertTrue(run.invalidates > 0)
            assertNull(run.lease!!.load(run.request))
            f.failOutputClose = false
            run.attempt.releaseResources()
            assertFalse(run.attempt.hasUnreleasedResources)
        }
    }

    @Test fun rebindBetweenSuccessfulWorkAndCommitRefusesTheOldCandidateWithoutHurtingNewBinding() {
        val f = Fixture()
        var next: PrivateTrialReuseLease? = null
        val nextRequest = auto(.75, 2)
        f.afterEncode = { next = f.session.bind(nextRequest) }
        val old = f.run()
        old.attempt.renderTrial { false }
        assertEquals(1, old.commits) // Store was proposed only after success; the stale lease refuses it.
        assertNull(old.lease!!.load(old.request))
        assertNull(requireNotNull(next).load(jpeg(nextRequest)))
        old.lease.invalidate()
        assertNotNull(f.session.forRequest(nextRequest))
    }

    @Test fun completedEntryNeverInvokesPrivateReuseEvenWhenProvided() {
        val recipe = JpegGenerationRecipe("synthetic-completed", RenderRecipeSnapshot("synthetic-policy", "ev0"))
        val request = JpegGenerationRequest("synthetic_completed", 1, source, recipe)
        val bytes = ByteArrayOutputStream()
        val untouched = object : TrialFoundationReuse {
            override fun load(request: JpegGenerationRequest): TrialPreparedFoundation? = error("Completed path touched private reuse")
            override fun commit(request: JpegGenerationRequest, foundation: TrialPreparedFoundation) = error("Completed path touched private reuse")
            override fun invalidate() = error("Completed path touched private reuse")
        }
        val attempt = JpegGenerationAttempt(request, object : PrivateJpegTarget {
            override val captureId = request.captureId; override val generation = request.generation
            override fun createOutput() = bytes
            override fun openRead() = bytes.toByteArray().inputStream()
        }, object : JpegFrameSource {
            override val contract = recipe.inputContract
            override fun load(source: SharedOriginalReference, longEdge: Int, shouldStop: () -> Boolean) = JpegFrame(image(), 8, 8, true)
        }, object : JpegControlsProvider {
            override val contract = recipe.controls.schema
            override fun prepare(image: LinearSrgbImage, policy: RenderRecipeSnapshot, shouldStop: () -> Boolean) =
                JpegControls(policy, 0.0, FloatPlane(4, 4, FloatArray(16)), FaceMask(4, 4, FloatArray(16)),
                    RenderedFaceState.VERIFIED_NONE, RenderedExposureState.SUPPORTED)
        }, { original.inputStream() }, LinearJpegEncoder { _, _, output, _ -> output.write(byteArrayOf(1, 2, 3)); true },
            JpegInspector { open, stop ->
                val fp = DngContentVerifier.identify(3, open, stop)
                JpegInspection(JpegOutputDescription(4, 4, 1, "sRGB", 3), fp)
            }, untouched)
        assertEquals(RenderedResultKind.COMPLETE_STAGE_CLEAR, attempt.render { false }.render.kind)
        attempt.releaseResources()
    }
}
