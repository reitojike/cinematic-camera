package dev.cinematic.p0

data class ControlToken(val epoch: Int, val sequence: Long)

/** No Android clock dependency: tests can advance time without sleeping. */
class ControlOperation {
    private var sequence = 0L
    var token: ControlToken? = null; private set
    var deadlineMs: Long = 0; private set
    fun begin(epoch: Int, nowMs: Long, budgetMs: Long): ControlToken {
        require(budgetMs > 0)
        return ControlToken(epoch, ++sequence).also { token = it; deadlineMs = nowMs + budgetMs }
    }
    fun current(value: ControlToken) = token == value
    fun expire(value: ControlToken, nowMs: Long): Boolean {
        if (!current(value) || nowMs < deadlineMs) return false
        token = null
        return true
    }
    fun finish(value: ControlToken) { if (current(value)) token = null }
    fun invalidate() { token = null }
}

fun controlBudgetMs(requestNs: Long?, previousNs: Long?, frameNs: Long?): Long =
    2_000L + 3L * (listOfNotNull(requestNs, previousNs, frameNs).maxOrNull()?.coerceAtLeast(0) ?: 0L)
        .let { it / 1_000_000 + if (it % 1_000_000 == 0L) 0 else 1 }.coerceAtLeast(334L)

/** Conservative deadline hints only; never sensor/acceptance evidence or persisted settings. */
class ExposureDurations {
    private val byCamera = mutableMapOf<String, Long>()
    fun previous(cameraId: String?): Long? = byCamera[cameraId]
    fun submitted(cameraId: String?, exposureNs: Long?) {
        if (cameraId != null && exposureNs != null) byCamera[cameraId] = maxOf(byCamera[cameraId] ?: 0L, exposureNs)
    }
    fun accepted(cameraId: String?, exposureNs: Long?) {
        if (cameraId == null) return
        if (exposureNs == null) byCamera.remove(cameraId) else byCamera[cameraId] = exposureNs
    }
}
