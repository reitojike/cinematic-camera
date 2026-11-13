package dev.cinematic.p0.rawcapture

import dev.cinematic.p0.jpegrender.original.Original24Request
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.MessageDigest

internal enum class ManualTrialAttemptPhase { STARTED, CANCELLED_CLEAN }

/** Request v2 embeds source/grant/fingerprint, generation and the canonical recipe unchanged. */
internal data class ManualTrialAttemptRecord(
    val request: Original24Request,
    val attempt: Long,
    val phase: ManualTrialAttemptPhase,
    val engine: String = "GPU",
    val executionVersion: String = ManualTrialAttemptCodec.EXECUTION_VERSION,
)

internal object ManualTrialAttemptCodec {
    const val MAX_BYTES = 4096
    // Explicit execution compatibility contract; never infer it from the current setting/app version.
    const val EXECUTION_VERSION = "manual-original24-gpu-cancellation-v1"
    private const val MAGIC = 0x43434d41

    fun encode(record: ManualTrialAttemptRecord): ByteArray {
        require(record.attempt > 0)
        require(record.engine == "GPU" && record.executionVersion == EXECUTION_VERSION)
        val request = ManualOriginalTrialRequestCodec.encode(record.request)
        val payload = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use {
                it.writeInt(MAGIC); it.writeInt(1)
                it.writeInt(request.size); it.write(request)
                it.writeUTF(record.engine); it.writeUTF(record.executionVersion)
                it.writeLong(record.attempt); it.writeUTF(record.phase.name)
            }
        }.toByteArray()
        return (payload + digest(payload)).also { require(it.size <= MAX_BYTES) }
    }

    fun decode(bytes: ByteArray): ManualTrialAttemptRecord {
        if (bytes.size !in 33..MAX_BYTES) throw IOException("Invalid manual attempt length")
        val payload = bytes.copyOfRange(0, bytes.size - 32)
        if (!MessageDigest.isEqual(digest(payload), bytes.copyOfRange(payload.size, bytes.size)))
            throw IOException("Manual attempt checksum mismatch")
        try {
            return DataInputStream(ByteArrayInputStream(payload)).use {
                if (it.readInt() != MAGIC || it.readInt() != 1) throw IOException("Unknown manual attempt schema")
                val size = it.readInt()
                if (size !in 33..ManualOriginalTrialRequestCodec.MAX_BYTES) throw IOException("Invalid attempt request")
                val requestBytes = ByteArray(size).also(it::readFully)
                val request = ManualOriginalTrialRequestCodec.decode(requestBytes)
                val engine = it.readUTF(); val version = it.readUTF()
                val record = ManualTrialAttemptRecord(request, it.readLong(), ManualTrialAttemptPhase.valueOf(it.readUTF()), engine, version)
                if (it.available() != 0 || !encode(record).contentEquals(bytes)) throw IOException("Noncanonical manual attempt")
                record
            }
        } catch (failure: IllegalArgumentException) { throw IOException("Unsupported manual attempt", failure) }
    }

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
}

/** Single writer on the existing queue IO. This file never replaces or deletes the old intent. */
internal class ManualOriginalTrialAttemptJournal(
    private val request: Original24Request,
    private val storage: AtomicJournalStorage,
    private val intent: AtomicJournalStorage,
) {
    private var uncertain = false

    fun read(): ManualTrialAttemptRecord? {
        if (uncertain) throw IOException("Manual attempt commit unconfirmed")
        val record = storage.read(ManualTrialAttemptCodec.MAX_BYTES)?.let(ManualTrialAttemptCodec::decode) ?: return null
        if (record.request != request || intent.read(ManualOriginalTrialRequestCodec.MAX_BYTES)
                ?.contentEquals(ManualOriginalTrialRequestCodec.encode(request)) != true) throw CaptureJournalConflict()
        return record
    }

    fun start(previous: ManualTrialAttemptRecord?): ManualTrialAttemptRecord {
        if (read() != previous) throw CaptureJournalConflict()
        val number = previous?.attempt ?: 0
        if (number == Long.MAX_VALUE) throw IOException("Manual attempt exhausted")
        return persist(ManualTrialAttemptRecord(request, number + 1, ManualTrialAttemptPhase.STARTED))
    }

    fun confirmCancellation(started: ManualTrialAttemptRecord): ManualTrialAttemptRecord {
        if (started.phase != ManualTrialAttemptPhase.STARTED || read() != started) throw CaptureJournalConflict()
        return persist(started.copy(phase = ManualTrialAttemptPhase.CANCELLED_CLEAN))
    }

    private fun persist(record: ManualTrialAttemptRecord): ManualTrialAttemptRecord {
        // Caller must have committed the matching intent before any attempt or producer.
        if (intent.read(ManualOriginalTrialRequestCodec.MAX_BYTES)
                ?.contentEquals(ManualOriginalTrialRequestCodec.encode(request)) != true) throw CaptureJournalConflict()
        val bytes = ManualTrialAttemptCodec.encode(record)
        uncertain = true
        storage.replace(bytes)
        if (storage.read(ManualTrialAttemptCodec.MAX_BYTES)?.contentEquals(bytes) != true)
            throw IOException("Manual attempt commit unconfirmed")
        uncertain = false
        return record
    }
}
