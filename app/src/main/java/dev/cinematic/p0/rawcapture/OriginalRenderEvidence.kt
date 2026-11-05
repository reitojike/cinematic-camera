package dev.cinematic.p0.rawcapture

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.MessageDigest

/** Public definition identifiers, not camera metadata, masks, file paths or success flags. */
internal data class RenderPolicyVersion(val id: String, val revision: Int) {
    init { require(id.matches(Regex("[A-Za-z0-9_.-]{1,64}")) && revision > 0) }
}

/** Frozen requirements. Registration, not this declaration, adopts an actual producer/policy. */
internal data class OriginalRenderDeclaration(
    val inputPolicy: RenderPolicyVersion,
    val geometryPolicy: RenderPolicyVersion,
    val faceDetector: RenderPolicyVersion,
    val meteringPolicy: RenderPolicyVersion,
    val minimumEv: Double,
    val maximumEv: Double,
) {
    init { require(minimumEv.isFinite() && maximumEv.isFinite() && minimumEv <= maximumEv) }
}

/**
 * Effective original raster before physical EXIF orientation, as defined by geometryPolicy.
 * sampledWidth/Height are the physically oriented input sample grid BEFORE any resize, not the
 * JPEG/header dimensions. fullCoverage acknowledges every original sample was processed. A
 * validated row/tile producer may report that grid without allocating a full RGB image.
 */
internal data class OriginalRasterGeometry(
    val sourceWidth: Int, val sourceHeight: Int, val sourceOrientation: Int,
    val sampledWidth: Int, val sampledHeight: Int, val fullCoverage: Boolean,
) {
    init {
        fun valid(w: Int, h: Int) = w in 1..32768 && h in 1..32768 && w.toLong() * h <= 100_000_000
        require(valid(sourceWidth, sourceHeight) && valid(sampledWidth, sampledHeight) && sourceOrientation in 1..8)
    }
    val orientedWidth get() = if (sourceOrientation >= 5) sourceHeight else sourceWidth
    val orientedHeight get() = if (sourceOrientation >= 5) sourceWidth else sourceHeight
    fun covers(output: JpegOutputDescription) = fullCoverage && sampledWidth == orientedWidth &&
        sampledHeight == orientedHeight && output.width == orientedWidth && output.height == orientedHeight
}

/** Same-attempt binding; the serial owner additionally compares attemptId with its durable intent. */
internal data class OriginalAttemptBinding(
    val captureId: String, val generation: Long, val attemptId: String,
    val sourceFingerprint: OriginalFingerprint, val recipeSha256: String, val rendererContract: String,
) {
    init {
        requireCaptureId(captureId)
        require(generation > 0 && attemptId.matches(Regex("[0-9a-f]{32}")))
        require(recipeSha256.matches(Regex("[0-9a-f]{64}")) && rendererContract.matches(Regex("[A-Za-z0-9_.-]{1,64}")))
    }
    fun matches(request: RenderedJpegRequest, renderer: String) = captureId == request.captureId &&
        generation == request.generation && sourceFingerprint == request.source.fingerprint &&
        recipeSha256 == recipeHash(request.recipe) && rendererContract == renderer

    companion object {
        fun recipeHash(recipe: RenderRecipeSnapshot): String {
            val bytes = ByteArrayOutputStream().also { buffer ->
                DataOutputStream(buffer).use { it.writeUTF(recipe.schema); it.writeUTF(recipe.payload) }
            }.toByteArray()
            return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        }
    }
}

/** Producer observations, not defaults copied from a request. No production producer is enrolled here. */
internal data class OriginalRenderObservation(
    val binding: OriginalAttemptBinding,
    val inputPolicy: RenderPolicyVersion,
    val geometryPolicy: RenderPolicyVersion,
    val profile: RenderPolicyVersion,
    val profileState: AutoInputProfile,
    val faceDetector: RenderPolicyVersion,
    val meteringPolicy: RenderPolicyVersion,
    val appliedEv: Double,
    val geometry: OriginalRasterGeometry,
) {
    init { require(appliedEv.isFinite()) }
    fun matches(declaration: OriginalRenderDeclaration, request: RenderedJpegRequest, renderer: String,
        face: RenderedFaceState, exposure: RenderedExposureState): Boolean = binding.matches(request, renderer) &&
        inputPolicy == declaration.inputPolicy && geometryPolicy == declaration.geometryPolicy &&
        faceDetector == declaration.faceDetector && meteringPolicy == declaration.meteringPolicy &&
        profileState == AutoInputProfile.SUPPORTED &&
        face in setOf(RenderedFaceState.VERIFIED_NONE, RenderedFaceState.VERIFIED_FACES) &&
        exposure == RenderedExposureState.SUPPORTED && appliedEv in declaration.minimumEv..declaration.maximumEv &&
        geometry.covers(request.output)
}

