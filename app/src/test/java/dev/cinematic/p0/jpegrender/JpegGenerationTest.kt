package dev.cinematic.p0.jpegrender

import dev.cinematic.p0.raw.*
import dev.cinematic.p0.rawcapture.*
import java.io.*
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class JpegGenerationTest {
    private val policy = RenderRecipeSnapshot("synthetic-controls-v1", "fixed-ev=0;faces=none")
    private val recipe get() = JpegGenerationRecipe("synthetic-frame-v1", policy)

    @Test fun trialPreservesUnverifiedStatesAndHasSeparateRecipeAndResult() {
        val f = Fixture(trial = true)
        val generated = f.attempt.renderTrial { false }
        assertEquals(JpegGenerationRecipe.TRIAL_SCHEMA, generated.request.recipe.schema)
        assertEquals(RenderedResultKind.TRIAL_PREVIEW, generated.kind)
        assertEquals(RenderedFaceState.NOT_RUN, generated.face)
        assertEquals(RenderedExposureState.DIAGNOSTIC, generated.exposure)
        assertEquals(f.request.source, generated.request.source)
        assertEquals(f.encoded.size.toLong(), generated.jpegFingerprint.bytes)
        assertEquals(2, f.sourceOpens)
        assertTrue(f.closed)
        assertFalse(f.attempt.hasUnreleasedResources)
        val attemptedPromotion = CompletedRender(generated.request, "trial", generated.kind, generated.face, generated.exposure)
        assertThrows(IOException::class.java) {
            attemptedPromotion.requirePublishable(setOf(RendererPublicationContract("trial", JpegGenerationRecipe.TRIAL_SCHEMA)))
        }
    }

    @Test fun wrongPurposeAndForgedTrialControlsAreRejectedBeforeOutput() {
        val trial = Fixture(trial = true)
        assertThrows(IllegalArgumentException::class.java) { trial.attempt.render { false } }
        assertEquals(0, trial.sourceOpens)
        val completed = Fixture()
        assertThrows(IllegalArgumentException::class.java) { completed.attempt.renderTrial { false } }
        assertEquals(0, completed.sourceOpens)
        for (f in listOf(Fixture(trial = true, face = RenderedFaceState.VERIFIED_NONE),
            Fixture(trial = true, exposure = RenderedExposureState.SUPPORTED), Fixture(trial = true, foundationWeight = .75f))) {
            assertThrows(Exception::class.java) { f.attempt.renderTrial { false } }
            assertEquals(0, f.outputOpens)
        }
    }

    @Test fun realTrialControlsUseAsShotDiagnosticExposureWithoutClaimingNoFaces() {
        val image = image()
        val prepared = AsShotNoFaceTrialControls.prepare(image, AsShotNoFaceTrialControls.POLICY) { false }
        val expected = R7PreviewInputs.prepare(R7PreviewInputs.AsShotInput(image,
            R7PreviewInputs.Source.LIBRAW_PRELIMINARY_AS_SHOT), emptyList())
        assertEquals(expected.exposure.externalBaseEv, prepared.externalEv, 0.0)
        assertEquals(RenderedFaceState.NOT_RUN, prepared.faceState)
        assertEquals(RenderedExposureState.DIAGNOSTIC, prepared.exposureState)
        assertTrue(prepared.faces.copyWeights().all { it == 0f })
        assertTrue(prepared.foundationRoi.copyValues().all { it == 0f })
        assertThrows(IllegalArgumentException::class.java) {
            AsShotNoFaceTrialControls.prepare(image, policy) { false }
        }
    }

    @Test fun trialFaultsAndPartialWriteCancellationCannotReturnConfirmableOutput() {
        for (f in listOf(Fixture(trial = true, codecSuccess = false), Fixture(trial = true, corruptRead = true),
            Fixture(trial = true, changeSourceOnSecondRead = true), Fixture(trial = true, oversizedCodec = true))) {
            assertThrows(IOException::class.java) { f.attempt.renderTrial { false } }
            assertFalse(f.attempt.hasUnreleasedResources)
        }
        val f = Fixture(trial = true)
        var stopped = false
        f.afterPartialWrite = { stopped = true }
        assertThrows(CancellationException::class.java) { f.attempt.renderTrial { stopped } }
        assertTrue(f.closed)
        assertFalse(f.attempt.hasUnreleasedResources)
    }

    @Test fun trialFailedCloseRetainsOwnerUntilPositiveRelease() {
        val f = Fixture(trial = true)
        f.failClose = true
        assertThrows(IOException::class.java) { f.attempt.renderTrial { false } }
        assertTrue(f.attempt.hasUnreleasedResources)
        f.failClose = false
        f.attempt.releaseResources()
        assertFalse(f.attempt.hasUnreleasedResources)
        assertThrows(IllegalStateException::class.java) { f.attempt.renderTrial { false } }
    }

    @Test fun invalidRecipeAndUnknownInputsCannotBecomeCompletion() {
        for (strength in listOf(Double.NaN, -1.0, 1.1)) assertThrows(IllegalArgumentException::class.java) { JpegGenerationRecipe("frame", policy, strength) }
        for (quality in listOf(0, 101)) assertThrows(IllegalArgumentException::class.java) { JpegGenerationRecipe("frame", policy, quality = quality) }
        val image = image()
        val controls = controls(image)
        for (bad in listOf(controls.copy(faceState = RenderedFaceState.NOT_RUN), controls.copy(faceState = RenderedFaceState.FAILED),
            controls.copy(exposureState = RenderedExposureState.DIAGNOSTIC), controls.copy(exposureState = RenderedExposureState.UNKNOWN),
            controls.copy(policy = policy.copy(payload = "different")))) {
            assertThrows(IOException::class.java) { bad.validate(image, policy) }
        }
        assertThrows(IllegalArgumentException::class.java) { controls.copy(faceState = RenderedFaceState.VERIFIED_FACES).validate(image, policy) }
        assertThrows(IllegalArgumentException::class.java) { controls.copy(faces = FaceMask(1, 1, floatArrayOf(0f))).validate(image, policy) }
    }

    @Test fun noDetectedFacesCanStillUseIndependentFoundationSkinRoi() {
        val f = Fixture(foundationWeight = .75f)
        val generated = f.attempt.render { false }
        assertEquals(RenderedFaceState.VERIFIED_NONE, generated.render.face)
        assertEquals(1, f.outputOpens)
        assertFalse(f.attempt.hasUnreleasedResources)
    }

    @Test fun writesClosesRechecksAndBindsMeasuredBytesToFixedRequest() {
        val f = Fixture()
        val result = f.attempt.render { false }
        assertEquals(f.request.recipe.snapshot, result.render.request.recipe)
        assertEquals(1, result.render.request.output.orientation)
        assertEquals("sRGB", result.render.request.output.color)
        assertEquals(RenderedFaceState.VERIFIED_NONE, result.render.face)
        assertEquals(RenderedExposureState.SUPPORTED, result.render.exposure)
        assertEquals(RenderedResultKind.COMPLETE_STAGE_CLEAR, result.render.kind)
        assertEquals(f.encoded.size.toLong(), result.jpegFingerprint.bytes)
        assertEquals(2, f.sourceOpens)
        assertTrue(f.closed)
        assertFalse(f.attempt.hasUnreleasedResources)
        assertThrows(IllegalStateException::class.java) { f.attempt.render { false } }
    }

    @Test fun unsafeControlsAndForeignTargetNeverCreateOutput() {
        for (face in listOf(RenderedFaceState.NOT_RUN, RenderedFaceState.FAILED)) {
            val f = Fixture(face = face)
            assertThrows(IOException::class.java) { f.attempt.render { false } }
            assertEquals(0, f.outputOpens)
        }
        val f = Fixture(targetGeneration = 2)
        assertThrows(IllegalArgumentException::class.java) { f.attempt.render { false } }
        assertEquals(0, f.outputOpens)
        assertEquals(0, f.sourceOpens)
    }

    @Test fun codecFailureCancellationAndReadbackMismatchNeverReturnCompletion() {
        val codec = Fixture(codecSuccess = false)
        assertThrows(IOException::class.java) { codec.attempt.render { false } }
        assertTrue(codec.closed)
        val corrupt = Fixture(corruptRead = true)
        assertThrows(IOException::class.java) { corrupt.attempt.render { false } }
        var calls = 0
        val stopped = Fixture()
        assertThrows(CancellationException::class.java) { stopped.attempt.render { ++calls > 7 } }
        assertFalse(stopped.attempt.hasUnreleasedResources)
    }

    @Test fun changedOriginalOversizedCodecAndForeignInputContractNeverComplete() {
        val changed = Fixture(changeSourceOnSecondRead = true)
        assertThrows(DngContentChanged::class.java) { changed.attempt.render { false } }
        assertTrue(changed.closed)
        assertFalse(changed.attempt.hasUnreleasedResources)
        val oversized = Fixture(oversizedCodec = true)
        assertThrows(IOException::class.java) { oversized.attempt.render { false } }
        assertTrue(oversized.closed)
        val foreign = Fixture(inputContract = "foreign-input")
        assertThrows(IllegalArgumentException::class.java) { foreign.attempt.render { false } }
        assertEquals(0, foreign.sourceOpens)
        assertEquals(0, foreign.outputOpens)
    }

    @Test fun cancellationAfterPartialCodecWriteClosesOutputWithoutCompletion() {
        val f = Fixture()
        var cancelled = false
        f.afterPartialWrite = { cancelled = true }
        assertThrows(CancellationException::class.java) { f.attempt.render { cancelled } }
        assertEquals(1, f.outputOpens)
        assertTrue(f.closed)
        assertFalse(f.attempt.hasUnreleasedResources)
        assertThrows(IllegalStateException::class.java) { f.attempt.render { false } }
    }

    @Test fun failedReaderClosesRemainHeldUntilPositiveRelease() {
        for (original in listOf(true, false)) {
            val f = Fixture()
            if (original) f.failOriginalReadClose = true else f.failJpegReadClose = true
            assertThrows(IOException::class.java) { f.attempt.render { false } }
            assertTrue(f.attempt.hasUnreleasedResources)
            assertThrows(IOException::class.java) { f.attempt.releaseResources() }
            f.failOriginalReadClose = false; f.failJpegReadClose = false
            f.attempt.releaseResources()
            assertFalse(f.attempt.hasUnreleasedResources)
            assertThrows(IllegalStateException::class.java) { f.attempt.render { false } }
        }
    }

    @Test fun failedCloseStaysHeldUntilPositiveReleaseAndNoNewWorkRuns() {
        val f = Fixture()
        f.failClose = true
        assertThrows(IOException::class.java) { f.attempt.render { false } }
        assertTrue(f.attempt.hasUnreleasedResources)
        assertThrows(IOException::class.java) { f.attempt.releaseResources() }
        assertEquals(1, f.outputOpens)
        f.failClose = false
        f.attempt.releaseResources()
        assertFalse(f.attempt.hasUnreleasedResources)
        assertThrows(IllegalStateException::class.java) { f.attempt.render { false } }
    }

    private fun image() = LinearSrgbImage(4, 4, FloatArray(48) { .2f })
    private fun controls(image: LinearSrgbImage, face: RenderedFaceState = RenderedFaceState.VERIFIED_NONE, foundationWeight: Float = 0f, controlPolicy: RenderRecipeSnapshot = policy,
        exposure: RenderedExposureState = RenderedExposureState.SUPPORTED) = JpegControls(
        controlPolicy, 0.0, FloatPlane(image.width, image.height, FloatArray(image.pixelCount) { foundationWeight }),
        FaceMask(image.width, image.height, FloatArray(image.pixelCount)), face, exposure,
    )

    private inner class Fixture(
        face: RenderedFaceState? = null,
        exposure: RenderedExposureState? = null,
        trial: Boolean = false,
        foundationWeight: Float = 0f,
        targetGeneration: Long = 1,
        codecSuccess: Boolean = true,
        corruptRead: Boolean = false,
        changeSourceOnSecondRead: Boolean = false,
        oversizedCodec: Boolean = false,
        inputContract: String? = null,
    ) {
        val encoded = "synthetic codec bytes".toByteArray()
        private val original = "synthetic source".toByteArray()
        val source = SharedOriginalReference("content://media/external_primary/images/media/1",
            DngContentVerifier.identify(original.size.toLong(), { original.inputStream() }), OriginalGrant.APP_OWNED)
        val request = JpegGenerationRequest("synthetic_render", 1, source, if (trial) JpegGenerationRecipe.trial() else recipe)
        var sourceOpens = 0; var outputOpens = 0; var failClose = false; var closed = false
        var failOriginalReadClose = false; var failJpegReadClose = false
        var afterPartialWrite: () -> Unit = {}
        private val storage = ByteArrayOutputStream()
        val attempt = JpegGenerationAttempt(request, object : PrivateJpegTarget {
            override val captureId = request.captureId
            override val generation = targetGeneration
            override fun createOutput(): OutputStream {
                outputOpens++
                return object : FilterOutputStream(storage) {
                    override fun close() { if (failClose) throw IOException("Synthetic close failure"); closed = true; super.close() }
                }
            }
            override fun openRead(): InputStream = object : FilterInputStream((if (corruptRead) "changed codec bytes".toByteArray() else storage.toByteArray()).inputStream()) {
                override fun close() { if (failJpegReadClose) throw IOException("Synthetic read close failure"); super.close() }
            }
        }, object : JpegFrameSource {
            override val contract = inputContract ?: request.recipe.inputContract
            override fun load(source: SharedOriginalReference, longEdge: Int, shouldStop: () -> Boolean) = JpegFrame(image(), 8, 8, true)
        }, object : JpegControlsProvider {
            override val contract = request.recipe.controls.schema
            override fun prepare(image: LinearSrgbImage, policy: RenderRecipeSnapshot, shouldStop: () -> Boolean) = controls(image,
                face ?: if (trial) RenderedFaceState.NOT_RUN else RenderedFaceState.VERIFIED_NONE, foundationWeight, policy,
                exposure ?: if (trial) RenderedExposureState.DIAGNOSTIC else RenderedExposureState.SUPPORTED)
        }, {
                sourceOpens++
                object : FilterInputStream((if (changeSourceOnSecondRead && sourceOpens == 2) "modified source!".toByteArray() else original).inputStream()) {
                    override fun close() { if (failOriginalReadClose) throw IOException("Synthetic original close failure"); super.close() }
                }
            },
            LinearJpegEncoder { _, _, output, _ ->
                if (oversizedCodec) repeat(129) { output.write(ByteArray(64 * 1024)) } else {
                    output.write(encoded, 0, 10)
                    afterPartialWrite()
                    output.write(encoded, 10, encoded.size - 10)
                }
                codecSuccess
            },
            JpegInspector { open, stop ->
                val fp = DngContentVerifier.identify(if (corruptRead) 19 else encoded.size.toLong(), open, stop)
                JpegInspection(JpegOutputDescription(4, 4, 1, "sRGB", fp.bytes), fp)
            })
    }
}
