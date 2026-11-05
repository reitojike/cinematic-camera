package dev.cinematic.p0.rawcapture

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

internal enum class AutoJobPhase { QUEUED, RENDERING, RETRYABLE, RENDERED, PUBLISHING, PUBLISHED, HELD }
internal enum class AutoJobReason { NONE, INTERRUPTED, RENDER_FAILED, UNSUPPORTED, SOURCE_UNAVAILABLE, SOURCE_CHANGED, INVALID_OUTPUT, PUBLICATION_UNKNOWN, CONTENT_CHANGED, MISSING_OBSERVATION }

internal data class AutoDevelopRecord(
    val request: AutoDevelopRequest,
    val revision: Long = 0,
    val phase: AutoJobPhase = AutoJobPhase.QUEUED,
    val attemptId: String? = null,
    val attemptCount: Int = 0,
    val outputFingerprint: OriginalFingerprint? = null,
    val reason: AutoJobReason = AutoJobReason.NONE,
    val observed: AutoRenderReceipt? = null,
) {
    init {
        require(revision >= 0 && attemptCount >= 0)
        require(attemptId == null || attemptId.matches(Regex("[0-9a-f]{32}")))
        require(attemptId == null || attemptCount > 0)
        require(phase != AutoJobPhase.QUEUED || attemptId == null)
        require(phase !in setOf(AutoJobPhase.RENDERING, AutoJobPhase.RENDERED, AutoJobPhase.PUBLISHING, AutoJobPhase.PUBLISHED) || attemptId != null)
        require((outputFingerprint != null) == (phase in setOf(AutoJobPhase.RENDERED, AutoJobPhase.PUBLISHING, AutoJobPhase.PUBLISHED) ||
            (phase == AutoJobPhase.HELD && outputFingerprint != null)))
        require(outputFingerprint == null || attemptId != null)
        require(observed == null || (outputFingerprint != null && request.accepts(observed) && observed.output.bytes == outputFingerprint.bytes))
        require(!request.interactive || outputFingerprint == null || observed != null)
        require(request.quality != AutoRenderQuality.PRIVATE_TRIAL_1024 || phase !in setOf(AutoJobPhase.PUBLISHING, AutoJobPhase.PUBLISHED))
    }
    fun completed(): CompletedRender {
        check(request.quality != AutoRenderQuality.PRIVATE_TRIAL_1024) { "Private trial has no completed publication result" }
        requireNotNull(outputFingerprint)
        val receipt = observed ?: throw IOException("Completed render requires an observed result")
        return request.completed(receipt.output, receipt.face, receipt.exposure)
    }
}

