package dev.cinematic.p0.rawcapture

import dev.cinematic.p0.jpegrender.original.Original24Request
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.MessageDigest

/** A bounded, checksummed record; separate storage is supplied by the existing serial authority.
 * No storage paths, queue, migration or publication state machine are introduced here. */
internal object SaveRecoveryCodec {
    const val MAX_BYTES = 16384
    private const val MAGIC = 0x43435243 // CCRC
    fun observation(value: JpegReconciliationObservation): ByteArray = encode(1) {
        blob(JpegPublicationCodec.encode(value.previous))
        writeUTF(value.finding.name)
        writeBoolean(value.uri != null)
        value.uri?.let { require(isDngRowUri(it)); writeUTF(it) }
        require((value.finding in setOf(JpegReconciliationFinding.PUBLIC_BYTES_MATCH,
            JpegReconciliationFinding.PENDING_BYTES_MATCH, JpegReconciliationFinding.PENDING_BYTES_DIFFER,
            JpegReconciliationFinding.PUBLIC_BYTES_DIFFER)) == (value.uri != null))
        require(value.previous.uri == null || value.uri == null || value.previous.uri == value.uri)
    }
    fun readObservation(bytes: ByteArray): JpegReconciliationObservation = decode(bytes, 1) {
        val previous = JpegPublicationCodec.decode(blob(JpegPublicationCodec.MAX_BYTES))
        val finding = JpegReconciliationFinding.valueOf(readUTF())
        val uri = when (readUnsignedByte()) { 0 -> null; 1 -> readUTF(); else -> throw IOException("Invalid URI flag") }
        JpegReconciliationObservation(previous, finding, uri).also {
            if (!observation(it).contentEquals(bytes)) throw IOException("Noncanonical observation")
        }
    }
    fun export(value: ExplicitRecoveryExport): ByteArray = encode(2) {
        writeUTF(value.key)
        blob(ManualOriginalTrialRequestCodec.encode(value.previous))
        writeUTF(value.previousIntentSha256)
        writeBoolean(value.previousPublication != null)
        value.previousPublication?.let { blob(JpegPublicationCodec.encode(it)) }
        blob(ManualOriginalTrialRequestCodec.encode(value.request))
        writeUTF(value.rendererContract)
    }
    fun readExport(bytes: ByteArray): ExplicitRecoveryExport = decode(bytes, 2) {
        val key = readUTF()
        val previous = ManualOriginalTrialRequestCodec.decode(blob(ManualOriginalTrialRequestCodec.MAX_BYTES))
        val intentHash = readUTF()
        val publication = when (readUnsignedByte()) {
            0 -> null
            1 -> JpegPublicationCodec.decode(blob(JpegPublicationCodec.MAX_BYTES))
            else -> throw IOException("Invalid publication flag")
        }
        ExplicitRecoveryExport(key, previous, intentHash, publication,
            ManualOriginalTrialRequestCodec.decode(blob(ManualOriginalTrialRequestCodec.MAX_BYTES)), readUTF()).also {
            if (!export(it).contentEquals(bytes)) throw IOException("Noncanonical export")
        }
    }
    private fun encode(kind: Int, write: DataOutputStream.() -> Unit): ByteArray {
        val payload = ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use {
            it.writeInt(MAGIC); it.writeInt(1); it.writeInt(kind); it.write()
        } }.toByteArray()
        return (payload + digest(payload)).also { if (it.size > MAX_BYTES) throw IOException("Recovery record too large") }
    }
    private fun <T> decode(bytes: ByteArray, kind: Int, readBody: DataInputStream.() -> T): T {
        if (bytes.size !in 45..MAX_BYTES) throw IOException("Invalid recovery record size")
        val payload = bytes.copyOfRange(0, bytes.size - 32)
        if (!MessageDigest.isEqual(digest(payload), bytes.copyOfRange(payload.size, bytes.size)))
            throw IOException("Recovery checksum mismatch")
        try {
            return DataInputStream(ByteArrayInputStream(payload)).use {
                if (it.readInt() != MAGIC || it.readInt() != 1 || it.readInt() != kind) throw IOException("Unknown recovery schema")
                val value = readBody(it)
                if (it.available() != 0) throw IOException("Trailing recovery data")
                value
            }
        } catch (error: IllegalArgumentException) { throw IOException("Invalid recovery fields", error) }
    }
    private fun DataOutputStream.blob(bytes: ByteArray) { writeInt(bytes.size); write(bytes) }
    private fun DataInputStream.blob(max: Int): ByteArray {
        val length = readInt()
        if (length !in 1..max || length > available()) throw IOException("Invalid nested record size")
        return ByteArray(length).also(::readFully)
    }
    fun digest(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}

