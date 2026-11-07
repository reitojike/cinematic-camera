package dev.cinematic.p0.photoedit

import dev.cinematic.p0.rawcapture.*
import java.io.*

/** Metadata only. Reading this record never grants access or proves JPEG publication. */
internal class PhotoEditRecipeStore(private val source: SharedOriginalReference, private val storage: AtomicJournalStorage) {
    fun read(): PhotoEditValues? = storage.read(PhotoEditRecipeCodec.MAX_BYTES)?.let {
        PhotoEditRecipeCodec.decode(source, it)
    }

    fun save(values: PhotoEditValues) {
        val encoded = PhotoEditRecipeCodec.encode(source, values)
        storage.replace(encoded)
        if (storage.read(PhotoEditRecipeCodec.MAX_BYTES)?.contentEquals(encoded) != true)
            throw IOException("Edit recipe commit unconfirmed")
    }
}

internal object PhotoEditRecipeCodec {
    const val MAX_BYTES = 4096
    private const val MAGIC = 0x43434544 // CCED, never a capture or JPEG journal.
    private const val VERSION = 1

    fun encode(source: SharedOriginalReference, values: PhotoEditValues): ByteArray {
        require(PhotoAdjustmentPolicy.scales.contains(values))
        val recipe = ManualOriginalTrialRender.recipe(values.strength, PhotoAdjustmentPolicy.adjustments(values))
        return ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use {
                it.writeInt(MAGIC); it.writeInt(VERSION)
                it.writeUTF(source.uri); it.writeLong(source.fingerprint.bytes)
                it.writeUTF(source.fingerprint.sha256); it.writeUTF(source.grant.name)
                it.writeDouble(values.strength); it.writeDouble(requireNotNull(values.temperature))
                it.writeDouble(requireNotNull(values.brightness))
                it.writeUTF(recipe.schema); it.writeUTF(recipe.payload)
            }
        }.toByteArray().also { require(it.size <= MAX_BYTES) }
    }

    fun decode(source: SharedOriginalReference, bytes: ByteArray): PhotoEditValues {
        if (bytes.size !in 1..MAX_BYTES) throw IOException("Invalid edit recipe size")
        try {
            return DataInputStream(ByteArrayInputStream(bytes)).use {
                if (it.readInt() != MAGIC || it.readInt() != VERSION) throw IOException("Unknown edit recipe schema")
                val stored = SharedOriginalReference(it.readUTF(), OriginalFingerprint(it.readLong(), it.readUTF()),
                    OriginalGrant.valueOf(it.readUTF()))
                if (stored != source) throw IOException("Edit recipe original differs")
                val values = PhotoEditValues(it.readDouble(), it.readDouble(), it.readDouble())
                if (!PhotoAdjustmentPolicy.scales.contains(values)) throw IOException("Invalid edit recipe values")
                val recipe = RenderRecipeSnapshot(it.readUTF(), it.readUTF())
                if (recipe != ManualOriginalTrialRender.recipe(values.strength, PhotoAdjustmentPolicy.adjustments(values)) ||
                    it.read() != -1) throw IOException("Edit recipe processing contract differs")
                values
            }
        } catch (failure: IllegalArgumentException) {
            throw IOException("Invalid edit recipe", failure)
        }
    }
}

/** Compose's saved values remain bound to the verified original and processing contract too. */
internal object PhotoEditRestoration {
    fun identity(source: SharedOriginalReference) = ManualOriginalTrialRequests.identity(source)
    fun contract(): String = ManualOriginalTrialRender.recipe(.5, PhotoAdjustmentPolicy.adjustments(PhotoAdjustmentPolicy.defaults))
        .let { it.schema + ":" + it.payload }
    fun requireMatches(source: SharedOriginalReference, identity: String?, contract: String?) {
        if (identity != identity(source) || contract != contract()) throw IOException("Restored edit authority differs")
    }
}

/** Shared serial executor is required across screen/store instances. Admission happens at close,
 * before awaiting IO, so a destroyed screen cannot enqueue its older save after a newer close. */
internal class PhotoEditRecipeQueue(
    private val executor: java.util.concurrent.Executor,
    private val storage: (SharedOriginalReference) -> AtomicJournalStorage,
) {
    fun read(source: SharedOriginalReference): java.util.concurrent.CompletableFuture<PhotoEditValues?> =
        java.util.concurrent.CompletableFuture.supplyAsync({ PhotoEditRecipeStore(source, storage(source)).read() }, executor)
    fun save(source: SharedOriginalReference, values: PhotoEditValues): java.util.concurrent.CompletableFuture<Void> =
        java.util.concurrent.CompletableFuture.runAsync({ PhotoEditRecipeStore(source, storage(source)).save(values) }, executor)
}
