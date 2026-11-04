package dev.cinematic.p0.rawcapture

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

internal enum class DngPublicationPhase(val code: Int) {
    PREPARED(0), INSERTING(1), ROW_RECORDED(2), UNKNOWN(3), COPYING(4), BYTES_COMPLETE(5),
    PUBLISHING(6), PUBLISHED(7), SHARED_READY(8), LOCAL_RELEASE_PENDING(9), SHARED_ONLY(10),
}

/** Version-1 target is immutable, including metadata persisted before insert. */
internal data class DngPublicationTarget(val captureId: String, val token: String) {
    init {
        requireCaptureId(captureId)
        require(token.matches(Regex("[0-9a-f]{32}"))) { "Invalid publication token" }
    }
    val operationId get() = "$captureId:ORIGINAL"
    val volume get() = "external_primary"
    val relativePath get() = "Pictures/Cinematic Camera/"
    val mimeType get() = "image/x-adobe-dng"
    val displayName get() = "CINEMATIC_${captureId}_$token.dng"

    fun accepts(row: DngPendingRow, pending: Boolean = true): Boolean = isDngRowUri(row.uri) && row.ownedByApp &&
        row.pending == pending && row.trashed == false && row.displayName == displayName && row.volume == volume &&
        row.relativePath == relativePath && row.mimeType == mimeType
}

internal fun isDngRowUri(uri: String): Boolean {
    val match = Regex("content://media/external_primary/images/media/([1-9][0-9]{0,18})").matchEntire(uri) ?: return false
    return match.groupValues[1].toLongOrNull() != null
}

internal data class DngPublicationRecord(
    val target: DngPublicationTarget,
    val revision: Long,
    val fingerprint: OriginalFingerprint,
    val phase: DngPublicationPhase,
    val uri: String? = null,
) {
    init {
        require(revision >= 0) { "Negative publication revision" }
        require(uri == null || isDngRowUri(uri)) { "Invalid DNG row URI" }
        require(phase !in DngPublicationPhase.entries.filter { it == DngPublicationPhase.ROW_RECORDED || it.code >= 4 } || uri != null) { "Missing recorded URI" }
        require(phase !in setOf(DngPublicationPhase.PREPARED, DngPublicationPhase.INSERTING) || uri == null) { "Premature URI" }
    }
}

/** One live journal/coordinator per ORIGINAL operation. Metadata IO only, on an IO executor. */
internal class DngPublicationJournal(
    private val captureId: String,
    private val storage: AtomicJournalStorage,
    private val tokenFactory: () -> String = { UUID.randomUUID().toString().replace("-", "") },
) {
    init { requireCaptureId(captureId) }

    @Synchronized
    fun read(): DngPublicationRecord? {
        val bytes = storage.read(DngPublicationCodec.MAX_BYTES) ?: return null
        return DngPublicationCodec.decode(bytes).also {
            if (it.target.captureId != captureId) throw IOException("Publication journal owner mismatch")
        }
    }

    /** Caller supplies the received DNG's committed fingerprint; no source/media bytes are inferred. */
    @Synchronized
    fun prepare(receipt: CaptureReceipt): DngPublicationRecord {
        require(receipt.captureId == captureId) { "Foreign capture receipt" }
        val fingerprint = requireNotNull(receipt.originalFingerprint) { "Original has not been verified" }
        read()?.let {
            if (it.fingerprint != fingerprint) throw CaptureJournalConflict()
            return it // Never regenerate an existing token or reset UNKNOWN.
        }
        return persist(DngPublicationRecord(DngPublicationTarget(captureId, tokenFactory()), 0, fingerprint, DngPublicationPhase.PREPARED))
    }

    @Synchronized
    fun markInserting(expectedRevision: Long): DngPublicationRecord {
        val current = current(expectedRevision)
        if (current.phase != DngPublicationPhase.PREPARED) throw CaptureJournalConflict()
        return persist(current.copy(revision = next(current), phase = DngPublicationPhase.INSERTING))
    }

    @Synchronized
    fun recordRow(expectedRevision: Long, uri: String): DngPublicationRecord {
        require(isDngRowUri(uri)) { "Invalid DNG row URI" }
        val current = current(expectedRevision)
        if (current.phase == DngPublicationPhase.ROW_RECORDED && current.uri == uri) return current
        if (current.phase != DngPublicationPhase.INSERTING) throw CaptureJournalConflict()
        return persist(current.copy(revision = next(current), phase = DngPublicationPhase.ROW_RECORDED, uri = uri))
    }

    @Synchronized
    fun markUnknown(expectedRevision: Long, uri: String?): DngPublicationRecord {
        val current = current(expectedRevision)
        if (current.uri != null && uri != null && current.uri != uri) throw CaptureJournalConflict()
        val retainedUri = current.uri ?: uri
        if (current.phase == DngPublicationPhase.UNKNOWN && current.uri == retainedUri) return current
        if (current.phase !in setOf(DngPublicationPhase.INSERTING, DngPublicationPhase.ROW_RECORDED,
                DngPublicationPhase.COPYING, DngPublicationPhase.BYTES_COMPLETE, DngPublicationPhase.PUBLISHING)) throw CaptureJournalConflict()
        return persist(current.copy(revision = next(current), phase = DngPublicationPhase.UNKNOWN, uri = retainedUri))
    }

    @Synchronized fun markCopying(revision: Long) = advance(revision, DngPublicationPhase.ROW_RECORDED, DngPublicationPhase.COPYING)
    @Synchronized fun markBytesComplete(revision: Long) = advance(revision, DngPublicationPhase.COPYING, DngPublicationPhase.BYTES_COMPLETE)
    @Synchronized fun markPublishing(revision: Long) = advance(revision, DngPublicationPhase.BYTES_COMPLETE, DngPublicationPhase.PUBLISHING)
    @Synchronized fun markPublished(revision: Long) = advance(revision, DngPublicationPhase.PUBLISHING, DngPublicationPhase.PUBLISHED)
    @Synchronized fun markSharedReady(revision: Long) = advance(revision, DngPublicationPhase.PUBLISHED, DngPublicationPhase.SHARED_READY)
    @Synchronized fun markLocalReleasePending(revision: Long) = advance(revision, DngPublicationPhase.SHARED_READY, DngPublicationPhase.LOCAL_RELEASE_PENDING)
    @Synchronized fun markSharedOnly(revision: Long) = advance(revision, DngPublicationPhase.LOCAL_RELEASE_PENDING, DngPublicationPhase.SHARED_ONLY)

    private fun advance(revision: Long, from: DngPublicationPhase, to: DngPublicationPhase): DngPublicationRecord {
        val current = current(revision)
        if (current.phase != from) throw CaptureJournalConflict()
        return persist(current.copy(revision = next(current), phase = to))
    }

    private fun current(expectedRevision: Long): DngPublicationRecord {
        val current = read() ?: throw CaptureJournalConflict()
        if (current.revision != expectedRevision) throw CaptureJournalConflict()
        return current
    }

    private fun next(current: DngPublicationRecord): Long {
        if (current.revision == Long.MAX_VALUE) throw IOException("Publication revision exhausted")
        return current.revision + 1
    }

    private fun persist(record: DngPublicationRecord): DngPublicationRecord {
        val encoded = DngPublicationCodec.encode(record)
        storage.replace(encoded) // A thrown write may already have committed. Never roll back.
        val observed = storage.read(DngPublicationCodec.MAX_BYTES)
        if (observed == null || !observed.contentEquals(encoded)) throw IOException("Publication commit unconfirmed")
        return record
    }
}

