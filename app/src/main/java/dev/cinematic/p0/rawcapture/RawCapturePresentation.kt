package dev.cinematic.p0.rawcapture

/** UI ownership only. File receipts and sensor/control readiness remain independent evidence. */
internal class RawCapturePresentation {
    internal class Request internal constructor(val token: String, val manual: Boolean) {
        internal var phase = Phase.PREPARING
    }
    internal enum class Phase { PREPARING, ISSUED, UNKNOWN, RECEIVED, FAILED }
    internal data class Completion(val interrupted: Boolean, val restoreAutoPreview: Boolean)
    private var latest: Request? = null

    fun begin(token: String, manual: Boolean): Request = Request(token, manual).also { latest = it }
    fun request(token: String): Request? = latest?.takeIf { it.token == token }
    fun invalidate() { latest = null }
    fun ownsCapture(request: Request) = latest === request && request.phase in setOf(Phase.PREPARING, Phase.ISSUED, Phase.UNKNOWN)
    fun ownsSharedNotice(request: Request) = latest === request
    fun issued(request: Request): Boolean {
        if (latest !== request || request.phase != Phase.PREPARING) return false
        request.phase = Phase.ISSUED
        return true
    }
    fun unknown(request: Request): Boolean {
        if (latest !== request || request.phase != Phase.ISSUED) return false
        request.phase = Phase.UNKNOWN
        return true
    }
    fun failed(request: Request): Boolean {
        if (!ownsCapture(request)) return false
        request.phase = Phase.FAILED
        return true
    }
    fun received(request: Request, receipt: CaptureReceipt): Completion? {
        if (latest !== request || request.phase !in setOf(Phase.ISSUED, Phase.UNKNOWN) || receipt.captureId != request.token ||
            receipt.receivedBytes.keys != receipt.expectedFormats) return null
        val interrupted = request.phase == Phase.UNKNOWN || receipt.phase == CaptureReceiptPhase.INTERRUPTED
        request.phase = Phase.RECEIVED
        return Completion(interrupted, restoreAutoPreview = !request.manual)
    }
}
