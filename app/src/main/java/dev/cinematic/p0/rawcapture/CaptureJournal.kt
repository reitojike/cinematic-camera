package dev.cinematic.p0.rawcapture

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Collections

internal enum class RawOutput(val fileName: String, val mask: Int) {
    DNG("original.dng", 1), JPEG("provisional.jpg", 2),
}

internal enum class CaptureReceiptPhase(val code: Int) {
    CAPTURING(0), INTERRUPTED(1), FILES_RECEIVED(2),
}

/** Receipt metadata only: these sizes do not establish DNG decode or shared-copy integrity. */
internal class CaptureReceipt(
    val captureId: String,
    val revision: Long,
    expectedFormats: Set<RawOutput>,
    receivedBytes: Map<RawOutput, Long>,
    val phase: CaptureReceiptPhase,
    val originalFingerprint: OriginalFingerprint? = null,
    val developIntent: CaptureDevelopIntent? = null,
) {
    val expectedFormats: Set<RawOutput> = Collections.unmodifiableSet(expectedFormats.toSet())
    val receivedBytes: Map<RawOutput, Long> = Collections.unmodifiableMap(receivedBytes.toMap())

    init {
        requireCaptureId(captureId)
        require(revision >= 0) { "Negative receipt revision" }
        require(RawOutput.DNG in this.expectedFormats) { "RAW receipt requires DNG" }
        require(this.receivedBytes.keys.all { it in this.expectedFormats }) { "Unexpected receipt format" }
        require(this.receivedBytes.values.all { it > 0 }) { "Empty receipt output" }
        val allReceived = this.receivedBytes.keys == this.expectedFormats
        require(phase != CaptureReceiptPhase.FILES_RECEIVED || allReceived) { "Incomplete receipt" }
        require(phase != CaptureReceiptPhase.CAPTURING || !allReceived) { "Invalid capture phase" }
        require(originalFingerprint == null || this.receivedBytes[RawOutput.DNG] == originalFingerprint.bytes) {
            "Original fingerprint requires matching DNG receipt"
        }
    }

    fun relativePath(output: RawOutput): String {
        require(output in expectedFormats) { "Unexpected receipt format" }
        return "$captureId/${output.fileName}"
    }
}

internal fun requireCaptureId(id: String) {
    require(id.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "Invalid capture ID" }
}

/** Atomic replacement, not deletion. A thrown write can have committed; callers must re-read. */
internal interface AtomicJournalStorage {
    /** Null only for positively established absence. Read errors must throw. */
    fun read(maxBytes: Int): ByteArray?
    fun replace(contents: ByteArray)
}

internal class CaptureJournalConflict : IOException("Stale or conflicting capture receipt")

/**
 * One process-scoped instance/writer per capture; never construct competing live writers.
 * Calls perform metadata IO and belong on the capture-priority IO executor, not the UI thread.
 * No media files are opened, inferred complete, published, or deleted by this class.
 */
internal class CaptureJournal(private val captureId: String, private val storage: AtomicJournalStorage) {
    init { requireCaptureId(captureId) }

    @Synchronized
    fun read(): CaptureReceipt? {
        val bytes = storage.read(CaptureReceiptCodec.MAX_BYTES) ?: return null
        val receipt = CaptureReceiptCodec.decode(bytes)
        if (receipt.captureId != captureId) throw IOException("Capture journal owner mismatch")
        return receipt
    }

    @Synchronized
    fun create(expected: Set<RawOutput>, developIntent: CaptureDevelopIntent? = null): CaptureReceipt {
        if (read() != null) throw CaptureJournalConflict()
        return persist(CaptureReceipt(captureId, 0, expected, emptyMap(), CaptureReceiptPhase.CAPTURING,
            developIntent = developIntent))
    }

    /** Caller must have confirmed this format's successful callback and file close. */
    @Synchronized
    fun received(expectedRevision: Long, output: RawOutput, bytes: Long): CaptureReceipt {
        val current = current(expectedRevision)
        require(output in current.expectedFormats && bytes > 0) { "Invalid receipt output" }
        current.receivedBytes[output]?.let {
            if (it != bytes) throw CaptureJournalConflict()
            return current
        }
        val received = current.receivedBytes + (output to bytes)
        val phase = if (current.phase == CaptureReceiptPhase.CAPTURING && received.keys == current.expectedFormats) {
            CaptureReceiptPhase.FILES_RECEIVED
        } else current.phase // Late callbacks can preserve receipts without hiding interruption.
        return persist(CaptureReceipt(captureId, next(current), current.expectedFormats, received, phase, current.originalFingerprint,
            current.developIntent))
    }

    /** Caller has read and successfully closed the received DNG; never replaces an existing identity. */
    @Synchronized
    fun recordOriginalFingerprint(expectedRevision: Long, fingerprint: OriginalFingerprint): CaptureReceipt {
        val current = current(expectedRevision)
        require(current.receivedBytes[RawOutput.DNG] == fingerprint.bytes) { "Original size does not match DNG receipt" }
        current.originalFingerprint?.let {
            if (it != fingerprint) throw CaptureJournalConflict()
            return current
        }
        return persist(CaptureReceipt(
            captureId, next(current), current.expectedFormats, current.receivedBytes, current.phase, fingerprint,
            current.developIntent,
        ))
    }

    @Synchronized
    fun interrupt(expectedRevision: Long): CaptureReceipt {
        val current = current(expectedRevision)
        return interrupt(current)
    }

    /** Boot recovery only, after the previous process's writers are known to be gone. */
    @Synchronized
    fun recoverAfterProcessDeath(): CaptureReceipt? = read()?.let { interrupt(it) }