internal object DngPublicationCodec {
    const val MAX_BYTES = 4096
    private const val MAGIC = 0x4343504a // CCPJ
    private const val VERSION = 2
    private const val DIGEST_BYTES = 32

    fun encode(record: DngPublicationRecord): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            text(out, record.target.captureId)
            text(out, record.target.token)
            out.writeLong(record.revision)
            out.writeLong(record.fingerprint.bytes)
            record.fingerprint.sha256.chunked(2).forEach { out.writeByte(it.toInt(16)) }
            text(out, record.target.volume)
            text(out, record.target.relativePath)
            text(out, record.target.mimeType)
            text(out, record.target.displayName)
            out.writeByte(record.phase.code)
            out.writeByte(if (record.uri == null) 0 else 1)
            record.uri?.let { text(out, it) }
        }
        val payload = buffer.toByteArray()
        return payload + MessageDigest.getInstance("SHA-256").digest(payload)
    }

    fun decode(encoded: ByteArray): DngPublicationRecord {
        if (encoded.size !in (DIGEST_BYTES + 1)..MAX_BYTES) throw IOException("Invalid publication journal length")
        val payload = encoded.copyOfRange(0, encoded.size - DIGEST_BYTES)
        if (!MessageDigest.isEqual(encoded.copyOfRange(payload.size, encoded.size), MessageDigest.getInstance("SHA-256").digest(payload))) {
            throw IOException("Publication journal checksum mismatch")
        }
        try {
            return DataInputStream(ByteArrayInputStream(payload)).use { input ->
                if (input.readInt() != MAGIC) throw IOException("Unknown publication journal format")
                val version = input.readInt()
                if (version !in 1..VERSION) throw IOException("Unknown publication journal format")
                val target = DngPublicationTarget(text(input, 64), text(input, 32))
                val revision = input.readLong()
                val bytes = input.readLong()
                val digest = ByteArray(DIGEST_BYTES).also { input.readFully(it) }
                val fingerprint = OriginalFingerprint(bytes, digest.joinToString("") { "%02x".format(it) })
                val metadata = listOf(text(input, 32), text(input, 128), text(input, 64), text(input, 128))
                if (metadata != listOf(target.volume, target.relativePath, target.mimeType, target.displayName)) {
                    throw IOException("Publication target metadata mismatch")
                }
                val code = input.readUnsignedByte()
                if (version == 1 && code > 3) throw IOException("Unknown version-one publication phase")
                val phase = DngPublicationPhase.entries.singleOrNull { it.code == code } ?: throw IOException("Unknown publication phase")
                val uri = when (input.readUnsignedByte()) {
                    0 -> null
                    1 -> text(input, 128)
                    else -> throw IOException("Invalid publication URI flag")
                }
                if (input.available() != 0) throw IOException("Trailing publication journal data")
                DngPublicationRecord(target, revision, fingerprint, phase, uri)
            }
        } catch (e: IllegalArgumentException) { throw IOException("Invalid publication journal fields", e) }
    }

    private fun text(out: DataOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.US_ASCII)
        out.writeShort(bytes.size)
        out.write(bytes)
    }

    private fun text(input: DataInputStream, maxBytes: Int): String {
        val size = input.readUnsignedShort()
        if (size !in 1..maxBytes) throw IOException("Invalid publication field length")
        return ByteArray(size).also { input.readFully(it) }.toString(Charsets.US_ASCII)
    }
}
