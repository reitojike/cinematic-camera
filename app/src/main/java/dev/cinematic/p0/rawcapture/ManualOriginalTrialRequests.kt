package dev.cinematic.p0.rawcapture

import dev.cinematic.p0.jpegrender.original.Original24Request
import dev.cinematic.p0.raw.PhotoAdjustments
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.MessageDigest

/** One retained metadata authority per source, called on the existing queue IO owner.
 * Storage belongs to the separate manual-original-trial root, never auto-develop inventory.
 * Generation admission must be serialized with publisher admission by the caller. */
internal class ManualOriginalTrialRequests(
    private val source: SharedOriginalReference,
    private val storage: AtomicJournalStorage,
    private val generationFence: (Original24Request) -> Boolean,
) {
    val captureId = identity(source)
    // A replace can throw after committing. Do not allocate past that uncertainty in this process.
    private var unconfirmed: ByteArray? = null

    @Synchronized fun prepare(strength: Double, adjustments: PhotoAdjustments = PhotoAdjustments.NONE): Original24Request {
        require(strength.isFinite() && strength in 0.0..1.0)
        val canonicalStrength = if (strength == 0.0) 0.0 else strength
        val canonicalAdjustments = PhotoAdjustments(if (adjustments.warmth == 0.0) 0.0 else adjustments.warmth,
            if (adjustments.exposureEv == 0.0) 0.0 else adjustments.exposureEv)
        val bytes = storage.read(ManualOriginalTrialRequestCodec.MAX_BYTES)
        unconfirmed?.let { expected ->
            if (bytes?.contentEquals(expected) != true) throw IOException("Manual trial request commit remains unconfirmed")
            unconfirmed = null
        }
        val previous = bytes?.let(ManualOriginalTrialRequestCodec::decode)?.also(::requireSource)
        if (previous != null && previous.strength == canonicalStrength && previous.adjustments == canonicalAdjustments) return previous
        if (previous != null && !generationFence(previous)) throw IOException("Previous manual trial generation is held")
        val generation = previous?.generation ?: 0
        if (generation == Long.MAX_VALUE) throw IOException("Manual trial generation exhausted")
        val request = Original24Request(captureId, generation + 1, source, canonicalStrength, canonicalAdjustments)
        val encoded = ManualOriginalTrialRequestCodec.encode(request)
        unconfirmed = encoded
        storage.replace(encoded)
        if (storage.read(ManualOriginalTrialRequestCodec.MAX_BYTES)?.contentEquals(encoded) != true)
            throw IOException("Manual trial request commit unconfirmed")
        unconfirmed = null
        return request
    }

    @Synchronized fun isCurrent(request: Original24Request): Boolean {
        if (unconfirmed != null || request.captureId != captureId || request.source != source) return false
        return storage.read(ManualOriginalTrialRequestCodec.MAX_BYTES)?.let(ManualOriginalTrialRequestCodec::decode)
            ?.also(::requireSource) == request
    }

    /** Metadata-only cold open. Never allocates a generation or repairs an uncertain commit. */
    @Synchronized fun current(): Original24Request? {
        if (unconfirmed != null) throw IOException("Manual trial request commit remains unconfirmed")
        return storage.read(ManualOriginalTrialRequestCodec.MAX_BYTES)?.let(ManualOriginalTrialRequestCodec::decode)
            ?.also(::requireSource)
    }

    private fun requireSource(request: Original24Request) {
        if (request.source != source || request.captureId != captureId) throw CaptureJournalConflict()
    }

    companion object {
        fun identity(source: SharedOriginalReference): String {
            val bytes = ByteArrayOutputStream().also { buffer ->
                DataOutputStream(buffer).use {
                    it.writeUTF(source.uri); it.writeLong(source.fingerprint.bytes)
                    it.writeUTF(source.fingerprint.sha256); it.writeUTF(source.grant.name)
                }
            }.toByteArray()
            return "TRIAL24_" + MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 255) }.take(56)
        }
    }
}

/** Fixed source, generation, strength, all adjustments and canonical recipe; no media bytes or inferred completion. */
internal object ManualOriginalTrialRequestCodec {
    const val MAX_BYTES = 4096
    private const val MAGIC = 0x43434d54 // CCMT, separate from auto-develop journals.
    private const val VERSION = 2

    fun encode(request: Original24Request): ByteArray {
        require(request.captureId == ManualOriginalTrialRequests.identity(request.source))
        require(request.strength.toRawBits() != (-0.0).toRawBits())
        require(request.adjustments.warmth.toRawBits() != (-0.0).toRawBits() &&
            request.adjustments.exposureEv.toRawBits() != (-0.0).toRawBits())
        val payload = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use {
                it.writeInt(MAGIC); it.writeInt(VERSION)
                it.writeUTF(request.captureId); it.writeLong(request.generation)
                it.writeUTF(request.source.uri); it.writeUTF(request.source.grant.name)
                it.writeLong(request.source.fingerprint.bytes); it.writeUTF(request.source.fingerprint.sha256)
                it.writeDouble(request.strength); it.writeDouble(request.adjustments.warmth); it.writeDouble(request.adjustments.exposureEv)
                it.writeUTF(request.recipe.schema); it.writeUTF(request.recipe.payload)
            }
        }.toByteArray()
        return (payload + digest(payload)).also { if (it.size > MAX_BYTES) throw IOException("Manual trial request too large") }
    }

    fun decode(encoded: ByteArray): Original24Request {
        if (encoded.size !in 33..MAX_BYTES) throw IOException("Invalid manual trial request length")
        val payload = encoded.copyOfRange(0, encoded.size - 32)
        if (!MessageDigest.isEqual(digest(payload), encoded.copyOfRange(payload.size, encoded.size)))
            throw IOException("Manual trial request checksum mismatch")
        try {
            return DataInputStream(ByteArrayInputStream(payload)).use {
                if (it.readInt() != MAGIC || it.readInt() != VERSION) throw IOException("Unknown manual trial request schema")
                val id = text(it, 64); val generation = it.readLong()
                val uri = text(it, 1024); val grant = OriginalGrant.valueOf(text(it, 32))
                val source = SharedOriginalReference(uri, OriginalFingerprint(it.readLong(), text(it, 64)), grant)
                val strength = it.readDouble()
                val request = Original24Request(id, generation, source, strength, PhotoAdjustments(it.readDouble(), it.readDouble()))
                val recipe = RenderRecipeSnapshot(text(it, 64), text(it, 1024))
                if (it.available() != 0 || recipe != request.recipe || !encode(request).contentEquals(encoded))
                    throw IOException("Noncanonical manual trial request")
                request
            }
        } catch (e: IllegalArgumentException) { throw IOException("Invalid manual trial request fields", e) }
    }

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun text(input: DataInputStream, max: Int) = input.readUTF().also {
        if (it.toByteArray(Charsets.UTF_8).size !in 1..max) throw IOException("Invalid manual trial field length")
    }
}
