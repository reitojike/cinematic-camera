package dev.cinematic.p0.gpu

/** 同じ GL thread が所有する全テクスチャの台帳。予算超過を縮小でごまかさない。 */
internal class GpuMemoryLedger(val budgetBytes: Long = PREVIEW_BUDGET) {
    var allocatedBytes: Long = 0; private set
    /** 成功した予約の累積最大値。解放しても戻さない（この台帳の生存期間全体）。 */
    var peakAllocatedBytes: Long = 0; private set
    var textureCount: Int = 0; private set
    init { require(budgetBytes > 0) }
    internal fun reserve(bytes: Long) {
        require(bytes > 0)
        if (bytes > budgetBytes - allocatedBytes) throw GpuMemoryBudgetException("GPU texture budget exceeded ($budgetBytes bytes)")
        allocatedBytes += bytes; textureCount++
        peakAllocatedBytes = maxOf(peakAllocatedBytes, allocatedBytes)
        recordMemoryDiagnostic()
    }
    internal fun release(bytes: Long) {
        check(bytes > 0 && bytes <= allocatedBytes && textureCount > 0)
        allocatedBytes -= bytes; textureCount--
        recordMemoryDiagnostic()
    }
    // Reservation accounting, not driver-resident RAM. No cross-thread inspection or budget change.
    private fun recordMemoryDiagnostic() {
        if (!dev.cinematic.p0.memory.MemoryAdmissionDiagnostic.enabled) return
        try {
            android.util.Log.i("CinematicMemory", "GPU_TEXTURE_LEDGER elapsedMs=${android.os.SystemClock.elapsedRealtime()} pid=${android.os.Process.myPid()} budgetBytes=$budgetBytes reservedBytes=$allocatedBytes peakReservedBytes=$peakAllocatedBytes textures=$textureCount")
        } catch (_: Throwable) { /* Includes log construction; retain authoritative ownership/accounting. */ }
    }
    companion object {
        const val PREVIEW_BUDGET = 128L * 1024 * 1024
        const val EXPORT_BUDGET = 192L * 1024 * 1024
    }
}
