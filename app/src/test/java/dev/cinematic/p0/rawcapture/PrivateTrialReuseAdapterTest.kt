package dev.cinematic.p0.rawcapture

import dev.cinematic.p0.jpegrender.*
import dev.cinematic.p0.raw.LinearSrgbImage
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class PrivateTrialReuseAdapterTest {
    private val original = "synthetic original".toByteArray()
    private val source = SharedOriginalReference("content://example.test/synthetic/1",
        DngContentVerifier.identify(original.size.toLong(), { original.inputStream() }), OriginalGrant.USER_SELECTED)
    private fun request(strength: Double = .5, generation: Long = 1) = AutoDevelopRequest("synthetic_reuse", generation,
        source, JpegGenerationRecipe.trial(strength).snapshot, JpegGenerationRecipe.TRIAL_SCHEMA, 1024, 1024,
        AutoRenderQuality.PRIVATE_TRIAL_1024, RenderedFaceState.NOT_RUN, RenderedExposureState.DIAGNOSTIC, AutoInputProfile.UNKNOWN)
    private fun jpeg(request: AutoDevelopRequest) = JpegGenerationRequest(request.captureId, request.generation,
        request.source, requireNotNull(JpegGenerationRecipe.fromTrialSnapshot(request.recipe)))

    private inner class Fixture {
        val session = PrivateTrialReuseSession()
        var lookups = 0
        var loads = 0
        var stops = 0
        var failClose = false
        val adapter = BoundedJpegRendererAdapter(JpegGenerationRecipe.trial(), { _, _ ->
            object : OwnedJpegFrameSource {
                override val contract = AndroidAsShotJpegFrames.CONTRACT
                override fun load(source: SharedOriginalReference, longEdge: Int, shouldStop: () -> Boolean): JpegFrame {
                    loads++
                    return JpegFrame(LinearSrgbImage(4, 4, FloatArray(48) { .2f }), 8, 8, true)
                }
                override fun requestStop() { stops++ }
                override fun close() { if (failClose) throw IOException("Synthetic native FD close failure") }
            }
        }, AsShotNoFaceTrialControls, LinearJpegEncoder { image, _, output, _ ->
            val data = DataOutputStream(output); image.copyPixels().forEach(data::writeFloat); true
        }, JpegInspector { open, stop ->
            val fp = DngContentVerifier.identify(192, open, stop)
            JpegInspection(JpegOutputDescription(4, 4, 1, "sRGB", 192), fp)
        }, { selected -> lookups++; session.forRequest(selected) })
        fun io() = object : AutoRenderIo {
            private val bytes = ByteArrayOutputStream()
            override fun openOriginal() = original.inputStream()
            override fun openOriginalDescriptor(): AutoReadOnlyDescriptor = error("Synthetic frame only")
            override fun openOutput() = bytes
            override fun openOutputRead() = bytes.toByteArray().inputStream()
        }
    }

    @Test fun adapterFrameCloseFailureInvalidatesPreviouslyProposedFoundationAndAllowsCloseRetry() {
        val f = Fixture()
        val selected = request(); val lease = requireNotNull(f.session.bind(selected))
        val attempt = f.adapter.createAttempt(selected, "a".repeat(32), f.io())
        attempt.render { false }
        assertNotNull(lease.load(jpeg(selected)))
        f.failClose = true
        assertThrows(IOException::class.java) { attempt.close() }
        assertNull(lease.load(jpeg(selected)))
        f.failClose = false; attempt.close()
        assertEquals(1, f.lookups)
    }

    @Test fun requestStopPreservesVerifiedBaseAndOnlyStopsTheFrameOwner() {
        val f = Fixture()
        val first = request(); val old = requireNotNull(f.session.bind(first))
        f.adapter.createAttempt(first, "a".repeat(32), f.io()).use { it.render { false } }
        val base = requireNotNull(old.load(jpeg(first)))
        val next = request(.75, 2); val lease = requireNotNull(f.session.bind(next))
        val attempt = f.adapter.createAttempt(next, "b".repeat(32), f.io())
        attempt.requestStop()
        assertEquals(1, f.stops)
        assertSame(base, lease.load(jpeg(next)))
        attempt.close()
        assertSame(base, lease.load(jpeg(next)))
    }

    @Test fun leaseIsSelectedOnlyOnceAtAttemptCreationAndOldFailureCannotClearANewBinding() {
        val f = Fixture()
        val first = request(); f.session.bind(first)
        val old = f.adapter.createAttempt(first, "a".repeat(32), f.io())
        val next = request(.75, 2); val current = requireNotNull(f.session.bind(next))
        val latest = f.adapter.createAttempt(next, "b".repeat(32), f.io())
        latest.render { false }; latest.close()
        val base = requireNotNull(current.load(jpeg(next)))
        old.render { false } // Its immutable old recipe may run, but cannot read/commit the new binding.
        f.failClose = true
        assertThrows(IOException::class.java) { old.close() }
        assertSame(base, current.load(jpeg(next)))
        f.failClose = false; old.close()
        assertEquals(2, f.lookups); assertEquals(2, f.loads)
    }

    @Test fun completedAdapterDoesNotCallThePrivateLookup() {
        val recipe = JpegGenerationRecipe("synthetic-completed", RenderRecipeSnapshot("synthetic-policy", "fixed"))
        val completed = request().copy(recipe = recipe.snapshot, rendererContract = JpegGenerationRecipe.RENDERER,
            quality = AutoRenderQuality.MANUAL_BOUNDED_1024_PRELIMINARY, exposure = RenderedExposureState.UNKNOWN)
        val adapter = BoundedJpegRendererAdapter(recipe, { _, _ -> object : OwnedJpegFrameSource {
            override val contract = recipe.inputContract
            override fun load(source: SharedOriginalReference, longEdge: Int, shouldStop: () -> Boolean): JpegFrame = error("No render")
            override fun close() {}
        } }, object : JpegControlsProvider {
            override val contract = recipe.controls.schema
            override fun prepare(image: LinearSrgbImage, policy: RenderRecipeSnapshot, shouldStop: () -> Boolean): JpegControls = error("No render")
        }, LinearJpegEncoder { _, _, _, _ -> error("No render") }, JpegInspector { _, _ -> error("No render") },
            { error("Completed adapter consulted private reuse") })
        assertTrue(adapter.supports(completed))
        adapter.createAttempt(completed, "a".repeat(32), Fixture().io()).close()
    }
}
