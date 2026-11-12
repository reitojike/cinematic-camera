package dev.cinematic.p0.rawcapture

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.MessageDigest

/** Unconnected import contract. Copy integrity never implies decoder compatibility. */
internal enum class DngImportPhase { PREPARED, INSERTING, COPYING, VERIFIED, PUBLISHING, READY, CANCELLED, DISCARDING }
internal enum class DngImportGrant { SESSION_ONLY, ALREADY_HELD, ACQUIRED, UNAVAILABLE }

internal data class DngImportTarget(val operationId: String, val token: String) {
    init {
        require(operationId.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        require(token.matches(Regex("[0-9a-f]{32}")))
    }
    val name get() = "CINEMATIC_IMPORT_${token}.dng"
    val path get() = "Pictures/Cinematic Camera/"
    val mime get() = "image/x-adobe-dng"
    fun accepts(row: DngPendingRow, pending: Boolean) = isDngRowUri(row.uri) && row.ownedByApp &&
        row.pending == pending && row.trashed == false && row.displayName == name &&
        row.relativePath == path && row.mimeType == mime && row.volume == "external_primary"
}

internal data class DngImportRecord(
    val target: DngImportTarget,
    val sourceUri: String,
    val fingerprint: OriginalFingerprint,
    val grant: DngImportGrant,
    val revision: Long = 0,
    val phase: DngImportPhase = DngImportPhase.PREPARED,
    val uri: String? = null,
    val stopRequested: Boolean = false,
) {
    init {
        require(sourceUri.startsWith("content://") && sourceUri.length > 10 && '\u0000' !in sourceUri)
        require(fingerprint.bytes in 1..MAX_SOURCE_BYTES)
        require(revision >= 0)
        require(uri == null || isDngRowUri(uri))
        require(phase !in setOf(DngImportPhase.COPYING, DngImportPhase.VERIFIED,
            DngImportPhase.PUBLISHING, DngImportPhase.READY, DngImportPhase.DISCARDING) || uri != null)
        require(phase != DngImportPhase.PREPARED || uri == null)
        require(phase !in setOf(DngImportPhase.CANCELLED, DngImportPhase.DISCARDING) || stopRequested)
    }
    companion object { const val MAX_SOURCE_BYTES = 64L * 1024 * 1024 }
}

/** One process owner per journal. A failed commit may have persisted; reread before any effect. */
internal class DngImportJournal(private val operationId: String, private val storage: AtomicJournalStorage) {
    @Synchronized fun read(): DngImportRecord? = storage.read(DngImportCodec.MAX_BYTES)?.let {
        DngImportCodec.decode(it).also { record ->
            if (record.target.operationId != operationId) throw IOException("Import owner mismatch")
        }
    }

    @Synchronized fun prepare(record: DngImportRecord): DngImportRecord {
        require(record.target.operationId == operationId && record.phase == DngImportPhase.PREPARED &&
            record.revision == 0L && !record.stopRequested)
        read()?.let {
            if (it.target != record.target || it.sourceUri != record.sourceUri || it.fingerprint != record.fingerprint)
                throw IOException("Conflicting import")
            return it
        }
        return persist(record)
    }

    @Synchronized fun stop(expected: DngImportRecord): DngImportRecord = change(expected) { it.copy(stopRequested = true) }

    @Synchronized fun grant(expected: DngImportRecord, value: DngImportGrant): DngImportRecord =
        change(expected) { it.copy(grant = value) }

    /** Caller has read/closed the newly selected source. Copy still rechecks every byte. */
    @Synchronized fun reselect(expected: DngImportRecord, sourceUri: String,
        observed: OriginalFingerprint, grant: DngImportGrant): DngImportRecord = change(expected) {
        require(!it.stopRequested && it.phase in setOf(DngImportPhase.PREPARED, DngImportPhase.INSERTING, DngImportPhase.COPYING))
        require(observed == it.fingerprint) { "Different import original" }
        it.copy(sourceUri = sourceUri, grant = grant)
    }

