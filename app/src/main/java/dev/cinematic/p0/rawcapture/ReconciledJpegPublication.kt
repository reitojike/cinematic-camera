package dev.cinematic.p0.rawcapture

import java.io.IOException

/** Uses the standard publisher journal after a separate durable observation, keeping UNKNOWN intact.
 * A partial/different row remains held: this path never authorizes truncation, copy or insert. */
internal class ReconciledJpegPublication(
    private val original: JpegPublicationJournal,
    val recovery: JpegPublicationJournal,
    private val receipt: JpegReconciliationReceipt,
    private val rows: JpegReconciliationRows,
    private val resources: DngIoResources,
    private val isCurrent: () -> Boolean,
) {
    var lastFinding: JpegReconciliationFinding? = null
        private set
    fun hasEvidence(): Boolean = receipt.read() != null
    fun hasPublishedEvidence(): Boolean = receipt.read()?.finding == JpegReconciliationFinding.PUBLIC_BYTES_MATCH
    fun read(): JpegPublicationRecord? {
        val old = original.read()
        val observed = receipt.read()
        val resumed = recovery.read()
        if (observed == null && resumed == null) return old
        if (old == null || observed == null || observed.previous != old || old.phase != JpegPublicationPhase.UNKNOWN ||
            observed.finding !in setOf(JpegReconciliationFinding.PUBLIC_BYTES_MATCH, JpegReconciliationFinding.PENDING_BYTES_MATCH))
            throw CaptureJournalConflict()
        if (resumed == null) return old
        if (resumed.target != old.target || resumed.render != old.render || resumed.fingerprint != old.fingerprint ||
            resumed.uri != observed.uri || resumed.phase !in setOf(JpegPublicationPhase.PUBLISHING,
                JpegPublicationPhase.PUBLISHED, JpegPublicationPhase.UNKNOWN)) throw CaptureJournalConflict()
        return resumed
    }
    fun reconcile(render: JpegPublicationRender, contracts: Set<RendererPublicationContract>, stop: () -> Boolean): JpegPublicationRecord? {
        val effective = read() ?: return null
        if (effective.phase != JpegPublicationPhase.UNKNOWN || recovery.read() != null) return effective
        val observed = JpegReconciliation.observe(effective, render, contracts, rows, resources,
            original::read, isCurrent, stop)
        lastFinding = observed.finding
        if (observed.finding !in setOf(JpegReconciliationFinding.PUBLIC_BYTES_MATCH,
                JpegReconciliationFinding.PENDING_BYTES_MATCH)) return effective
        fun checkAuthority() {
            if (stop() || !isCurrent() || resources.hasOpenResources) throw IOException("Reconciliation stopped or resources held")
            if (original.read() != effective) throw CaptureJournalConflict()
        }
        val pinned = receipt.read()
        if (pinned == null) receipt.commit(observed, ::checkAuthority)
        else if (pinned.previous != observed.previous || pinned.uri != observed.uri) throw CaptureJournalConflict()
        checkAuthority()
        return recovery.prepareReconciled(observed).also { checkAuthority() }
    }
}