/** Immutable consent record, not STARTED/completed/cancelled evidence. One record per predecessor.
 * previousIntentSha256 identifies the exact old bytes, including legacy bytes retained by caller.
 * request is a distinct generation of the SAME source, never a copied source URI.
 */
internal data class ExplicitRecoveryExport(
    val key: String,
    val previous: Original24Request,
    val previousIntentSha256: String,
    val previousPublication: JpegPublicationRecord?,
    val request: Original24Request,
    val rendererContract: String,
) {
    init {
        require(key.matches(Regex("[0-9a-f]{32}")))
        require(previousIntentSha256.matches(Regex("[0-9a-f]{64}")))
        require(previous.generation < Long.MAX_VALUE && request.generation == previous.generation + 1)
        require(previous.source == request.source && previous.captureId == request.captureId)
        require(rendererContract.toByteArray(Charsets.UTF_8).size in 1..128)
        previousPublication?.let {
            require(it.phase == JpegPublicationPhase.UNKNOWN)
            require(it.target.captureId == previous.captureId && it.target.generation == previous.generation)
            require(it.render.request.source == previous.source && it.render.request.recipe == previous.recipe)
        }
    }
}

/** Append-only evidence slot for one observation, allocated by caller under its serial authority.
 * A conflicting observation needs a new slot; old evidence is never replaced with a newer inference.
 */
internal class JpegReconciliationReceipt(private val storage: AtomicJournalStorage) {
    @Synchronized fun commit(value: JpegReconciliationObservation, checkAuthority: () -> Unit): JpegReconciliationObservation {
        val bytes = SaveRecoveryCodec.observation(value)
        checkAuthority()
        val old = storage.read(SaveRecoveryCodec.MAX_BYTES)
        if (old != null && !old.contentEquals(bytes)) throw CaptureJournalConflict()
        if (old == null) storage.replace(bytes)
        if (storage.read(SaveRecoveryCodec.MAX_BYTES)?.contentEquals(bytes) != true) throw IOException("Observation commit unconfirmed")
        checkAuthority()
        return value
    }
    fun read(): JpegReconciliationObservation? = storage.read(SaveRecoveryCodec.MAX_BYTES)?.let(SaveRecoveryCodec::readObservation)
}

/** Single consent slot per predecessor, on the existing serial authority, not a job queue.
 * All OLD writer admissions must consult oldMayRun, including after restart. New admissions must
 * match this record AND use the existing save owner's durable intent (this is NOT a start permit).
 * Thus lost acceptance ack returns the same request without claiming the producer did/didn't start.
 */
internal class ExplicitRecoveryExports(private val storage: AtomicJournalStorage) {
    private var uncertain = false
    @Synchronized fun accept(value: ExplicitRecoveryExport, checkQuiescentAuthority: () -> Unit): ExplicitRecoveryExport {
        val bytes = SaveRecoveryCodec.export(value)
        checkQuiescentAuthority() // Exact old bytes/source hash + no active old/new work or held resources.
        val old = storage.read(SaveRecoveryCodec.MAX_BYTES)
        if (old != null) {
            val saved = SaveRecoveryCodec.readExport(old)
            if (saved != value) throw CaptureJournalConflict() // Also rejects a second tap with a different key.
            checkQuiescentAuthority()
            uncertain = false
            return saved
        }
        if (uncertain) throw IOException("Export acceptance remains unconfirmed")
        uncertain = true // Fence old work even if replace throws before/after commit.
        storage.replace(bytes)
        if (storage.read(SaveRecoveryCodec.MAX_BYTES)?.contentEquals(bytes) != true) throw IOException("Export commit unconfirmed")
        checkQuiescentAuthority()
        uncertain = false
        return value
    }
    @Synchronized fun read(): ExplicitRecoveryExport? = storage.read(SaveRecoveryCodec.MAX_BYTES)?.let(SaveRecoveryCodec::readExport)
    @Synchronized fun oldMayRun(): Boolean = !uncertain && read() == null
    @Synchronized fun newMayRun(value: ExplicitRecoveryExport): Boolean = !uncertain && read() == value
}
