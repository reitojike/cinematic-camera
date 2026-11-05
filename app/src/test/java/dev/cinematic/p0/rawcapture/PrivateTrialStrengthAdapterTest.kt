package dev.cinematic.p0.rawcapture

import dev.cinematic.p0.jpegrender.*
import dev.cinematic.p0.raw.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import org.junit.Assert.*
import org.junit.Test

class PrivateTrialStrengthAdapterTest {
    private val original = "synthetic original bytes".toByteArray()
    private val source = SharedOriginalReference("content://example.test/original/1",
        DngContentVerifier.identify(original.size.toLong(), { original.inputStream() }), OriginalGrant.USER_SELECTED)
    private fun image() = LinearSrgbImage(4, 4, FloatArray(48) { channel ->
        if (channel / 3 == 5) 1.3f else (.02 + (channel % 11) * .045).toFloat()
    })
    private fun request(strength: Double, generation: Long = 1) = AutoDevelopRequest("synthetic_strength", generation,
        source, JpegGenerationRecipe.trial(strength).snapshot, JpegGenerationRecipe.TRIAL_SCHEMA, 1024, 1024,
        AutoRenderQuality.PRIVATE_TRIAL_1024, RenderedFaceState.NOT_RUN, RenderedExposureState.DIAGNOSTIC, AutoInputProfile.UNKNOWN)

    private class Io(private val original: ByteArray) : AutoRenderIo {
        var sourceOpens = 0
        var outputOpens = 0
        val bytes = ByteArrayOutputStream()
        override fun openOriginal(): InputStream { sourceOpens++; return original.inputStream() }
        override fun openOriginalDescriptor(): AutoReadOnlyDescriptor = error("No native input in this synthetic unit test")
        override fun openOutput(): OutputStream { outputOpens++; return bytes }
        override fun openOutputRead() = bytes.toByteArray().inputStream()
    }

    private inner class Fixture {
        val pixels = mutableListOf<FloatArray>()
        var frameFactories = 0
        var frameLoads = 0
        var frameCloses = 0
        val adapter = BoundedJpegRendererAdapter(JpegGenerationRecipe.trial(), { request, _ ->
            frameFactories++
            object : OwnedJpegFrameSource {
                override val contract = AndroidAsShotJpegFrames.CONTRACT
                override fun load(source: SharedOriginalReference, longEdge: Int, shouldStop: () -> Boolean): JpegFrame {
                    assertEquals(request.source, source)
                    assertEquals(1024, longEdge)
                    frameLoads++
                    return JpegFrame(image(), 8, 8, true)
                }
                override fun close() { frameCloses++ }
            }
        }, AsShotNoFaceTrialControls, LinearJpegEncoder { image, quality, output, _ ->
            assertEquals(95, quality)
            val captured = image.copyPixels()
            pixels += captured
            // Synthetic codec serializes Float32 pixels so readback remains tied to the produced pixels.
            val data = DataOutputStream(output)
            captured.forEach(data::writeFloat)
            true
        }, JpegInspector { open, stop ->
            val fp = DngContentVerifier.identify(4L * 4 * 3 * 4, open, stop)
            JpegInspection(JpegOutputDescription(4, 4, 1, "sRGB", fp.bytes), fp)
        })
        fun expected(strength: Double): FloatArray {
            val input = image()
            val controls = AsShotNoFaceTrialControls.prepare(input, AsShotNoFaceTrialControls.POLICY) { false }
            val base = R7Base.render(input, controls.foundationRoi, controls.externalEv)
            return StageClearLook.renderFromBase(base, controls.faces, strength).image.copyPixels()
        }
    }

    @Test fun oneCatalogAdapterAcceptsEveryKnownStrengthWithoutExtraEnrollment() {
        val fixture = Fixture()
        val catalog = AutoRendererCatalog(listOf(fixture.adapter))
        for (strength in listOf(0.0, .123456789, .5, 1.0)) assertSame(fixture.adapter, catalog.find(request(strength)))
        assertEquals(0, fixture.frameFactories)
        assertEquals(0, fixture.frameLoads)
    }