/** One owner per capture+generation, on the serial IO worker. Never reset or auto-retry a HELD/public operation. */
internal class AutoDevelopJournal(
    private val captureId: String,
    private val generation: Long,
    private val storage: AtomicJournalStorage,
    private val attemptFactory: () -> String = { UUID.randomUUID().toString().replace("-", "") },
) {
    init { requireCaptureId(captureId); require(generation > 0) }
    @Synchronized fun read(): AutoDevelopRecord? = storage.read(AutoDevelopCodec.MAX_BYTES)?.let {
        AutoDevelopCodec.decode(it).also { record ->
            if (record.request.captureId != captureId || record.request.generation != generation) throw IOException("Foreign auto-develop journal")
        }
    }
    @Synchronized fun prepare(request: AutoDevelopRequest): AutoDevelopRecord {
        if (request.captureId != captureId || request.generation != generation) throw CaptureJournalConflict()
        read()?.let { if (it.request != request) throw CaptureJournalConflict(); return it }
        return persist(AutoDevelopRecord(request))
    }
    @Synchronized fun start(revision: Long): AutoDevelopRecord {
        val current = current(revision)
        if (current.phase != AutoJobPhase.QUEUED || current.attemptCount == Int.MAX_VALUE) throw CaptureJournalConflict()
        return persist(current.copy(revision = next(current), phase = AutoJobPhase.RENDERING,
            attemptId = attemptFactory(), attemptCount = current.attemptCount + 1, reason = AutoJobReason.NONE))
    }
    @Synchronized fun rendered(revision: Long, fingerprint: OriginalFingerprint, observed: AutoRenderReceipt? = null): AutoDevelopRecord {
        val current = current(revision)
        if (current.phase != AutoJobPhase.RENDERING) throw CaptureJournalConflict()
        JpegOutputDescription(current.request.width, current.request.height, 1, "sRGB", fingerprint.bytes)
        return persist(current.copy(revision = next(current), phase = AutoJobPhase.RENDERED, outputFingerprint = fingerprint, observed = observed))
    }
    @Synchronized fun publishing(revision: Long): AutoDevelopRecord {
        val current = current(revision)
        if (current.request.quality == AutoRenderQuality.PRIVATE_TRIAL_1024 || current.observed == null) throw CaptureJournalConflict()
        return advance(revision, AutoJobPhase.RENDERED, AutoJobPhase.PUBLISHING)
    }
    @Synchronized fun published(revision: Long) = advance(revision, AutoJobPhase.PUBLISHING, AutoJobPhase.PUBLISHED)
    @Synchronized fun retryable(revision: Long, reason: AutoJobReason): AutoDevelopRecord {
        val current = current(revision)
        if (current.phase != AutoJobPhase.RENDERING) throw CaptureJournalConflict()
        return persist(current.copy(revision = next(current), phase = AutoJobPhase.RETRYABLE, reason = reason))
    }
    @Synchronized fun hold(revision: Long, reason: AutoJobReason): AutoDevelopRecord {
        val current = current(revision)
        if (current.phase == AutoJobPhase.PUBLISHED) throw CaptureJournalConflict()
        return persist(current.copy(revision = next(current), phase = AutoJobPhase.HELD, reason = reason))
    }
    @Synchronized fun retry(revision: Long): AutoDevelopRecord {
        val current = current(revision)
        val reselect = current.request.interactive && current.phase == AutoJobPhase.HELD &&
            current.reason == AutoJobReason.SOURCE_UNAVAILABLE && current.attemptId == null && current.outputFingerprint == null
        if (current.phase != AutoJobPhase.RETRYABLE && !reselect) throw CaptureJournalConflict()
        return persist(current.copy(revision = next(current), phase = AutoJobPhase.QUEUED, attemptId = null, reason = AutoJobReason.NONE))
    }
    /** Call only when rebuilding the process owner, never while a live attempt/close is outstanding. */
    @Synchronized fun recoverAfterProcessDeath(): AutoDevelopRecord? {
        val current = read() ?: return null
        return if (current.phase == AutoJobPhase.RENDERING) retryable(current.revision, AutoJobReason.INTERRUPTED) else current
    }
    private fun advance(revision: Long, from: AutoJobPhase, to: AutoJobPhase): AutoDevelopRecord {
        val current = current(revision)
        if (current.phase != from) throw CaptureJournalConflict()
        return persist(current.copy(revision = next(current), phase = to))
    }
    private fun current(revision: Long) = (read() ?: throw CaptureJournalConflict()).also { if (it.revision != revision) throw CaptureJournalConflict() }
    private fun next(record: AutoDevelopRecord): Long {
        if (record.revision == Long.MAX_VALUE) throw IOException("Auto-develop revision exhausted")
        return record.revision + 1
    }
    private fun persist(record: AutoDevelopRecord): AutoDevelopRecord {
        val encoded = AutoDevelopCodec.encode(record)
        storage.replace(encoded)
        if (storage.read(AutoDevelopCodec.MAX_BYTES)?.contentEquals(encoded) != true) throw IOException("Auto-develop commit unconfirmed")
        return record
    }
}

