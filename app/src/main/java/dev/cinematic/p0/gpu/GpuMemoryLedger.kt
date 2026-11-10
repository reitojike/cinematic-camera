package dev.cinematic.p0.gpu

/** 同じ GL thread が所有する全テクスチャの台帳。予算超過を縮小でごまかさない。 */
internal class GpuMemoryLedger(val budgetBytes: Long = PREVIEW_BUDGET) {
    var allocatedBytes: Long = 0; private set
    var textureCount: Int = 0; private set
    init { require(budgetBytes > 0) }
    internal fun reserve(bytes: Long) {
        require(bytes > 0)
        if (bytes > budgetBytes - allocatedBytes) throw GpuMemoryBudgetException("GPU texture budget exceeded ($budgetBytes bytes)")
        allocatedBytes += bytes; textureCount++
    }
    internal fun release(bytes: Long) {
        check(bytes > 0 && bytes <= allocatedBytes && textureCount > 0)
        allocatedBytes -= bytes; textureCount--
    }
    companion object {
        const val PREVIEW_BUDGET = 64L * 1024 * 1024
        const val EXPORT_BUDGET = 192L * 1024 * 1024
    }
}
