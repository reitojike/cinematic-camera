package dev.cinematic.p0.rawcapture

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.MessageDigest

/** Metadata allocator only, called on the existing queue IO owner. An ID is never a URI grant.
 * Current recipe selection is stored separately from each immutable generation/attempt journal. */
internal class ManualDevelopRequests(private val source: SharedOriginalReference, private val storage: AtomicJournalStorage) {
    val captureId = identity(source)
    fun prepare(recipe: RenderRecipeSnapshot): AutoDevelopRequest {
        val trial = recipe.schema == "stage-clear-private-no-face-trial-v1"
        require(trial || recipe.schema == "stage-clear-bounded-jpeg-v1")
        val previous = storage.read(AutoDevelopCodec.MAX_BYTES)?.let(AutoDevelopCodec::decode)
        if (previous != null && (previous.request.source != source || previous.request.captureId != captureId ||
                !previous.request.interactive || previous.phase != AutoJobPhase.QUEUED)) throw CaptureJournalConflict()
        if (previous?.request?.recipe == recipe) return previous.request
        val generation = previous?.request?.generation ?: 0
        if (generation == Long.MAX_VALUE) throw IOException("Manual generation exhausted")
        val request = AutoDevelopRequest(captureId, generation + 1, source, recipe, recipe.schema, 1024, 1024,
            if (trial) AutoRenderQuality.PRIVATE_TRIAL_1024 else AutoRenderQuality.MANUAL_BOUNDED_1024_PRELIMINARY,
            RenderedFaceState.NOT_RUN, if (trial) RenderedExposureState.DIAGNOSTIC else RenderedExposureState.UNKNOWN, AutoInputProfile.UNKNOWN)
        val bytes = AutoDevelopCodec.encode(AutoDevelopRecord(request))
        storage.replace(bytes)
        if (storage.read(AutoDevelopCodec.MAX_BYTES)?.contentEquals(bytes) != true) throw IOException("Manual request commit unconfirmed")
        return request
    }
    companion object {
        fun identity(source: SharedOriginalReference): String {
            val buffer = ByteArrayOutputStream()
            DataOutputStream(buffer).use { out ->
                out.writeUTF(source.uri); out.writeLong(source.fingerprint.bytes)
                out.writeUTF(source.fingerprint.sha256); out.writeUTF(source.grant.name)
            }
            return "manual_" + MessageDigest.getInstance("SHA-256").digest(buffer.toByteArray())
                .joinToString("") { "%02x".format(it) }.take(56)
        }
    }
}
