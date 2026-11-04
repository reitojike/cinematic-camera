package dev.cinematic.p0.jpegrender

import dev.cinematic.p0.raw.R7Base
import dev.cinematic.p0.raw.StageClearLook
import dev.cinematic.p0.rawcapture.*
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** One attempt, one frozen request. Failed closes remain owned until releaseResources succeeds. */
internal class JpegGenerationAttempt(
    private val request: JpegGenerationRequest,
    private val target: PrivateJpegTarget,
    private val frames: JpegFrameSource,
    private val controls: JpegControlsProvider,
    private val openOriginal: (SharedOriginalReference) -> InputStream,
    private val encoder: LinearJpegEncoder,
    private val inspector: JpegInspector,
) {
    private val active = AtomicBoolean(false)
    private var started = false
    private val resources = DngIoResources()
    val hasUnreleasedResources get() = resources.hasOpenResources

    fun releaseResources() = exclusively { resources.releaseAll()?.let { throw it }; Unit }

    fun render(shouldStop: () -> Boolean): GeneratedJpeg = renderChecked(false, shouldStop) { observed, prepared ->
        GeneratedJpeg(CompletedRender(outputRequest(observed), JpegGenerationRecipe.RENDERER,
            RenderedResultKind.COMPLETE_STAGE_CLEAR, prepared.faceState, prepared.exposureState), observed.fingerprint)
    }

    fun renderTrial(shouldStop: () -> Boolean): TrialJpeg = renderChecked(true, shouldStop) { observed, _ ->
        TrialJpeg(outputRequest(observed), observed.fingerprint)
    }

    private fun outputRequest(observed: JpegInspection) = RenderedJpegRequest(request.captureId, request.generation,
        request.source, request.recipe.snapshot, observed.output)

    private fun <T> renderChecked(trial: Boolean, shouldStop: () -> Boolean, finish: (JpegInspection, JpegControls) -> T): T = exclusively {
        check(!started) { "JPEG attempt is single use" }
        started = true
        require(request.recipe.isTrial == trial) { "JPEG recipe belongs to a different entry" }
        require(target.captureId == request.captureId && target.generation == request.generation)
        require(frames.contract == request.recipe.inputContract && controls.contract == request.recipe.controls.schema)
        var failure: Throwable? = null
        try {
            fun checkpoint() { if (shouldStop()) throw CancellationException("JPEG generation stopped") }
            // Convert verifier cancellation to the attempt's common cancellation type.
            val stop = { checkpoint(); false }
            fun verifyOriginal() = DngContentVerifier.verify(request.source.fingerprint,
                { resources.input(openOriginal(request.source)) }, stop)
            checkpoint()
            verifyOriginal()
            val frame = frames.load(request.source, JpegGenerationRecipe.MAX_EDGE, stop)
            checkpoint()
            require(frame.physicallyOriented && frame.sourceWidth > 0 && frame.sourceHeight > 0)
            require(frame.image.width <= JpegGenerationRecipe.MAX_EDGE && frame.image.height <= JpegGenerationRecipe.MAX_EDGE)
            val prepared = controls.prepare(frame.image, request.recipe.controls, stop)
            checkpoint()
            if (trial) prepared.validateTrial(frame.image, request.recipe.controls)
            else prepared.validate(frame.image, request.recipe.controls)
            val base = R7Base.render(frame.image, prepared.foundationRoi, prepared.externalEv, stop)
            val result = StageClearLook.renderFromBase(base, prepared.faces, request.recipe.strength, stop).image
            checkpoint()
            val written = resources.output(target.createOutput()).use { raw ->
                val encoded = MeasuredOutput(raw, shouldStop)
                try {
                    if (!encoder.encode(result, request.recipe.quality, encoded, stop)) throw IOException("JPEG codec failed")
                    encoded.flush()
                    encoded.fingerprint()
                } finally { encoded.sealed = true }
            }
            checkpoint()
            val observed = inspector.inspect({ BoundedReadback(resources.input(target.openRead()), written.bytes) }, stop)
            if (observed.fingerprint != written || observed.output.bytes != written.bytes ||
                observed.output.width != result.width || observed.output.height != result.height ||
                observed.output.orientation != 1 || observed.output.color != "sRGB") throw IOException("JPEG readback differs from encoded output")
            verifyOriginal()
            checkpoint()
            finish(observed, prepared)
        } catch (t: Throwable) {
            failure = t
            throw t
        } finally {
            resources.releaseAll()?.let { cleanup ->
                val primary = failure
                if (primary == null) throw cleanup
                if (primary !== cleanup) primary.addSuppressed(cleanup)
            }
        }
    }

    private inline fun <T> exclusively(action: () -> T): T {
        check(active.compareAndSet(false, true)) { "JPEG attempt is already active" }
        try { return action() } finally { active.set(false) }
    }

    private class BoundedReadback(private val input: InputStream, private val expected: Long) : InputStream() {
        private var read = 0L
        override fun read(): Int {
            val value = input.read()
            if (value >= 0) account(1)
            return value
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            require(offset >= 0 && length >= 0 && offset <= buffer.size - length)
            if (length == 0) return 0
            val count = input.read(buffer, offset, minOf(length.toLong(), expected - read + 1).toInt())
            if (count > 0) account(count)
            return count
        }
        private fun account(count: Int) {
            read += count
            if (read > expected) throw IOException("JPEG readback exceeds encoded length")
        }
        override fun close() = input.close()
    }

    private class MeasuredOutput(private val output: OutputStream, private val stop: () -> Boolean) : OutputStream() {
        private val digest = MessageDigest.getInstance("SHA-256")
        private var bytes = 0L
        var sealed = false
        override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            check(!sealed) { "JPEG codec writes ended" }
            require(offset >= 0 && length >= 0 && offset <= buffer.size - length)
            if (stop()) throw CancellationException("JPEG generation stopped")
            if (length.toLong() > JpegGenerationRecipe.MAX_BYTES - bytes) throw IOException("JPEG exceeds byte bound")
            output.write(buffer, offset, length)
            digest.update(buffer, offset, length)
            bytes += length
            if (stop()) throw CancellationException("JPEG generation stopped")
        }
        override fun flush() { check(!sealed); output.flush() }
        // Codec must not close the caller's stream. The owning use block closes it positively.
        override fun close() = throw IOException("JPEG codec must not close its output")
        fun fingerprint(): OriginalFingerprint {
            if (bytes == 0L) throw IOException("JPEG codec produced no bytes")
            return OriginalFingerprint(bytes, digest.digest().joinToString("") { "%02x".format(it) })
        }
    }
}