    private fun current(expectedRevision: Long): CaptureReceipt {
        val current = read() ?: throw CaptureJournalConflict()
        if (current.revision != expectedRevision) throw CaptureJournalConflict()
        return current
    }

    private fun interrupt(current: CaptureReceipt): CaptureReceipt {
        if (current.phase != CaptureReceiptPhase.CAPTURING) return current
        return persist(CaptureReceipt(
            captureId, next(current), current.expectedFormats, current.receivedBytes, CaptureReceiptPhase.INTERRUPTED,
            current.originalFingerprint,
            current.developIntent,
        ))
    }

    private fun next(current: CaptureReceipt): Long {
        if (current.revision == Long.MAX_VALUE) throw IOException("Capture receipt revision exhausted")
        return current.revision + 1
    }

    private fun persist(receipt: CaptureReceipt): CaptureReceipt {
        val encoded = CaptureReceiptCodec.encode(receipt)
        storage.replace(encoded)
        // Do not trust a return from the storage API or roll back a possibly committed write.
        val observed = storage.read(CaptureReceiptCodec.MAX_BYTES)
        if (observed == null || !observed.contentEquals(encoded)) {
            throw IOException("Capture journal commit unconfirmed")
        }
        return receipt
    }
}

/** Versioned, bounded receipt envelope. Digest detects damage, not malicious tampering. */
internal object CaptureReceiptCodec {
    const val MAX_BYTES = 4096
    private const val MAGIC = 0x4343524a // CCRJ
    private const val VERSION = 3
    private const val DIGEST_BYTES = 32

    fun encode(receipt: CaptureReceipt): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeByte(receipt.captureId.length)
            out.write(receipt.captureId.toByteArray(Charsets.US_ASCII))
            out.writeLong(receipt.revision)
            out.writeByte(receipt.expectedFormats.fold(0) { mask, format -> mask or format.mask })
            out.writeByte(receipt.phase.code)
            out.writeLong(receipt.receivedBytes[RawOutput.DNG] ?: -1L)
            out.writeLong(receipt.receivedBytes[RawOutput.JPEG] ?: -1L)
            out.writeByte(if (receipt.originalFingerprint == null) 0 else 1)
            receipt.originalFingerprint?.sha256?.chunked(2)?.forEach { out.writeByte(it.toInt(16)) }
            out.writeByte(if (receipt.developIntent == null) 0 else 1)
            receipt.developIntent?.let { intent ->
                out.writeByte(0) // Explicit, stable code for requested FINAL_ORIGINAL; not an observed quality.
                out.writeUTF(intent.rendererContract)
                out.writeUTF(intent.recipe.schema)
                out.writeUTF(intent.recipe.payload)
            }
        }
        val payload = buffer.toByteArray()
        return (payload + MessageDigest.getInstance("SHA-256").digest(payload)).also {
            if (it.size > MAX_BYTES) throw IOException("Capture journal too large")
        }
    }

    fun decode(encoded: ByteArray): CaptureReceipt {
        if (encoded.size !in (DIGEST_BYTES + 1)..MAX_BYTES) throw IOException("Invalid capture journal length")
        val payload = encoded.copyOfRange(0, encoded.size - DIGEST_BYTES)
        val expected = encoded.copyOfRange(payload.size, encoded.size)
        if (!MessageDigest.isEqual(expected, MessageDigest.getInstance("SHA-256").digest(payload))) {
            throw IOException("Capture journal checksum mismatch")
        }
        try {
            return DataInputStream(ByteArrayInputStream(payload)).use { input ->
                if (input.readInt() != MAGIC) throw IOException("Invalid capture journal magic")
                val version = input.readInt()
                if (version !in 1..VERSION) throw IOException("Unsupported capture journal version")
                val idSize = input.readUnsignedByte()
                if (idSize !in 1..64) throw IOException("Invalid capture ID length")
                val idBytes = ByteArray(idSize).also { input.readFully(it) }
                val id = idBytes.toString(Charsets.US_ASCII)
                val revision = input.readLong()
                val mask = input.readUnsignedByte()
                if (mask != 1 && mask != 3) throw IOException("Invalid capture output mask")
                val code = input.readUnsignedByte()
                val phase = CaptureReceiptPhase.entries.singleOrNull { it.code == code }
                    ?: throw IOException("Invalid capture receipt phase")
                val expectedFormats = RawOutput.entries.filter { mask and it.mask != 0 }.toSet()
                val received = buildMap {
                    RawOutput.entries.forEach { output ->
                        val size = input.readLong()
                        if (size != -1L) put(output, size)
                    }
                }
                val fingerprint = if (version == 1) null else when (input.readUnsignedByte()) {
                    0 -> null
                    1 -> {
                        val digest = ByteArray(DIGEST_BYTES).also { input.readFully(it) }
                        OriginalFingerprint(
                            received[RawOutput.DNG] ?: throw IOException("Original fingerprint without DNG receipt"),
                            digest.joinToString("") { "%02x".format(it) },
                        )
                    }
                    else -> throw IOException("Invalid original fingerprint flag")
                }
                val intent = if (version < 3) null else when (input.readUnsignedByte()) {
                    0 -> null
                    1 -> {
                        if (input.readUnsignedByte() != 0) throw IOException("Unknown requested capture quality")
                        val renderer = input.readUTF()
                        CaptureDevelopIntent(RenderRecipeSnapshot(input.readUTF(), input.readUTF()), renderer)
                    }
                    else -> throw IOException("Invalid capture develop intent flag")
                }
                if (input.available() != 0) throw IOException("Trailing capture receipt data")
                CaptureReceipt(id, revision, expectedFormats, received, phase, fingerprint, intent)
            }
        } catch (e: IllegalArgumentException) {
            throw IOException("Invalid capture receipt fields", e)
        }
    }
}