/** Created by the owner only after writer/engine close and independent inspection of that output. */
internal data class OriginalRenderCompletion(
    val declaration: OriginalRenderDeclaration,
    val observation: OriginalRenderObservation,
    val outputFingerprint: OriginalFingerprint,
) {
    fun matches(request: RenderedJpegRequest, renderer: String, face: RenderedFaceState, exposure: RenderedExposureState) =
        outputFingerprint.bytes == request.output.bytes && observation.matches(declaration, request, renderer, face, exposure)
}

/** Embedded in the two existing bounded/checksummed journals; never a new journal or image cache. */
internal object OriginalEvidenceCodec {
    fun writeDeclaration(out: DataOutputStream, value: OriginalRenderDeclaration?) {
        out.writeBoolean(value != null); value?.let { declaration(out, it) }
    }
    fun readDeclaration(input: DataInputStream) = if (flag(input)) declaration(input) else null
    fun writeObservation(out: DataOutputStream, value: OriginalRenderObservation?) {
        out.writeBoolean(value != null); value?.let { observation(out, it) }
    }
    fun readObservation(input: DataInputStream) = if (flag(input)) observation(input) else null
    fun writeCompletion(out: DataOutputStream, value: OriginalRenderCompletion?) {
        out.writeBoolean(value != null)
        value?.let { declaration(out, it.declaration); observation(out, it.observation); fingerprint(out, it.outputFingerprint) }
    }
    fun readCompletion(input: DataInputStream) = if (flag(input))
        OriginalRenderCompletion(declaration(input), observation(input), fingerprint(input)) else null

    private fun declaration(out: DataOutputStream, value: OriginalRenderDeclaration) {
        policy(out, value.inputPolicy); policy(out, value.geometryPolicy); policy(out, value.faceDetector); policy(out, value.meteringPolicy)
        out.writeDouble(value.minimumEv); out.writeDouble(value.maximumEv)
    }
    private fun declaration(input: DataInputStream) = OriginalRenderDeclaration(policy(input), policy(input), policy(input), policy(input),
        input.readDouble(), input.readDouble())
    private fun observation(out: DataOutputStream, value: OriginalRenderObservation) {
        val b = value.binding
        out.writeUTF(b.captureId); out.writeLong(b.generation); out.writeUTF(b.attemptId)
        fingerprint(out, b.sourceFingerprint); out.writeUTF(b.recipeSha256); out.writeUTF(b.rendererContract)
        policy(out, value.inputPolicy); policy(out, value.geometryPolicy); policy(out, value.profile)
        out.writeInt(value.profileState.ordinal); policy(out, value.faceDetector); policy(out, value.meteringPolicy)
        out.writeDouble(value.appliedEv)
        val g = value.geometry
        out.writeInt(g.sourceWidth); out.writeInt(g.sourceHeight); out.writeInt(g.sourceOrientation)
        out.writeInt(g.sampledWidth); out.writeInt(g.sampledHeight); out.writeBoolean(g.fullCoverage)
    }
    private fun observation(input: DataInputStream): OriginalRenderObservation {
        val binding = OriginalAttemptBinding(text(input, 64), input.readLong(), text(input, 32), fingerprint(input), text(input, 64), text(input, 64))
        val inputPolicy = policy(input); val geometryPolicy = policy(input); val profile = policy(input)
        val state = AutoInputProfile.entries.getOrNull(input.readInt()) ?: throw IOException("Unknown original profile state")
        val detector = policy(input); val metering = policy(input); val ev = input.readDouble()
        val geometry = OriginalRasterGeometry(input.readInt(), input.readInt(), input.readInt(), input.readInt(), input.readInt(), flag(input))
        return OriginalRenderObservation(binding, inputPolicy, geometryPolicy, profile, state, detector, metering, ev, geometry)
    }
    private fun policy(out: DataOutputStream, value: RenderPolicyVersion) { out.writeUTF(value.id); out.writeInt(value.revision) }
    private fun policy(input: DataInputStream) = RenderPolicyVersion(text(input, 64), input.readInt())
    private fun fingerprint(out: DataOutputStream, value: OriginalFingerprint) { out.writeLong(value.bytes); out.writeUTF(value.sha256) }
    private fun fingerprint(input: DataInputStream) = OriginalFingerprint(input.readLong(), text(input, 64))
    private fun flag(input: DataInputStream) = when (input.readUnsignedByte()) { 0 -> false; 1 -> true; else -> throw IOException("Invalid original evidence flag") }
    private fun text(input: DataInputStream, maxBytes: Int) = input.readUTF().also {
        if (it.toByteArray(Charsets.UTF_8).size !in 1..maxBytes) throw IOException("Invalid original evidence field length")
    }
}