    @Synchronized fun advance(expected: DngImportRecord, phase: DngImportPhase, uri: String? = expected.uri): DngImportRecord =
        change(expected) {
            val allowed = when (it.phase) {
                DngImportPhase.PREPARED -> setOf(DngImportPhase.INSERTING, DngImportPhase.CANCELLED)
                DngImportPhase.INSERTING -> setOf(DngImportPhase.COPYING, DngImportPhase.DISCARDING)
                DngImportPhase.COPYING -> setOf(DngImportPhase.VERIFIED, DngImportPhase.DISCARDING)
                DngImportPhase.VERIFIED -> setOf(DngImportPhase.PUBLISHING, DngImportPhase.DISCARDING)
                DngImportPhase.PUBLISHING -> setOf(DngImportPhase.READY, DngImportPhase.DISCARDING)
                DngImportPhase.DISCARDING -> setOf(DngImportPhase.CANCELLED)
                else -> emptySet()
            }
            require(phase in allowed)
            require(it.uri == null || uri == it.uri)
            require(!it.stopRequested || phase in setOf(DngImportPhase.CANCELLED, DngImportPhase.DISCARDING, DngImportPhase.READY))
            it.copy(phase = phase, uri = uri)
        }

    private fun change(expected: DngImportRecord, transform: (DngImportRecord) -> DngImportRecord): DngImportRecord {
        if (read() != expected || expected.revision == Long.MAX_VALUE) throw IOException("Stale import revision")
        return persist(transform(expected).copy(revision = expected.revision + 1))
    }

    private fun persist(record: DngImportRecord): DngImportRecord {
        val bytes = DngImportCodec.encode(record)
        storage.replace(bytes)
        if (storage.read(DngImportCodec.MAX_BYTES)?.contentEquals(bytes) != true) throw IOException("Import commit unconfirmed")
        return record
    }
}

/** Bounded metadata only; separate magic/version from capture receipts, UTF-8 through modified UTF. */
internal object DngImportCodec {
    const val MAX_BYTES = 4096
    private const val MAGIC = 0x4343494d
    fun encode(record: DngImportRecord): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.writeInt(MAGIC); out.writeInt(1)
            out.writeUTF(record.target.operationId); out.writeUTF(record.target.token); out.writeUTF(record.sourceUri)
            out.writeLong(record.fingerprint.bytes); out.writeUTF(record.fingerprint.sha256)
            out.writeInt(record.grant.ordinal); out.writeLong(record.revision); out.writeInt(record.phase.ordinal)
            out.writeUTF(record.uri ?: ""); out.writeBoolean(record.stopRequested)
        }
        val payload = buffer.toByteArray()
        require(payload.size + 32 <= MAX_BYTES) { "Import metadata too large" }
        return payload + MessageDigest.getInstance("SHA-256").digest(payload)
    }
    fun decode(bytes: ByteArray): DngImportRecord {
        if (bytes.size !in 33..MAX_BYTES) throw IOException("Invalid import length")
        val payload = bytes.copyOf(bytes.size - 32)
        if (!MessageDigest.isEqual(bytes.copyOfRange(payload.size, bytes.size), MessageDigest.getInstance("SHA-256").digest(payload)))
            throw IOException("Import checksum mismatch")
        try {
            return DataInputStream(ByteArrayInputStream(payload)).use { input ->
                if (input.readInt() != MAGIC || input.readInt() != 1) throw IOException("Unknown import format")
                val target = DngImportTarget(input.readUTF(), input.readUTF())
                val source = input.readUTF()
                val fingerprint = OriginalFingerprint(input.readLong(), input.readUTF())
                val grant = DngImportGrant.entries.getOrNull(input.readInt()) ?: throw IOException("Unknown import grant")
                val revision = input.readLong()
                val phase = DngImportPhase.entries.getOrNull(input.readInt()) ?: throw IOException("Unknown import phase")
                val uri = input.readUTF().ifEmpty { null }
                val stop = when (input.readUnsignedByte()) { 0 -> false; 1 -> true; else -> throw IOException("Invalid stop flag") }
                if (input.available() != 0) throw IOException("Trailing import bytes")
                DngImportRecord(target, source, fingerprint, grant, revision, phase, uri, stop)
            }
        } catch (e: IllegalArgumentException) { throw IOException("Invalid import fields", e) }
    }
}
