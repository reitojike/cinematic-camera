package dev.cinematic.p0.rawcapture

import java.io.IOException
import java.io.InputStream

/** Read-only observation for #377. A result is not permission to insert, overwrite or rerender.
 * The caller retains the existing serial publication authority throughout observation and commit.
 * In particular, neither absence nor a missing private JPEG proves that publication never happened.
 */
internal interface JpegReconciliationRows {
    /** Include both public and pending rows. Query/permission failures must throw, never return empty. */
    fun find(target: JpegPublicationTarget): List<DngPendingRow>
    fun inspect(uri: String): DngPendingRow?
    fun openRead(target: JpegPublicationTarget, uri: String, pending: Boolean): InputStream
}

internal enum class JpegReconciliationFinding {
    PUBLIC_BYTES_MATCH, PENDING_BYTES_MATCH, PENDING_BYTES_DIFFER,
    NO_CANDIDATE, AMBIGUOUS, METADATA_MISMATCH, PUBLIC_BYTES_DIFFER,
}

/** Carries the complete old evidence (including UNKNOWN/revision), never a rewritten old journal.
 * PENDING_BYTES_DIFFER describes only a mismatch, not a proven partial prefix or permission to copy.
 */
internal data class JpegReconciliationObservation(
    val previous: JpegPublicationRecord,
    val finding: JpegReconciliationFinding,
    val uri: String?,
)

internal object JpegReconciliation {
    /** Exceptions (permission, IO, close, stop, stale revision) remain HOLD at the owner boundary.
     * Resources belong to that owner and remain retained when close cannot be confirmed.
     * No private output is required to recognize the exact already-public JPEG.
     */
    fun observe(
        previous: JpegPublicationRecord,
        expectedRender: JpegPublicationRender,
        supportedContracts: Set<RendererPublicationContract>,
        rows: JpegReconciliationRows,
        resources: DngIoResources,
        readCurrent: () -> JpegPublicationRecord?,
        isCurrent: () -> Boolean,
        stop: () -> Boolean = { false },
    ): JpegReconciliationObservation {
        fun checkAuthority() {
            if (stop() || !isCurrent()) throw DngVerificationStopped()
            if (readCurrent() != previous) throw CaptureJournalConflict()
            if (resources.hasOpenResources) throw IOException("Reconciliation resources remain open")
        }
        fun result(finding: JpegReconciliationFinding, uri: String? = null): JpegReconciliationObservation {
            checkAuthority()
            return JpegReconciliationObservation(previous, finding, uri)
        }
        checkAuthority()
        if (previous.render != expectedRender) throw CaptureJournalConflict()
        expectedRender.requirePublishable(supportedContracts)
        expectedRender.requireFingerprint(previous.fingerprint)
        // Query even when a URI is known: ambiguity must not be hidden by the recorded URI.
        val candidates = rows.find(previous.target)
        checkAuthority()
        if (candidates.isEmpty()) return result(JpegReconciliationFinding.NO_CANDIDATE)
        val row = candidates.singleOrNull() ?: return result(JpegReconciliationFinding.AMBIGUOUS)
        val pending = row.pending
        if (pending == null || !previous.target.accepts(row, pending) ||
            (previous.uri != null && previous.uri != row.uri) || rows.inspect(row.uri) != row) {
            return result(JpegReconciliationFinding.METADATA_MISMATCH)
        }
        val equal = try {
            DngContentVerifier.verify(previous.fingerprint,
                { resources.input(rows.openRead(previous.target, row.uri, pending)) },
                { stop() || !isCurrent() })
            true
        } catch (changed: DngContentChanged) {
            // A failed close is not a successful negative byte observation.
            if (changed.suppressed.isNotEmpty() || resources.hasOpenResources) throw changed
            false
        }
        checkAuthority()
        // Recheck membership and all metadata after reading bytes. No platform snapshot is assumed.
        if (rows.inspect(row.uri) != row || rows.find(previous.target) != listOf(row)) {
            return result(JpegReconciliationFinding.METADATA_MISMATCH)
        }
        return result(when {
            pending && equal -> JpegReconciliationFinding.PENDING_BYTES_MATCH
            pending -> JpegReconciliationFinding.PENDING_BYTES_DIFFER
            equal -> JpegReconciliationFinding.PUBLIC_BYTES_MATCH
            else -> JpegReconciliationFinding.PUBLIC_BYTES_DIFFER
        }, row.uri)
    }
}
