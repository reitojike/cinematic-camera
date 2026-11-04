package dev.cinematic.p0.rawcapture

/** Process-lifetime metadata claim. Closing/cancelling does not permit a second AtomicFile/publisher owner. */
internal class JpegOperationOwnerRegistry {
    private val owners = mutableMapOf<Triple<String, String, Long>, Any>()
    @Synchronized fun claim(rootIdentity: String, captureId: String, generation: Long, owner: Any) {
        requireCaptureId(captureId)
        require(generation > 0)
        val key = Triple(rootIdentity, captureId, generation)
        val current = owners[key]
        if (current != null && current !== owner) throw CaptureJournalConflict()
        owners[key] = owner
    }
}

/** Shared by manual factory and auto backend. A real process restart clears claims, never disk receipts. */
internal object JpegOperationOwners { val registry = JpegOperationOwnerRegistry() }
