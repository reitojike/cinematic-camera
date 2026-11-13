package dev.cinematic.p0.rawcapture

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

internal class JpegPublicationTooLarge : IOException("JPEG journal too large")

internal enum class JpegPublicationPhase { PREPARED, INSERTING, ROW_RECORDED, COPYING, BYTES_COMPLETE, PUBLISHING, PUBLISHED, UNKNOWN }

internal data class JpegPublicationTarget(val captureId: String, val generation: Long, val token: String) {
    init { requireCaptureId(captureId); require(generation > 0); require(token.matches(Regex("[0-9a-f]{32}"))) }
    val operationId get() = "$captureId:RENDERED:$generation"
    val volume get() = "external_primary"
    val relativePath get() = "Pictures/Cinematic Camera/"
    val mimeType get() = "image/jpeg"
    val displayName get() = "CINEMATIC_${captureId}_R${generation}_$token.jpg"
    fun accepts(row: DngPendingRow, pending: Boolean = true) = isDngRowUri(row.uri) && row.ownedByApp &&
        row.pending == pending && row.trashed == false && row.displayName == displayName && row.volume == volume &&
        row.relativePath == relativePath && row.mimeType == mimeType
}

internal data class JpegPublicationRecord(
    val target: JpegPublicationTarget,
    val render: JpegPublicationRender,
    val fingerprint: OriginalFingerprint,
    val revision: Long = 0,
    val phase: JpegPublicationPhase = JpegPublicationPhase.PREPARED,
    val uri: String? = null,
) {
    init {
        require(target.captureId == render.request.captureId && target.generation == render.request.generation)
        require(revision >= 0 && fingerprint.bytes == render.request.output.bytes)
        require(uri == null || isDngRowUri(uri))
        require(phase !in setOf(JpegPublicationPhase.PREPARED, JpegPublicationPhase.INSERTING) || uri == null)
        require(phase in setOf(JpegPublicationPhase.PREPARED, JpegPublicationPhase.INSERTING, JpegPublicationPhase.UNKNOWN) || uri != null)
    }
}

/** One live writer per capture+generation. The caller must retain this operation across retries. */
internal class JpegPublicationJournal(
    private val captureId: String,
    private val generation: Long,
    private val storage: AtomicJournalStorage,
    private val tokenFactory: () -> String = { UUID.randomUUID().toString().replace("-", "") },
) {
    init { requireCaptureId(captureId); require(generation > 0) }
    @Synchronized fun read(): JpegPublicationRecord? = storage.read(JpegPublicationCodec.MAX_BYTES)?.let {
        JpegPublicationCodec.decode(it).also { record ->
            if (record.target.captureId != captureId || record.target.generation != generation) throw IOException("Foreign JPEG journal")
        }
    }

    @Synchronized fun prepare(render: JpegPublicationRender, fingerprint: OriginalFingerprint): JpegPublicationRecord {
        if (render.request.captureId != captureId || render.request.generation != generation) throw CaptureJournalConflict()
        render.requireFingerprint(fingerprint)
        read()?.let {
            if (it.render != render || it.fingerprint != fingerprint) throw CaptureJournalConflict()
            return it
        }
        val prepared = JpegPublicationRecord(JpegPublicationTarget(captureId, generation, tokenFactory()), render, fingerprint)
        JpegPublicationCodec.requireRowCapacity(prepared)
        return persist(prepared)
    }

    /** Dedicated recovery journal only. The caller retains the original UNKNOWN and its receipt.
     * Complete bytes resume at the existing PUBLISHING step; no insert or copy is authorized. */
    @Synchronized fun prepareReconciled(observation: JpegReconciliationObservation): JpegPublicationRecord {
        val old = observation.previous
        if (old.phase != JpegPublicationPhase.UNKNOWN || old.target.captureId != captureId ||
            old.target.generation != generation || observation.uri == null || observation.finding !in setOf(
                JpegReconciliationFinding.PUBLIC_BYTES_MATCH, JpegReconciliationFinding.PENDING_BYTES_MATCH)) throw CaptureJournalConflict()
        val prepared = old.copy(revision = 0, phase = JpegPublicationPhase.PUBLISHING, uri = observation.uri)
        read()?.let {
            if (it.target != prepared.target || it.render != prepared.render || it.fingerprint != prepared.fingerprint ||
                it.uri != prepared.uri) throw CaptureJournalConflict()
            return it
        }
        return persist(prepared)
    }

    @Synchronized fun recordRow(revision: Long, uri: String): JpegPublicationRecord {
        val current = current(revision)
        if (current.phase != JpegPublicationPhase.INSERTING || !isDngRowUri(uri)) throw CaptureJournalConflict()
        return persist(current.copy(revision = next(current), phase = JpegPublicationPhase.ROW_RECORDED, uri = uri))
    }

    @Synchronized fun unknown(revision: Long, uri: String? = null): JpegPublicationRecord {
        val current = current(revision)
        if (current.phase == JpegPublicationPhase.PUBLISHED || (current.uri != null && uri != null && current.uri != uri)) throw CaptureJournalConflict()
        if (current.phase == JpegPublicationPhase.UNKNOWN) return current
        return persist(current.copy(revision = next(current), phase = JpegPublicationPhase.UNKNOWN, uri = current.uri ?: uri))
    }

    @Synchronized fun advance(revision: Long, from: JpegPublicationPhase, to: JpegPublicationPhase): JpegPublicationRecord {
        val current = current(revision)
        val allowed = when (from) {
            JpegPublicationPhase.PREPARED -> JpegPublicationPhase.INSERTING
            JpegPublicationPhase.ROW_RECORDED -> JpegPublicationPhase.COPYING
            JpegPublicationPhase.COPYING -> JpegPublicationPhase.BYTES_COMPLETE
            JpegPublicationPhase.BYTES_COMPLETE -> JpegPublicationPhase.PUBLISHING
            JpegPublicationPhase.PUBLISHING -> JpegPublicationPhase.PUBLISHED
            else -> null
        }
        if (current.phase != from || allowed != to) throw CaptureJournalConflict()
        return persist(current.copy(revision = next(current), phase = to))
    }
    private fun current(revision: Long) = (read() ?: throw CaptureJournalConflict()).also {
        if (it.revision != revision) throw CaptureJournalConflict()
    }
    private fun next(record: JpegPublicationRecord): Long {
        if (record.revision == Long.MAX_VALUE) throw IOException("JPEG revision exhausted")
        return record.revision + 1
    }
    private fun persist(record: JpegPublicationRecord): JpegPublicationRecord {
        val encoded = JpegPublicationCodec.encode(record)
        storage.replace(encoded)
        if (storage.read(JpegPublicationCodec.MAX_BYTES)?.contentEquals(encoded) != true) throw IOException("JPEG commit unconfirmed")
        return record
    }
}