internal object AutoDevelopCodec {
    const val MAX_BYTES = 4096
    private const val MAGIC = 0x43434144 // CCAD; independent of receipt and JPEG publication.
    private const val VERSION = 2
    fun encode(record: AutoDevelopRecord): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.writeInt(MAGIC); out.writeInt(VERSION)
            val r = record.request
            out.writeUTF(r.captureId); out.writeLong(r.generation)
            out.writeUTF(r.source.uri); fp(out, r.source.fingerprint); out.writeInt(r.source.grant.ordinal)
            out.writeUTF(r.recipe.schema); out.writeUTF(r.recipe.payload); out.writeUTF(r.rendererContract)
            out.writeInt(r.width); out.writeInt(r.height); out.writeInt(r.quality.ordinal)
            out.writeInt(r.face.ordinal); out.writeInt(r.exposure.ordinal); out.writeInt(r.profile.ordinal)
            out.writeLong(record.revision); out.writeInt(record.phase.ordinal); out.writeInt(record.attemptCount)
            out.writeBoolean(record.attemptId != null); record.attemptId?.let(out::writeUTF)
            out.writeBoolean(record.outputFingerprint != null); record.outputFingerprint?.let { fp(out, it) }
            out.writeInt(record.reason.ordinal)
            out.writeBoolean(record.observed != null)
            record.observed?.let {
                out.writeInt(it.output.width); out.writeInt(it.output.height); out.writeInt(it.output.orientation)
                out.writeUTF(it.output.color); out.writeLong(it.output.bytes)
                out.writeInt(it.face.ordinal); out.writeInt(it.exposure.ordinal)
            }
        }
        val payload = buffer.toByteArray()
        return (payload + MessageDigest.getInstance("SHA-256").digest(payload)).also {
            if (it.size > MAX_BYTES) throw IOException("Auto-develop journal too large")
        }
    }
    fun decode(encoded: ByteArray): AutoDevelopRecord {
        if (encoded.size !in 33..MAX_BYTES) throw IOException("Invalid auto-develop journal length")
        val payload = encoded.copyOfRange(0, encoded.size - 32)
        if (!MessageDigest.isEqual(encoded.copyOfRange(payload.size, encoded.size), MessageDigest.getInstance("SHA-256").digest(payload))) {
            throw IOException("Auto-develop checksum mismatch")
        }
        try {
            return DataInputStream(ByteArrayInputStream(payload)).use { input ->
                if (input.readInt() != MAGIC) throw IOException("Unknown auto-develop schema")
                val version = input.readInt()
                if (version !in 1..VERSION) throw IOException("Unknown auto-develop schema")
                val id = text(input, 64); val generation = input.readLong()
                val uri = text(input, 1024); val fingerprint = fp(input); val grant = enumValue<OriginalGrant>(input.readInt())
                val source = SharedOriginalReference(uri, fingerprint, grant)
                val recipe = RenderRecipeSnapshot(text(input, 64), text(input, 1024)); val renderer = text(input, 64)
                val request = AutoDevelopRequest(id, generation, source, recipe, renderer, input.readInt(), input.readInt(),
                    enumValue(input.readInt()), enumValue(input.readInt()), enumValue(input.readInt()), enumValue(input.readInt()))
                val revision = input.readLong(); val phase = enumValue<AutoJobPhase>(input.readInt()); val count = input.readInt()
                val attempt = if (flag(input)) text(input, 32) else null
                val output = if (flag(input)) fp(input) else null
                val reason = enumValue<AutoJobReason>(input.readInt())
                val observed = if (version >= 2 && flag(input)) AutoRenderReceipt(request,
                    JpegOutputDescription(input.readInt(), input.readInt(), input.readInt(), text(input, 32), input.readLong()),
                    enumValue(input.readInt()), enumValue(input.readInt())) else null
                if (version == 1 && request.quality !in setOf(AutoRenderQuality.FINAL_ORIGINAL, AutoRenderQuality.PREVIEW)) throw IOException("Invalid legacy quality")
                if (input.available() != 0) throw IOException("Trailing auto-develop data")
                AutoDevelopRecord(request, revision, phase, attempt, count, output, reason, observed)
            }
        } catch (e: IllegalArgumentException) { throw IOException("Invalid auto-develop fields", e) }
    }
    private fun flag(input: DataInputStream) = when (input.readUnsignedByte()) { 0 -> false; 1 -> true; else -> throw IOException("Invalid auto-develop flag") }
    private inline fun <reified T : Enum<T>> enumValue(code: Int): T = enumValues<T>().getOrNull(code) ?: throw IOException("Unknown auto-develop value")
    private fun text(input: DataInputStream, maxBytes: Int) = input.readUTF().also {
        if (it.toByteArray(Charsets.UTF_8).size !in 1..maxBytes) throw IOException("Invalid auto-develop field length")
    }
    private fun fp(out: DataOutputStream, value: OriginalFingerprint) { out.writeLong(value.bytes); out.writeUTF(value.sha256) }
    private fun fp(input: DataInputStream) = OriginalFingerprint(input.readLong(), text(input, 64))
}
