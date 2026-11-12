package dev.cinematic.p0.rawcapture

import java.io.IOException

/** Read grants only. No write permission or document mutation can be requested. */
internal interface DngImportReadGrantPort {
    fun held(uri: String): Boolean
    fun takeRead(uri: String)
    fun releaseRead(uri: String)
}

internal class DngImportReadGrants(private val journal: DngImportJournal, private val port: DngImportReadGrantPort) {
    fun retain(record: DngImportRecord, offered: Boolean): DngImportRecord {
        if (port.held(record.sourceUri)) return if (record.grant == DngImportGrant.ACQUIRED) record
            else journal.grant(record, DngImportGrant.ALREADY_HELD)
        if (!offered) return record
        // Sole import owner persists acquisition intent before the external API call.
        var current = if (record.grant == DngImportGrant.ACQUIRED) record else journal.grant(record, DngImportGrant.ACQUIRED)
        try { port.takeRead(record.sourceUri) } catch (_: SecurityException) {
            if (!port.held(record.sourceUri)) current = journal.grant(current, DngImportGrant.SESSION_ONLY)
            return current
        }
        if (!port.held(record.sourceUri)) current = journal.grant(current, DngImportGrant.SESSION_ONLY)
        return current
    }

    fun release(record: DngImportRecord, otherUses: Boolean): DngImportRecord {
        if (record.grant != DngImportGrant.ACQUIRED || otherUses) return record
        if (port.held(record.sourceUri)) port.releaseRead(record.sourceUri)
        if (port.held(record.sourceUri)) throw IOException("Import read grant release unconfirmed")
        return journal.grant(record, DngImportGrant.SESSION_ONLY)
    }

    fun reselect(record: DngImportRecord, uri: String, fingerprint: OriginalFingerprint,
        offered: Boolean, otherUses: Boolean): DngImportRecord {
        require(record.fingerprint == fingerprint)
        var current = record
        if (uri != record.sourceUri && record.grant == DngImportGrant.ACQUIRED) {
            if (otherUses) throw IOException("Import source grant still shared")
            current = release(record, false) // Do not lose ownership of the old URI's grant.
        }
        current = journal.reselect(current, uri, fingerprint,
            if (uri == current.sourceUri) current.grant else DngImportGrant.SESSION_ONLY)
        return retain(current, offered)
    }
}