internal object JpegPublicationCodec {
    const val MAX_BYTES = 4096
    private const val MAGIC = 0x43434a50 // CCJP; separate from capture and ORIGINAL journals.
    private const val VERSION = 3

    fun encode(record: JpegPublicationRecord): ByteArray {
        val buffer = ByteArrayOutputStream()
        val version = when (record.render) {
            is ManualOriginalTrialRender -> 3
            is CompletedRender -> if (record.render.original != null || record.render.kind == RenderedResultKind.COMPLETE_ORIGINAL) 2 else 1
        }
        DataOutputStream(buffer).use { out ->
            out.writeInt(MAGIC); out.writeInt(version)
            out.writeUTF(record.target.captureId); out.writeLong(record.target.generation); out.writeUTF(record.target.token)
            out.writeLong(record.revision); out.writeInt(record.phase.ordinal)
            out.writeBoolean(record.uri != null); record.uri?.let(out::writeUTF)
            val render = record.render
            val request = render.request
            out.writeUTF(request.source.uri); out.writeInt(request.source.grant.ordinal)
            fingerprint(out, request.source.fingerprint)
            out.writeUTF(request.recipe.schema); out.writeUTF(request.recipe.payload)
            out.writeInt(request.output.width); out.writeInt(request.output.height); out.writeInt(request.output.orientation)
            out.writeUTF(request.output.color); out.writeLong(request.output.bytes)
            out.writeUTF(render.rendererContract); out.writeInt(render.kind.ordinal); out.writeInt(render.face.ordinal); out.writeInt(render.exposure.ordinal)
            fingerprint(out, record.fingerprint)
            if (version == 2) OriginalEvidenceCodec.writeCompletion(out, render.original)
            if (version == 3) {
                val trial = render as ManualOriginalTrialRender
                val g = trial.geometry
                out.writeInt(g.sourceWidth); out.writeInt(g.sourceHeight); out.writeInt(g.sourceOrientation)
                out.writeInt(g.sampledWidth); out.writeInt(g.sampledHeight); out.writeBoolean(g.fullCoverage)
                fingerprint(out, trial.outputFingerprint)
                out.writeDouble(trial.appliedStrength); out.writeDouble(trial.externalEv)
            }
        }
        val payload = buffer.toByteArray()
        val encoded = payload + MessageDigest.getInstance("SHA-256").digest(payload)
        if (encoded.size > MAX_BYTES) throw JpegPublicationTooLarge()
        return encoded
    }

