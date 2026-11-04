package dev.cinematic.p0.rawcapture

import java.io.IOException

internal data class DngPendingRow(
    val uri: String,
    val displayName: String?,
    val volume: String?,
    val relativePath: String?,
    val mimeType: String?,
    val ownedByApp: Boolean,
    val pending: Boolean?,
    val trashed: Boolean? = false,
)

/** Include pending rows. Unavailable queries/columns throw; unknown attribute values remain unknown. */
internal interface DngPendingRows {
    fun insert(target: DngPublicationTarget): String?
    fun find(target: DngPublicationTarget): List<DngPendingRow>
    fun inspect(uri: String): DngPendingRow?
}

/** One live coordinator per operation, on IO executor. No copy, publish, update or delete capability. */
internal class DngInsertCoordinator(private val journal: DngPublicationJournal, private val rows: DngPendingRows) {
    @Synchronized
    fun advance(): DngPublicationRecord {
        val current = journal.read() ?: throw IOException("Publication has not been prepared")
        return when (current.phase) {
            DngInsertPhase.UNKNOWN -> current
            DngInsertPhase.PREPARED -> {
                val inserting = journal.markInserting(current.revision) // Must read back before issuing insert.
                val uri = try { rows.insert(inserting.target) }
                catch (_: Exception) { return journal.markUnknown(inserting.revision, null) }
                if (uri == null || !isDngRowUri(uri)) return journal.markUnknown(inserting.revision, null)
                val row = try { rows.inspect(uri) }
                catch (_: Exception) { return journal.markUnknown(inserting.revision, uri) }
                if (row == null || row.uri != uri || !inserting.target.accepts(row)) journal.markUnknown(inserting.revision, uri)
                else journal.recordRow(inserting.revision, uri)
            }
            DngInsertPhase.INSERTING -> {
                val candidates = try { rows.find(current.target) }
                catch (_: Exception) { return journal.markUnknown(current.revision, null) }
                val row = candidates.singleOrNull()
                if (row == null || !current.target.accepts(row)) journal.markUnknown(current.revision, null)
                else journal.recordRow(current.revision, row.uri)
            }
            DngInsertPhase.ROW_RECORDED -> {
                val uri = requireNotNull(current.uri)
                val row = try { rows.inspect(uri) }
                catch (_: Exception) { return journal.markUnknown(current.revision, uri) }
                if (row == null || row.uri != uri || !current.target.accepts(row)) journal.markUnknown(current.revision, uri)
                else current // Not a permanent write permit: every later copy must inspect again.
            }
        }
    }
}
