package dev.cinematic.p0.gpu

/** Photo-local immutable GL storage reuse. Only closed leases may be reused or evicted.
 * Ledger counts real storage, including idle entries, not the number of active leases.
 * Fragment writes use normal context ordering; imageStore users add a pre-write memory barrier.
 */
internal class GpuTexturePool(private val egl: EglContexts, private val ledger: GpuMemoryLedger) : AutoCloseable {
    private val idle = mutableListOf<GpuTexture.Storage>()
    private val active = mutableSetOf<GpuTexture.Storage>()
    private var closed = false
    internal var allocationCount = 0; private set
    internal var reuseCount = 0; private set
    internal val idleBytes get() = idle.sumOf { it.byteSize }

    internal fun acquire(context: EglContexts, memory: GpuMemoryLedger, width: Int, height: Int,
        format: GpuTexture.Format): GpuTexture {
        egl.requireCurrent(); check(!closed) { "GPU texture pool is closed" }
        require(context === egl && memory === ledger) { "Foreign texture pool" }
        require(width > 0 && height > 0)
        val index = idle.indexOfLast { it.width == width && it.height == height && it.format == format }
        val storage = if (index >= 0) {
            reuseCount++
            idle.removeAt(index)
        } else {
            val bytes = width.toLong() * height * format.bytesPerPixel
            // Never hide idle storage from the ledger. Evict oldest idle allocations before reserve.
            while (bytes > ledger.budgetBytes - ledger.allocatedBytes && idle.isNotEmpty()) {
                idle.first().destroy(); idle.removeAt(0)
            }
            GpuTexture.Storage(egl, ledger, width, height, format).also { allocationCount++ }
        }
        check(active.add(storage))
        return GpuTexture.lease(storage, this)
    }

    internal fun recycle(storage: GpuTexture.Storage) {
        egl.glThread.requireCurrent()
        check(!closed && storage in active) { "Foreign or closed texture lease" }
        // Validate the current context before transferring ownership, including context-loss cleanup.
        egl.releaseResource { }
        idle += storage
        active.remove(storage)
    }

    override fun close() {
        egl.glThread.requireCurrent()
        if (closed) return
        check(active.isEmpty()) { "GPU texture pool still has active leases" }
        while (idle.isNotEmpty()) { idle.first().destroy(); idle.removeAt(0) }
        closed = true
    }
}