    /** Reserve the bounded ASCII MediaStore URI before inserting a row; every other field is fixed. */
    fun requireRowCapacity(prepared: JpegPublicationRecord) {
        val maxUriBytes = "content://media/external_primary/images/media/".length + Long.MAX_VALUE.toString().length
        if (encode(prepared).size + 2 + maxUriBytes > MAX_BYTES) throw JpegPublicationTooLarge()
    }

    fun decode(encoded: ByteArray): JpegPublicationRecord {
        if (encoded.size !in 33..MAX_BYTES) throw IOException("Invalid JPEG journal length")
        val payload = encoded.copyOfRange(0, encoded.size - 32)
        if (!MessageDigest.isEqual(encoded.copyOfRange(payload.size, encoded.size), MessageDigest.getInstance("SHA-256").digest(payload))) {
            throw IOException("JPEG journal checksum mismatch")
        }
        try {
            return DataInputStream(ByteArrayInputStream(payload)).use { input ->
                if (input.readInt() != MAGIC) throw IOException("Unknown JPEG journal schema")
                val version = input.readInt()
                if (version !in 1..VERSION) throw IOException("Unknown JPEG journal schema")
                val target = JpegPublicationTarget(text(input, 64), input.readLong(), text(input, 32))
                val revision = input.readLong()
                val phase = enumValue<JpegPublicationPhase>(input.readInt())
                val uri = when (input.readUnsignedByte()) { 0 -> null; 1 -> text(input, 128); else -> throw IOException("Invalid JPEG URI flag") }
                val sourceUri = text(input, 1024)
                val grant = enumValue<OriginalGrant>(input.readInt())
                val source = SharedOriginalReference(sourceUri, fingerprint(input), grant)
                val recipe = RenderRecipeSnapshot(text(input, 64), text(input, 1024))
                val output = JpegOutputDescription(input.readInt(), input.readInt(), input.readInt(), text(input, 32), input.readLong())
                val request = RenderedJpegRequest(target.captureId, target.generation, source, recipe, output)
                val renderer = text(input, 64); val kind = enumValue<RenderedResultKind>(input.readInt())
                val face = enumValue<RenderedFaceState>(input.readInt()); val exposure = enumValue<RenderedExposureState>(input.readInt())
                val fingerprint = fingerprint(input)
                val render = if (version == 3) {
                    if (renderer != ManualOriginalTrialRender.CONTRACT.renderer || kind != RenderedResultKind.MANUAL_ORIGINAL_TRIAL ||
                        face != RenderedFaceState.NOT_RUN || exposure != RenderedExposureState.DIAGNOSTIC)
                        throw IOException("Invalid manual trial JPEG result")
                    val geometry = OriginalRasterGeometry(input.readInt(), input.readInt(), input.readInt(),
                        input.readInt(), input.readInt(), when (input.readUnsignedByte()) {
                            0 -> false; 1 -> true; else -> throw IOException("Invalid manual trial geometry flag")
                        })
                    val outputFingerprint = fingerprint(input)
                    val strength = input.readDouble(); val externalEv = input.readDouble()
                    ManualOriginalTrialRender(request, geometry, outputFingerprint, strength, externalEv,
                        ManualOriginalTrialRender.adjustmentsFromRecipe(recipe, strength)).also {
                        it.requireFingerprint(fingerprint)
                    }
                } else {
                    val original = if (version == 2) OriginalEvidenceCodec.readCompletion(input) else null
                    if (kind == RenderedResultKind.MANUAL_ORIGINAL_TRIAL || version == 1 && kind == RenderedResultKind.COMPLETE_ORIGINAL)
                        throw IOException("Invalid legacy JPEG result kind")
                    CompletedRender(request, renderer, kind, face, exposure, original)
                }
                if (input.available() != 0) throw IOException("Trailing JPEG journal data")
                JpegPublicationRecord(target, render, fingerprint, revision, phase, uri)
            }
        } catch (e: IllegalArgumentException) { throw IOException("Invalid JPEG journal fields", e) }
    }
    private inline fun <reified T : Enum<T>> enumValue(code: Int): T = enumValues<T>().getOrNull(code) ?: throw IOException("Unknown JPEG journal value")
    private fun text(input: DataInputStream, maxBytes: Int): String = input.readUTF().also {
        if (it.toByteArray(Charsets.UTF_8).size !in 1..maxBytes) throw IOException("Invalid JPEG journal field length")
    }
    private fun fingerprint(out: DataOutputStream, value: OriginalFingerprint) { out.writeLong(value.bytes); out.writeUTF(value.sha256) }
    private fun fingerprint(input: DataInputStream) = OriginalFingerprint(input.readLong(), text(input, 64))
}