    @Test fun eachAttemptUsesItsFrozenRequestStrengthEvenWhenAnotherAttemptIsCreatedLater() {
        val fixture = Fixture()
        val zero = request(0.0)
        val one = request(1.0, 2)
        val zeroIo = Io(original)
        val oneIo = Io(original)
        val zeroAttempt = fixture.adapter.createAttempt(zero, "a".repeat(32), zeroIo)
        val oneAttempt = fixture.adapter.createAttempt(one, "b".repeat(32), oneIo)
        assertEquals(0, zeroIo.sourceOpens + oneIo.sourceOpens)
        assertEquals(0, zeroIo.outputOpens + oneIo.outputOpens)
        try {
            val oneReceipt = oneAttempt.render { false }
            val zeroReceipt = zeroAttempt.render { false }
            assertEquals(one, oneReceipt.request)
            assertEquals(zero, zeroReceipt.request)
            assertEquals(RenderedFaceState.NOT_RUN, zeroReceipt.face)
            assertEquals(RenderedExposureState.DIAGNOSTIC, oneReceipt.exposure)
            assertArrayEquals(fixture.expected(1.0), fixture.pixels[0], 0f)
            assertArrayEquals(fixture.expected(0.0), fixture.pixels[1], 0f)
            assertFalse(fixture.pixels[0].contentEquals(fixture.pixels[1]))
            assertFalse(zeroIo.bytes.toByteArray().contentEquals(oneIo.bytes.toByteArray()))
            assertEquals(2, zeroIo.sourceOpens)
            assertEquals(2, oneIo.sourceOpens)
        } finally { oneAttempt.close(); zeroAttempt.close() }
        assertEquals(2, fixture.frameCloses)
    }

    @Test fun halfStrengthStillProducesTheExistingTrialPipelineResult() {
        val fixture = Fixture()
        val selected = request(.5)
        fixture.adapter.createAttempt(selected, "a".repeat(32), Io(original)).use { attempt ->
            assertEquals(selected, attempt.render { false }.request)
        }
        assertArrayEquals(fixture.expected(.5), fixture.pixels.single(), 0f)
    }

    @Test fun unknownPoliciesAndCompletedRequestsAreRejectedBeforeFactoriesOrIo() {
        val fixture = Fixture()
        val valid = request(.5)
        val completedRecipe = JpegGenerationRecipe("synthetic-completed-v1", RenderRecipeSnapshot("synthetic-policy-v1", "fixed"))
        val completed = valid.copy(recipe = completedRecipe.snapshot, rendererContract = JpegGenerationRecipe.RENDERER,
            quality = AutoRenderQuality.MANUAL_BOUNDED_1024_PRELIMINARY, exposure = RenderedExposureState.UNKNOWN)
        val wrong = listOf(
            valid.copy(recipe = valid.recipe.copy(payload = valid.recipe.payload.replace("quality=95", "quality=96"))),
            valid.copy(recipe = valid.recipe.copy(payload = valid.recipe.payload + ";extra=value")),
            valid.copy(face = RenderedFaceState.VERIFIED_NONE),
            valid.copy(exposure = RenderedExposureState.SUPPORTED),
            valid.copy(profile = AutoInputProfile.SUPPORTED),
            completed,
        )
        for (unsupported in wrong) {
            val io = Io(original)
            assertFalse(fixture.adapter.supports(unsupported))
            assertThrows(AutoRenderUnsupported::class.java) { fixture.adapter.createAttempt(unsupported, "a".repeat(32), io) }
            assertEquals(0, io.sourceOpens + io.outputOpens)
        }
        assertEquals(0, fixture.frameFactories)
        val completedAdapter = BoundedJpegRendererAdapter(completedRecipe,
            { _, _ -> error("support check must not create frames") }, object : JpegControlsProvider {
                override val contract = completedRecipe.controls.schema
                override fun prepare(image: LinearSrgbImage, policy: RenderRecipeSnapshot, shouldStop: () -> Boolean): JpegControls = error("support only")
            }, LinearJpegEncoder { _, _, _, _ -> error("support only") }, JpegInspector { _, _ -> error("support only") })
        assertTrue(completedAdapter.supports(completed))
        assertFalse(completedAdapter.supports(valid))
        assertFalse(completedAdapter.supports(completed.copy(recipe = JpegGenerationRecipe(completedRecipe.inputContract,
            completedRecipe.controls, strength = 1.0).snapshot)))
    }
}
